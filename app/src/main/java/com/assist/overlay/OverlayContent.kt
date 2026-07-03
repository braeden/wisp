package com.assist.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assist.data.SessionEntity

/**
 * Root of the overlay content. Renders the collapsed [Bubble] or the expanded
 * [Panel] against a single [OverlayUiState]. All window-level effects are handled
 * by [OverlayService] through the passed callbacks.
 *
 * @param onDrag pixel delta for the current drag gesture (moves the window).
 * @param onSetFocusable request the window become focusable (to capture typing).
 * @param onStop stop the overlay entirely.
 */
@Composable
fun OverlayRoot(
    state: OverlayUiState,
    sessions: List<SessionEntity>,
    dictating: Boolean,
    onToggleExpanded: () -> Unit,
    onDrag: (Offset) -> Unit,
    onInterrupt: () -> Unit,
    onRecordNewMessage: () -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (Long) -> Unit,
    onSubmitReply: (String) -> Unit,
    onDictate: suspend () -> String?,
    onSetFocusable: (Boolean) -> Unit,
    onStop: () -> Unit,
) {
    if (state.expanded) {
        Panel(
            state = state,
            sessions = sessions,
            dictating = dictating,
            onCollapse = onToggleExpanded,
            onDrag = onDrag,
            onInterrupt = onInterrupt,
            onNewSession = onNewSession,
            onSwitchSession = onSwitchSession,
            onSubmitReply = onSubmitReply,
            onDictate = onDictate,
            onSetFocusable = onSetFocusable,
            onStop = onStop,
        )
    } else {
        Bubble(
            state = state,
            dictating = dictating,
            onTap = onToggleExpanded,
            onInterrupt = onInterrupt,
            onRecordNewMessage = onRecordNewMessage,
            onDrag = onDrag,
        )
    }
}

// --- Collapsed bubble -------------------------------------------------------

