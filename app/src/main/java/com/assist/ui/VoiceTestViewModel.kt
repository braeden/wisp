package com.assist.ui

import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assist.agent.AgentService
import com.assist.voice.SttEngine
import com.assist.voice.SttException
import com.assist.voice.TtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import javax.inject.Inject

/** Backing state for the manual voice test screen (say / listen / push-to-talk). */
data class VoiceTestUiState(
    val status: String = "Idle",
    val heard: String = "",
    val listening: Boolean = false,
    val speaking: Boolean = false,
)

/**
 * Drives the phase-08 manual test screen straight against the [SttEngine] /
 * [TtsEngine] seams (no wake word needed) so voice is exercisable on-device.
 */
@HiltViewModel
class VoiceTestViewModel @Inject constructor(
    private val stt: SttEngine,
    private val tts: TtsEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceTestUiState())
    val state: StateFlow<VoiceTestUiState> = _state.asStateFlow()

    private var listenJob: Job? = null

    fun say(text: String) {
        val toSpeak = text.ifBlank { "Hi, this is Assist. Voice output is working." }
        viewModelScope.launch {
            _state.value = _state.value.copy(speaking = true, status = "Speaking…")
            runCatching { tts.say(toSpeak) }
            _state.value = _state.value.copy(speaking = false, status = "Idle")
        }
    }

    /** One-shot listen (also serves press-and-release push-to-talk). */
    fun listen() {
        if (_state.value.listening) return
        listenJob = viewModelScope.launch {
            _state.value = _state.value.copy(listening = true, status = "Listening…", heard = "")
            val outcome = runCatching { stt.transcribeOnce() }
            _state.value = outcome.fold(
                onSuccess = { r ->
                    _state.value.copy(listening = false, status = "Heard", heard = r.text)
                },
                onFailure = { e ->
                    val why = (e as? SttException)?.error?.name ?: e.message ?: "error"
                    _state.value.copy(listening = false, status = "No result ($why)", heard = "")
                },
            )
        }
    }

    /** Push-to-talk release: end capture early (endpoint now). */
    fun stopListening() {
        stt.cancel()
    }

    /** Kick off a real agent run with the given (or spoken) intent. */
    fun runAgent(intent: String) {
        val trimmed = intent.trim()
        if (trimmed.isEmpty()) return
        ContextCompat.startForegroundService(appContext, AgentService.runIntent(appContext, trimmed))
        _state.value = _state.value.copy(status = "Running: ${trimmed.take(40)}")
    }

    override fun onCleared() {
        stt.cancel()
        tts.stop()
    }
}
