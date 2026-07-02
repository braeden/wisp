package com.assist.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Learned-tasks (recipes) browser (phase-12): lists the `TaskRecipe` index
 * (title, app, use count, last used) with "view content" (the recipe markdown)
 * and "delete" (removes both the index row and the `/memories/tasks/<slug>.md` file).
 */
@Composable
fun RecipesScreen(
    onBack: () -> Unit,
    viewModel: RecipesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val viewing by viewModel.viewing.collectAsState()
    var deleteTarget by remember { mutableStateOf<RecipeRowUi?>(null) }

    Scaffold { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Learned tasks", style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
            item {
                Text(
                    "Recipes the agent recorded under /memories/tasks. It checks these " +
                        "first to complete repeat tasks faster.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.loading && state.rows.isEmpty()) {
                item {
                    Text(
                        "No recipes yet. The agent writes one after finishing a novel task.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.rows, key = { it.id }) { row ->
                RecipeCard(
                    row = row,
                    onView = { viewModel.viewContent(row.id, row.title) },
                    onDelete = { deleteTarget = row },
                )
            }
        }
    }

    viewing?.let { content ->
        AlertDialog(
            onDismissRequest = viewModel::dismissContent,
            title = { Text(content.title) },
            text = {
                Text(
                    content.markdown,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismissContent) { Text("Close") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recipe?") },
            text = { Text("\"${target.title}\" and its memory file will be permanently deleted.") },
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
private fun RecipeCard(
    row: RecipeRowUi,
    onView: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            val meta = buildList {
                row.appPackage?.let { add(it) }
                add("used ${row.useCount}×")
                add(formatRelativeTime(row.lastUsedAt))
            }.joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                row.memoryPath,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onView) { Text("View") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
