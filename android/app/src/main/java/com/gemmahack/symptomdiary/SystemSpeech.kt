package com.gemmahack.symptomdiary

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The phone's on-device speech recognizer (Android 12+). On Pixel this is Google's on-device speech
 * service. It runs fully offline once the language pack is installed. Gemma still does all the
 * understanding: extraction, follow-ups and the report. Must be called on the main thread.
 *
 * Language packs: we ask the service which languages are installed / downloading / supported
 * (Android 13+), pick the right tag (Mandarin is often "cmn-Hans-CN", not "zh-CN"), and download
 * with progress (Android 14+).
 */
class SystemSpeech(private val context: Context) {
    enum class Pack { INSTALLED, DOWNLOADING, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

    private var recognizer: SpeechRecognizer? = null
    private var helper: SpeechRecognizer? = null
    private val resolvedTag = mutableMapOf<String, String>()

    fun available(): Boolean = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun defaultTag(lang: String) = if (lang == "zh") "zh-CN" else "en-US"

    fun tagFor(lang: String): String = resolvedTag[lang] ?: defaultTag(lang)

    private fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** Picks the tag for our language from what the service offers. Mandarin, Simplified script only. */
    private fun pick(lang: String, tags: List<String>): String? {
        val lower = tags.map { it.lowercase() }
        val preferred = if (lang == "zh") listOf("cmn-hans-cn", "zh-cn", "cmn-cn", "zh-hans-cn", "zh") else listOf("en-us", "en-gb", "en")
        preferred.forEach { p -> lower.indexOf(p).takeIf { it >= 0 }?.let { return tags[it] } }
        val idx = lower.indexOfFirst { t ->
            if (lang == "zh") (t.startsWith("cmn") || t.startsWith("zh")) && listOf("hant", "tw", "hk", "yue").none { it in t }
            else t.startsWith("en")
        }
        return if (idx >= 0) tags[idx] else null
    }

    /** Reports whether the pack for [lang] is installed, downloading, downloadable or unsupported. */
    fun checkPack(lang: String, onResult: (Pack, String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(Pack.UNKNOWN, tagFor(lang))
            return
        }
        helper?.destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        helper = r
        r.checkRecognitionSupport(intent(defaultTag(lang)), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = pick(lang, support.installedOnDeviceLanguages)
                val pending = pick(lang, support.pendingOnDeviceLanguages)
                val supported = pick(lang, support.supportedOnDeviceLanguages)
                val (state, tag) = when {
                    installed != null -> Pack.INSTALLED to installed
                    pending != null -> Pack.DOWNLOADING to pending
                    supported != null -> Pack.DOWNLOADABLE to supported
                    else -> Pack.UNSUPPORTED to defaultTag(lang)
                }
                resolvedTag[lang] = tag
                onResult(state, tag)
            }

            override fun onError(error: Int) {
                onResult(Pack.UNKNOWN, tagFor(lang))
            }
        })
    }

    /**
     * Downloads the pack for [lang]. Android 14+ reports progress; Android 13 only schedules it.
     * The download is done by the system speech service, not by this app (which has no network access).
     */
    fun downloadPack(
        lang: String,
        progress: (Int) -> Unit,
        done: () -> Unit,
        scheduled: () -> Unit,
        failed: (Int) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            failed(-1)
            return
        }
        helper?.destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        helper = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            r.triggerModelDownload(intent(tagFor(lang)), context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = progress(completedPercent)
                override fun onSuccess() = done()
                override fun onScheduled() = scheduled()
                override fun onError(error: Int) = failed(error)
            })
        } else {
            r.triggerModelDownload(intent(tagFor(lang)))
            scheduled()
        }
    }

    fun start(lang: String, partial: (String) -> Unit, result: (String) -> Unit, failed: (Int) -> Unit) {
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
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(partial)
            }

            override fun onResults(results: Bundle?) {
                result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                destroy()
            }

            override fun onError(error: Int) {
                failed(error)
                destroy()
            }
        })
        r.startListening(intent(tagFor(lang)))
    }

    /** Ends listening; the final text arrives through onResult. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    fun release() {
        destroy()
        helper?.destroy()
        helper = null
    }

    companion object {
        fun isMissingPack(code: Int) =
            code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

        fun isNotHeard(code: Int) = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
}
