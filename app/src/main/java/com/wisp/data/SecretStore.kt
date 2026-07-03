package com.wisp.data

/**
 * Secure storage for the Anthropic API key. Owned by phase-02; consumed by the
 * LLM layer in phase-04.
 *
 * The key never leaves the device except in Authorization headers to the
 * Anthropic API, and is never logged.
 */
interface SecretStore {
    /** @return the stored API key, or null if none has been set. */
    fun getApiKey(): String?

    /** Persist [value]; pass a blank string to clear. */
    fun setApiKey(value: String)

    /** True if a non-blank key is stored. */
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
}
