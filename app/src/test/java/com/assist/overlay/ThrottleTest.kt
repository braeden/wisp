package com.assist.overlay

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {
    @Test
    fun `a burst within one window collapses to leading plus trailing`() =
        runTest {
            // Ten values emitted with no virtual-time gap: the throttle should emit the
            // first immediately (leading) and only the last of the burst (trailing).
            val source = flow { repeat(10) { emit(it) } }

            source.throttleLatest(100L).test {
                assertEquals(0, awaitItem()) // leading
                assertEquals(9, awaitItem()) // trailing = latest of the window
                awaitComplete()
            }
        }

    @Test
    fun `values spaced beyond the window are each emitted`() =
        runTest {
            val source =
                flow {
                    emit(1)
                    delay(200L)
                    emit(2)
                    delay(200L)
                    emit(3)
                }

            val seen = mutableListOf<Int>()
            source.throttleLatest(100L).test {
                seen += awaitItem()
                seen += awaitItem()
                seen += awaitItem()
                awaitComplete()
            }
            assertEquals(listOf(1, 2, 3), seen)
        }

    @Test
    fun `the final value is never dropped`() =
        runTest {
            // Leading emit, then a trailing straggler that must still be delivered.
            val source =
                flow {
                    emit("a")
                    emit("b")
                    emit("final")
                }
            val seen = mutableListOf<String>()
            source.throttleLatest(50L).test {
                while (true) {
                    val event = awaitEvent()
                    if (event is app.cash.turbine.Event.Item) seen += event.value else break
                }
            }
            assertTrue("final must be present, got $seen", seen.last() == "final")
        }

    @Test
    fun `non-positive period is a pass-through`() =
        runTest {
            val source = flow { repeat(3) { emit(it) } }
            source.throttleLatest(0L).test {
                assertEquals(0, awaitItem())
                assertEquals(1, awaitItem())
                assertEquals(2, awaitItem())
                awaitComplete()
            }
        }
}
