package com.assist.data

/**
 * A snapshot of a session's context economy, for the agent's introspection tools
 * and the overlay HUD.
 *
 * @property usedTokens estimated tokens currently live in context (dropped media excluded).
 * @property windowTokens the model's context-window size.
 * @property costUsd total spend on the session so far.
 * @property screenshotCount number of live (not dropped) screenshots in context.
 */
data class ContextStatus(
    val usedTokens: Int,
    val windowTokens: Int,
    val costUsd: Double,
    val screenshotCount: Int,
)
