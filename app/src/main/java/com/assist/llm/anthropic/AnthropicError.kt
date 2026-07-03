package com.assist.llm.anthropic

/**
 * Typed errors from the Anthropic API. [retryable] tells the agent loop
 * (phase-06) whether a backoff-and-retry is worthwhile (rate limit / overloaded
 * / transient 5xx / network) versus a permanent failure (auth / bad request).
 * Anthropic-specific; never surfaces outside `com.assist.llm.anthropic`.
 */
sealed class AnthropicException(
    message: String,
    val statusCode: Int? = null,
    val errorType: String? = null,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 401 — missing/invalid API key. */
class AnthropicAuthException(
    message: String,
    errorType: String? = null,
) : AnthropicException(message, statusCode = 401, errorType = errorType, retryable = false)

/** 403 — key lacks permission for the model/feature. */
class AnthropicPermissionException(
    message: String,
    errorType: String? = null,
) : AnthropicException(message, statusCode = 403, errorType = errorType, retryable = false)

/** 400 — malformed request / invalid parameters. */
class AnthropicInvalidRequestException(
    message: String,
    statusCode: Int = 400,
    errorType: String? = null,
) : AnthropicException(message, statusCode = statusCode, errorType = errorType, retryable = false)

/** 404 — unknown model or endpoint. */
class AnthropicNotFoundException(
    message: String,
    errorType: String? = null,
) : AnthropicException(message, statusCode = 404, errorType = errorType, retryable = false)

/** 413 — request exceeds size limits. */
class AnthropicRequestTooLargeException(
    message: String,
    errorType: String? = null,
) : AnthropicException(message, statusCode = 413, errorType = errorType, retryable = false)

/** 429 — rate limited. [retryAfterSeconds] from the `retry-after` header when present. */
class AnthropicRateLimitException(
    message: String,
    retryAfterSeconds: Long? = null,
    errorType: String? = null,
) : AnthropicException(
        message,
        statusCode = 429,
        errorType = errorType,
        retryable = true,
        retryAfterSeconds = retryAfterSeconds,
    )

/** 5xx — transient server error. */
class AnthropicServerException(
    message: String,
    statusCode: Int,
    errorType: String? = null,
) : AnthropicException(message, statusCode = statusCode, errorType = errorType, retryable = true)

/** 529 — service overloaded. */
class AnthropicOverloadedException(
    message: String,
    retryAfterSeconds: Long? = null,
    errorType: String? = null,
) : AnthropicException(
        message,
        statusCode = 529,
        errorType = errorType,
        retryable = true,
        retryAfterSeconds = retryAfterSeconds,
    )

/** Transport failure before/while a response was received. */
class AnthropicNetworkException(
    message: String,
    cause: Throwable? = null,
) : AnthropicException(message, retryable = true, cause = cause)

internal object AnthropicErrorMapper {
    /** Map an HTTP status + parsed error payload to a typed exception. */
    fun map(
        statusCode: Int,
        errorType: String?,
        message: String,
        retryAfterSeconds: Long?,
    ): AnthropicException =
        when (statusCode) {
            401 -> AnthropicAuthException(message, errorType)
            403 -> AnthropicPermissionException(message, errorType)
            404 -> AnthropicNotFoundException(message, errorType)
            413 -> AnthropicRequestTooLargeException(message, errorType)
            429 -> AnthropicRateLimitException(message, retryAfterSeconds, errorType)
            529 -> AnthropicOverloadedException(message, retryAfterSeconds, errorType)
            in 500..599 -> AnthropicServerException(message, statusCode, errorType)
            else -> AnthropicInvalidRequestException(message, statusCode, errorType)
        }
}
