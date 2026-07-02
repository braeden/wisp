package com.assist.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.assist.ui.theme.AssistTheme
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the manual voice test screen (phase-08). Launched from onboarding. */
@AndroidEntryPoint
class VoiceTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssistTheme {
                VoiceTestScreen()
            }
        }
    }
}
