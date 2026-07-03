package com.assist.llm

/**
 * Model-agnostic client for one assistant turn. The concrete Claude impl lives in
 * `com.assist.llm.anthropic`; no provider types leak through this interface.
 */
interface LlmClient {
    /**
     * Run one assistant turn. Invokes [onEvent] for each [LlmStreamEvent] as the
     * response streams and resolves to the full [LlmResponse]. Cancelling the
     * calling coroutine must promptly abort the underlying HTTP stream
     * (interruptibility, phase-08).
     */
    suspend fun send(
        request: LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit,
    ): LlmResponse

    /** Count input tokens for [request] without generating (count-tokens endpoint). */
    suspend fun countTokens(request: LlmRequest): Int
}