@Composable
private fun Bubble(
    state: OverlayUiState,
    dictating: Boolean,
    onTap: () -> Unit,
    onInterrupt: () -> Unit,
    onRecordNewMessage: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    val busy = state.phase != AgentPhase.IDLE && state.phase != AgentPhase.LISTENING
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = phaseColor(state.phase),
        shadowElevation = 6.dp,
        modifier = Modifier.pointerInput(Unit) {
            detectDragGestures { change, delta ->
                change.consume()
                onDrag(delta)
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Status + tap-to-expand (only this region expands; buttons act directly).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
                Text(
                    text = phaseLabel(state.phase),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            // Record a new spoken instruction (runs it as a task). While capturing,
            // the mic goes hot: red glyph on a white pill so the state is obvious.
            Surface(
                shape = CircleShape,
                color = if (dictating) Color.White else Color.Transparent,
                modifier = Modifier.size(36.dp),
            ) {
                IconButton(
                    onClick = { if (!dictating) onRecordNewMessage() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        MicIcon,
                        contentDescription = if (dictating) "Recording…" else "Record a message",
                        tint = if (dictating) RecordingRed else Color.White,
                    )
                }
            }
            // Stop the current task.
            IconButton(onClick = onInterrupt, modifier = Modifier.size(36.dp)) {
                StopGlyph()
            }
        }
    }
}

// --- Expanded panel ---------------------------------------------------------

@Composable
private fun Panel(
    state: OverlayUiState,
    sessions: List<SessionEntity>,
    dictating: Boolean,
    onCollapse: () -> Unit,
    onDrag: (Offset) -> Unit,
    onInterrupt: () -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (Long) -> Unit,
    onSubmitReply: (String) -> Unit,
    onDictate: suspend () -> String?,
    onSetFocusable: (Boolean) -> Unit,
    onStop: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: drag handle + tap-to-collapse, with – (collapse) and ✕ (hide).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, delta ->
                            change.consume()
                            onDrag(delta)
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onCollapse() }) },
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(phaseColor(state.phase)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = phaseLabel(state.phase),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                    Text("–", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Hide overlay",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            state.intent?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
            }

            Hud(state.hud)

            Spacer(Modifier.height(8.dp))

            // Scrollable transcript region.
            Column(
                modifier = Modifier
                    .heightInBounded()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.isThinking && state.assistantText.isBlank()) {
                    ThinkingRow()
                }
                if (state.assistantText.isNotBlank()) {
                    Text(
                        text = state.assistantText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.toolChips.forEach { ToolChipRow(it) }
                state.error?.let {
                    Text(
                        text = "Error: $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.summary?.takeIf { state.finished }?.let {
                    Text(
                        text = "Done: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Confirmation gate: Yes/No feed straight back through the reply seam.
            state.confirmation?.let { prompt ->
                Spacer(Modifier.height(8.dp))
                ConfirmationRow(prompt, onSubmitReply)
            }

            Spacer(Modifier.height(10.dp))
            ReplyBar(
                dictating = dictating,
                onSubmitReply = onSubmitReply,
                onDictate = onDictate,
                onInterrupt = onInterrupt,
                onSetFocusable = onSetFocusable,
            )

            Spacer(Modifier.height(6.dp))
            SessionsRow(
                sessions = sessions,
                currentId = state.sessionId,
                onSwitchSession = onSwitchSession,
                onNewSession = onNewSession,
            )
        }
    }
}

@Composable
private fun Hud(hud: HudState?) {
    if (hud == null) {
        Text(
            text = "No active session",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        LinearProgressIndicator(
            progress = { hud.contextFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${formatTokens(hud.usedTokens)}/${formatTokens(hud.windowTokens)} ctx",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "\$${"%.4f".format(hud.costUsd)} · ${hud.screenshotCount} photos",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolChipRow(chip: ToolChip) {
    val (tint, glyph) = when (chip.status) {
        ToolStatus.RUNNING -> MaterialTheme.colorScheme.primary to "…"
        ToolStatus.SUCCESS -> Color(0xFF2E7D32) to "✓"
        ToolStatus.FAILURE -> MaterialTheme.colorScheme.error to "✗"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = glyph, color = tint, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chip.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                val detail = chip.result?.takeIf { it.isNotBlank() } ?: chip.argsJson
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationRow(prompt: ConfirmationPrompt, onSubmitReply: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = prompt.question,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSubmitReply("yes") }) { Text("Yes") }
                OutlinedButton(onClick = { onSubmitReply("no") }) { Text("No") }
            }
        }
    }
}

/**
 * Always-visible reply bar: one text box (mic that becomes a send arrow once
 * there's text, as its trailing icon) plus a stop button. The window is
 * non-focusable by default, so a transparent tap-catcher sits over the field
 * until the first tap flips the window focusable and moves focus in — after
 * which the soft keyboard appears.
 */
@Composable
private fun ReplyBar(
    dictating: Boolean,
    onSubmitReply: (String) -> Unit,
    onDictate: suspend () -> String?,
    onInterrupt: () -> Unit,
    onSetFocusable: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var inputActive by remember { mutableStateOf(false) }

    fun submit() {
        if (text.isBlank()) return
        onSubmitReply(text)
        text = ""
        inputActive = false
        onSetFocusable(false)
    }

    LaunchedEffect(inputActive) {
        if (inputActive) runCatching { focusRequester.requestFocus() }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Message…", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = {
                    when {
                        dictating -> Icon(
                            MicIcon,
                            contentDescription = "Recording…",
                            tint = RecordingRed,
                        )
                        text.isNotBlank() -> IconButton(onClick = { submit() }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                        else -> IconButton(onClick = {
                            scope.launch {
                                val spoken = onDictate()
                                if (!spoken.isNullOrBlank()) {
                                    text = if (text.isBlank()) spoken else "$text $spoken"
                                }
                            }
                        }) { Icon(MicIcon, contentDescription = "Dictate") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (!inputActive) {
                // Tap-catcher over the field (but not the trailing icon): the first
                // tap flips the window focusable, then focus + IME follow.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(end = 48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                inputActive = true
                                onSetFocusable(true)
                            })
                        },
                )
            }
        }
        IconButton(onClick = onInterrupt) { StopGlyph(MaterialTheme.colorScheme.error) }
    }
}

/**
 * Collapsed session strip: the current session's title with an expand caret and
 * a "+" (new session). Expanding lists recent sessions to switch to.
 */
@Composable
private fun SessionsRow(
    sessions: List<SessionEntity>,
    currentId: Long?,
    onSwitchSession: (Long) -> Unit,
    onNewSession: () -> Unit,
) {
    var listOpen by remember { mutableStateOf(false) }
    val current = sessions.firstOrNull { it.id == currentId }?.title ?: "No session"

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = (if (listOpen) "▾ " else "▸ ") + current,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { listOpen = !listOpen },
            )
            IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "New session",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (listOpen) {
            sessions.take(5).forEach { session ->
                val selected = session.id == currentId
                TextButton(
                    onClick = {
                        onSwitchSession(session.id)
                        listOpen = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (if (selected) "• " else "") + session.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// --- Small helpers ----------------------------------------------------------

@Composable
private fun StopGlyph(color: Color = Color.White) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** Hot-mic tint shared by the record affordances. */
private val RecordingRed = Color(0xFFD32F2F)

@Composable
private fun Modifier.heightInBounded(): Modifier = this.then(Modifier.height(220.dp))

@Composable
private fun phaseColor(phase: AgentPhase): Color = when (phase) {
    // Deep purple, dark enough that the bubble's white text/icons stay readable
    // regardless of the dynamic Material theme.
    AgentPhase.IDLE -> Color(0xFF4A148C)
    AgentPhase.LISTENING -> Color(0xFF6A1B9A)
    AgentPhase.THINKING -> Color(0xFF1565C0)
    AgentPhase.SPEAKING -> Color(0xFF00838F)
    AgentPhase.ACTING -> Color(0xFFEF6C00)
}

private fun phaseLabel(phase: AgentPhase): String = when (phase) {
    AgentPhase.IDLE -> "Assist"
    AgentPhase.LISTENING -> "Listening"
    AgentPhase.THINKING -> "Thinking"
    AgentPhase.SPEAKING -> "Speaking"
    AgentPhase.ACTING -> "Acting"
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.0fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}
