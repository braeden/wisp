package com.assist.ui.sessions

import androidx.lifecycle.ViewModel
import com.assist.data.AgentModel
import com.assist.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Exposes the persisted Fast-mode toggle and model selection for the settings UI. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) : ViewModel() {
        val fastMode: StateFlow<Boolean> = settings.fastMode

        fun setFastMode(enabled: Boolean) = settings.setFastModeEnabled(enabled)

        val agentModel: StateFlow<AgentModel> = settings.agentModel

        fun setAgentModel(model: AgentModel) = settings.setAgentModel(model)
    }
