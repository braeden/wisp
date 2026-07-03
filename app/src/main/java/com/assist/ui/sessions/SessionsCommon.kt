package com.assist.ui.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assist.data.AgentModel

/** USD formatting shared across the session screens. */
fun formatUsd(value: Double): String =
    when {
        value <= 0.0 -> "$0.00"
        value < 0.01 -> "$" + "%.4f".format(value)
        else -> "$" + "%.2f".format(value)
    }

/** Human-readable relative timestamp ("5 min ago"). */
fun formatRelativeTime(millis: Long): String =
    DateUtils
        .getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

/** Compact token count ("12.3K / 1.0M"). */
fun formatTokens(count: Int): String =
    when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> "%.1fK".format(count / 1_000.0)
        else -> "%.1fM".format(count / 1_000_000.0)
    }

/** Short human label for a model id ("Sonnet 5"); falls back to the raw id. */
fun modelLabel(modelId: String): String =
    AgentModel.fromModelId(modelId)?.label ?: modelId.removePrefix("claude-")

/**
 * The persisted Fast-mode toggle (phase-12). [supported] gates the switch to the
 * models that actually take fast mode (Opus 4.8/4.7) — on other models it is
 * disabled and explains why.
 */
@Composable
fun FastModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supported: Boolean = true,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Fast mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (supported) {
                        "~2.5× faster output on Opus 4.8/4.7. Requires API fast-mode " +
                            "access and bills at premium (2×) pricing. Off by default."
                    } else {
                        "Only available on Opus 4.8/4.7 — switch the default model " +
                            "to Opus to enable."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled && supported,
                onCheckedChange = onToggle,
                enabled = supported,
            )
        }
    }
}

/** Reusable Sonnet/Opus/Haiku chip row (settings default + per-session switcher). */
@Composable
fun ModelChips(
    selected: AgentModel?,
    onSelect: (AgentModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentModel.entries.forEach { model ->
            FilterChip(
                selected = model == selected,
                onClick = { onSelect(model) },
                label = { Text(model.label) },
            )
        }
    }
}

/**
 * Default-model selector: which model **new sessions** start on. A live session's
 * model is switched from its transcript screen and takes effect on the next step.
 */
@Composable
fun ModelPickerCard(
    selected: AgentModel,
    onSelect: (AgentModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Default model", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used for new sessions. Switch a running session from its transcript.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ModelChips(selected = selected, onSelect = onSelect)
            Spacer(Modifier.height(6.dp))
            Text(
                selected.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
