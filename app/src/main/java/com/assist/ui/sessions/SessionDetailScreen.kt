package com.assist.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.assist.data.AgentModel
import com.assist.data.TranscriptRole

/**
 * Session detail / transcript (phase-12): the full exchange — user/assistant/
 * system messages, tool-call chips (name + args + result + ok/fail), thinking
 * indicators, screenshot markers — plus a context/usage panel (tokens vs window,
 * per-model cost, screenshot count).
 */
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOf(state.status, modelLabel(state.model))
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }

            // Mid-session model switcher: a running loop re-reads the session's
            // model every step, so this takes effect on the next request.
            item {
                ModelChips(
                    selected = AgentModel.fromModelId(state.model),
                    onSelect = viewModel::setModel,
                )
            }

            state.context?.let { item { ContextPanel(it) } }

            if (!state.loading && state.items.isEmpty()) {
                item {
                    Text(
                        "No messages yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.items, key = { it.key }) { item -> TranscriptItem(item) }
        }
    }
}

@Composable
private fun ContextPanel(panel: ContextPanelUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Context & cost", style = MaterialTheme.typography.titleSmall)
            val pct = if (panel.windowTokens > 0) {
                (panel.usedTokens.toFloat() / panel.windowTokens).coerceIn(0f, 1f)
            } else {
                0f
            }
            Text(
                "${formatTokens(panel.usedTokens)} / ${formatTokens(panel.windowTokens)} tokens" +
                    " · ${panel.screenshotCount} screenshots",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Text("Total ${formatUsd(panel.costUsd)}", style = MaterialTheme.typography.bodyMedium)
            panel.perModel.forEach { m ->
                Text(
                    "  ${m.model}: ${formatUsd(m.costUsd)}  (${formatTokens(m.inputTokens)} in / ${formatTokens(m.outputTokens)} out · ${m.turns} turns)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TranscriptItem(item: TranscriptItemUi) {
    when (item) {
        is TranscriptItemUi.Message -> MessageBubble(item)
        is TranscriptItemUi.Thinking -> Text(
            "💭 ${item.preview}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        is TranscriptItemUi.ToolChip -> ToolChip(item)
        is TranscriptItemUi.Screenshot -> Text(
            if (item.dropped) "🖼 screenshot (dropped to save context)" else "🖼 screenshot",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun MessageBubble(item: TranscriptItemUi.Message) {
    val label = when (item.role) {
        TranscriptRole.USER -> "You"
        TranscriptRole.ASSISTANT -> "Assist"
        TranscriptRole.SYSTEM -> "System (steering)"
        TranscriptRole.SYSTEM_NOTE -> "System note"
        TranscriptRole.TOOL_RESULT -> "Tool result"
    }
    val container = when (item.role) {
        TranscriptRole.ASSISTANT -> MaterialTheme.colorScheme.primaryContainer
        TranscriptRole.USER -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToolChip(item: TranscriptItemUi.ToolChip) {
    val statusColor = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔧 ${item.name}", style = MaterialTheme.typography.titleSmall)
                val dur = item.durationMs?.let { " · ${it}ms" } ?: ""
                Text(
                    (if (item.ok) "ok" else "failed") + dur,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }
            if (item.argsJson.isNotBlank() && item.argsJson != "{}") {
                Text(
                    item.argsJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.result?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
