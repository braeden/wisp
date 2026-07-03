package com.wisp.data

/**
 * Secure storage for API credentials. Owned by phase-02; the Anthropic key is
 * consumed by the LLM layer (phase-04), the Picovoice AccessKey by the wake-word
 * detector (phase-09).
 *
 * Keys never leave the device except in auth headers/handshakes to their own
 * service, and are never logged.
 */
interface SecretStore {
    /** @return the stored Anthropic API key, or null if none has been set. */
    fun getApiKey(): String?

    /** Persist [value]; pass a blank string to clear. */
    fun setApiKey(value: String)

    /** True if a non-blank key is stored. */
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    /** @return the stored Picovoice AccessKey (wake word), or null if unset. */
    fun getPicovoiceKey(): String?

    /** Persist [value]; pass a blank string to clear. */
    fun setPicovoiceKey(value: String)

    /** True if a non-blank Picovoice AccessKey is stored. */
    fun hasPicovoiceKey(): Boolean = !getPicovoiceKey().isNullOrBlank()
}
