package com.assist.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Manual voice test surface (phase-08): exercise TTS (`say`), one-shot STT
 * (`listen`), push-to-talk (hold), and a real agent run from a typed/spoken
 * intent — all without the wake word (phase-09). Reached from onboarding.
 */
@Composable
fun VoiceTestScreen(viewModel: VoiceTestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var sayText by remember { mutableStateOf("") }
    var intentText by remember { mutableStateOf("") }

    Scaffold { inner ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Voice test", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Status: ${state.status}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Speak (TTS)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = sayText,
                        onValueChange = { sayText = it },
                        label = { Text("Text to speak (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.say(sayText) },
                        enabled = !state.speaking,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.speaking) "Speaking…" else "Say") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Listen (STT)", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { viewModel.listen() },
                        enabled = !state.listening,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.listening) "Listening…" else "Listen once") }

                    // Push-to-talk: capture while held, endpoint on release.
                    OutlinedButton(
                        onClick = {},
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            viewModel.listen()
                                            tryAwaitRelease()
                                            viewModel.stopListening()
                                        },
                                    )
                                },
                    ) { Text("Hold to talk") }

                    if (state.heard.isNotBlank()) {
                        Text("Heard: ${state.heard}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Run a task", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = intentText,
                        onValueChange = { intentText = it },
                        label = { Text("e.g. open Clock and start a timer") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.runAgent(intentText) },
                        enabled = intentText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Run agent") }
                    OutlinedButton(
                        onClick = {
                            val heard = state.heard
                            if (heard.isNotBlank()) viewModel.runAgent(heard)
                        },
                        enabled = state.heard.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Run the spoken intent") }
                }
            }
        }
    }
}
