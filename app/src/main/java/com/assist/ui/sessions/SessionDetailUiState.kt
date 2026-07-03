package com.assist.ui.sessions

import com.assist.data.ContextStatus
import com.assist.data.ModelUsage
import com.assist.data.SessionEntity
import com.assist.data.ToolCallEntity
import com.assist.data.TranscriptBlock
import com.assist.data.TranscriptMessage
import com.assist.data.TranscriptRole
import com.assist.data.UsageSummary

/** A single rendered item in the transcript. */
sealed interface TranscriptItemUi {
    val key: String

    data class Message(
        override val key: String,
        val role: TranscriptRole,
        val text: String,
    ) : TranscriptItemUi

    data class Thinking(
        override val key: String,
        val preview: String,
    ) : TranscriptItemUi

    data class ToolChip(
        override val key: String,
        val name: String,
        val argsJson: String,
        val result: String?,
        val ok: Boolean,
        val durationMs: Long?,
    ) : TranscriptItemUi

    data class Screenshot(
        override val key: String,
        val dropped: Boolean,
    ) : TranscriptItemUi
}

/** The context/usage panel: tokens vs window, cost, screenshots, per-model. */
data class ContextPanelUi(
    val usedTokens: Int,
    val windowTokens: Int,
    val costUsd: Double,
    val screenshotCount: Int,
    val perModel: List<ModelUsage>,
)

data class SessionDetailUiState(
    val loading: Boolean = true,
    val title: String = "",
    val status: String = "",
    val model: String = "",
    val items: List<TranscriptItemUi> = emptyList(),
    val context: ContextPanelUi? = null,
)

/**
 * Pure reducer (phase-12): folds a decoded transcript + tool-call records +
 * usage + context status into detail-UI state. `tool_use` blocks are paired with
 * their `tool_result` (by id) for the ok/fail chip, and enriched with the
 * matching [ToolCallEntity] (in order) for duration. Framework-free, unit-tested.
 */
object SessionDetailReducer {
    fun reduce(
        session: SessionEntity?,
        transcript: List<TranscriptMessage>,
        toolCalls: List<ToolCallEntity>,
        usage: UsageSummary,
        context: ContextStatus?,
    ): SessionDetailUiState {
        // Resolve tool results by their tool_use id (they arrive on a later turn).
        val resultsById = HashMap<String, TranscriptBlock.ToolResult>()
        for (m in transcript) {
            for (b in m.blocks) {
                if (b is TranscriptBlock.ToolResult) resultsById[b.toolUseId] = b
            }
        }

        val items = ArrayList<TranscriptItemUi>()
        var callIdx = 0
        for (m in transcript) {
            m.blocks.forEachIndexed { i, b ->
                val key = "${m.id}-$i"
                when (b) {
                    is TranscriptBlock.Text ->
                        if (b.text.isNotBlank()) {
                            items +=
                                TranscriptItemUi.Message(key, m.role, b.text)
                        }

                    is TranscriptBlock.Thinking ->
                        items += TranscriptItemUi.Thinking(key, b.text.take(240))

                    is TranscriptBlock.ToolUse -> {
                        // Advance through recorded tool calls in emission order.
                        val record = toolCalls.getOrNull(callIdx)?.also { callIdx++ }
                        val matched = record?.takeIf { it.name == b.name }
                        val res = resultsById[b.id]
                        items +=
                            TranscriptItemUi.ToolChip(
                                key = key,
                                name = b.name,
                                argsJson = b.argsJson,
                                result = res?.text ?: matched?.resultJson,
                                ok = res?.isError != true && matched?.success != false,
                                durationMs = matched?.durationMs?.takeIf { it > 0 },
                            )
                    }

                    is TranscriptBlock.ToolResult -> Unit // rendered as part of the chip

                    is TranscriptBlock.Image ->
                        items += TranscriptItemUi.Screenshot(key, b.dropped)
                }
            }
        }

        val panel =
            ContextPanelUi(
                usedTokens = context?.usedTokens ?: 0,
                windowTokens = context?.windowTokens ?: 0,
                costUsd = context?.costUsd ?: usage.totalCostUsd,
                screenshotCount = context?.screenshotCount ?: 0,
                perModel = usage.perModel,
            )

        return SessionDetailUiState(
            loading = false,
            title = session?.title?.ifBlank { "Untitled session" } ?: "Session",
            status = session?.status.orEmpty(),
            model = session?.modelDefault.orEmpty(),
            items = items,
            context = panel,
        )
    }
}
