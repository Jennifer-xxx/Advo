package com.gemmahack.symptomdiary

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecordsScreen(vm: WithYouViewModel) {
    val records = RecordRules.symptoms(vm.data).sortedByDescending { it.createdAt }
    Title("Your records", "Your symptom story, medicines and allergies. All on this phone.")
    EntryRow("health", "Health profile", "Medicines, allergies and medical history") { vm.navigate(Screen.HEALTH) }
    SectionLabel("Symptom entries")
    if (records.isEmpty()) Panel {
        Text("Start with how you feel", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text("Talk to Advo whenever something is on your mind. Each conversation becomes one symptom entry.", color = Muted)
        Secondary("Make a symptom entry") { vm.newVisit() }
    }
    records.forEach { record ->
        val summary = record.sources.lastOrNull { it.summary.isNotBlank() }?.summary
        EntryRow("paper", record.title, "${displayDate(record.createdAt)}${if (record.demo) " · demo" else ""}\n" +
            (summary?.take(150) ?: "${record.talks.sumOf { it.turns.count { turn -> turn.role == TalkRole.PATIENT } }} notes · original words saved")) { vm.selectVisit(record.id) }
    }
    TextButton(onClick = { vm.newVisit(Screen.SCAN) }) { Text("Add a paper or photo", color = Muted) }
}

@Composable
fun VisitsScreen(vm: WithYouViewModel) {
    Title("Your visits", "Bring the right symptoms. Leave with clear next steps.")
    Primary("Prepare for a visit") { vm.openBriefSelection() }
    Secondary("Record a visit") { vm.newVisit(Screen.CONSENT) }
    val visits = RecordRules.visits(vm.data).sortedByDescending { it.createdAt }
    if (visits.isEmpty()) Panel { Text("No visits yet", fontSize = 21.sp); Text("You can keep recording symptoms in your Library between appointments.", color = Muted) }
    visits.forEach { visit ->
        val remaining = CarePlanRules.actions(visit).count { it.id !in visit.completedActionIds }
        EntryRow(if (visit.isCapturing) "mic" else "paper", visit.title,
            "${displayDate(visit.createdAt)} · " + when {
                visit.isCapturing -> "Recording active"
                visit.stage in setOf(Stage.POST_VISIT, Stage.ARCHIVED) -> if (remaining > 0) "$remaining next steps" else "Care plan & saved visit"
                visit.stage == Stage.WRAP_UP -> "Review with your doctor"
                visit.stage == Stage.IN_VISIT -> "Visit in progress"
                else -> "Appointment brief"
            }) { vm.selectVisit(visit.id) }
    }
}

@Composable
fun BriefSelectionScreen(vm: WithYouViewModel) {
    val records = RecordRules.symptoms(vm.data).sortedByDescending { it.createdAt }
    val target = vm.data.visits.firstOrNull { it.id == vm.briefTargetId }
    val selected = remember(vm.briefTargetId) { mutableStateListOf<String>().apply { addAll(target?.relatedRecordIds.orEmpty()) } }
    Title("What would you\nlike to discuss?", "Choose symptoms from your Library. Advo will make one brief for your doctor.")
    if (records.isEmpty()) {
        Text("Your Library has no symptom entries yet.", color = Muted)
        Primary("Make a symptom entry") { vm.newVisit() }
        return
    }
    Row {
        TextButton(onClick = { selected.clear(); selected.addAll(records.map { it.id }) }) { Text("Select all", color = Muted) }
        TextButton(onClick = { selected.clear() }) { Text("Clear", color = Muted) }
    }
    records.forEach { record -> Panel {
        val preview = record.sources.lastOrNull { it.summary.isNotBlank() }?.summary
            ?: record.sources.firstOrNull { it.parentId == null && it.text.isNotBlank() }?.text.orEmpty()
        CheckRow(record.title, record.id in selected, "${displayDate(record.createdAt)}\n${preview.take(180)}") { include ->
            if (include) { if (record.id !in selected) selected.add(record.id) } else selected.remove(record.id)
        }
    } }
    Primary("Create brief · ${selected.size} selected", enabled = selected.isNotEmpty() && !vm.busy) { vm.prepareFromRecords(selected.toSet()) }
}

@Composable
fun HealthScreen(vm: WithYouViewModel, ui: UiController) {
    var historical by remember { mutableStateOf(false) }
    Title("Health\nessentials", "Current details and their history. Unknown never means none.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!historical, { historical = false }, label = { Text("Current") })
        FilterChip(historical, { historical = true }, label = { Text("History") })
    }
    listOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY).forEach { category ->
        SectionLabel(category.title)
        val entries = vm.data.profile.filter { it.fact.category == category && (if (historical) it.status == ProfileStatus.HISTORICAL else it.status == ProfileStatus.CURRENT) }
        if (entries.isEmpty()) Panel {
            val state = vm.data.settings.categoryStates[category.name] ?: KnowledgeState.NOT_RECORDED
            Text(if (historical) "No historical entries." else when (state) {
                KnowledgeState.NOT_RECORDED -> "Not recorded"
                KnowledgeState.UNKNOWN -> "You marked this as unknown"
                KnowledgeState.NONE_CONFIRMED -> "You reported none · review if anything changes"
                KnowledgeState.RECORDED -> "No current entries"
            }, color = Muted)
            if (!historical) Row {
                TextButton(onClick = { vm.setCategoryState(category, KnowledgeState.UNKNOWN) }) { Text("I don’t know", color = Muted) }
                TextButton(onClick = { ui.confirm("Record that you report none?", "This records your statement for ${category.title.lowercase()}. An empty record alone never means none.", "I report none") {
                    vm.setCategoryState(category, KnowledgeState.NONE_CONFIRMED)
                } }) { Text("I report none", color = Rust) }
            }
        }
        entries.forEach { entry -> Panel {
            Text(entry.fact.quote, fontSize = 18.sp, lineHeight = 26.sp)
            Text("Patient reviewed · ${entry.createdAt.take(10)}${if (entry.previousId != null) " · revision of an earlier record" else ""}", color = Muted, fontSize = 12.sp)
            entry.changes.forEach { Text("${it.at.take(10)} · ${it.action}", color = Muted, fontSize = 12.sp) }
            val origin = vm.data.visits.firstOrNull { v -> v.sources.any { it.id == entry.fact.sourceId } }
            if (origin != null) TextButton(onClick = { vm.selectVisit(origin.id); vm.openSource(entry.fact.sourceId) }) { Text("Source & visit", color = Rust) }
            if (!historical) {
                TextButton(onClick = { ui.confirm("Move to history?", "This records your update and preserves the entry and source. It is not advice to stop any medicine.", "Move to history") { vm.markHistorical(entry.id) } }) { Text("Mark as historical", color = Muted) }
                TextButton(onClick = { vm.newProfileCorrection(entry.id); ui.factCategory = category }) { Text("Add a correction", color = Muted) }
            }
        } }
    }
    Primary("Update by voice") { ui.withMic { vm.newProfileRecord(voice = true) } }
    Secondary("Update by typing") { vm.newProfileRecord() }
    Secondary("Add a photo or paper") { vm.newProfileRecord(Screen.SCAN) }
}

