package com.assist.agent

/**
 * The agent's channel to the user for the `say`/`ask`/`finish` tools and for
 * [ActionGate] confirmations. Phase-06 ships a logging stub ([LoggingUserIo]);
 * phase-08 swaps in the real voice/overlay implementation (TTS + STT / tap).
 *
 * Contract consumed by 07/08.
 */
interface UserIo {
    /** Speak/display [text] to the user. One-way. */
    suspend fun say(text: String)

    /**
     * Ask [question] and block for the user's reply (voice or tap). The returned
     * string is the user's answer; for yes/no gates the caller checks it against
     * an affirmative (see [ActionGate.isAffirmative]).
     */
    suspend fun ask(question: String): String
}
