package com.assist.agent

import android.util.Log
import com.assist.data.SessionRepository
import com.assist.data.SettingsStore
import com.assist.di.AppScope
import com.assist.llm.ContentBlock
import com.assist.llm.ContextManagement
import com.assist.llm.Effort
import com.assist.llm.LlmClient
import com.assist.llm.LlmRequest
import com.assist.llm.LlmStreamEvent
import com.assist.llm.Role
import com.assist.llm.Speed
import com.assist.llm.ToolCall
import com.assist.llm.anthropic.ModelRouter
import com.assist.llm.anthropic.StepDifficulty
import com.assist.service.DeviceController
import com.assist.service.ScreenChangeSignals
import com.assist.service.ScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent orchestration spine: builds requests, runs the tool-use loop with
 * safety gates + auto-perception, persists everything, and streams events.
 * Cancellable — [interrupt] aborts the in-flight `llm.send`/gesture within ~1s and
 * transitions to [AgentEvent.Listening] so the user can redirect (phase-08 barge-in).
 */
@Singleton
class AgentLoop @Inject constructor(
    private val llm: LlmClient,
    private val modelRouter: ModelRouter,
    private val repository: SessionRepository,
    private val device: DeviceController,
    private val router: ToolRouter,
    private val actionGate: ActionGate,
    private val bus: AgentEventBus,
    private val userIo: UserIo,
    private val promptProvider: SystemPromptProvider,
    private val screenChangeSignals: ScreenChangeSignals,
    private val settings: SettingsStore,
    private val json: Json,
    @AppScope private val scope: CoroutineScope,
) {

    @Volatile
    private var currentJob: Job? = null

    /** True while a run is active. */
    val isRunning: Boolean get() = currentJob?.isActive == true

    /**
     * Start a run for [sessionId] with [userIntent]. Cancels any in-flight run
     * first. Returns the [Job] (join it to await completion). Runs on the app
     * scope so it survives the triggering caller.
     */
    fun start(sessionId: Long, userIntent: String): Job {
        currentJob?.cancel(CancellationException("superseded by a new run"))
        val job = scope.launch(CoroutineName("AgentLoop")) {
            try {
                runLoop(sessionId, userIntent)
            } catch (c: CancellationException) {
                Log.i(TAG, "loop cancelled -> listening")
                bus.emit(AgentEvent.Listening)
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "agent loop error", t)
                bus.emit(AgentEvent.Error(t.message ?: t::class.simpleName ?: "error"))
            }
        }
        currentJob = job
        return job
    }

    /**
     * Interrupt the running task: cancels the in-flight LLM stream / gesture and
     * transitions to listening. Returns promptly; the loop unwinds within ~1s
     * because `llm.send` aborts its HTTP call on cancellation.
     */
    fun interrupt() {
        Log.i(TAG, "interrupt requested")
        currentJob?.cancel(CancellationException("interrupted by user"))
    }

    // --- Core loop ----------------------------------------------------------

    private suspend fun runLoop(sessionId: Long, userIntent: String) {
        bus.emit(AgentEvent.Started(sessionId, userIntent))

        var latestScreen = safeGetScreen()
        repository.appendMessage(
            sessionId,
            Role.USER,
            listOf(
                ContentBlock.Text(userIntent),
                ContentBlock.Text(screenBlockText(latestScreen)),
            ),
        )

        val tools = AgentTools.catalog()
        val toolNames = tools.map { it.name }
        // Fast mode is session-level (switching speed busts the prompt cache), so
        // read the pref once at the start of the run.
        val speed = if (settings.isFastModeEnabled()) Speed.FAST else Speed.STANDARD
        var pendingContext: ContextManagement? = null
        val progress = NoProgressTracker()
        var step = 0

        while (true) {
            coroutineContext.ensureActive()
            step++
            if (step > MAX_STEPS) {
                finishRun(sessionId, "Reached the step limit without completing the task.")
                return
            }

            val model = modelRouter.modelFor(StepDifficulty.COMPLEX)
            val request = LlmRequest(
                model = model,
                system = promptProvider.system(
                    SystemPromptContext(sessionId, userIntent, toolNames),
                ),
                messages = repository.buildLlmMessages(sessionId),
                tools = tools,
                maxTokens = MAX_TOKENS,
                effort = Effort.MEDIUM,
                thinkingAdaptive = true,
                contextManagement = pendingContext,
                speed = speed,
            )
            pendingContext = null

            val response = llm.send(request) { event -> onStream(event) }
            repository.appendMessage(sessionId, Role.ASSISTANT, response.content)
            repository.recordUsage(sessionId, messageId = null, model = model, usage = response.usage)
            bus.emit(AgentEvent.UsageUpdated(response.usage))

            if (response.toolCalls.isEmpty()) {
                // No tool calls: the model is done (or just talking). Treat as finish.
                val summary = response.text.ifBlank { null }
                summary?.let { userIo.say(it) }
                bus.emit(AgentEvent.Finished(summary))
                return
            }

            val resultBlocks = mutableListOf<ContentBlock>()
            var acted = false
            var producedPerception = false
            var finished = false
            var finishSummary: String? = null

            for (call in response.toolCalls) {
                coroutineContext.ensureActive()
                bus.emit(AgentEvent.ToolCallStarted(call.id, call.name, call.argumentsJson))

                val exec = gateAndExecute(sessionId, call, latestScreen)
                repository.appendToolCall(
                    sessionId = sessionId,
                    messageId = null,
                    name = call.name,
                    argsJson = call.argumentsJson,
                    resultJson = exec.message,
                    success = exec.success,
                    durationMs = 0,
                )
                bus.emit(AgentEvent.ToolCallFinished(call.id, call.name, exec.success, exec.message))
                Log.i(TAG, "step $step tool=${call.name} ok=${exec.success} : ${exec.message.take(80)}")

                resultBlocks += exec.resultBlock
                acted = acted || exec.didAct
                producedPerception = producedPerception || exec.producedPerception
                exec.screenState?.let { latestScreen = it }
                exec.contextEdit?.let { pendingContext = mergeContext(pendingContext, it) }
                if (exec.finished) {
                    finished = true
                    finishSummary = exec.finishSummary
                    break
                }
            }

            if (finished) {
                repository.appendMessage(sessionId, Role.USER, resultBlocks, kind = TOOL_RESULT_KIND)
                bus.emit(AgentEvent.Finished(finishSummary))
                return
            }

            // Auto-perception: attach a fresh screen outline for the next turn,
            // unless a perception tool already produced one this turn.
            if (acted && !producedPerception) {
                awaitSettle()
                latestScreen = safeGetScreen()
                resultBlocks += ContentBlock.Text(screenBlockText(latestScreen))
            }
            repository.appendMessage(sessionId, Role.USER, resultBlocks, kind = TOOL_RESULT_KIND)

            // Loop guard: no-progress detection (same screen + same lead action).
            val signature = latestScreen.signature() + "|" + response.toolCalls.first().name
            if (progress.record(signature)) {
                val answer = userIo.ask(
                    "I don't seem to be making progress. Should I keep trying? (yes/no)",
                )
                if (!ActionGate.isAffirmative(answer)) {
                    bus.emit(AgentEvent.Finished("Stopped: no progress."))
                    return
                }
                progress.reset()
            }

            // Cost/context ceiling: drop old screenshots and request a context edit.
            if (response.usage.inputTokens > CONTEXT_CEILING_TOKENS) {
                Log.i(TAG, "context ceiling hit (${response.usage.inputTokens} tok) — dropping screenshots")
                repository.markScreenshotsDropped(sessionId, keepLast = 1)
                pendingContext = mergeContext(
                    pendingContext,
                    ContextManagement(clearToolUses = true, keepLastToolUses = 1),
                )
            }
        }
    }

    private suspend fun gateAndExecute(
        sessionId: Long,
        call: ToolCall,
        latestScreen: ScreenState,
    ): ToolExecution {
        val gateInput = buildGateInput(call, latestScreen)
        val decision = actionGate.classify(gateInput)
        if (decision.gated) {
            val proceed = actionGate.confirm(gateInput, userIo, bus)
            if (!proceed) {
                return ToolExecution(
                    resultBlock = ContentBlock.ToolResult(
                        toolUseId = call.id,
                        content = listOf(
                            ContentBlock.Text(
                                "The user declined this ${decision.category?.label ?: "action"}. " +
                                    "Do not retry it; consider an alternative or ask the user.",
                            ),
                        ),
                        isError = true,
                    ),
                    success = false,
                    message = "declined (${decision.category?.name})",
                )
            }
        }
        return router.execute(sessionId, call)
    }

    private fun buildGateInput(call: ToolCall, screen: ScreenState): GateInput {
        val elementId = argInt(call.argumentsJson, "element_id")
        val (targetText, isPassword) = screen.elementText(elementId)
        return GateInput(
            toolName = call.name,
            argsJson = call.argumentsJson,
            targetText = targetText,
            isPasswordField = isPassword,
        )
    }

    // --- Helpers ------------------------------------------------------------

    private suspend fun finishRun(sessionId: Long, message: String) {
        userIo.say(message)
        bus.emit(AgentEvent.Finished(message))
    }

    private fun onStream(event: LlmStreamEvent) {
        when (event) {
            is LlmStreamEvent.TextDelta -> bus.emit(AgentEvent.AssistantText(event.text))
            is LlmStreamEvent.ThinkingDelta -> bus.emit(AgentEvent.Thinking(event.text))
            is LlmStreamEvent.UsageUpdate -> bus.emit(AgentEvent.UsageUpdated(event.usage))
            else -> Unit
        }
    }

    private suspend fun safeGetScreen(): ScreenState =
        runCatching { device.getScreenState() }.getOrElse { ScreenState.EMPTY }

    private suspend fun awaitSettle() {
        // Catch the (late) window-content-changed event, then debounce so the tree
        // stabilizes. Both are bounded so a static screen costs at most the timeout.
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) { screenChangeSignals.events.first() }
        delay(SETTLE_DEBOUNCE_MS)
    }

    private fun screenBlockText(screen: ScreenState): String =
        "Current screen:\n" + screen.toOutline()

    private fun argInt(argsJson: String, key: String): Int? =
        runCatching { json.parseToJsonElement(argsJson).jsonObject[key]?.jsonPrimitive?.intOrNull }
            .getOrNull()

    private fun mergeContext(a: ContextManagement?, b: ContextManagement?): ContextManagement? {
        if (a == null) return b
        if (b == null) return a
        return ContextManagement(
            clearToolUses = a.clearToolUses || b.clearToolUses,
            keepLastToolUses = b.keepLastToolUses ?: a.keepLastToolUses,
            compact = a.compact || b.compact,
        )
    }

    /** Detects the same (screen, lead-action) repeating N times in a row. */
    private class NoProgressTracker(private val threshold: Int = 3) {
        private var last: String? = null
        private var count = 0

        /** Records [signature]; returns true when the repeat threshold is reached. */
        fun record(signature: String): Boolean {
            if (signature == last) count++ else { last = signature; count = 1 }
            return count >= threshold
        }

        fun reset() {
            last = null
            count = 0
        }
    }

    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_STEPS = 25
        private const val MAX_TOKENS = 4096
        private const val SETTLE_TIMEOUT_MS = 1500L
        private const val SETTLE_DEBOUNCE_MS = 300L
        private const val CONTEXT_CEILING_TOKENS = 300_000
        private const val TOOL_RESULT_KIND = com.assist.data.MessageKind.TOOL_RESULT
    }
}

/** Stable-ish signature of a screen for no-progress detection. */
internal fun ScreenState.signature(): String =
    appPackage + "#" + elements.size + "#" + toOutline().hashCode()
