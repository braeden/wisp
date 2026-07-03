package com.assist.voice.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Thin wrapper around transient, may-duck audio focus for assistant playback and
 * capture. Requesting focus ducks/pauses music; abandoning restores it. Uses
 * `USAGE_ASSISTANT` so the system routes and mixes us as an assistant (and honors
 * headset routing). minSdk 30 → the [AudioFocusRequest] API is always available.
 */
internal class AudioFocus(
    context: Context,
) {
    private val manager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private var request: AudioFocusRequest? = null

    val audioAttributes: AudioAttributes get() = attributes

    /** Request transient focus with ducking. Idempotent. */
    @Synchronized
    fun request() {
        if (request != null) return
        val req =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()
        val result = manager.requestAudioFocus(req)
        request = req
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "audio focus not granted ($result); proceeding anyway")
        }
    }

    /** Abandon focus, restoring other apps' audio. Idempotent. */
    @Synchronized
    fun abandon() {
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }

    private companion object {
        const val TAG = "AudioFocus"
    }
}
