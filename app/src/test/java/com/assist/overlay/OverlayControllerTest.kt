package com.assist.overlay

import com.assist.agent.AgentEvent
import com.assist.agent.AgentEventBus
import com.assist.agent.AgentLoop
import com.assist.data.AgentModel
import com.assist.data.ContextStatus
import com.assist.data.ContextTracker
import com.assist.data.SessionEntity
import com.assist.data.SessionRepository
import com.assist.data.SettingsStore
import com.assist.llm.Usage
import com.assist.voice.AudioSessionArbiter
import com.assist.voice.SttEngine
import com.assist.voice.TtsEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayControllerTest {
    private val bus = AgentEventBus()
    private val loop = mockk<AgentLoop>(relaxed = true)
    private val repo = mockk<SessionRepository>(relaxed = true)
    private val tracker = mockk<ContextTracker>()
    private val stt = mockk<SttEngine>(relaxed = true)
    private val arbiter = mockk<AudioSessionArbiter>(relaxed = true)
    private val tts = mockk<TtsEngine>(relaxed = true)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { getAgentModel() } returns AgentModel.DEFAULT
        }

    @Test
    fun `folds agent events into state and refreshes the HUD`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            every { repo.listSessions() } returns flowOf(emptyList())
            coEvery { tracker.contextStatus(5L) } returns
                ContextStatus(
                    usedTokens = 1_000,
                    windowTokens = 200_000,
                    costUsd = 0.02,
                    screenshotCount = 1,
                )

            val controller =
                OverlayController(bus, loop, repo, tracker, stt, tts, arbiter, settings, scope)
            // Keep the WhileSubscribed StateFlow hot for the duration of the test.
            val subscription = scope.launch { controller.uiState.collect {} }

            bus.emit(AgentEvent.Started(5L, "open clock"))
            bus.emit(AgentEvent.ToolCallStarted("a", "tap", "{}"))
            bus.emit(AgentEvent.ToolCallFinished("a", "tap", success = true, message = "ok"))
            bus.emit(AgentEvent.UsageUpdated(Usage(inputTokens = 1_000)))
            advanceUntilIdle() // flush the throttle's trailing emission

            val latest = controller.uiState.value
            assertEquals(5L, latest.sessionId)
            assertEquals("open clock", latest.intent)
            assertEquals(1, latest.toolChips.size)
            assertEquals(ToolStatus.SUCCESS, latest.toolChips[0].status)
            assertEquals(1_000, latest.hud?.usedTokens)
            assertEquals(1, latest.hud?.screenshotCount)

            subscription.cancel()
            scope.cancel()
        }

    @Test
    fun `interrupt and session controls delegate to the loop and repository`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            every { repo.listSessions() } returns flowOf(emptyList())
            coEvery { repo.createSession(any(), any(), any()) } returns
                SessionEntity(
                    id = 9,
                    title = "New session",
                    createdAt = 0,
                    updatedAt = 0,
                    modelDefault = "claude-opus-4-8",
                    status = "active",
                    systemPromptVersion = 1,
                )

            val controller =
                OverlayController(bus, loop, repo, tracker, stt, tts, arbiter, settings, scope)

            controller.interrupt()
            verify { loop.interrupt() }

            controller.newSession()
            advanceUntilIdle()
            coVerify { repo.createSession(any(), any(), any()) }

            controller.switchSession(3L)
            advanceUntilIdle()
            coVerify { repo.resumeSession(3L) }

            scope.cancel()
        }

    @Test
    fun `submitReply is delivered to awaitTypedReply`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            every { repo.listSessions() } returns flowOf(emptyList())
            val controller =
                OverlayController(bus, loop, repo, tracker, stt, tts, arbiter, settings, scope)

            var received: String? = null
            val waiter = scope.launch { received = controller.awaitTypedReply() }
            controller.submitReply("  hello  ")
            advanceUntilIdle()

            assertEquals("hello", received)
            waiter.cancel()
            scope.cancel()
        }

    @Test
    fun `blank replies are ignored`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            every { repo.listSessions() } returns flowOf(emptyList())
            val controller =
                OverlayController(bus, loop, repo, tracker, stt, tts, arbiter, settings, scope)

            val results = mutableListOf<String>()
            val job = scope.launch { controller.typedReplies.collect { results += it } }

            controller.submitReply("   ")
            controller.submitReply("real")
            advanceUntilIdle()

            assertEquals(listOf("real"), results)
            job.cancel()
            scope.cancel()
        }
}
