package com.gemmahack.symptomdiary

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Refuse read-aloud unless a non-network voice is installed. No default-voice fallback. */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var completed: (() -> Unit)? = null
    private var activeId: String? = null
    private val tts = TextToSpeech(context.applicationContext) { ready = it == TextToSpeech.SUCCESS }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) { main.post {
        if (id == activeId) { val callback = completed; completed = null; activeId = null; callback?.invoke() }
    } }

    fun speak(text: String, done: () -> Unit): Boolean {
        stop()
        if (!ready || text.isBlank()) return false
        val voice = tts.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired &&
            !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency })?.firstOrNull()
            ?: return false
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        tts.setSpeechRate(0.92f)
        activeId = newId(); completed = done
        // Large content is chunked at natural boundaries; never silently truncate instructions.
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength() - 100)
        chunks.forEachIndexed { index, chunk ->
            val id = if (index == chunks.lastIndex) activeId else newId()
            if (tts.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                stop(); return false
            }
        }
        return true
    }

    fun stop() { completed = null; activeId = null; tts.stop() }
    fun close() { stop(); tts.shutdown() }
}
