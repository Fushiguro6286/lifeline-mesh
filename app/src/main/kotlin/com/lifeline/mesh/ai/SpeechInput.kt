package com.lifeline.mesh.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Voice in (CLAUDE.md 3): Android SpeechRecognizer, the on-device recognizer when the phone has one (Android 12+),
 * offline preferred. Must be used from the main thread. Typing is always available as the fallback.
 */
class SpeechInput(private val context: Context) {
    sealed interface Event {
        data object Ready : Event
        data class Partial(val text: String) : Event
        /** Input level, roughly -2..10 dB. */
        data class Level(val rmsDb: Float) : Event
        data class Final(val text: String) : Event
        data class Failed(val reason: Reason) : Event
    }

    enum class Reason { NO_MATCH, LANGUAGE_UNAVAILABLE, NO_PERMISSION, BUSY, OTHER }

    private var recognizer: SpeechRecognizer? = null

    val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun available(): Boolean = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening. The on-device recognizer is tried first; if it lacks this language (no offline pack), the
     * phone's regular recognizer is tried once, still asking for offline. Without a pack it may need internet.
     */
    fun start(languageTag: String, onEvent: (Event) -> Unit) = start(languageTag, onEvent, useOnDevice = onDevice)

    private fun start(languageTag: String, onEvent: (Event) -> Unit, useOnDevice: Boolean) {
        destroy()
        val r = if (useOnDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(Event.Ready)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                Log.w(TAG, "recognizer error $error (onDevice=$useOnDevice, lang=$languageTag)")
                val noPack = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (useOnDevice && noPack && SpeechRecognizer.isRecognitionAvailable(context)) {
                    start(languageTag, onEvent, useOnDevice = false)
                    return
                }
                onEvent(Event.Failed(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Reason.NO_MATCH
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Reason.NO_PERMISSION
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Reason.BUSY
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> Reason.LANGUAGE_UNAVAILABLE
                    else -> Reason.OTHER
                }))
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onEvent(if (text.isBlank()) Event.Failed(Reason.NO_MATCH) else Event.Final(text))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { onEvent(Event.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale(languageTag))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        }
        r.startListening(intent)
    }

    /** Android 13+: ask the system to download the on-device pack for [languageTag] (needs internet now). */
    fun canDownloadPack(): Boolean = Build.VERSION.SDK_INT >= 33 && onDevice

    fun downloadPack(languageTag: String) {
        if (Build.VERSION.SDK_INT < 33 || !onDevice) return
        runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).triggerModelDownload(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale(languageTag))
            )
        }.onFailure { Log.w(TAG, "pack download not started", it) }
    }

    /** Stop listening and deliver the final result. */
    fun stop() = recognizer?.stopListening()

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val TAG = "SpeechInput"
        fun locale(tag: String) = when (tag) { "hi" -> "hi-IN"; "kn" -> "kn-IN"; else -> "en-IN" }
    }
}
