package com.wisp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [SecretStore] backed by [EncryptedSharedPreferences] (AES-256, key material in
 * the Android Keystore). Values are encrypted at rest.
 */
class EncryptedSecretStore(
    context: Context,
) : SecretStore {
    private val prefs: SharedPreferences =
        run {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    override fun getApiKey(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    override fun setApiKey(value: String) {
        prefs
            .edit()
            .apply {
                if (value.isBlank()) remove(KEY_API) else putString(KEY_API, value.trim())
            }.apply()
    }

    override fun getPicovoiceKey(): String? =
        prefs.getString(KEY_PICOVOICE, null)?.takeIf { it.isNotBlank() }

    override fun setPicovoiceKey(value: String) {
        prefs
            .edit()
            .apply {
                if (value.isBlank()) remove(KEY_PICOVOICE) else putString(KEY_PICOVOICE, value.trim())
            }.apply()
    }

    private companion object {
        const val PREFS_FILE = "wisp_secrets"
        const val KEY_API = "anthropic_api_key"
        const val KEY_PICOVOICE = "picovoice_access_key"
    }
}
