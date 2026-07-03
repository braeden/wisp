package com.assist.voice.android

import android.speech.SpeechRecognizer
import com.assist.voice.SttError
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure mapping test — reads `SpeechRecognizer.ERROR_*` compile-time constants. */
class SttErrorMapperTest {
    @Test
    fun `no match maps to NO_MATCH`() {
        assertEquals(SttError.NO_MATCH, SttErrorMapper.map(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun `speech timeout maps to NO_SPEECH`() {
        assertEquals(SttError.NO_SPEECH, SttErrorMapper.map(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun `insufficient permissions maps to PERMISSION`() {
        assertEquals(
            SttError.PERMISSION,
            SttErrorMapper.map(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun `recognizer busy maps to BUSY`() {
        assertEquals(SttError.BUSY, SttErrorMapper.map(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test
    fun `network errors map to NETWORK`() {
        assertEquals(SttError.NETWORK, SttErrorMapper.map(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SttError.NETWORK, SttErrorMapper.map(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(SttError.NETWORK, SttErrorMapper.map(SpeechRecognizer.ERROR_SERVER))
    }

    @Test
    fun `language errors map to LANGUAGE_UNAVAILABLE`() {
        assertEquals(
            SttError.LANGUAGE_UNAVAILABLE,
            SttErrorMapper.map(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
        assertEquals(
            SttError.LANGUAGE_UNAVAILABLE,
            SttErrorMapper.map(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
    }

    @Test
    fun `cannot check support maps to ON_DEVICE_UNAVAILABLE`() {
        assertEquals(
            SttError.ON_DEVICE_UNAVAILABLE,
            SttErrorMapper.map(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT),
        )
    }

    @Test
    fun `client and audio map to CLIENT`() {
        assertEquals(SttError.CLIENT, SttErrorMapper.map(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(SttError.CLIENT, SttErrorMapper.map(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test
    fun `unknown code maps to UNKNOWN`() {
        assertEquals(SttError.UNKNOWN, SttErrorMapper.map(9999))
    }
}
