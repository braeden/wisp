package com.assist.voice

/**
 * Phase-07 hook: a source of typed replies from the overlay. When present,
 * [VoiceUserIo.ask] races a typed reply against the spoken answer and takes
 * whichever arrives first. Phase-08 ships voice-only (no binding); phase-07 binds
 * an implementation backed by the overlay text field.
 */
fun interface TypedReplySource {
    /** Suspends until the user submits a typed reply, or until cancelled. */
    suspend fun awaitTypedReply(): String
}
