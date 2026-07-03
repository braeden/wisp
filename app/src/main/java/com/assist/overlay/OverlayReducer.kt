package com.assist.overlay

import com.assist.agent.AgentEvent
import com.assist.data.ContextStatus

/**
 * The one input the overlay state fold consumes: either an agent [AgentEvent], an
 * async HUD refresh, or a UI-local expand/collapse toggle. Keeping all three in a
 * single fold means the overlay reads exactly one [OverlayUiState] snapshot.
 */
sealed interface OverlayInput {
    @JvmInline value class Event(val event: AgentEvent) : OverlayInput
    @JvmInline value class Hud(val status: ContextStatus) : OverlayInput
    @JvmInline value class Expand(val expanded: Boolean) : OverlayInput

    /** New/switched session: clear the previous run's transcript view. */
    @JvmInline value class SessionChanged(val sessionId: Long) : OverlayInput
}

/**
 * Pure, framework-free reducer: `(state, input) -> state`. No coroutines, no
 * Android, no I/O — every transition is unit-tested. High-frequency text deltas
 * are handled upstream by throttling the *snapshot* stream (see
 * [OverlayController.throttleLatest]); this reducer just accumulates.
 */
class OverlayReducer {

    fun reduce(state: OverlayUiState, input: OverlayInput): OverlayUiState = when (input) {
        is OverlayInput.Event -> reduceEvent(state, input.event)
        is OverlayInput.Hud -> state.copy(hud = HudState.from(input.status))
        is OverlayInput.Expand -> state.copy(expanded = input.expanded)
        // Same clearing as a fresh run: the old run's text/chips belong to the
        // previous session and would otherwise linger next to a reset HUD.
        is OverlayInput.SessionChanged -> OverlayUiState(
            phase = AgentPhase.IDLE,
            expanded = state.expanded,
            sessionId = input.sessionId,
            hud = state.hud,
        )
    }

    private fun reduceEvent(state: OverlayUiState, event: AgentEvent): OverlayUiState = when (event) {
        is AgentEvent.Started -> OverlayUiState(
            // Fresh run: clear the transcript but preserve UI-local + HUD context.
            phase = AgentPhase.THINKING,
            expanded = state.expanded,
            sessionId = event.sessionId,
            intent = event.intent,
            hud = state.hud,
        )

        is AgentEvent.Thinking -> state.copy(
            phase = AgentPhase.THINKING,
            thinking = state.thinking + event.text,
            isThinking = true,
        )

        is AgentEvent.AssistantText -> state.copy(
            phase = AgentPhase.SPEAKING,
            assistantText = state.assistantText + event.text,
            isThinking = false,
            confirmation = null,
        )

        is AgentEvent.ToolCallStarted -> state.copy(
            phase = AgentPhase.ACTING,
            isThinking = false,
            toolChips = state.toolChips + ToolChip(
                id = event.id,
                name = event.name,
                argsJson = event.argsJson,
                status = ToolStatus.RUNNING,
            ),
        )

        is AgentEvent.ToolCallFinished -> state.copy(
            phase = AgentPhase.ACTING,
            confirmation = null,
            toolChips = state.toolChips.map { chip ->
                if (chip.id == event.id) {
                    chip.copy(
                        status = if (event.success) ToolStatus.SUCCESS else ToolStatus.FAILURE,
                        result = event.message,
                    )
                } else {
                    chip
                }
            },
        )

        is AgentEvent.AwaitingConfirmation -> state.copy(
            phase = AgentPhase.LISTENING,
            confirmation = ConfirmationPrompt(event.question, event.category),
        )

        is AgentEvent.Listening -> state.copy(phase = AgentPhase.LISTENING)

        is AgentEvent.Speaking -> state.copy(phase = AgentPhase.SPEAKING)

        // HUD is refreshed out-of-band from the DB; the raw usage event only nudges phase.
        is AgentEvent.UsageUpdated -> state

        is AgentEvent.Error -> state.copy(
            phase = AgentPhase.IDLE,
            isThinking = false,
            error = event.message,
        )

        is AgentEvent.Finished -> state.copy(
            phase = AgentPhase.IDLE,
            isThinking = false,
            confirmation = null,
            finished = true,
            summary = event.summary,
        )
    }
}
