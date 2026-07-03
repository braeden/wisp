package com.wisp.voice.wake

import ai.picovoice.porcupine.Porcupine
import java.util.Locale

/**
 * Mapping between our persisted keyword names (`SettingsStore.wakeKeyword`,
 * `WakeConfig.keyword`) and Porcupine's built-in keyword models. Custom "Hey
 * Wisp"-style keywords need a Console-trained `.ppn` shipped as an asset and
 * arrive via [com.wisp.voice.WakeConfig.modelAsset] instead.
 */
object PorcupineKeywords {
    /** Resolve a stored name ("PORCUPINE", "hey google", "jarvis") to a built-in. */
    fun builtInFor(name: String): Porcupine.BuiltInKeyword? {
        val normalized =
            name
                .trim()
                .uppercase(Locale.US)
                .replace(Regex("[\\s-]+"), "_")
        return Porcupine.BuiltInKeyword.values().firstOrNull { it.name == normalized }
    }

    /** All built-in keyword names, for the Settings dropdown. */
    fun allNames(): List<String> = Porcupine.BuiltInKeyword.values().map { it.name }

    /** "HEY_GOOGLE" -> "Hey google" for display. */
    fun displayName(name: String): String =
        name
            .lowercase(Locale.US)
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase(Locale.US) }
}
