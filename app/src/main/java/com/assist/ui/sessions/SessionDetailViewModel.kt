package com.assist.ui.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assist.data.ContextTracker
import com.assist.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the session detail / transcript screen (phase-12). Re-decodes the
 * transcript whenever the session's messages change, then folds everything
 * (transcript + tool calls + usage + context status) via the pure
 * [SessionDetailReducer]. The `sessionId` nav arg is read from [SavedStateHandle].
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val contextTracker: ContextTracker,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: Long = savedStateHandle.get<Long>(ARG_SESSION_ID)
        ?: savedStateHandle.get<String>(ARG_SESSION_ID)?.toLongOrNull()
        ?: -1L

    private val _state = MutableStateFlow(SessionDetailUiState())
    val state: StateFlow<SessionDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Any message change re-decodes the transcript; the session row flow
            // supplies the header.
            repository.observeMessages(sessionId).collectLatest {
                val session = repository.getSession(sessionId)
                val transcript = repository.getTranscript(sessionId)
                val toolCalls = repository.listToolCalls(sessionId)
                val usage = repository.aggregateUsage(sessionId)
                val ctx = runCatching { contextTracker.contextStatus(sessionId) }.getOrNull()
                _state.value = SessionDetailReducer.reduce(session, transcript, toolCalls, usage, ctx)
            }
        }
    }

    companion object {
        const val ARG_SESSION_ID = "sessionId"
    }
}
