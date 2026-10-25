package com.gemmahack.symptomdiary

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

enum class Screen(val label: String) {
    HOME("Talk"), PREPARE("Prepare your visit"), REVIEW("Review your answers"), REPORT("Your visit report"),
    CONSENT("Start your visit"), LIVE("During your visit"), WRAP_UP("Before you leave"), SCAN("Save your instructions"),
    EXTRACTION("Review the details"), PLAN("Your plan"), SAVE("Save to your records"), RECORDS("My records"),
    HEALTH("Health essentials"), DETAIL("Visit details"), SETTINGS("Settings"), RESOURCES("Offline resources"),
    LANGUAGE("Your language"), DOCTOR("Show doctor"), SOURCE("Original source"),
}

class WithYouViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    val repository = (app as WithYouApplication).repository
    private val preferences = app.getSharedPreferences("with-you-navigation", Context.MODE_PRIVATE)
    private val engine = GemmaEngine(app)
    private val intelligence = VisitIntelligence(app, engine)
    private val speech = SystemSpeech(app)
    private val voiceNote = VoiceNoteRecorder()
    private val speaker = Speaker(app)
    private val power = app.getSystemService(PowerManager::class.java)
    var data by mutableStateOf(repository.state.value); private set
    var screen by mutableStateOf(Screen.HOME); private set
    var visitId by mutableStateOf(preferences.getString("visit", null)); private set
    val visit: Visit? get() = data.visits.find { it.id == visitId }
    var input by mutableStateOf(""); private set
    private var inputKind = visit?.inputDraftKind ?: SourceKind.PATIENT_TEXT
    var notice by mutableStateOf<String?>(repository.openError)
    var busy by mutableStateOf(false); private set
    var modelLoading by mutableStateOf(false); private set
    var modelReady by mutableStateOf(false); private set
    var modelStatus by mutableStateOf("Checking local Gemma resources…"); private set
    var modelFingerprint by mutableStateOf(""); private set
    var voiceStatus by mutableStateOf("Tap to talk. Nothing is listening yet."); private set
    var listening by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var useGemmaVoice by mutableStateOf(false)
    var progressMb by mutableLongStateOf(0L); private set
    var reportFile by mutableStateOf<File?>(null); private set
    var reportIndex by mutableIntStateOf(-1)
    var sourceId by mutableStateOf<String?>(null); private set
    var doctorQuestion by mutableStateOf(""); private set
    var doctorReason by mutableStateOf(""); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var playing by mutableStateOf(false); private set
    var thermal by mutableIntStateOf(power.currentThermalStatus); private set
    private var player: MediaPlayer? = null
    private var draftJob: Job? = null
    private var voiceToken = 0
    private var sourceBack = Screen.DETAIL
    private var doctorBack = Screen.LIVE
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermal = it }

    val modelName: String get() = engine.modelName
    val backend: String get() = engine.backendName
    val audioAvailable: Boolean get() = modelReady && engine.audioEnabled
    val visionAvailable: Boolean get() = modelReady && engine.visionEnabled
    val hardware: String get() = "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MODEL}"
    val storageGb: String get() = "%.1f GB free".format(repository.vault.directory.usableSpace / 1e9)
    val processingQueue: Int get() = data.visits.sumOf { v -> v.sources.count { it.status in setOf(Processing.QUEUED, Processing.PROCESSING, Processing.FAILED) } }

    init {
        input = visit?.inputDraft.orEmpty()
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        viewModelScope.launch { repository.state.collect { data = it } }
        loadModel()
        viewModelScope.launch {
            while (isActive) {
                delay(1500)
                // Real captured chunks only. Fall behind gracefully without manufacturing live results.
                val candidate = data.visits.firstOrNull { it.recording == Recording.RECORDING }
                val pending = candidate?.sources?.firstOrNull { it.status == Processing.QUEUED }
                if (candidate != null && pending != null && modelReady && !busy && thermal < PowerManager.THERMAL_STATUS_SEVERE) {
                    process(candidate.id, pending.id, quiet = true)
                }
            }
        }
        // Inexact alarms are restored after a process restart; no cloud or exact-alarm permission.
        data.visits.forEach { v -> v.reminders.forEach { r -> runCatching { LocalReminders.schedule(context, v.id, r) } } }
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message?.take(260) ?: "Could not finish. Your saved sources are preserved. Please retry." }
    }
    private suspend fun change(block: (HealthStore) -> HealthStore) = withContext(Dispatchers.IO) { repository.update(block) }
    private suspend fun changeVisit(id: String, block: (Visit) -> Visit) = withContext(Dispatchers.IO) { repository.updateVisit(id, block) }

    fun navigate(target: Screen) {
        stopSpeaking(); cancelDictation()
        if (target != Screen.SOURCE) { preview = null; stopPlayback() }
        screen = target
    }

    fun back() = navigate(when (screen) {
        Screen.HOME -> Screen.HOME
        Screen.PREPARE, Screen.RECORDS -> Screen.HOME
        Screen.REVIEW -> Screen.PREPARE
        Screen.REPORT -> Screen.REVIEW
        Screen.CONSENT -> if (visit?.reports?.isNotEmpty() == true) Screen.REPORT else Screen.DETAIL
        Screen.LIVE -> Screen.DETAIL
        Screen.WRAP_UP -> Screen.LIVE
        Screen.SCAN -> Screen.DETAIL
        Screen.EXTRACTION -> Screen.SCAN
        Screen.PLAN -> Screen.EXTRACTION
        Screen.SAVE -> Screen.PLAN
        Screen.HEALTH, Screen.SETTINGS, Screen.DETAIL -> Screen.RECORDS
        Screen.RESOURCES, Screen.LANGUAGE -> Screen.SETTINGS
        Screen.DOCTOR -> doctorBack
        Screen.SOURCE -> sourceBack
    })

    fun newVisit(target: Screen = Screen.PREPARE, thenListen: Boolean = false) = action {
        val v = Visit(language = data.settings.voiceLanguage)
        change { it.copy(visits = it.visits + v) }
        selectVisit(v.id, target)
        if (thenListen) startDictation()
    }

    fun selectVisit(id: String, target: Screen = Screen.DETAIL) {
        visitId = id; preferences.edit().putString("visit", id).apply()
        input = data.visits.find { it.id == id }?.inputDraft.orEmpty()
        inputKind = data.visits.find { it.id == id }?.inputDraftKind ?: SourceKind.PATIENT_TEXT
        reportIndex = -1; reportFile = null
        navigate(target)
    }

    fun editInput(text: String) {
        input = text
        val id = visitId ?: return
        draftJob?.cancel()
        val origin = inputKind
        draftJob = action { delay(350); changeVisit(id) { it.copy(inputDraft = text, inputDraftKind = origin) } }
    }

    fun submit(kind: SourceKind? = null) = action {
        val id = visitId ?: return@action
        val text = input.trim()
        if (text.isBlank()) return@action
        draftJob?.cancel()
        val v = repository.visit(id)
        val source = Source(kind = kind ?: if (v.stage in setOf(Stage.PREPARING, Stage.READY)) inputKind
            else SourceKind.PATIENT_RECOLLECTION, text = text, originalText = text, status = Processing.QUEUED)
        changeVisit(id) { old -> old.copy(sources = old.sources + source, inputDraft = "", summaryReview = Review.DRAFT,
            title = if (old.title == "New visit") text.take(70).replace('\n', ' ') else old.title) }
        input = ""
        inputKind = SourceKind.PATIENT_TEXT
        if (modelReady && !busy) process(id, source.id) else notice = "Your words are saved. You can review them manually or organize them when Gemma is ready."
    }

    fun skipQuestion() = action {
        val id = visitId ?: return@action
        changeVisit(id) { it.copy(skippedQuestions = it.skippedQuestions + it.nextQuestion,
            nextQuestion = "Add anything else, or review what you’ve shared.") }
    }

    fun reviewFact(id: String, status: Review, doctorChecked: Boolean = false) = action {
        val current = visitId ?: return@action
        changeVisit(current) { VisitRules.review(it, id, status, doctorChecked).copy(summaryReview = Review.DRAFT) }
    }

    fun translateFact(factId: String) = action {
        val id = visitId ?: return@action
        if (!modelReady || busy) return@action
        val fact = repository.visit(id).facts.first { it.id == factId }
        busy = true
        try {
            val result = intelligence.translate(fact)
            changeVisit(id) { v -> v.copy(facts = v.facts.map { if (it.id == factId && it.quote == fact.quote) it.copy(
                english = result.text, translationReview = Review.DRAFT) else it }, generations = v.generations +
                Generation(task = "English translation draft", model = engine.modelName, promptVersion = "literal-translation-v1",
                    sourceIds = listOf(fact.sourceId), seconds = result.seconds)) }
        } finally { busy = false }
    }
    fun reviewTranslation(factId: String) = action {
        val id = visitId ?: return@action
        changeVisit(id) { v -> v.copy(facts = v.facts.map { if (it.id == factId) it.copy(translationReview = Review.PATIENT_REVIEWED) else it }) }
    }

    fun correctFact(id: String, wording: String) = action {
        val current = visitId ?: return@action
        require(wording.isNotBlank())
        changeVisit(current) { v ->
            val previous = v.facts.first { it.id == id }
            val source = Source(kind = SourceKind.PATIENT_TEXT, text = wording, originalText = wording, parentId = previous.sourceId, status = Processing.READY)
            val replacement = Fact(id = stableId(source.id, previous.category.name, wording), category = previous.category,
                label = previous.label, quote = wording, sourceId = source.id, status = Review.DRAFT, supersedes = previous.id)
            v.copy(sources = v.sources + source, facts = v.facts.map { if (it.id == id) it.copy(superseded = true) else it } + replacement,
                summaryReview = Review.DRAFT)
        }
        notice = "Correction added with its own source. The previous wording is retained; review both before saving."
    }

    fun addManualFact(category: Category, text: String, name: String = "", dose: String = "", unit: String = "", frequency: String = "", reaction: String = "", timing: String = "", uncertain: Boolean = false) = action {
        val id = visitId ?: return@action
        require(text.isNotBlank())
        listOf(name, dose, unit, frequency, reaction, timing).filter(String::isNotBlank).forEach {
            require(text.contains(it, true)) { "The name, dose, unit, frequency and reaction must also appear in the original wording." }
        }
        val source = Source(kind = if (visit?.stage == Stage.IN_VISIT) SourceKind.PATIENT_RECOLLECTION else SourceKind.PATIENT_TEXT,
            text = text, originalText = text, status = Processing.READY)
        val fact = Fact(id = stableId(source.id, category.name, text), category = category, label = category.title,
            quote = text, sourceId = source.id, name = name.ifBlank { null }, dose = dose.ifBlank { null }, unit = unit.ifBlank { null },
            frequency = frequency.ifBlank { null }, reaction = reaction.ifBlank { null }, timing = timing.ifBlank { null }, uncertain = uncertain)
        changeVisit(id) { v -> v.copy(sources = v.sources + source, facts = VisitRules.mergeFacts(v.facts, listOf(fact)), summaryReview = Review.DRAFT) }
    }

    fun addQuestion(text: String) = action {
        val id = visitId ?: return@action
        require(text.isNotBlank())
        changeVisit(id) { it.copy(questions = it.questions + VisitQuestion(text = text, origin = QuestionOrigin.PATIENT)) }
    }
    fun changeQuestion(id: String, selected: Boolean? = null, state: QuestionState? = null, text: String? = null, move: Int = 0, delete: Boolean = false) = action {
        val current = visitId ?: return@action
        changeVisit(current) { v ->
            val list = v.questions.map { if (it.id == id) it.copy(selected = selected ?: it.selected, state = state ?: it.state, text = text ?: it.text) else it }.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0 && move != 0) { val item = list.removeAt(index); list.add((index + move).coerceIn(0, list.size), item) }
            if (delete) list.removeAll { it.id == id }
            v.copy(questions = list)
        }
    }

    fun createReport() = action {
        val id = visitId ?: return@action
        var snapshot: ReportSnapshot? = null
        changeVisit(id) { v ->
            VisitRules.snapshot(v, repository.state.value.profile).copy(categoryStates = repository.state.value.settings.categoryStates,
                sourceDetails = repository.state.value.visits.flatMap { it.sources }.associate { it.id to "${it.label}; ${displayDate(it.createdAt)}" })
                .also { snapshot = it }.let { v.copy(reports = v.reports + it, stage = if (v.stage == Stage.PREPARING) Stage.READY else v.stage) }
        }
        reportFile = withContext(Dispatchers.IO) { ReportExport.render(context, snapshot!!) }
        reportIndex = -1; navigate(Screen.REPORT)
    }
    fun renderReport(index: Int = reportIndex) = action {
        val reports = visit?.reports ?: return@action
        val snapshot = if (index < 0) reports.lastOrNull() else reports.getOrNull(index)
        if (snapshot != null) reportFile = withContext(Dispatchers.IO) { ReportExport.render(context, snapshot) }
    }

    fun savePdf(uri: Uri) = action {
        EncryptedBackup.requireLocal(uri, context)
        val file = reportFile ?: error("Generate the report first.")
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("The destination could not be written.") }
        notice = "Report saved to the local file you selected."
    }

    fun setStage(stage: Stage, target: Screen) = action {
        val id = visitId ?: return@action
        if (stage != Stage.IN_VISIT && visit?.isCapturing == true) stopVisitRecording()
        changeVisit(id) { it.copy(stage = stage) }; navigate(target)
    }

    fun startVisitRecording(everyoneAgreed: Boolean = false) = action {
        require(everyoneAgreed) { "Ask everyone in the room and confirm consent first." }
        val id = visitId ?: return@action
        stopSpeaking(); cancelDictation()
        changeVisit(id) { v -> check(!v.isCapturing); v.copy(stage = Stage.IN_VISIT, consents = v.consents + Consent()) }
        VisitRecordingService.command(context, id, "start")
        navigate(Screen.LIVE)
    }
    fun manualVisit() = setStage(Stage.IN_VISIT, Screen.LIVE)
    fun pauseVisitRecording() { stopSpeaking(); stopPlayback(); visitId?.let { VisitRecordingService.command(context, it, if (visit?.recording == Recording.PAUSED) "resume" else "pause") } }
    fun stopVisitRecording(withdraw: Boolean = false) {
        val id = visitId ?: return
        VisitRecordingService.command(context, id, if (withdraw) "withdraw" else "stop")
        if (withdraw) action { changeVisit(id) { v -> v.copy(consents = v.consents.map {
            if (it.active) it.copy(withdrawnAt = now()) else it }) } }
    }

    fun showDoctor(question: String, reason: String = "") {
        doctorQuestion = question; doctorReason = reason; doctorBack = screen; navigate(Screen.DOCTOR)
    }

    fun startDictation() {
        if (listening || visit?.isCapturing == true) return
        val id = visitId ?: return
        stopSpeaking(); val token = ++voiceToken
        if (useGemmaVoice) {
            if (!audioAvailable) { notice = "Gemma audio is not available. Use installed Pixel dictation or type instead."; return }
            try {
                listening = true; voiceStatus = "Listening · short note, up to 12 seconds"
                voiceNote.start { bytes -> viewModelScope.launch {
                    listening = false; voiceStatus = "Stopped. Saving your voice note."
                    if (bytes == null) { notice = "No clear audio was captured. Type instead or retry."; return@launch }
                    action {
                        val blob = withContext(Dispatchers.IO) { repository.vault.put(bytes) }
                        val source = Source(kind = SourceKind.PATIENT_VOICE, blob = blob, mime = "audio/wav", status = Processing.QUEUED,
                            startMs = 0, endMs = (bytes.size - 44).toLong() * 1000 / 32000)
                        changeVisit(id) { it.copy(sources = it.sources + source) }
                        if (modelReady && !busy) process(id, source.id)
                    }
                } }
            } catch (_: Exception) { listening = false; notice = "The microphone could not start. Type instead or retry." }
            return
        }
        voiceStatus = "Checking installed offline speech…"
        speech.check(data.settings.voiceLanguage) { installed ->
            if (token != voiceToken) return@check
            if (!installed) { voiceStatus = "Offline speech is not verified for this language. Use Gemma audio or type instead."; return@check }
            val before = input
            var languageConfident = data.settings.voiceLanguage != "auto"
            listening = true; voiceStatus = "Listening · on-device dictation"
            speech.start(data.settings.voiceLanguage, partial = { input = (before + " " + it).trim() },
                result = { text -> listening = false; inputKind = SourceKind.PATIENT_VOICE; editInput((before + " " + text).trim()); voiceStatus = "Paused · review your words, then send."
                    if (!languageConfident) notice = "The input language was unclear. Choose your voice language in Settings before trying again; review this transcription carefully." },
                failed = { listening = false; editInput(input); voiceStatus = "Dictation stopped. Review the words or type instead." },
                detected = { language, confident -> languageConfident = confident; voiceStatus = if (confident) "Listening · detected $language · interface stays English"
                    else "Listening · language unclear; you can choose it in Settings" })
        }
    }
    fun stopDictation() { if (useGemmaVoice) voiceNote.stop() else speech.stop() }
    private fun cancelDictation() { voiceToken++; if (listening) { voiceNote.stop(); speech.cancel(); listening = false; editInput(input) } }
    fun onBackground() { cancelDictation(); stopSpeaking(); stopPlayback() }

    fun readAloud(text: String) {
        if (speaking) { stopSpeaking(); return }
        if (visit?.recording == Recording.RECORDING) { notice = "Pause or stop visit recording before using read-aloud."; return }
        speaking = speaker.speak(text) { speaking = false }
        if (!speaking) notice = "An installed English offline voice is required. Read the text here or provision a voice before the demo."
    }
    fun stopSpeaking() { speaker.stop(); speaking = false }

    fun retrySource(id: String) = action { if (!busy) process(visitId ?: return@action, id) }
    fun organizePending() = action {
        val id = visitId ?: return@action
        if (busy) return@action
        val pending = repository.visit(id).sources.filter { it.status in setOf(Processing.IDLE, Processing.QUEUED, Processing.FAILED) }
        for (source in pending) process(id, source.id)
    }

    private suspend fun process(id: String, sourceId: String, quiet: Boolean = false) {
        if (busy || !modelReady) { if (!quiet) notice = "Gemma is not ready. Your original is saved for later."; return }
        busy = true
        val started = System.nanoTime()
        try {
            changeVisit(id) { v -> v.copy(sources = v.sources.map { if (it.id == sourceId) it.copy(status = Processing.PROCESSING, error = null) else it }) }
            var source = repository.visit(id).sources.first { it.id == sourceId }
            if (source.text.isBlank()) {
                val bytes = withContext(Dispatchers.IO) { repository.vault.read(source.blob ?: error("Original file is unavailable.")) }
                val result = when {
                    source.mime == "audio/wav" -> intelligence.transcribe(bytes, repository.visit(id).language)
                    source.mime?.startsWith("image/") == true -> {
                        val image = withContext(Dispatchers.IO) { LocalFiles.image(bytes) }
                        try { intelligence.readPaper(LocalFiles.png(image)) } finally { image.recycle() }
                    }
                    source.mime == "application/pdf" -> {
                        val image = withContext(Dispatchers.IO) { LocalFiles.withPdf(context, bytes) { LocalFiles.pdfPage(it, (source.page ?: 1) - 1) } }
                        try { intelligence.readPaper(LocalFiles.png(image)) } finally { image.recycle() }
                    }
                    else -> error("Type the original wording to organize this source.")
                }
                check(result.text.isNotBlank()) { "No text could be read. Review the original or type its wording." }
                source = source.copy(text = result.text, originalText = result.text)
                val updated = source
                changeVisit(id) { v -> v.copy(sources = v.sources.map { if (it.id == sourceId) updated else it },
                    generations = v.generations + Generation(task = if (updated.mime == "audio/wav") "transcription" else "paper transcription",
                        model = engine.modelName, promptVersion = "literal-source-v1", sourceIds = listOf(sourceId), seconds = result.seconds)) }
            }
            val task = if (repository.visit(id).stage in setOf(Stage.PREPARING, Stage.READY)) "prepare" else "post / live"
            val (result, generation) = intelligence.extract(repository.visit(id), source, task)
            changeVisit(id) { v ->
                // A user can edit a source while inference runs. Never apply output to a changed input.
                if (v.sources.first { it.id == sourceId }.text != source.text) return@changeVisit v
                val slots = (2 - v.questions.count { it.origin == QuestionOrigin.SUGGESTED && it.state == QuestionState.TO_ASK }).coerceAtLeast(0)
                val suggestions = result.suggestions.filter { text -> v.questions.none { it.text.equals(text, true) } }.take(slots)
                val retained = v.facts.map { if (it.sourceId == sourceId && !source.text.contains(it.quote)) it.copy(superseded = true) else it }
                v.copy(facts = VisitRules.mergeFacts(retained, result.facts),
                    sources = v.sources.map { if (it.id == sourceId) it.copy(status = Processing.READY) else it },
                    nextQuestion = result.nextQuestion?.takeIf { it !in v.skippedQuestions } ?: "Anything else you want to discuss?",
                    questions = v.questions + suggestions.map { VisitQuestion(text = it, origin = QuestionOrigin.SUGGESTED,
                        sourceIds = listOf(sourceId), reason = "Suggested from ${source.label}; not a clinical recommendation.") },
                    generations = v.generations + generation, summaryReview = Review.DRAFT)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            changeVisit(id) { v -> v.copy(sources = v.sources.map { if (it.id == sourceId) it.copy(status = Processing.FAILED,
                error = "Local processing could not finish or verify its output. Original retained. Retry or edit manually.") else it },
                generations = v.generations + Generation(task = "Processing failed or output rejected", model = engine.modelName,
                    promptVersion = "prepare-v2 / literal-source-v1", sourceIds = listOf(sourceId), seconds = (System.nanoTime() - started) / 1e9, valid = false)) }
            if (!quiet) notice = "Local processing needs another try. Your original is saved; you can also add a detail manually."
        } finally { busy = false }
    }

    fun importDocument(uri: Uri) = action {
        val id = visitId ?: return@action
        EncryptedBackup.requireLocal(uri, context)
        withContext(Dispatchers.IO) {
            val bytes = LocalFiles.readDocument(context, uri)
            val mime = LocalFiles.type(bytes)
            val blob = repository.vault.put(bytes)
            val pageCount = if (mime == "application/pdf") LocalFiles.withPdf(context, bytes) { it.pageCount } else 1
            require(pageCount in 1..200) { "Split documents longer than 200 pages before importing." }
            repository.updateVisit(id) { v ->
                val first = (v.sources.filter { it.kind == SourceKind.DOCUMENT }.maxOfOrNull { it.page ?: 0 } ?: 0) + 1
                v.copy(summaryReview = Review.DRAFT, sources = v.sources + (0 until pageCount).map { page -> Source(kind = SourceKind.DOCUMENT,
                    blob = blob, mime = mime, page = if (mime == "application/pdf") page + 1 else first + page,
                    status = Processing.QUEUED) })
            }
        }
        notice = "Original saved on this phone. Review, crop, retake or extract the pages when ready."
        navigate(Screen.EXTRACTION)
    }

    fun importCameraFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        importDocument(uri).invokeOnCompletion { file.delete() }
    }

    fun exportSource(uri: Uri, source: Source) = action {
        EncryptedBackup.requireLocal(uri, context)
        withContext(Dispatchers.IO) {
            val bytes = source.blob?.let(repository.vault::read) ?: source.text.toByteArray()
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("The chosen local file could not be written.")
        }
        notice = "Source exported to your chosen local file."
    }

    fun exportSummary(uri: Uri) = action {
        EncryptedBackup.requireLocal(uri, context)
        val v = visit ?: return@action
        val text = "${v.title}\n${v.summaryReview}\n\n${v.summary}\n\nDetails needing review:\n" + v.unresolved.joinToString("\n") { it.quote }
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: error("The chosen local file could not be written.") }
        notice = "Summary exported to your chosen local file."
    }

    fun openSource(id: String) {
        if (screen != Screen.SOURCE) sourceBack = screen
        sourceId = id; preview = null; navigate(Screen.SOURCE)
        val s = visit?.sources?.find { it.id == id } ?: return
        if (s.blob != null && (s.mime?.startsWith("image/") == true || s.mime == "application/pdf")) action {
            preview = withContext(Dispatchers.IO) { LocalFiles.preview(context, repository.vault.read(s.blob), s.mime!!, (s.page ?: 1) - 1) }
        }
    }
    fun playSource(id: String) = action {
        if (playing) { stopPlayback(); return@action }
        if (visit?.recording == Recording.RECORDING) { notice = "Pause or stop recording before playing audio."; return@action }
        val s = visit?.sources?.find { it.id == id } ?: return@action
        val bytes = withContext(Dispatchers.IO) { repository.vault.read(s.blob ?: error("Audio was deleted.")) }
        player = LocalFiles.play(bytes) { playing = false; player = null }; playing = true
    }
    fun stopPlayback() { player?.release(); player = null; playing = false }
    fun editSource(id: String, text: String) = action {
        val current = visitId ?: return@action
        require(text.isNotBlank())
        changeVisit(current) { v -> v.copy(sources = v.sources.map { if (it.id == id) it.copy(text = text,
            editedAt = now(), status = Processing.QUEUED) else it }, facts = v.facts.map { if (it.sourceId == id)
            it.copy(status = Review.UNRESOLVED, reviewNote = "Source edited; review the earlier extraction against the original.") else it }, summaryReview = Review.DRAFT) }
        notice = "Correction saved. Original transcription is retained. Retry extraction when ready."
    }
    fun cropSource(left: Float, top: Float, right: Float, bottom: Float) = action {
        val id = visitId ?: return@action
        val s = visit?.sources?.find { it.id == sourceId } ?: return@action
        val bitmap = preview ?: return@action
        require(right - left > 0.1f && bottom - top > 0.1f)
        val cropped = Bitmap.createBitmap(bitmap, (left * bitmap.width).toInt(), (top * bitmap.height).toInt(),
            ((right - left) * bitmap.width).toInt(), ((bottom - top) * bitmap.height).toInt())
        val blob = withContext(Dispatchers.IO) { try { repository.vault.put(LocalFiles.png(cropped)) } finally { cropped.recycle() } }
        val source = Source(kind = SourceKind.DOCUMENT, blob = blob, mime = "image/png", page = s.page,
            parentId = s.id, status = Processing.QUEUED)
        changeVisit(id) { it.copy(sources = it.sources + source) }; openSource(source.id)
    }

    fun explainPlan() = action {
        val id = visitId ?: return@action
        if (!modelReady || busy) { notice = "Review the source details now, or retry when Gemma is ready."; return@action }
        busy = true
        try {
            val sourceFacts = repository.visit(id).facts
            val result = intelligence.plainLanguage(repository.visit(id))
            changeVisit(id) { v -> if (v.facts != sourceFacts) v else v.copy(summary = result.text, summaryReview = Review.DRAFT,
                generations = v.generations + Generation(task = "plain-language explanation", model = engine.modelName,
                    promptVersion = "follow-through-v1", sourceIds = sourceFacts.map { it.sourceId }.distinct(), seconds = result.seconds)) }
        } finally { busy = false }
    }
    fun reviewSummary() = action { val id = visitId ?: return@action; changeVisit(id) { it.copy(summaryReview = Review.PATIENT_REVIEWED) } }
    fun archive(summary: Boolean, originals: Boolean, reviewed: Boolean, promote: Set<String>) = action {
        val id = visitId ?: return@action
        check(visit?.isCapturing != true) { "Stop recording before saving selected items." }
        require(summary || originals || reviewed || promote.isNotEmpty()) { "Choose at least one item to save." }
        require(!summary || visit?.summary?.isNotBlank() == true) { "There is no explanation yet. Choose originals or reviewed details, or create an explanation first." }
        change { store ->
            val v = store.visits.first { it.id == id }
            val promoted = VisitRules.promote(store, v, promote)
            promoted.copy(visits = promoted.visits.map { old -> if (old.id != id) old else old.copy(
                stage = Stage.ARCHIVED, archivedAt = old.archivedAt ?: now(), savedSummary = old.savedSummary || summary,
                savedSourceIds = (old.savedSourceIds + if (originals) old.sources.map { it.id } else emptyList()).distinct(),
                savedFactIds = (old.savedFactIds + if (reviewed) old.facts.filter { !it.needsReview }.map { it.id } else emptyList()).distinct()) })
        }
        navigate(Screen.DETAIL); notice = "Selected items saved. Unresolved details stay flagged; unselected material remains in this visit's draft."
    }

    fun promoteFact(id: String) = action {
        val current = visitId ?: return@action
        change { state -> VisitRules.promote(state, state.visits.first { it.id == current }, setOf(id)) }
    }
    fun markHistorical(id: String) = action {
        change { state -> state.copy(profile = state.profile.map { if (it.id == id) it.copy(status = ProfileStatus.HISTORICAL,
            changes = it.changes + ProfileChange(action = "Patient explicitly moved this entry to history")) else it }) }
    }
    fun newProfileCorrection(entryId: String) = action {
        val entry = data.profile.first { it.id == entryId }
        val v = Visit(title = "Update ${entry.fact.category.title.lowercase()}", replacingProfileId = entryId,
            nextQuestion = "What would you like to correct?", language = data.settings.voiceLanguage)
        change { it.copy(visits = it.visits + v) }
        selectVisit(v.id, Screen.REVIEW)
    }
    fun setCategoryState(category: Category, status: KnowledgeState) = action {
        val wording = if (status == KnowledgeState.NONE_CONFIRMED) "Patient reports none for ${category.title.lowercase()}."
            else "Patient says ${category.title.lowercase()} are unknown."
        val source = Source(kind = SourceKind.PATIENT_TEXT, text = wording, originalText = wording, status = Processing.READY)
        val fact = Fact(id = stableId(source.id, category.name, wording), category = category, label = category.title,
            quote = wording, sourceId = source.id, status = if (status == KnowledgeState.NONE_CONFIRMED) Review.PATIENT_REVIEWED else Review.UNRESOLVED)
        change { state -> state.copy(settings = state.settings.copy(categoryStates = state.settings.categoryStates + (category.name to status),
            categoryStateSourceIds = state.settings.categoryStateSourceIds + (category.name to source.id)),
            visits = state.visits + Visit(title = "Health essentials updated", stage = Stage.ARCHIVED, sources = listOf(source), facts = listOf(fact))) }
    }
    fun settings(transform: (Settings) -> Settings) = action { change { it.copy(settings = transform(it.settings)) } }
    fun updateIdentity(name: String, dob: String, appointment: String, doctor: String, clinic: String) = action {
        val id = visitId ?: return@action
        require(listOf(name, appointment, doctor, clinic).all { it.length <= 80 } && dob.length <= 30) { "Keep each optional header field under 80 characters (date of birth under 30)." }
        changeVisit(id) { it.copy(patientName = name, dateOfBirth = dob, appointment = appointment, doctor = doctor, clinic = clinic) }
    }

    fun deleteAudio() = action {
        val id = visitId ?: return@action
        stopPlayback(); withContext(Dispatchers.IO) { repository.deleteAudio(id) }; notice = "Audio deleted. Your transcript, summary and paper sources are retained."
    }
    fun deleteSummary() = action {
        val id = visitId ?: return@action
        changeVisit(id) { it.copy(summary = "", savedSummary = false, summaryReview = Review.DRAFT) }
    }
    fun deleteVisit() = action {
        val id = visitId ?: return@action
        check(!busy && visit?.isCapturing != true) { "Stop recording and wait for processing before deleting this visit." }
        val old = repository.visit(id)
        old.reminders.forEach { LocalReminders.cancel(context, id, it.id) }
        // Preserve profile provenance by copying source facts to a separate retained profile-source visit.
        change { state ->
            val retainedIds = (state.profile.map { it.fact.sourceId } + state.settings.categoryStateSourceIds.values).toMutableSet()
            do {
                val before = retainedIds.size
                old.sources.filter { it.id in retainedIds }.mapNotNull { it.parentId }.forEach(retainedIds::add)
            } while (before != retainedIds.size)
            val sources = old.sources.filter { it.id in retainedIds }
            val retained = if (sources.isNotEmpty()) listOf(Visit(title = "Retained health record sources", stage = Stage.ARCHIVED,
                sources = sources, facts = old.facts.filter { it.sourceId in retainedIds })) else emptyList()
            state.copy(visits = state.visits.filter { it.id != id } + retained)
        }
        val kept = repository.state.value.visits.flatMap { it.sources }.mapNotNull { it.blob }.toSet()
        withContext(Dispatchers.IO) { old.sources.mapNotNull { it.blob }.distinct().filter { it !in kept }.forEach(repository.vault::delete) }
        visitId = null; input = ""; navigate(Screen.RECORDS)
    }

    fun scheduleReminder(factId: String, localDateTime: String) = action {
        val id = visitId ?: return@action
        val fact = visit?.facts?.first { it.id == factId } ?: return@action
        require(fact.category == Category.ACTION && !fact.needsReview) { "Review this action before setting a reminder." }
        val zone = ZoneId.systemDefault()
        val time = LocalDateTime.parse(localDateTime).atZone(zone)
        val reminder = ActionReminder(id = stableId(id, factId), factId = factId, at = time.toString(), zone = zone.id)
        LocalReminders.schedule(context, id, reminder)
        changeVisit(id) { it.copy(reminders = it.reminders.filter { old -> old.id != reminder.id } + reminder) }
        notice = "Local reminder saved for ${time.toLocalDateTime()} (${zone.id}). Delivery is approximate."
    }
    fun cancelReminder(id: String) = action {
        val current = visitId ?: return@action
        LocalReminders.cancel(context, current, id)
        changeVisit(current) { it.copy(reminders = it.reminders.filter { reminder -> reminder.id != id }) }
    }

    fun loadModel() = action {
        if (modelLoading || busy) return@action
        modelLoading = true; modelStatus = "Loading local Gemma…"
        try {
            val seconds = engine.load(repository.state.value.settings.modelPreference)
            modelReady = true; modelStatus = "Gemma ready · ${engine.backendName} · loaded in %.1f s".format(seconds)
            val file = engine.candidateFiles(repository.state.value.settings.modelPreference).firstOrNull { it.name == engine.modelName }
            modelFingerprint = context.getSharedPreferences("model-integrity", Context.MODE_PRIVATE).getString(file?.name, null)
                ?.let { "SHA-256 verified on import: $it" } ?: "Sideloaded model. Publisher checksum has not been verified in the app."
        } catch (_: Exception) { modelReady = false; modelStatus = "Gemma not available. Import a compatible local model or retry. Manual records still work." }
        finally { modelLoading = false }
    }
    fun reloadModel() = action {
        if (busy || modelLoading) return@action
        modelReady = false; engine.unload(); loadModel()
    }
    fun importModel(uri: Uri, variant: String, checksum: String) = action {
        if (busy || modelLoading) return@action
        EncryptedBackup.requireLocal(uri, context)
        modelLoading = true; modelReady = false; progressMb = 0
        try {
            engine.unload()
            modelFingerprint = withContext(Dispatchers.IO) { ModelProvisioner.import(context, uri, variant, checksum) { count ->
                viewModelScope.launch { progressMb = count / (1024 * 1024) }
            } }
            change { it.copy(settings = it.settings.copy(modelPreference = variant)) }
            modelStatus = "Model imported and checksum verified. Tap Load model."
        } finally { modelLoading = false }
    }
    fun backup(uri: Uri, password: String, restore: Boolean) = action {
        check(!busy) { "Wait for processing before backing up or restoring." }
        busy = true
        val secret = password.toCharArray()
        try {
            val count = withContext(Dispatchers.IO) {
                if (restore) EncryptedBackup.restore(context, repository, uri, secret)
                else { EncryptedBackup.export(context, repository, uri, secret); 0 }
            }
            notice = if (restore) "Backup verified. $count visits added. Existing visit IDs were kept; nothing was overwritten."
                else "Encrypted backup saved to your chosen local file. Keep your password separately; there is no password recovery."
        } finally { secret.fill('\u0000'); busy = false }
    }

    fun loadDemo() = action {
        change { state ->
            if (state.visits.any { it.id == "with-you-demo-v2" }) state else {
                val source = Source(id = "demo-patient-source", kind = SourceKind.PATIENT_TEXT,
                    text = "Headaches for 3 weeks, now 4–5 days a week. A band squeezing around my head. I take ibuprofen 400 mg most days; it helps for 2–3 hours. Penicillin caused hives. Could my poor sleep be related?",
                    status = Processing.READY)
                fun fact(category: Category, quote: String, name: String? = null) = Fact(id = stableId(source.id, category.name, quote),
                    category = category, label = category.title, quote = quote, sourceId = source.id, name = name)
                val facts = listOf(fact(Category.SYMPTOM, "Headaches for 3 weeks, now 4–5 days a week."),
                    fact(Category.SYMPTOM, "A band squeezing around my head."),
                    fact(Category.MEDICINE, "I take ibuprofen 400 mg most days; it helps for 2–3 hours.", "ibuprofen"),
                    fact(Category.ALLERGY, "Penicillin caused hives.", "Penicillin"), fact(Category.GOAL, "Could my poor sleep be related?"))
                state.copy(visits = state.visits + Visit(id = "with-you-demo-v2", title = "Headaches for 3 weeks", sources = listOf(source),
                    facts = facts, questions = listOf(VisitQuestion(text = "Could my poor sleep be related?", origin = QuestionOrigin.PATIENT),
                        VisitQuestion(text = "Is taking ibuprofen this often appropriate for me?", origin = QuestionOrigin.SUGGESTED)), demo = true))
            }
        }
        selectVisit("with-you-demo-v2", Screen.REVIEW)
    }

    override fun onCleared() {
        speech.close(); voiceNote.stop(); speaker.close(); stopPlayback()
        power.removeThermalStatusListener(thermalListener)
        CoroutineScope(Dispatchers.IO).launch { engine.close() }
    }
}