@Composable
fun DetailScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    if (visit.kind == RecordKind.PROFILE) { ProfileUpdateScreen(vm, ui); return }
    if (visit.kind == RecordKind.SYMPTOM) { SymptomDetailScreen(vm, ui); return }
    var tools by remember(visit.id) { mutableStateOf(false) }
    if (visit.demo) Flag("Synthetic demonstration · no real patient")
    Title(visit.title, displayDate(visit.createdAt))
    val afterVisit = visit.stage in setOf(Stage.POST_VISIT, Stage.ARCHIVED)
    if (afterVisit) {
        val remaining = CarePlanRules.actions(visit).count { it.id !in visit.completedActionIds }
        Primary(if (remaining > 0) "Your care plan · $remaining next steps" else "Your care plan") { vm.navigate(Screen.PLAN) }
    } else if (visit.stage == Stage.WRAP_UP) {
        Primary("Confirm with your doctor") { vm.navigate(Screen.WRAP_UP) }
    } else {
        Primary(if (visit.isCapturing) "Return to visit" else if (visit.stage == Stage.IN_VISIT) "Continue visit" else "Start visit") {
            vm.navigate(if (visit.isCapturing || visit.stage == Stage.IN_VISIT) Screen.LIVE else Screen.CONSENT)
        }
    }
    if (visit.reports.isNotEmpty()) EntryRow("paper", "Appointment brief", "${visit.relatedRecordIds.size} selected symptom entries") { vm.navigate(Screen.REPORT) }
    if (visit.stage in setOf(Stage.PREPARING, Stage.READY, Stage.IN_VISIT))
        TextButton(onClick = { vm.openBriefSelection(forCurrentVisit = true) }) { Text(if (visit.relatedRecordIds.isEmpty()) "Choose symptoms to discuss" else "Change selected symptoms", color = Rust) }
    if (visit.recordedMs > 0 || visit.stage == Stage.IN_VISIT) EntryRow("paper", "Ask Advo", "Understand something from this visit") { vm.navigate(Screen.VISIT_CHAT) }
    if (visit.summary.isNotBlank()) Panel { Text("Visit summary", fontWeight = FontWeight.Medium); Text(visit.summary) }
    TextButton(onClick = { tools = !tools }) { Text(if (tools) "Hide visit tools" else "Visit tools & saved sources", color = Muted) }
    if (tools) {
    EntryRow("paper", "Prepare & review", "Symptoms, relevant records and your questions") { vm.navigate(Screen.REVIEW) }
    if (visit.reports.isNotEmpty()) EntryRow("paper", "Visit reports", "${visit.reports.size} immutable versions · English doctor view") { vm.navigate(Screen.REPORT); vm.renderReport() }
    EntryRow("mic", "During your visit", if (visit.recording == Recording.NOT_STARTED) "Start with consent, or use manual notes" else "${visit.recording.name.lowercase()} · ${visit.recordedMs / 1000} seconds saved") {
        vm.navigate(if (visit.stage in setOf(Stage.PREPARING, Stage.READY) && !visit.isCapturing) Screen.CONSENT else Screen.LIVE)
    }
    EntryRow("check", "Before I leave", "Confirm what you understood, one item at a time") { vm.navigate(Screen.WRAP_UP) }
    EntryRow("paper", "Paper & instructions", "Import, crop, compare and review original sources") { vm.navigate(Screen.SCAN) }
    EntryRow("health", "Your plan", if (visit.summary.isBlank()) "Source wording and next steps" else "Plain-language explanation + original wording") { vm.navigate(Screen.PLAN) }
    SectionLabel("All sources")
    visit.sources.forEach { SourceRow(vm, it) }
    if (visit.sources.isEmpty()) Text("No sources saved yet.", color = Muted)
    SectionLabel("Recording consent history")
    if (visit.consents.isEmpty()) Text("No recording consent recorded.", color = Muted, fontSize = 13.sp)
    visit.consents.forEach { consent -> Text("Agreed ${consent.agreedAt}\n${if (consent.withdrawnAt != null) "Withdrawn ${consent.withdrawnAt}" else if (consent.endedAt != null) "Ended ${consent.endedAt}" else "Active for this recording"}", fontSize = 12.sp, color = Muted) }
    if (visit.reminders.isNotEmpty()) {
        SectionLabel("Local reminders")
        visit.reminders.forEach { r -> Panel { Text("${r.at} · ${r.zone}", fontSize = 14.sp)
            TextButton(onClick = { vm.cancelReminder(r.id) }) { Text("Cancel reminder", color = Rust) } } }
    }
    if (visit.generations.isNotEmpty()) {
        SectionLabel("Local processing history")
        visit.generations.takeLast(5).forEach { generation -> Text("${generation.task} · ${generation.model}\n${generation.promptVersion} · %.1f s · ${generation.sourceIds.size} source(s)".format(generation.seconds), fontSize = 12.sp, color = Muted) }
    }
    Secondary("Optional report identity & appointment") {
        ui.fields("Report header", listOf("Patient name (optional)", "Date of birth (optional)", "Appointment (optional)", "Doctor (optional)", "Clinic (optional)"),
            listOf(visit.patientName, visit.dateOfBirth, visit.appointment, visit.doctor, visit.clinic), description = "Only supplied fields appear. Existing report versions do not change.") {
            vm.updateIdentity(it[0], it[1], it[2], it[3], it[4])
        }
    }
    if (visit.summary.isNotBlank()) Secondary("Export summary as local text") { ui.exportSummary() }
    Secondary("Delete recording only", enabled = !visit.isCapturing) { ui.confirm("Permanently delete audio?", "This deletes all saved audio segments for this visit. The transcript, summary, paper sources and reviewed details remain. There is no undo.", "Delete audio") { vm.deleteAudio() } }
    if (visit.summary.isNotBlank()) Secondary("Delete explanation only") { ui.confirm("Delete the explanation?", "Your original sources, extracted details and reports remain.", "Delete explanation") { vm.deleteSummary() } }
    Secondary("Delete visit", enabled = !visit.isCapturing && !vm.busy) { ui.confirm("Permanently delete this visit?", "The visit, reports, notes and attachments will be removed. Sources used by your health profile are retained separately so profile entries keep their provenance. Local exported files and backups are not deleted.", "Delete visit") { vm.deleteVisit() } }
    }
}

