package com.assist.voice

/**
 * Serializes mic access so exactly one owner records at a time. Higher-priority
 * owners **preempt** lower-priority ones (barge-in beats a wake-word listen);
 * same-or-lower priority requests wait for the current owner to release. Phase-09
 * reuses this so wake / `listenOnce` / barge-in never contend for the mic.
 */
interface AudioSessionArbiter {
    /**
     * Hold the mic as [owner] for the duration of [block]. If a higher-priority
     * owner acquires while [block] runs, [block] is cancelled (its coroutine
     * throws [kotlinx.coroutines.CancellationException]) and the mic is handed
     * over. Returns [block]'s result on normal completion.
     */
    suspend fun <T> withMic(
        owner: MicOwner,
        block: suspend () -> T,
    ): T
}

/** Mic owners in ascending priority: `BARGE_IN` > `LISTEN_ONCE` > `WAKE_WORD`. */
enum class MicOwner(
    val priority: Int,
) {
    WAKE_WORD(0),
    LISTEN_ONCE(1),
    BARGE_IN(2),
}
