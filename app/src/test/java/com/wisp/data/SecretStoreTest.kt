package com.wisp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for [SecretStore] against an in-memory fake. Verifies the
 * behavior the real [EncryptedSecretStore] must honor (trim, blank-clears,
 * hasApiKey), without touching the Android Keystore.
 */
class SecretStoreTest {
    /** Mirrors EncryptedSecretStore's normalization rules. */
    private class FakeSecretStore : SecretStore {
        private var value: String? = null
        private var picovoice: String? = null

        override fun getApiKey(): String? = value?.takeIf { it.isNotBlank() }

        override fun setApiKey(value: String) {
            this.value = if (value.isBlank()) null else value.trim()
        }

        override fun getPicovoiceKey(): String? = picovoice?.takeIf { it.isNotBlank() }

        override fun setPicovoiceKey(value: String) {
            this.picovoice = if (value.isBlank()) null else value.trim()
        }
    }

    @Test
    fun `no key by default`() {
        val store = FakeSecretStore()
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun `set then get returns trimmed key`() {
        val store = FakeSecretStore()
        store.setApiKey("  sk-ant-123  ")
        assertEquals("sk-ant-123", store.getApiKey())
        assertTrue(store.hasApiKey())
    }

    @Test
    fun `blank clears the key`() {
        val store = FakeSecretStore()
        store.setApiKey("sk-ant-123")
        store.setApiKey("   ")
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun `picovoice key is independent of the anthropic key`() {
        val store = FakeSecretStore()
        store.setPicovoiceKey("  pv-abc  ")
        assertEquals("pv-abc", store.getPicovoiceKey())
        assertTrue(store.hasPicovoiceKey())
        assertFalse(store.hasApiKey())
        store.setPicovoiceKey("")
        assertFalse(store.hasPicovoiceKey())
    }
}
