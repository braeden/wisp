package com.assist.voice.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.assist.agent.AgentLoop
import com.assist.voice.AudioSessionArbiter
import com.assist.voice.MicOwner
import com.assist.voice.SttEngine
import com.assist.voice.TtsEngine
import com.assist.voice.TtsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Barge-in: while [TtsEngine] is speaking, a lightweight [AudioRecord] energy tap
 * ([EnergyVad]) watches for the user talking over the assistant. On detection it
 * **stops TTS**, **interrupts the agent loop**, and captures the redirect via
 * `SttEngine.transcribeOnce()` at [MicOwner.BARGE_IN] priority — the core
 * interruptibility behavior.
 *
 * Per `.claude/voice-architecture.md`, `SpeechRecognizer` can't listen while TTS
 * plays, so the coarse `AudioRecord` gate triggers first, then hands off to the
 * recognizer. Device-only (emulator mic is unreliable).
 */
class BargeInDetector(
    context: Context,
    private val tts: TtsEngine,
    private val stt: SttEngine,
    private val arbiter: AudioSessionArbiter,
    private val agentLoop: AgentLoop,
    private val vad: EnergyVad = EnergyVad(),
) {
    private val appContext = context.applicationContext

    @Volatile
    private var job: Job? = null

    /**
     * Start watching. While a run is active the detector arms itself whenever the
     * TTS reports [TtsEvent.Started] and disarms on stop/done. [onRedirect] is
     * invoked with the captured instruction (blank if capture failed).
     */
    fun start(scope: CoroutineScope, onRedirect: suspend (String) -> Unit) {
        stop()
        job = scope.launch(Dispatchers.Default) {
            tts.events().collect { event ->
                when (event) {
                    is TtsEvent.Started -> monitorWhileSpeaking(onRedirect)
                    else -> Unit
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Runs the energy tap until speech is detected or TTS finishes/cancels. */
    private suspend fun monitorWhileSpeaking(onRedirect: suspend (String) -> Unit) {
        if (!hasMicPermission()) return
        vad.reset()
        val record = openRecord() ?: return
        try {
            withContext(Dispatchers.Default) {
                val frame = ShortArray(FRAME_SAMPLES)
                record.startRecording()
                while (coroutineContext.isActive && tts.isSpeaking) {
                    val read = record.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    if (vad.onFrame(frame, read)) {
                        coroutineContext.ensureActive()
                        handleBargeIn(onRedirect)
                        return@withContext
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "barge-in monitor error: ${t.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private suspend fun handleBargeIn(onRedirect: suspend (String) -> Unit) {
        Log.i(TAG, "barge-in detected — interrupting")
        tts.stop()
        agentLoop.interrupt()
        val redirect = runCatching {
            arbiter.withMic(MicOwner.BARGE_IN) { stt.transcribeOnce() }.text
        }.getOrDefault("")
        onRedirect(redirect)
    }

    @SuppressLint("MissingPermission") // guarded by hasMicPermission()
    private fun openRecord(): AudioRecord? = runCatching {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(FRAME_SAMPLES * 2)
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minBuf,
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }.getOrNull()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BargeInDetector"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz
    }
}
