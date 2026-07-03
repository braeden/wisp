package com.assist.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAudioSessionArbiterTest {
    @Test
    fun `priority ordering is barge-in over listen over wake`() {
        assertTrue(MicOwner.BARGE_IN.priority > MicOwner.LISTEN_ONCE.priority)
        assertTrue(MicOwner.LISTEN_ONCE.priority > MicOwner.WAKE_WORD.priority)
    }

    @Test
    fun `single owner runs block and returns its result`() =
        runTest {
            val arbiter = DefaultAudioSessionArbiter()
            val result = arbiter.withMic(MicOwner.LISTEN_ONCE) { "hello" }
            assertEquals("hello", result)
        }

    @Test
    fun `higher priority preempts a running lower-priority owner`() =
        runTest {
            val arbiter = DefaultAudioSessionArbiter()
            val lowStarted = CompletableDeferred<Unit>()
            val lowCancelled = CompletableDeferred<Unit>()
            val neverEnds = CompletableDeferred<Unit>()

            val low =
                launch {
                    try {
                        arbiter.withMic(MicOwner.WAKE_WORD) {
                            lowStarted.complete(Unit)
                            neverEnds.await() // hold until preempted
                        }
                    } catch (e: CancellationException) {
                        lowCancelled.complete(Unit)
                    }
                }
            lowStarted.await()

            var highRan = false
            arbiter.withMic(MicOwner.BARGE_IN) { highRan = true }

            assertTrue("barge-in should run", highRan)
            lowCancelled.await() // low's block was cancelled by preemption
            low.cancel()
        }

    @Test
    fun `equal-or-lower priority waits for the current holder to release`() =
        runTest {
            val arbiter = DefaultAudioSessionArbiter()
            val highHolds = CompletableDeferred<Unit>()
            val releaseHigh = CompletableDeferred<Unit>()
            var lowRan = false

            val high =
                launch {
                    arbiter.withMic(MicOwner.LISTEN_ONCE) {
                        highHolds.complete(Unit)
                        releaseHigh.await()
                    }
                }
            highHolds.await()

            val low =
                launch {
                    arbiter.withMic(MicOwner.WAKE_WORD) { lowRan = true }
                }
            runCurrent()
            assertFalse("low must wait while high holds", lowRan)

            releaseHigh.complete(Unit)
            high.join()
            low.join()
            assertTrue("low runs after high releases", lowRan)
        }
}
