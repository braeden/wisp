package com.assist.voice

import android.util.Log
import com.assist.agent.AgentEvent
import com.assist.agent.AgentEventBus
import com.assist.agent.UserIo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * The real [UserIo] (replaces phase-06's `LoggingUserIo`): speaks via [TtsEngine]
 * and listens via [SttEngine], arbitrated through [AudioSessionArbiter]. Emits
 * [AgentEvent.Speaking]/[AgentEvent.Listening] so the overlay stays in sync.
 *
 * `ask()` says the question, then captures a spoken answer at `LISTEN_ONCE`
 * priority; if a [TypedReplySource] is wired (phase-07), the typed reply and the
 * spoken answer race and the first one wins. Recognition failures degrade to an
 * empty answer rather than crashing the agent loop.
 */
class VoiceUserIo(
    private val tts: TtsEngine,
    private val stt: SttEngine,
    private val arbiter: AudioSessionArbiter,
    private val bus: AgentEventBus,
    private val typedReplies: TypedReplySource? = null,
) : UserIo {

    override suspend fun say(text: String) {
        if (text.isBlank()) return
        bus.emit(AgentEvent.Speaking(text))
        runCatching { tts.say(text) }
            .onFailure { Log.w(TAG, "TTS say failed: ${it.message}") }
    }

    override suspend fun ask(question: String): String {
        say(question)
        bus.emit(AgentEvent.Listening)
        return listenForReply()
    }

    private suspend fun listenForReply(): String {
        val typed = typedReplies
        if (typed == null) return transcribe()
        // Race the spoken answer against a typed overlay reply; first wins.
        return coroutineScope {
            val voice = async { transcribe() }
            val text = async { typed.awaitTypedReply() }
            try {
                select {
                    voice.onAwait { it }
                    text.onAwait { it }
                }
            } finally {
                voice.cancel()
                text.cancel()
            }
        }
    }

    private suspend fun transcribe(): String =
        runCatching {
            arbiter.withMic(MicOwner.LISTEN_ONCE) { stt.transcribeOnce() }.text
        }.getOrElse {
            if (it is SttException) {
                Log.i(TAG, "no spoken answer (${it.error})")
            } else {
                Log.w(TAG, "listen failed: ${it.message}")
            }
            ""
        }

    private companion object {
        const val TAG = "VoiceUserIo"
    }
}
