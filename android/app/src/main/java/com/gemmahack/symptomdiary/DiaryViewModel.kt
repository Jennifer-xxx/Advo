package com.gemmahack.symptomdiary

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

enum class Role { USER, BOT, ALERT, INFO }

data class ChatMessage(val role: Role, val text: String)

class DiaryViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val assets = AppAssets(app)
    private val gemma = GemmaEngine(app)
    private val extractor = Extractor(gemma, assets)
    private val store = EntryStore(app, assets)
    private val recorder = AudioRecorder()
    private val speech = SystemSpeech(app)

    // ---- language: the whole app shows one language at a time ----
    var lang by mutableStateOf(prefs.getString("lang", "zh") ?: "zh")
        private set
    val tx: Texts get() = Texts(lang)
    private var interview = Interview(extractor, gemma, assets, lang)

    // ---- model status ----
    var modelReady by mutableStateOf(false)
        private set
    private var loadSeconds by mutableStateOf(0.0)
    private var loadError by mutableStateOf<String?>(null)
    private var missingPaths by mutableStateOf<List<String>?>(null)
    private var timing by mutableStateOf<Pair<String, Double>?>(null)
    val statusText: String
        get() = when {
            missingPaths != null -> tx.modelNotFound(missingPaths!!)
            loadError != null -> tx.loadFailed(loadError)
            modelReady -> tx.ready(gemma.backendName, loadSeconds)
            else -> tx.loading
        }
    val timingText: String get() = timing?.let { (kind, s) -> tx.timing(kind, s) } ?: ""

    // ---- record screen ----
    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var input by mutableStateOf("")
    val messages = mutableStateListOf<ChatMessage>()
    var question by mutableStateOf<Question?>(null)
        private set
    val selected = mutableStateListOf<String>()
    /** One line under the voice buttons: pack status / download progress. */
    var voiceStatus by mutableStateOf("")
        private set
    private var downloading = false
    var voiceEngine by mutableStateOf(
        prefs.getString("voice", null) ?: if (speech.available()) "system" else "gemma")
        private set

    // ---- history + report ----
    var entries by mutableStateOf(runCatching { store.load() }.getOrDefault(emptyList()))
        private set
    var reportDays by mutableIntStateOf(14)
    var reportLang by mutableStateOf(lang)
    var narrative by mutableStateOf("")
    var questionsForDoctor by mutableStateOf("")
    var reportHtml by mutableStateOf<String?>(null)
        private set
    private var summary: ReportSummary? = null
    private var summaryLang = lang

    init {
        greet()
        loadModel()
    }

    private fun info(text: String) {
        messages += ChatMessage(Role.INFO, text)
    }

    private fun greet() {
        messages.clear()
        messages += ChatMessage(Role.BOT, tx.greeting)
    }

    fun switchLang() {
        if (busy || recording) return
        voiceStatus = ""
        lang = if (lang == "zh") "en" else "zh"
        prefs.edit().putString("lang", lang).apply()
        reportLang = lang
        newEntry() // start fresh so one conversation never mixes languages
    }

    fun loadModel() {
        viewModelScope.launch {
            loadError = null
            missingPaths = null
            try {
                loadSeconds = gemma.load()
                modelReady = true
            } catch (e: ModelNotFoundException) {
                missingPaths = e.paths
            } catch (t: Throwable) {
                loadError = t.message
            }
        }
    }

    /** Runs one model-backed action at a time and reports errors in the chat. */
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                info(tx.error(t.message))
            } finally {
                busy = false
            }
        }
    }

    fun toggle(option: String, multi: Boolean) {
        if (option in selected) {
            selected.remove(option)
        } else {
            if (!multi) selected.clear()
            selected += option
        }
    }

    fun send() {
        val text = input.trim()
        val opts = selected.toList()
        if (text.isEmpty() && opts.isEmpty()) return
        if (!modelReady) {
            info(tx.notReady())
            return
        }
        input = ""
        selected.clear()
        messages += ChatMessage(Role.USER, (opts + listOfNotNull(text.ifEmpty { null })).joinToString(tx.answerSep))
        work {
            val result = if (!interview.started) {
                interview.start(text.ifEmpty { opts.joinToString(tx.listSep) })
            } else {
                interview.answer(text.ifEmpty { null }, opts.ifEmpty { null })
            }
            show(result)
        }
    }

    fun skip() = work { show(interview.answer(null, null)) }

    private fun show(r: StepResult) {
        r.newRedFlags.forEach { messages += ChatMessage(Role.ALERT, it) }
        timing = "infer" to interview.lastSeconds
        question = r.question
        val q = r.question
        if (q != null) messages += ChatMessage(Role.BOT, q.text) else info(tx.complete)
    }

    fun save() {
        if (!interview.started || busy) return
        val e = interview.entry()
        interview = Interview(extractor, gemma, assets, lang)
        question = null
        selected.clear()
        viewModelScope.launch {
            entries = withContext(Dispatchers.IO) { store.add(e) }
            info(tx.saved(summaryLine(e)))
        }
    }

    fun newEntry() {
        interview = Interview(extractor, gemma, assets, lang)
        question = null
        selected.clear()
        input = ""
        greet()
    }

    fun summaryLine(e: DiaryEntry): String {
        val r = e.record
        val sep = if (e.lang == "zh") "、" else ", "
        val with = if (e.lang == "zh") "伴 " else "with "
        return listOfNotNull(
            r.symptom, r.location, r.severity?.let { "$it/10" }, r.onset,
            r.character.takeIf { it.isNotEmpty() }?.joinToString(sep),
            r.associated.takeIf { it.isNotEmpty() }?.let { with + it.joinToString(sep) },
        ).joinToString(" · ")
    }

    // ---- voice ----
    fun toggleVoiceEngine() {
        if (recording) return
        voiceEngine = if (voiceEngine == "system") "gemma" else "system"
        prefs.edit().putString("voice", voiceEngine).apply()
    }

    fun startRecording() {
        if (busy || recording) return
        if (voiceEngine == "system") startSystemSpeech() else startGemmaRecording()
    }

    fun stopRecording() {
        if (!recording) return
        if (voiceEngine == "system") speech.stop() else stopGemmaRecording()
    }

    private fun startSystemSpeech() {
        if (!speech.available()) {
            voiceEngine = "gemma"
            info(tx.systemUnavailable)
            return
        }
        if (downloading) {
            voiceStatus = tx.packStillDownloading
            return
        }
        val speakLang = lang
        // Ask the phone first: is the pack for this language installed, downloading, or downloadable?
        speech.checkPack(speakLang) { pack, tag ->
            when (pack) {
                SystemSpeech.Pack.INSTALLED, SystemSpeech.Pack.UNKNOWN -> {
                    voiceStatus = tx.packReady(tag)
                    listenSystem(speakLang)
                }
                SystemSpeech.Pack.DOWNLOADING -> voiceStatus = tx.packStillDownloading
                SystemSpeech.Pack.DOWNLOADABLE -> downloadPack(speakLang, tag)
                SystemSpeech.Pack.UNSUPPORTED -> {
                    voiceEngine = "gemma"
                    voiceStatus = ""
                    info(tx.packUnsupported)
                }
            }
        }
    }

    private fun downloadPack(speakLang: String, tag: String) {
        downloading = true
        voiceStatus = tx.packDownloading(tag, null)
        speech.downloadPack(
            speakLang,
            progress = { pct -> voiceStatus = tx.packDownloading(tag, pct) },
            done = {
                downloading = false
                voiceStatus = tx.packDone
            },
            scheduled = {
                downloading = false
                voiceStatus = tx.packScheduled
            },
            failed = { code ->
                downloading = false
                voiceStatus = tx.packFailed(code)
            },
        )
    }

    private fun listenSystem(speakLang: String) {
        val before = input
        val q = question
        recording = true
        info(tx.listening)
        speech.start(
            speakLang,
            partial = { heard -> input = (before + " " + heard).trim() },
            result = { text ->
                recording = false
                val clean = toSimplified(text.trim())
                input = (before + " " + clean).trim()
                if (clean.isEmpty()) info(tx.notHeard) else if (q != null) autoSelect(q, clean)
            },
            failed = { code ->
                recording = false
                when {
                    SystemSpeech.isMissingPack(code) -> downloadPack(speakLang, speech.tagFor(speakLang))
                    SystemSpeech.isNotHeard(code) -> info(tx.notHeard)
                    else -> info(tx.micError("speech error $code"))
                }
            },
        )
    }

    private fun startGemmaRecording() {
        if (!modelReady) {
            info(tx.notReady())
            return
        }
        try {
            recorder.start()
            recording = true
            info(tx.listening)
        } catch (t: Throwable) {
            info(tx.micError(t.message))
        }
    }

    private fun stopGemmaRecording() {
        recording = false
        val wav = recorder.stop()
        if (wav.size < 44 + 3200) { // shorter than 0.1 s
            info(tx.noAudio)
            return
        }
        work {
            val q = question
            // Tell the model which words to expect: biases recognition toward the current options.
            val context = when {
                q != null && q.options.isNotEmpty() ->
                    fill(assets.prompt("transcribe_context"), "question" to q.text, "options" to q.options.joinToString(tx.listSep))
                q != null -> "- The patient is answering this question: \"${q.text}\""
                else -> ""
            }
            val prompt = fill(assets.prompt("transcribe"), "lang_name" to langName(lang), "context" to context)
            val r = gemma.generateWithAudio(wav, prompt)
            val text = toSimplified(r.text.trim().trim('"', '“', '”'))
            input = (input + " " + text).trim() // user checks the transcript, then taps Send
            if (q != null) autoSelect(q, text)
            timing = "asr" to r.seconds
        }
    }

    /** Safety net: in Chinese mode, convert any Traditional characters to Simplified. */
    private val hantToHans = runCatching { android.icu.text.Transliterator.getInstance("Traditional-Simplified") }.getOrNull()

    private fun toSimplified(s: String): String = if (lang == "zh") hantToHans?.transliterate(s) ?: s else s

    /** Highlight the chips the user said out loud, so one voice answer fills the slot. */
    private fun autoSelect(q: Question, transcript: String) {
        val t = norm(transcript)
        val hits = if (q.slot == "severity_0_10") {
            listOfNotNull(spokenNumber(transcript)).filter { it in q.options }
        } else {
            q.options.filter { o ->
                norm(o) in t || o.split("或", "/", "、", " or ").map(::norm).any { it.length >= 2 && it in t }
            }
        }
        if (hits.isEmpty()) return
        if (!q.multi) selected.clear()
        hits.forEach { if (it !in selected) selected += it }
    }

    private fun spokenNumber(text: String): String? {
        Regex("(10|[0-9])").find(text)?.let { return it.value }
        val cn = mapOf('零' to "0", '一' to "1", '二' to "2", '两' to "2", '三' to "3", '四' to "4", '五' to "5",
            '六' to "6", '七' to "7", '八' to "8", '九' to "9", '十' to "10")
        Regex("([零一二两三四五六七八九十])\\s*分").find(text)?.let { m -> return cn[m.groupValues[1].first()] }
        val en = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        Regex("\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.let { m -> return en.indexOf(m.value.lowercase()).toString() }
        return null
    }

    // ---- history ----
    fun loadDemo() {
        viewModelScope.launch { entries = withContext(Dispatchers.IO) { store.loadDemo(lang) } }
    }

    fun clearAll() {
        reportHtml = null
        summary = null
        viewModelScope.launch { entries = withContext(Dispatchers.IO) { store.clear() } }
    }

    // ---- report ----
    fun generateReport() = work {
        val target = reportLang
        val base = ReportBuilder.aggregate(entries, assets.knowledge, target, reportDays) ?: run {
            reportHtml = null
            return@work
        }
        var finalSummary: ReportSummary = base
        var seconds = 0.0
        // Entries written in another language: translate the patient's words for the doctor.
        if (modelReady && base.sourceLangs.any { it != target }) {
            val items = base.translatableTerms().filter { term -> term.any { it.isLetter() } && detectLang(term) != target }
            if (items.isNotEmpty()) {
                val prompt = fill(assets.prompt("translate_terms"),
                    "lang_name" to langName(target), "items_json" to JSONArray(items).toString())
                val out = gemma.generate(prompt)
                seconds += out.seconds
                val obj = JsonExtract.parseObject(out.text)
                if (obj != null) finalSummary = base.translated(obj.keys().asSequence().associateWith { obj.optString(it) })
            }
        }
        summary = finalSummary
        summaryLang = target
        narrative = if (modelReady) {
            val prompt = fill(assets.prompt("report_narrative"),
                "lang_name" to langName(target), "summary_json" to finalSummary.narrativeInput())
            val out = gemma.generate(prompt)
            seconds += out.seconds
            out.text
        } else {
            Texts(target).placeholderNarrative
        }
        timing = "report" to seconds
        rerender()
    }

    fun rerender() {
        val s = summary ?: return
        reportHtml = ReportBuilder.html(s, narrative, summaryLang, questionsForDoctor.lines())
    }

    override fun onCleared() {
        speech.release()
        gemma.close()
    }
}
