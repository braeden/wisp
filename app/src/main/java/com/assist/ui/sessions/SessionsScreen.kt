package com.assist.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.assist.R
import com.assist.agent.AgentService
import com.assist.overlay.OverlayService
import com.assist.ui.Permissions

/**
 * Sessions list (phase-12, the lead surface): a scrollable list of every session
 * with its title/intent, timestamp, status, running cost, and message count.
 * Actions: new session, open (transcript), rename, delete. The Fast-mode toggle
 * and a link to the learned-tasks browser live at the top.
 */
@Composable
fun SessionsScreen(
    onOpenSession: (Long) -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    var renameTarget by remember { mutableStateOf<SessionRowUi?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionRowUi?>(null) }
    var showStartDialog by remember { mutableStateOf(false) }

    // NOTE: no inner Scaffold here — this screen renders inside MainActivity's
    // Scaffold; nesting a second one double-applied insets and added layout work
    // to every frame of a scroll.
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Sessions", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            // Voice-first entry: the overlay comes up already listening. The
            // typed dialog remains the fallback when overlay/mic aren't granted.
            Button(
                onClick = {
                    if (Permissions.canDrawOverlays(context) &&
                        Permissions.hasMicrophone(context)
                    ) {
                        OverlayService.startListening(context)
                    } else {
                        showStartDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start a task") }
        }

        if (!state.loading && state.rows.isEmpty()) {
            item {
                Text(
                    "No sessions yet — tap \"Start a task\" and just say what you want done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.rows, key = { it.id }) { row ->
            SessionCard(
                row = row,
                onOpen = { onOpenSession(row.id) },
                onRename = { renameTarget = row },
                onDelete = { deleteTarget = row },
            )
        }
    }

    if (showStartDialog) {
        StartTaskDialog(
            onDismiss = { showStartDialog = false },
            onStart = { intent ->
                // Launch the agent loop in its foreground service, and surface the
                // overlay (if permitted) so the run is visible while it drives apps.
                ContextCompat.startForegroundService(
                    context,
                    AgentService.runIntent(context, intent),
                )
                if (Permissions.canDrawOverlays(context)) OverlayService.start(context)
                Toast
                    .makeText(
                        context,
                        context.getString(R.string.start_session_started, intent.take(40)),
                        Toast.LENGTH_SHORT,
                    ).show()
                showStartDialog = false
            },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { newTitle ->
                viewModel.rename(target.id, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = { Text("\"${target.title}\" and its screenshots will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionCard(
    row: SessionRowUi,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        // Bottom padding is small on purpose: the TextButton row below already
        // carries ~12dp of internal touch-target padding.
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(2.dp))
            // status is omitted while it's the default "active" — today nothing ends
            // a session, so the prefix carried no information.
            val statusPrefix = row.status.takeIf { it != "active" }?.let { "$it · " } ?: ""
            Text(
                text =
                    statusPrefix + "${modelLabel(row.model)} · ${row.messageCount} msgs · " +
                        "${formatUsd(row.costUsd)} · ${formatRelativeTime(row.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(field.trim().ifBlank { initial }) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StartTaskDialog(
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    var intent by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_session)) },
        text = {
            OutlinedTextField(
                value = intent,
                onValueChange = { intent = it },
                label = { Text(stringResource(R.string.start_session_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(intent.trim()) },
                enabled = intent.isNotBlank(),
            ) { Text(stringResource(R.string.start_session_go)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
