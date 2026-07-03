package com.wisp.voice.wake

import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import com.wisp.data.SecretStore
import com.wisp.voice.AudioSessionArbiter
import com.wisp.voice.MicOwner
import com.wisp.voice.WakeConfig
import com.wisp.voice.WakeEvent
import com.wisp.voice.WakeWordDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive

/**
 * Phase-09 [WakeWordDetector] backed by Picovoice Porcupine (on-device keyword
 * spotting; `PorcupineManager` owns its own `AudioRecord` loop). Requires a
 * Picovoice AccessKey ([SecretStore.getPicovoiceKey]) and `RECORD_AUDIO`.
 *
 * Mic coordination: the detector listens while holding the shared
 * [AudioSessionArbiter] at [MicOwner.WAKE_WORD] — the lowest priority — so an
 * `ask()`/dictation (`LISTEN_ONCE`) or barge-in preempts it instantly. On
 * preemption it stops Porcupine, waits for the mic to free up, and re-arms;
 * detection is only ever paused, never lost.
 *
 * All Porcupine SDK types stay inside `voice/wake` (behind the seam).
 */
class PorcupineWakeWordDetector(
    context: Context,
    private val secrets: SecretStore,
    private val arbiter: AudioSessionArbiter,
) : WakeWordDetector {
    private val appContext = context.applicationContext

    override suspend fun isAvailable(): Boolean = secrets.hasPicovoiceKey()

    override fun detections(config: WakeConfig): Flow<WakeEvent> =
        channelFlow {
            val accessKey =
                secrets.getPicovoiceKey()
                    ?: throw IllegalStateException("No Picovoice AccessKey configured")
            while (isActive) {
                try {
                    arbiter.withMic(MicOwner.WAKE_WORD) { spotUntilCancelled(accessKey, config) }
                } catch (c: CancellationException) {
                    // Collector gone -> propagate. Mic preempted (higher-priority
                    // owner) -> our scope is still active; re-arm shortly.
                    currentCoroutineContext().ensureActive()
                    Log.i(TAG, "mic preempted; re-arming wake word in ${REARM_DELAY_MS}ms")
                    delay(REARM_DELAY_MS)
                }
            }
        }

    /** Runs Porcupine until cancelled (preemption or collector cancellation). */
    private suspend fun ProducerScope<WakeEvent>.spotUntilCancelled(
        accessKey: String,
        config: WakeConfig,
    ) {
        val manager = buildManager(accessKey, config)
        try {
            manager.start()
            Log.i(TAG, "wake word armed (keyword=${config.keyword})")
            awaitCancellation()
        } finally {
            runCatching { manager.stop() }
            runCatching { manager.delete() }
        }
    }

    private fun ProducerScope<WakeEvent>.buildManager(
        accessKey: String,
        config: WakeConfig,
    ): PorcupineManager {
        val builder =
            PorcupineManager
                .Builder()
                .setAccessKey(accessKey)
                .setSensitivity(config.sensitivity)
        val asset = config.modelAsset
        if (asset != null) {
            builder.setKeywordPath(asset)
        } else {
            val keyword =
                PorcupineKeywords.builtInFor(config.keyword)
                    ?: throw IllegalArgumentException(
                        "Unknown wake keyword '${config.keyword}' (no built-in match, no modelAsset)",
                    )
            builder.setKeyword(keyword)
        }
        builder.setErrorCallback { error ->
            Log.w(TAG, "porcupine error", error)
            close(error)
        }
        return builder.build(appContext) { _ ->
            trySend(
                WakeEvent(
                    keyword = config.keyword,
                    confidence = 1f, // Porcupine reports detections, not scores.
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private companion object {
        const val TAG = "PorcupineWake"
        const val REARM_DELAY_MS = 250L
    }
}
