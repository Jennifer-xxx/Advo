package com.gemmahack.symptomdiary

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*

/** Pixel's installed on-device recognizer only. Never create the network-capable recognizer or request downloads. */
class SystemSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var support: SpeechRecognizer? = null
    private var generation = 0
    private var installedTags = arrayListOf<String>()
    private var resolvedTag = "en-US"
    private val resolvedTags = mutableMapOf<String, String>()
    fun available(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (tag == "auto") resolvedTag else resolvedTags[tag] ?: if (tag == "en") "en-US" else tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (tag == "auto" && Build.VERSION.SDK_INT >= 34 && installedTags.isNotEmpty()) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, installedTags)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, installedTags)
        }
    }

    fun check(tag: String, result: (Boolean) -> Unit) {
        if (!available()) { result(false); return }
        if (Build.VERSION.SDK_INT < 33) { result(false); return }
        support?.destroy()
        val helper = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        support = helper
        helper.checkRecognitionSupport(intent(tag), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(value: RecognitionSupport) {
                installedTags = ArrayList(value.installedOnDeviceLanguages.filter { language ->
                    language.startsWith("en", true) || language.startsWith("es", true) || language.equals("zh-CN", true) || language.startsWith("cmn-Hans", true)
                })
                if (tag == "auto") {
                    resolvedTag = installedTags.firstOrNull { it.startsWith("en", true) } ?: installedTags.firstOrNull() ?: "en-US"
                    result(Build.VERSION.SDK_INT >= 34 && installedTags.isNotEmpty())
                    return
                }
                val language = tag.substringBefore('-')
                val matched = value.installedOnDeviceLanguages.firstOrNull { it.equals(tag, true) } ?:
                    value.installedOnDeviceLanguages.firstOrNull { (language == "zh" && it.startsWith("cmn-Hans", true)) ||
                        it.substringBefore('-').equals(language, true) }
                if (matched != null) resolvedTags[tag] = matched
                result(matched != null)
            }
            override fun onError(error: Int) = result(false)
        })
    }

    fun start(tag: String, partial: (String) -> Unit, result: (String) -> Unit, failed: () -> Unit, detected: (String?, Boolean) -> Unit = { _, _ -> }, ready: () -> Unit = {}) {
        cancel()
        check(available()) { "On-device speech is unavailable." }
        val token = generation
        val local = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = local
        local.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (token == generation) ready() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onLanguageDetection(results: Bundle) {
                if (Build.VERSION.SDK_INT >= 34 && token == generation) detected(results.getString(SpeechRecognizer.DETECTED_LANGUAGE),
                    results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL) >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT)
            }
            override fun onPartialResults(values: Bundle?) {
                if (token == generation) partial(values?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onResults(values: Bundle?) {
                if (token != generation) return
                val text = values?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                cancel(); result(text)
            }
            override fun onError(error: Int) { if (token == generation) { cancel(); failed() } }
        })
        local.startListening(intent(tag))
    }

    fun stop() { recognizer?.stopListening() }
    fun cancel() { generation++; recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    fun close() { cancel(); support?.destroy(); support = null }
}
