package com.wisp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which Claude model the agent loop drives the phone with. The setting is the
 * **default for new sessions**; each session carries its own current model
 * (`SessionEntity.modelDefault`) which can be switched mid-session from the
 * transcript screen — the loop re-reads it every step (a swap busts the prompt
 * cache once, then re-caches on the new model).
 *
 * [supportsFast] marks the models eligible for Anthropic fast mode (Opus 4.8/4.7
 * only) so the UI can gate the Fast-mode toggle.
 */
enum class AgentModel(
    val modelId: String,
    val label: String,
    val blurb: String,
    val supportsFast: Boolean,
) {
    SONNET(
        modelId = "claude-sonnet-5",
        label = "Sonnet 5",
        blurb = "Balanced default — capable and ~40% cheaper per token than Opus.",
        supportsFast = false,
    ),
    OPUS(
        modelId = "claude-opus-4-8",
        label = "Opus 4.8",
        blurb = "Most capable. Best on ambiguous screens and long tasks. Highest cost.",
        supportsFast = true,
    ),
    HAIKU(
        modelId = "claude-haiku-4-5",
        label = "Haiku 4.5",
        blurb = "Fastest and cheapest (~80% less). May miss on complex UIs.",
        supportsFast = false,
    ),
    ;

    companion object {
        val DEFAULT = SONNET

        fun fromName(name: String?): AgentModel = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Resolve a raw model id (e.g. from a session row); null if unknown. */
        fun fromModelId(modelId: String?): AgentModel? =
            entries.firstOrNull { it.modelId == modelId }

        fun supportsFast(modelId: String?): Boolean = fromModelId(modelId)?.supportsFast == true
    }
}

/**
 * Small persisted user preferences (phase-12). Holds the **Fast mode** toggle and
 * the agent **model** selection. Kept tiny and separate from [SecretStore] (no
 * encryption needed).
 *
 * Fast mode: when on, the agent loop requests Anthropic fast mode (Opus 4.8/4.7),
 * which needs research-preview access and is billed at premium pricing. Default OFF.
 */
interface SettingsStore {
    fun isFastModeEnabled(): Boolean

    fun setFastModeEnabled(enabled: Boolean)

    /** Observable for UI; emits the current value immediately. */
    val fastMode: StateFlow<Boolean>

    fun setAgentModel(model: AgentModel)

    /**
     * Current model + observable. Read the snapshot as `agentModel.value` —
     * a `getAgentModel()` method would clash with this property's JVM getter.
     */
    val agentModel: StateFlow<AgentModel>

    fun setTtsEngine(enginePackage: String?)

    /** TTS engine package to synthesize with; null = system default engine. */
    val ttsEngine: StateFlow<String?>

    fun setWakeKeyword(keyword: String)

    /** Wake keyword name (a Porcupine built-in, e.g. "PORCUPINE"). */
    val wakeKeyword: StateFlow<String>
}

class PrefsSettingsStore(
    context: Context,
) : SettingsStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _fastMode = MutableStateFlow(prefs.getBoolean(KEY_FAST_MODE, false))
    private val _agentModel =
        MutableStateFlow(AgentModel.fromName(prefs.getString(KEY_MODEL, null)))

    override val fastMode: StateFlow<Boolean> = _fastMode.asStateFlow()

    override fun isFastModeEnabled(): Boolean = _fastMode.value

    override fun setFastModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAST_MODE, enabled).apply()
        _fastMode.value = enabled
    }

    override val agentModel: StateFlow<AgentModel> = _agentModel.asStateFlow()

    override fun setAgentModel(model: AgentModel) {
        prefs.edit().putString(KEY_MODEL, model.name).apply()
        _agentModel.value = model
    }

    private val _ttsEngine = MutableStateFlow(prefs.getString(KEY_TTS_ENGINE, null))

    override val ttsEngine: StateFlow<String?> = _ttsEngine.asStateFlow()

    override fun setTtsEngine(enginePackage: String?) {
        prefs
            .edit()
            .apply {
                if (enginePackage.isNullOrBlank()) {
                    remove(KEY_TTS_ENGINE)
                } else {
                    putString(KEY_TTS_ENGINE, enginePackage)
                }
            }.apply()
        _ttsEngine.value = enginePackage?.takeIf { it.isNotBlank() }
    }

    private val _wakeKeyword =
        MutableStateFlow(prefs.getString(KEY_WAKE_KEYWORD, null) ?: DEFAULT_WAKE_KEYWORD)

    override val wakeKeyword: StateFlow<String> = _wakeKeyword.asStateFlow()

    override fun setWakeKeyword(keyword: String) {
        prefs.edit().putString(KEY_WAKE_KEYWORD, keyword).apply()
        _wakeKeyword.value = keyword
    }

    private companion object {
        const val PREFS = "wisp_settings"
        const val KEY_FAST_MODE = "fast_mode_enabled"
        const val KEY_MODEL = "agent_model"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_WAKE_KEYWORD = "wake_keyword"
        const val DEFAULT_WAKE_KEYWORD = "PORCUPINE"
    }
}
