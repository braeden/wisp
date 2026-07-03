package com.assist.ui.sessions

import com.assist.data.SessionSummary

/** One row in the sessions list. Raw fields; formatting happens in Compose. */
data class SessionRowUi(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val status: String,
    val costUsd: Double,
    val messageCount: Int,
    /** The session's current model id (mid-session swaps update it). */
    val model: String = "",
)

data class SessionsUiState(
    val rows: List<SessionRowUi> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Pure reducer (phase-12): maps the DB [SessionSummary] projection to list-UI
 * state. Framework-free so it is unit-tested with fabricated summaries.
 */
object SessionsReducer {
    fun reduce(summaries: List<SessionSummary>): SessionsUiState =
        SessionsUiState(
            loading = false,
            rows = summaries.map { s ->
                SessionRowUi(
                    id = s.session.id,
                    title = s.session.title.ifBlank { "Untitled session" },
                    updatedAt = s.session.updatedAt,
                    status = s.session.status,
                    costUsd = s.costUsd,
                    messageCount = s.messageCount,
                    model = s.session.modelDefault,
                )
            },
        )
}
