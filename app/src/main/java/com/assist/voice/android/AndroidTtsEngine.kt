package com.assist.voice.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.assist.voice.Latency
import com.assist.voice.Quality
import com.assist.voice.SpeakOptions
import com.assist.voice.TtsEngine
import com.assist.voice.TtsEvent
import com.assist.voice.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * `TextToSpeech`-backed [TtsEngine]. Init is gated on the `OnInitListener`
 * SUCCESS; [say] suspends until `onDone` (or resolves early on stop/cancel);
 * coroutine cancellation calls [stop] for clean barge-in. Requests transient
 * ducking focus around playback and tags audio as `USAGE_ASSISTANT`.
 *
 * All `TextToSpeech` types stay inside this class (behind [TtsEngine]).
 */
class AndroidTtsEngine(
    context: Context,
    private val defaultLocale: java.util.Locale = java.util.Locale.US,
) : TtsEngine {
    private val appContext = context.applicationContext
    private val audioFocus = AudioFocus(appContext)

    private val ready = CompletableDeferred<Boolean>()
    private val utteranceSeq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _events =
        MutableSharedFlow<TtsEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val tts: TextToSpeech =
        TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                runCatching { tts.language = defaultLocale }
                tts.setAudioAttributes(audioFocus.audioAttributes)
                tts.setOnUtteranceProgressListener(progressListener)
            } else {
                Log.w(TAG, "TextToSpeech init failed: $status")
            }
            ready.complete(ok)
        }

    private val progressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                _events.tryEmit(TtsEvent.Started(utteranceId))
            }

            override fun onRangeStart(
                utteranceId: String,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                _events.tryEmit(TtsEvent.Range(utteranceId, start, end))
            }

            override fun onDone(utteranceId: String) {
                _events.tryEmit(TtsEvent.Done(utteranceId))
                audioFocus.abandon()
                pending.remove(utteranceId)?.complete(true)
            }

            override fun onStop(
                utteranceId: String,
                interrupted: Boolean,
            ) {
                _events.tryEmit(TtsEvent.Stopped(utteranceId, interrupted))
                audioFocus.abandon()
                pending.remove(utteranceId)?.complete(false)
            }

            @Deprecated("Framework requires the no-code override", ReplaceWith("onError(id, code)"))
            override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(
                utteranceId: String,
                errorCode: Int,
            ) {
                _events.tryEmit(TtsEvent.Failed(utteranceId))
                audioFocus.abandon()
                pending.remove(utteranceId)?.complete(false)
            }
        }

    override suspend fun isAvailable(): Boolean = ready.await()

    override fun voices(): List<VoiceInfo> =
        runCatching {
            tts.voices.orEmpty().map { it.toVoiceInfo() }
        }.getOrDefault(emptyList())

    override suspend fun say(
        text: String,
        opts: SpeakOptions,
    ) {
        if (text.isBlank()) return
        if (!ready.await()) return

        val id = "u-${utteranceSeq.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        pending[id] = done

        applyVoice(opts)
        tts.setPitch(opts.pitch)
        tts.setSpeechRate(opts.rate)
        audioFocus.request()

        val queueMode = if (opts.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts.speak(text, queueMode, null, id)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(id)
            audioFocus.abandon()
            _events.tryEmit(TtsEvent.Failed(id))
            return
        }

        suspendCancellableCoroutine { cont ->
            done.invokeOnCompletion { cont.resume(Unit) }
            cont.invokeOnCancellation {
                // Barge-in: stop playback; the onStop callback settles `done`.
                runCatching { tts.stop() }
                audioFocus.abandon()
            }
        }
    }

    override fun stop() {
        runCatching { tts.stop() }
        audioFocus.abandon()
        // Settle anything the listener didn't (e.g. stop before onStart).
        pending.keys.toList().forEach { pending.remove(it)?.complete(false) }
    }

    override val isSpeaking: Boolean
        get() = runCatching { tts.isSpeaking }.getOrDefault(false)

    override fun events(): Flow<TtsEvent> = _events.asSharedFlow()

    /** Release the engine (process teardown). Not reusable afterwards. */
    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        audioFocus.abandon()
    }

    private fun applyVoice(opts: SpeakOptions) {
        val voiceId = opts.voiceId ?: return
        val match = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceId }
        if (match != null) tts.voice = match
    }

    private fun Voice.toVoiceInfo(): VoiceInfo =
        VoiceInfo(
            id = name,
            locale = locale.toLanguageTag(),
            networkRequired = isNetworkConnectionRequired,
            quality =
                when {
                    quality >= Voice.QUALITY_VERY_HIGH -> Quality.VERY_HIGH
                    quality >= Voice.QUALITY_HIGH -> Quality.HIGH
                    quality >= Voice.QUALITY_NORMAL -> Quality.NORMAL
                    else -> Quality.LOW
                },
            latency =
                when {
                    latency <= Voice.LATENCY_VERY_LOW -> Latency.VERY_LOW
                    latency <= Voice.LATENCY_LOW -> Latency.LOW
                    latency <= Voice.LATENCY_NORMAL -> Latency.NORMAL
                    else -> Latency.HIGH
                },
        )

    private companion object {
        const val TAG = "AndroidTtsEngine"
    }
}
