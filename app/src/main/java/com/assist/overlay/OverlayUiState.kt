package com.assist.overlay

import com.assist.data.ContextStatus

/** Coarse agent status the collapsed bubble renders (colour + label + affordance). */
enum class AgentPhase { IDLE, LISTENING, THINKING, SPEAKING, ACTING }

/** Result state of a single tool call, shown as an ordered chip in the panel. */
enum class ToolStatus { RUNNING, SUCCESS, FAILURE }

/** One tool_use chip: name + raw args + running/success/fail + result text. */
data class ToolChip(
    val id: String,
    val name: String,
    val argsJson: String,
    val status: ToolStatus,
    val result: String? = null,
)

/** A gated action awaiting an explicit yes/no (feeds back into `ActionGate`). */
data class ConfirmationPrompt(
    val question: String,
    val category: String,
)

/**
 * Context-economy HUD, projected from [ContextStatus]. [contextFraction] is the
 * clamped used/window ratio for the gauge.
 */
data class HudState(
    val usedTokens: Int,
    val windowTokens: Int,
    val costUsd: Double,
    val screenshotCount: Int,
) {
    val contextFraction: Float
        get() =
            if (windowTokens <=
                0
            ) {
                0f
            } else {
                (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f)
            }

    companion object {
        fun from(status: ContextStatus): HudState =
            HudState(
                usedTokens = status.usedTokens,
                windowTokens = status.windowTokens,
                costUsd = status.costUsd,
                screenshotCount = status.screenshotCount,
            )
    }
}

/**
 * The complete, immutable snapshot the overlay renders. Built by folding the
 * [com.assist.agent.AgentEvent] stream (see [OverlayReducer]) plus async HUD
 * refreshes and the user's expand/collapse toggle. Because every state is a full
 * snapshot (text and chips accumulate), dropping intermediate snapshots under
 * throttling never loses information.
 */
data class OverlayUiState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val expanded: Boolean = false,
    val sessionId: Long? = null,
    val intent: String? = null,
    /** Accumulated visible assistant text for the current run. */
    val assistantText: String = "",
    /** Accumulated model reasoning (when surfaced). */
    val thinking: String = "",
    val isThinking: Boolean = false,
    /** Tool calls in emission order. */
    val toolChips: List<ToolChip> = emptyList(),
    val confirmation: ConfirmationPrompt? = null,
    val hud: HudState? = null,
    val error: String? = null,
    val finished: Boolean = false,
    val summary: String? = null,
) {
    companion object {
        val INITIAL = OverlayUiState()
    }
}
