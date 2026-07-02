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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.assist.R

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val hasApiKey by viewModel.hasApiKey.collectAsState()

    // Bumped on every ON_RESUME so permission rows re-read after the user returns
    // from a Settings deep-link.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
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
    val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        Permissions.hasNotifications(context)

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { resumeTick++ }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { resumeTick++ }

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            val ready = hasApiKey && accessibilityOk
            Button(
                onClick = { /* no-op until phase-06 wires the agent loop */ },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start_session))
            }

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
                text = stringResource(
                    if (hasApiKey) R.string.api_key_present else R.string.api_key_absent,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; justSaved = false },
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
