package com.gemmahack.symptomdiary

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The phone's on-device speech recognizer (Android 12+). Runs fully offline once the language pack
 * is installed, and is usually more accurate than Gemma for Chinese dictation.
 * Gemma still does all the understanding: extraction, follow-ups and the report.
 * Must be called on the main thread.
 */
class SystemSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var downloader: SpeechRecognizer? = null

    fun available(): Boolean = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun intent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "zh") "zh-CN" else "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    fun start(lang: String, onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (Int) -> Unit) {
        destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                destroy()
            }

            override fun onError(error: Int) {
                onError(error)
                destroy()
            }
        })
        r.startListening(intent(lang))
    }

    /** Ends listening; the final text arrives through onResult. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Asks the system to download the offline pack for this language (Android 13+). */
    fun downloadPack(lang: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        downloader?.destroy()
        downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.triggerModelDownload(intent(lang)) }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        fun isMissingPack(code: Int) =
            code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

        fun isNotHeard(code: Int) = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
}
