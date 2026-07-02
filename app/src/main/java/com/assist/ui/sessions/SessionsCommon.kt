package com.assist.ui.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** USD formatting shared across the session screens. */
fun formatUsd(value: Double): String = when {
    value <= 0.0 -> "$0.00"
    value < 0.01 -> "$" + "%.4f".format(value)
    else -> "$" + "%.2f".format(value)
}

/** Human-readable relative timestamp ("5 min ago"). */
fun formatRelativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/** Compact token count ("12.3K / 1.0M"). */
fun formatTokens(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "%.1fK".format(count / 1_000.0)
    else -> "%.1fM".format(count / 1_000_000.0)
}

/**
 * The persisted Fast-mode toggle (phase-12). Reusable so the main agent can drop
 * it into onboarding/settings. Notes the access + pricing caveats.
 */
@Composable
fun FastModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
                    "~2.5× faster output on Opus 4.8/4.7. Requires API fast-mode access " +
                        "and bills at premium (2×) pricing. Off by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
