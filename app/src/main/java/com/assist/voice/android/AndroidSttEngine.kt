package com.assist.voice.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.assist.voice.SttConfig
import com.assist.voice.SttEngine
import com.assist.voice.SttError
import com.assist.voice.SttEvent
import com.assist.voice.SttException
import com.assist.voice.SttResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `SpeechRecognizer`-backed [SttEngine]. Prefers the on-device recognizer
 * (`createOnDeviceSpeechRecognizer`, API 31+; the Pixel 7 Pro qualifies) with
 * `EXTRA_PREFER_OFFLINE`, falling back to the default recognizer. All recognizer
 * calls are marshaled to the **main thread** (the framework contract); callbacks
 * arrive there too. `SpeechRecognizer` types never escape this class.
 */
class AndroidSttEngine(context: Context) : SttEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The recognizer currently listening, if any — destroyed by [cancel]. */
    @Volatile
    private var active: SpeechRecognizer? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main.immediate) {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    override suspend fun transcribeOnce(config: SttConfig): SttResult {
        if (!hasMicPermission()) throw SttException(SttError.PERMISSION)
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val recognizer = createRecognizer(config)
                val settled = AtomicBoolean(false)

                val listener = object : BaseListener() {
                    override fun onResults(results: Bundle) {
                        if (!settled.compareAndSet(false, true)) return
                        val result = results.toSttResult()
                        destroy(recognizer)
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onError(error: Int) {
                        if (!settled.compareAndSet(false, true)) return
                        destroy(recognizer)
                        if (cont.isActive) {
                            cont.resumeWithException(SttException(SttErrorMapper.map(error)))
                        }
                    }
                }

                startListening(recognizer, listener, config)
                cont.invokeOnCancellation {
                    if (settled.compareAndSet(false, true)) main.post { destroy(recognizer) }
                }
            }
        }
    }

    override fun stream(config: SttConfig): Flow<SttEvent> = callbackFlow {
        if (!hasMicPermission()) {
            trySend(SttEvent.Failed(SttError.PERMISSION))
            close()
            return@callbackFlow
        }

        var recognizer: SpeechRecognizer? = null
        val listener = object : BaseListener() {
            override fun onBeginningOfSpeech() {
                trySend(SttEvent.BeginningOfSpeech)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SttEvent.Rms(rmsdB))
            }

            override fun onPartialResults(partialResults: Bundle) {
                partialResults.firstResult()?.let { trySend(SttEvent.Partial(it)) }
            }

            override fun onEndOfSpeech() {
                trySend(SttEvent.EndOfSpeech)
            }

            override fun onResults(results: Bundle) {
                trySend(SttEvent.Final(results.toSttResult()))
                close()
            }

            override fun onError(error: Int) {
                trySend(SttEvent.Failed(SttErrorMapper.map(error)))
                close()
            }
        }

        main.post {
            val r = createRecognizer(config)
            recognizer = r
            startListening(r, listener, config)
        }

        awaitClose {
            main.post { recognizer?.let { destroy(it) } }
        }
    }

    override fun cancel() {
        val current = active
        main.post { current?.let { destroy(it) } }
    }

    // --- Recognizer plumbing (main thread) ---------------------------------

    private fun createRecognizer(config: SttConfig): SpeechRecognizer {
        val onDevice = config.preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }
                .getOrDefault(false)
        val recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        active = recognizer
        return recognizer
    }

    private fun startListening(
        recognizer: SpeechRecognizer,
        listener: RecognitionListener,
        config: SttConfig,
    ) {
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(buildIntent(config))
    }

    private fun destroy(recognizer: SpeechRecognizer) {
        runCatching {
            recognizer.stopListening()
            recognizer.cancel()
            recognizer.destroy()
        }.onFailure { Log.d(TAG, "recognizer teardown: ${it.message}") }
        if (active === recognizer) active = null
    }

    private fun buildIntent(config: SttConfig) =
        android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, config.maxAlternatives.coerceAtLeast(1))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, config.preferOnDevice)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            config.silenceTimeoutMs?.let {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it)
            }
        }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AndroidSttEngine"
    }
}

/** No-op [RecognitionListener] base so impls override only what they need. */
private abstract class BaseListener : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onResults(results: Bundle) {}
    override fun onError(error: Int) {}
}

private fun Bundle.firstResult(): String? =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

private fun Bundle.toSttResult(): SttResult {
    val hypotheses = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    val scores = getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
    return SttResult(
        text = hypotheses.firstOrNull().orEmpty(),
        alternatives = if (hypotheses.size > 1) hypotheses.drop(1) else emptyList(),
        confidence = scores?.firstOrNull(),
    )
}
