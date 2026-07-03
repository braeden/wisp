package com.assist.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assist.R
import com.assist.overlay.OverlayService
import com.assist.ui.sessions.FastModeCard
import com.assist.ui.sessions.ModelPickerCard
import com.assist.ui.sessions.SettingsViewModel

/**
 * Setup + preferences + debug surface (the "Settings" tab). Absorbs the old
 * onboarding screen: permission grants, the encrypted API-key field, the agent
 * model picker, fast mode, the overlay toggle, and the voice-test entry point.
 * Starting a task now lives on the Sessions tab.
 */
@Composable
fun SettingsScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val agentModel by settingsViewModel.agentModel.collectAsState()
    val fastMode by settingsViewModel.fastMode.collectAsState()

    // Bumped on every ON_RESUME so permission rows re-read after the user returns
    // from a Settings deep-link.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // resumeTick participates so these recompute after returning from Settings.
    @Suppress("UNUSED_EXPRESSION")
    resumeTick
    val accessibilityOk = Permissions.isAccessibilityEnabled(context)
    val overlayOk = Permissions.canDrawOverlays(context)
    val micOk = Permissions.hasMicrophone(context)
    val notifOk =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            Permissions.hasNotifications(context)

    val micLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { resumeTick++ }
    val notifLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { resumeTick++ }

    Scaffold { inner ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
            )

            // --- Agent: the knobs you actually reach for -----------------------
            SectionHeader("Agent")
            ModelPickerCard(
                selected = agentModel,
                onSelect = settingsViewModel::setAgentModel,
            )
            FastModeCard(
                enabled = fastMode,
                onToggle = settingsViewModel::setFastMode,
                supported = agentModel.supportsFast,
            )
            OverlayCard(overlayOk = overlayOk)

            // --- Setup: one-time provisioning ----------------------------------
            SectionHeader("Setup")
            PermissionsCard(
                accessibilityOk = accessibilityOk,
                overlayOk = overlayOk,
                micOk = micOk,
                notifOk = notifOk,
                onAccessibility = { Permissions.openAccessibilitySettings(context) },
                onOverlay = { Permissions.openOverlaySettings(context) },
                onMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onNotif = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            ApiKeyCard(
                hasApiKey = hasApiKey,
                onSave = viewModel::saveApiKey,
            )

            // --- Debug ----------------------------------------------------------
            SectionHeader("Debug")
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(context, VoiceTestActivity::class.java),
                    )
                },
                enabled = micOk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_test_open))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionsCard(
    accessibilityOk: Boolean,
    overlayOk: Boolean,
    micOk: Boolean,
    notifOk: Boolean,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onMic: () -> Unit,
    onNotif: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.permissions_header),
                style = MaterialTheme.typography.titleMedium,
            )
            PermissionRow(
                title = stringResource(R.string.perm_accessibility),
                desc = stringResource(R.string.perm_accessibility_desc),
                granted = accessibilityOk,
                onGrant = onAccessibility,
            )
            PermissionRow(
                title = stringResource(R.string.perm_overlay),
                desc = stringResource(R.string.perm_overlay_desc),
                granted = overlayOk,
                onGrant = onOverlay,
            )
            PermissionRow(
                title = stringResource(R.string.perm_microphone),
                desc = stringResource(R.string.perm_microphone_desc),
                granted = micOk,
                onGrant = onMic,
            )
            PermissionRow(
                title = stringResource(R.string.perm_notifications),
                desc = stringResource(R.string.perm_notifications_desc),
                granted = notifOk,
                onGrant = onNotif,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Text(
                text = stringResource(R.string.status_granted),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            OutlinedButton(onClick = onGrant) {
                Text(stringResource(R.string.status_missing))
            }
        }
    }
}

@Composable
private fun OverlayCard(overlayOk: Boolean) {
    val context = LocalContext.current
    // Reflect the overlay's *actual* run state (survives leaving/returning to the
    // app), not a local toggle that desyncs when the overlay is closed elsewhere.
    val shown by OverlayService.running.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.overlay_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.overlay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!overlayOk) {
                Text(
                    text = stringResource(R.string.overlay_needs_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    if (shown) OverlayService.stop(context) else OverlayService.start(context)
                },
                enabled = overlayOk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (shown) R.string.overlay_hide else R.string.overlay_show,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    hasApiKey: Boolean,
    onSave: (String) -> Unit,
) {
    var field by remember { mutableStateOf("") }
    var justSaved by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.api_key_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text =
                    stringResource(
                        if (hasApiKey) R.string.api_key_present else R.string.api_key_absent,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    justSaved = false
                },
                label = { Text(stringResource(R.string.api_key_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        onSave(field)
                        field = ""
                        justSaved = true
                    },
                    enabled = field.isNotBlank(),
                ) {
                    Text(stringResource(R.string.api_key_save))
                }
                if (justSaved) {
                    Text(
                        text = stringResource(R.string.api_key_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
