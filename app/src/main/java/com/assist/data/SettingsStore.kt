package com.assist.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small persisted user preferences (phase-12). Currently just the **Fast mode**
 * toggle: when on, the agent loop requests Anthropic fast mode (Opus 4.8/4.7),
 * which needs research-preview access and is billed at premium pricing. Default
 * OFF. Kept tiny and separate from [SecretStore] (no encryption needed).
 */
interface SettingsStore {
    fun isFastModeEnabled(): Boolean
    fun setFastModeEnabled(enabled: Boolean)

    /** Observable for UI; emits the current value immediately. */
    val fastMode: StateFlow<Boolean>
}

class PrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _fastMode = MutableStateFlow(prefs.getBoolean(KEY_FAST_MODE, false))

    override val fastMode: StateFlow<Boolean> = _fastMode.asStateFlow()

    override fun isFastModeEnabled(): Boolean = _fastMode.value

    override fun setFastModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAST_MODE, enabled).apply()
        _fastMode.value = enabled
    }

    private companion object {
        const val PREFS = "assist_settings"
        const val KEY_FAST_MODE = "fast_mode_enabled"
    }
}
