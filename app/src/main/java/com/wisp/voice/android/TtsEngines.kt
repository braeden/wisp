package com.wisp.voice.android

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** An installed TTS engine the user can pick in Settings. */
data class TtsEngineChoice(
    /** Engine package name (what `TextToSpeech(context, listener, engine)` takes). */
    val packageName: String,
    /** Human-readable engine label. */
    val label: String,
)

/**
 * Discovers installed TTS engines via `TextToSpeech.getEngines()` for the
 * Settings dropdown. Uses a throwaway `TextToSpeech` instance (the list is
 * available regardless of which engine that instance bound to) and shuts it
 * down immediately. Visibility of third-party engines is covered by the
 * existing `QUERY_ALL_PACKAGES` permission (needed anyway for `open_app`).
 */
object TtsEngines {
    /** Installed engines plus which one is the system default (null on failure). */
    suspend fun discover(context: Context): Discovery =
        suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            tts =
                TextToSpeech(context.applicationContext) { _ ->
                    // Engines are listable even when init reports failure.
                    val engines =
                        runCatching {
                            tts?.engines.orEmpty().map {
                                TtsEngineChoice(packageName = it.name, label = it.label)
                            }
                        }.getOrDefault(emptyList())
                    val default = runCatching { tts?.defaultEngine }.getOrNull()
                    runCatching { tts?.shutdown() }
                    if (cont.isActive) cont.resume(Discovery(engines, default))
                }
            cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
        }

    data class Discovery(
        val engines: List<TtsEngineChoice>,
        val defaultEngine: String?,
    )
}
