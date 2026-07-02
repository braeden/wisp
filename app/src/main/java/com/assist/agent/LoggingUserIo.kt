package com.assist.agent

import android.util.Log

/**
 * Stub [UserIo] for phase-06 (no voice yet): logs `say`/`ask` and returns a fixed
 * [autoAnswer] for questions. [autoAnswer] defaults to "yes" so gated actions
 * proceed during headless debug runs while still surfacing the confirmation via
 * the [ActionGate] (which emits [AgentEvent.AwaitingConfirmation] and logs the
 * decision). Phase-08 replaces this with real TTS/STT.
 */
class LoggingUserIo(
    private val autoAnswer: String = "yes",
) : UserIo {

    override suspend fun say(text: String) {
        Log.i(TAG, "SAY: $text")
    }

    override suspend fun ask(question: String): String {
        Log.i(TAG, "ASK: $question -> [stub answers \"$autoAnswer\"]")
        return autoAnswer
    }

    private companion object {
        const val TAG = "UserIo"
    }
}
