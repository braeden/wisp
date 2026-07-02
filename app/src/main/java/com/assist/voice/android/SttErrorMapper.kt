package com.assist.voice.android

import android.speech.SpeechRecognizer
import com.assist.voice.SttError

/**
 * Maps `SpeechRecognizer.ERROR_*` codes to the backend-neutral [SttError]. Pure
 * (only reads the framework's compile-time `int` constants) so it is unit-tested
 * without a device. Branches on the **named** constants — numeric values are
 * treated as implementation detail (see `.claude/voice-architecture.md`). All
 * referenced constants exist by API 33; the app compiles against SDK 35.
 */
internal object SttErrorMapper {

    fun map(code: Int): SttError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.NO_SPEECH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SttError.BUSY
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SttError.NETWORK
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SttError.LANGUAGE_UNAVAILABLE
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> SttError.ON_DEVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_AUDIO -> SttError.CLIENT
        else -> SttError.UNKNOWN
    }
}
