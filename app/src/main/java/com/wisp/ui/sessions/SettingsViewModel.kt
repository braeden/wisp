package com.wisp.ui.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.data.AgentModel
import com.wisp.data.SettingsStore
import com.wisp.voice.android.TtsEngineChoice
import com.wisp.voice.android.TtsEngines
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings-tab state: the persisted Fast-mode toggle, model selection, TTS
 * engine choice (discovered on-device engines), and the wake-word model name.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsStore,
    ) : ViewModel() {
        val fastMode: StateFlow<Boolean> = settings.fastMode

        fun setFastMode(enabled: Boolean) = settings.setFastModeEnabled(enabled)

        val agentModel: StateFlow<AgentModel> = settings.agentModel

        fun setAgentModel(model: AgentModel) = settings.setAgentModel(model)

        // --- TTS engine -------------------------------------------------------

        val ttsEngine: StateFlow<String?> = settings.ttsEngine

        fun setTtsEngine(enginePackage: String?) = settings.setTtsEngine(enginePackage)

        private val _ttsEngines = MutableStateFlow<List<TtsEngineChoice>>(emptyList())

        /** Installed engines for the dropdown (loaded once, async). */
        val ttsEngines: StateFlow<List<TtsEngineChoice>> = _ttsEngines.asStateFlow()

        private val _defaultTtsEngine = MutableStateFlow<String?>(null)

        /** The system default engine package (labels the "System default" row). */
        val defaultTtsEngine: StateFlow<String?> = _defaultTtsEngine.asStateFlow()

        init {
            viewModelScope.launch {
                val discovery = TtsEngines.discover(context)
                _ttsEngines.value = discovery.engines
                _defaultTtsEngine.value = discovery.defaultEngine
            }
        }

        // --- Wake word ----------------------------------------------------------

        val wakeKeyword: StateFlow<String> = settings.wakeKeyword

        fun setWakeKeyword(keyword: String) = settings.setWakeKeyword(keyword)
    }