@Composable
fun SymptomDetailScreen(vm: WithYouViewModel, ui: UiController) {
    val record = vm.visit ?: return
    var transcript by remember(record.id) { mutableStateOf(false) }
    var more by remember(record.id) { mutableStateOf(false) }
    val dictations = record.sources.filter { it.parentId == null && it.text.isNotBlank() }
    Title(record.title, displayDate(record.createdAt))
    dictations.forEach { source -> Panel {
        if (source.summary.isNotBlank()) {
            Text("Your summary", color = Muted, fontSize = 13.sp)
            Text(source.summary, fontSize = 19.sp, lineHeight = 28.sp)
            Text("Draft · based on your words", color = Muted, fontSize = 12.sp)
        } else if (source.summaryStatus in setOf(Processing.QUEUED, Processing.PROCESSING) && vm.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Rust, trackColor = Peach)
            Text("Your dictation is saved. Advo is summarizing the conversation…", color = Muted)
        } else {
            Text(source.text, fontSize = 18.sp, lineHeight = 26.sp)
            Text(if (source.summaryStatus == Processing.FAILED) "Summary unavailable. Your full dictation is safe." else "Original dictation · saved on this phone", color = Muted, fontSize = 13.sp)
            if (!vm.busy && vm.modelReady) TextButton(onClick = { vm.summarizeSource(source.id) }) { Text("Summarize", color = Rust) }
        }
    } }
    val unfinished = record.talks.lastOrNull()?.finishedAt == null && record.talks.isNotEmpty()
    if (unfinished) Primary("Continue conversation") { vm.selectVisit(record.id, Screen.PREPARE) }
    else Primary("Back to Library") { vm.navigate(Screen.RECORDS) }
    TextButton(onClick = { transcript = !transcript }) { Text(if (transcript) "Hide full dictation" else "Full dictation", color = Muted) }
    if (transcript) dictations.forEach { Panel { Text(it.text, fontSize = 17.sp, lineHeight = 25.sp) } }
    TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "More", color = Muted) }
    if (more) {
        Secondary("Use in an appointment brief", enabled = !vm.busy) { vm.openBriefSelection() }
        Secondary(if (vm.speaking) "Stop reading" else "Read summary aloud") { vm.readAloud(dictations.joinToString("\n") { it.summary.ifBlank { it.text } }) }
        record.sources.forEach { SourceRow(vm, it) }
        Secondary("Review extracted details") { vm.navigate(Screen.EXTRACTION) }
        Secondary("Add a paper or photo") { vm.navigate(Screen.SCAN) }
        Secondary("Delete this symptom entry", enabled = !vm.busy) {
            ui.confirm("Delete this symptom entry?", "This removes this entry and its attachments. Existing appointment report snapshots and health-profile provenance remain.", "Delete entry") { vm.deleteVisit() }
        }
    }
}

