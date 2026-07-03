package com.assist.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Framework-free [AudioSessionArbiter]. All state transitions are guarded by a
 * single [Mutex]; preemption cancels the running owner's block coroutine while a
 * lower/equal-priority acquirer parks on the current owner's `released` signal and
 * retries. No Android types — unit-tested directly.
 */
@Singleton
class DefaultAudioSessionArbiter
    @Inject
    constructor() : AudioSessionArbiter {
        private val lock = Mutex()
        private var holder: Token? = null

        private class Token(
            val owner: MicOwner,
        ) {
            /** Completed (with Unit) when a higher-priority owner preempts this holder. */
            val preempted = CompletableDeferred<Unit>()

            /** Completed when this holder releases the mic (normally or via preemption). */
            val released = CompletableDeferred<Unit>()
        }

        override suspend fun <T> withMic(
            owner: MicOwner,
            block: suspend () -> T,
        ): T {
            val token = acquire(owner)
            try {
                return coroutineScope {
                    val work = async { block() }
                    // Cancel the block the instant this holder is preempted.
                    val watcher =
                        launch {
                            token.preempted.await()
                            work.cancel(
                                CancellationException("mic preempted by higher-priority owner"),
                            )
                        }
                    try {
                        work.await()
                    } finally {
                        watcher.cancel()
                    }
                }
            } finally {
                release(token)
            }
        }

        /** Suspends until [owner] holds the mic, preempting any lower-priority holder. */
        private suspend fun acquire(owner: MicOwner): Token {
            while (true) {
                val waitOn: Token? =
                    lock.withLock {
                        val current = holder
                        when {
                            current == null -> {
                                val token = Token(owner)
                                holder = token
                                return token
                            }
                            owner.priority > current.owner.priority -> {
                                // Preempt: signal the holder to cancel; then park on its release.
                                current.preempted.complete(Unit)
                                current
                            }
                            else -> current // equal/lower priority: wait it out.
                        }
                    }
                waitOn?.released?.await()
            }
        }

        private suspend fun release(token: Token) {
            lock.withLock {
                if (holder === token) holder = null
            }
            token.released.complete(Unit)
        }
    }
