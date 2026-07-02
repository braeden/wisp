package com.assist.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assist.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the sessions-list screen (phase-12). Reactive state comes straight from
 * [SessionRepository.observeSessionSummaries] through the pure [SessionsReducer];
 * actions (new/rename/delete) are one-shot suspend calls.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: SessionRepository,
) : ViewModel() {

    val state: StateFlow<SessionsUiState> =
        repository.observeSessionSummaries()
            .map { SessionsReducer.reduce(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    /** Create a fresh session and hand its id back (e.g. to open the transcript). */
    fun newSession(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val session = repository.createSession(title = "New session")
            onCreated(session.id)
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { repository.renameSession(id, title) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteSession(id) }
    }
}