@Composable
fun ReportScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    val context = LocalContext.current
    Title("Ready for\nyour visit", "English doctor view · patient draft")
    if (visit.briefStatus == Processing.PROCESSING) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Rust, trackColor = Peach)
        Text(vm.briefProgress.ifBlank { "Preparing your selected history…" }, color = Muted)
    }
    if (visit.briefStatus == Processing.FAILED) Panel {
        Text("AI summary needs another try", fontWeight = FontWeight.Medium)
        Text(visit.briefError ?: "Your originals are preserved.", color = Muted)
        Secondary("Retry AI summary", enabled = !vm.busy && !vm.modelLoading) { vm.retryBrief() }
        if (!vm.modelReady) TextButton(onClick = { vm.navigate(Screen.RESOURCES) }) { Text("Offline resources", color = Rust) }
    }
    if (visit.reports.isEmpty()) {
        Text("Create your report from the current details. You can keep unknowns and unresolved items.", color = Muted)
        if (visit.briefStatus != Processing.PROCESSING) Primary("Create report from original wording", enabled = !vm.busy) { vm.createReport() }
        return
    }
    val report = if (vm.reportIndex < 0) visit.reports.last() else visit.reports[vm.reportIndex.coerceAtMost(visit.reports.lastIndex)]
    if (report.recordCount > 0) Text("Based on ${report.recordCount} selected symptom entries", color = Muted, fontSize = 14.sp)
    if (visit.stage in setOf(Stage.PREPARING, Stage.READY, Stage.IN_VISIT)) TextButton(onClick = { vm.openBriefSelection(forCurrentVisit = true) }) {
        Text("Change selected symptoms", color = Rust)
    }
    LaunchedEffect(report.id) { vm.renderReport(vm.reportIndex) }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        visit.reports.forEachIndexed { index, item -> FilterChip(item.id == report.id, { vm.reportIndex = index }, label = { Text("v${item.version}") }) }
    }
    Flag(report.reviewLabel)
    vm.reportFile?.let { PdfPreview(it) }
    Text("US Letter · selectable text · pinch to zoom. Long reports use clearly marked continuation pages.", color = Muted, fontSize = 12.sp)
    var exporting by remember { mutableStateOf(false) }
    Box {
        Secondary("Export brief", enabled = vm.reportFile != null) { exporting = true }
        DropdownMenu(expanded = exporting, onDismissRequest = { exporting = false }) {
            DropdownMenuItem(text = { Text("Share PDF") }, onClick = { exporting = false; try { ReportExport.share(context, vm.reportFile!!) } catch (_: Exception) { vm.notice = "Could not open sharing. You can save the PDF instead." } })
            DropdownMenuItem(text = { Text("Save on this phone") }, onClick = { exporting = false; ui.savePdf() })
            DropdownMenuItem(text = { Text("Print") }, onClick = { exporting = false; try { ReportExport.print(context, vm.reportFile!!) } catch (_: Exception) { vm.notice = "Printing could not start." } })
            DropdownMenuItem(text = { Text("Update from current records") }, onClick = { exporting = false; vm.createReport() })
        }
    }
    Primary(if (visit.isCapturing || visit.stage == Stage.IN_VISIT) "Return to visit" else if (visit.stage in setOf(Stage.POST_VISIT, Stage.ARCHIVED)) "Your care plan" else "Start visit") {
        vm.navigate(if (visit.isCapturing || visit.stage == Stage.IN_VISIT) Screen.LIVE else if (visit.stage in setOf(Stage.POST_VISIT, Stage.ARCHIVED)) Screen.PLAN else Screen.CONSENT)
    }

}

