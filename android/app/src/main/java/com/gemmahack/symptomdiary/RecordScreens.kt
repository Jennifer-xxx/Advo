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
    Title("Your health\nstory", "Your health, at a glance.")
    EntryRow("health", "Medicines", "${vm.data.profile.count { it.status == ProfileStatus.CURRENT && it.fact.category == Category.MEDICINE }} current entries · review your list") { vm.navigate(Screen.HEALTH) }
    val allergyCount = vm.data.profile.count { it.status == ProfileStatus.CURRENT && it.fact.category == Category.ALLERGY }
    EntryRow("health", "Allergies", if (allergyCount == 0) "Not recorded / unknown" else "$allergyCount recorded · check reactions") { vm.navigate(Screen.HEALTH) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(true, {}, label = { Text("Visits") })
        FilterChip(false, { vm.navigate(Screen.HEALTH) }, label = { Text("Health") })
    }
    if (vm.data.visits.isEmpty()) Panel {
        Text("Your story starts here", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text("Prepare a visit, add a paper or update what you know. No complete health form is needed.", color = Muted)
    }
    vm.data.visits.sortedByDescending { it.createdAt }.forEach { visit ->
        EntryRow("paper", visit.title, "${visit.createdAt.take(10)} · ${visit.stage.name.lowercase().replace('_', ' ')}${if (visit.demo) " · SYNTHETIC DEMO" else ""}\n" +
            "${visit.sources.size} sources · ${visit.unresolved.size} details need review") { vm.selectVisit(visit.id) }
    }
    Primary("Add a record") { vm.newVisit(Screen.SCAN) }
    Secondary("Update by voice or text") { vm.newVisit() }
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
    Primary("Update by voice") { ui.withMic { vm.newVisit(thenListen = true) } }
    Secondary("Update by typing") { vm.newVisit() }
    Secondary("Add a photo or paper") { vm.newVisit(Screen.SCAN) }
}

@Composable
fun DetailScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    if (visit.demo) Flag("Synthetic demonstration · no real patient")
    Title(visit.title, "${visit.createdAt.take(10)} · ${visit.stage.name.lowercase().replace('_', ' ')}")
    if (visit.unresolved.isNotEmpty()) Flag("${visit.unresolved.size} details remain unresolved or unreviewed")
    if (visit.isCapturing) EntryRow("mic", "Recording is active", "${visit.recordedMs / 1000} seconds saved. Return to pause or stop.") { vm.navigate(Screen.LIVE) }
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

@Composable
fun ReportScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    val context = LocalContext.current
    Title("Ready for\nyour visit", "English doctor view · patient draft")
    if (visit.reports.isEmpty()) {
        Text("Create your report from the current details. You can keep unknowns and unresolved items.", color = Muted)
        Primary("Create report") { vm.createReport() }
        return
    }
    val report = if (vm.reportIndex < 0) visit.reports.last() else visit.reports[vm.reportIndex.coerceAtMost(visit.reports.lastIndex)]
    LaunchedEffect(report.id) { vm.renderReport(vm.reportIndex) }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        visit.reports.forEachIndexed { index, item -> FilterChip(item.id == report.id, { vm.reportIndex = index }, label = { Text("v${item.version}") }) }
    }
    Flag(report.reviewLabel)
    vm.reportFile?.let { PdfPreview(it) }
    Text("US Letter · selectable text · pinch to zoom. Long reports use clearly marked continuation pages.", color = Muted, fontSize = 12.sp)
    Primary("Share PDF", enabled = vm.reportFile != null) {
        ui.confirm("Share this report?", "Only report v${report.version} will be handed to the app you choose. Sharing destinations may use their own network access. The local report remains if you cancel.", "Open share sheet") {
            try { ReportExport.share(context, vm.reportFile!!) } catch (_: Exception) { vm.notice = "No sharing app is available. You can save the PDF locally." }
        }
    }
    Secondary("Save PDF on this device", enabled = vm.reportFile != null) { ui.savePdf() }
    Secondary("Print", enabled = vm.reportFile != null) { try { ReportExport.print(context, vm.reportFile!!) } catch (_: Exception) { vm.notice = "Printing could not start. Save the PDF and print later." } }
    Secondary("Create a new version from current details") { vm.createReport() }
    Primary("Start visit") { vm.navigate(Screen.CONSENT) }
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
