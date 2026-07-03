package com.assist.ui

import androidx.lifecycle.ViewModel
import com.assist.data.SecretStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** UI state + actions for the onboarding/home screen. */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val secretStore: SecretStore,
    ) : ViewModel() {
        private val _hasApiKey = MutableStateFlow(secretStore.hasApiKey())
        val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

        fun saveApiKey(raw: String) {
            secretStore.setApiKey(raw)
            _hasApiKey.value = secretStore.hasApiKey()
        }
    }