@Composable
fun PdfPreview(file: File) {
    val context = LocalContext.current
    var page by remember(file) { mutableIntStateOf(0) }
    val rendered by produceState<Pair<android.graphics.Bitmap, Int>?>(null, file, page) {
        value = withContext(Dispatchers.IO) { LocalFiles.withPdf(context, file.readBytes()) { renderer -> LocalFiles.pdfPage(renderer, page) to renderer.pageCount } }
    }
    var scale by remember(file, page) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 4f) }
    rendered?.let { (bitmap, count) ->
        Box(Modifier.fillMaxWidth().aspectRatio(612f / 792f).clipToBounds().transformable(transform)) {
            Image(bitmap.asImageBitmap(), "Visit report page ${page + 1} of $count", modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale))
        }
        if (count > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous page") }
            Text("${page + 1} / $count", color = Muted)
            TextButton(enabled = page < count - 1, onClick = { page++ }) { Text("Next page") }
        }
    } ?: CircularProgressIndicator(color = Rust)
}

@Composable
fun SourceScreen(vm: WithYouViewModel, ui: UiController) {
    val source = vm.visit?.sources?.find { it.id == vm.sourceId } ?: return
    var showOriginal by remember(source.id) { mutableStateOf(false) }
    var crop by remember(source.id) { mutableStateOf(false) }
    var left by remember(source.id) { mutableFloatStateOf(0f) }
    var top by remember(source.id) { mutableFloatStateOf(0f) }
    var right by remember(source.id) { mutableFloatStateOf(1f) }
    var bottom by remember(source.id) { mutableFloatStateOf(1f) }
    var scale by remember(source.id) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 4f) }
    Title(source.label, "${source.createdAt.take(10)} · ${source.status.name.lowercase()}")
    if (source.error != null) Flag(source.error)
    vm.preview?.let { bitmap ->
        Box(Modifier.fillMaxWidth().heightIn(max = 540.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height)
            .clipToBounds().transformable(transform)) {
            Image(bitmap.asImageBitmap(), "Original paper page ${source.page ?: 1}. Pinch to zoom.",
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale))
        }
        Secondary(if (crop) "Cancel crop" else "Crop a copy") { crop = !crop }
        if (crop) Panel {
            Text("Keep the selected edges. A cropped copy is added; the original remains.", color = Muted, fontSize = 13.sp)
            Text("Left edge · ${(left * 100).toInt()}%")
            Slider(left, { left = it }, valueRange = 0f..0.4f)
            Text("Top edge · ${(top * 100).toInt()}%")
            Slider(top, { top = it }, valueRange = 0f..0.4f)
            Text("Right edge · ${(right * 100).toInt()}%")
            Slider(right, { right = it }, valueRange = 0.6f..1f)
            Text("Bottom edge · ${(bottom * 100).toInt()}%")
            Slider(bottom, { bottom = it }, valueRange = 0.6f..1f)
            Primary("Save cropped copy") { vm.cropSource(left, top, right, bottom) }
        }
    }
    if (source.mime == "audio/wav" && source.blob != null) Primary(if (vm.playing) "Stop playback" else "Play this audio segment") { vm.playSource(source.id) }
    if (source.editedAt != null) Row {
        FilterChip(!showOriginal, { showOriginal = false }, label = { Text("Your correction") })
        Spacer(Modifier.width(8.dp))
        FilterChip(showOriginal, { showOriginal = true }, label = { Text("Original transcription") })
    }
    Panel {
        Text(if (source.kind == SourceKind.VISIT_AUDIO) "Transcription · speaker not verified" else "Source wording", fontWeight = FontWeight.Medium)
        Text((if (showOriginal) source.originalText else source.text).ifBlank { "Not yet transcribed. Review the original, type its wording or run local processing." }, fontSize = 17.sp, lineHeight = 25.sp)
        if (source.kind == SourceKind.LEGACY) Text("Full original diary payload: ${source.originalText}", fontSize = 12.sp, color = Muted)
    }
    Secondary("Edit / type transcription") { ui.ask("Review source wording", "Exact wording · keep unclear details marked", source.text,
        description = "The initial transcription remains available. Editing a source flags its existing facts for another review.") { vm.editSource(source.id, it) } }
    Primary("Extract / retry locally", enabled = vm.modelReady && !vm.busy) { vm.retrySource(source.id) }
    if (source.kind == SourceKind.DOCUMENT) Secondary("Retake / add a clearer photo") { ui.capture() }
    Secondary("Export this source to local storage") { ui.confirm("Export this source?", "This creates a readable local copy of this source outside the app's encrypted storage. Choose local device storage.", "Choose local file") { ui.exportSource(source) } }
}
