package com.wisp.voice.wake

import com.wisp.voice.WakeConfig
import com.wisp.voice.WakeEvent
import com.wisp.voice.WakeWordDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Temporary placeholder while the openWakeWord-backed detector lands (the
 * Porcupine implementation was removed — Picovoice no longer has a free tier).
 * Keeps the Hilt graph and [WakeWordService] compiling; arming the service
 * fails fast with a clear error.
 */
class NoopWakeWordDetector : WakeWordDetector {
    override suspend fun isAvailable(): Boolean = false

    override fun detections(config: WakeConfig): Flow<WakeEvent> =
        flow {
            throw IllegalStateException("Wake-word detector not available (openWakeWord port pending)")
        }
}
