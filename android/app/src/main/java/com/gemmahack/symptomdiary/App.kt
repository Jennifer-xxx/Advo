package com.gemmahack.symptomdiary

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay

data class FormRequest(val title: String, val labels: List<String>, val initial: List<String>, val button: String,
    val description: String, val password: Boolean, val done: (List<String>) -> Unit)
data class Confirmation(val title: String, val text: String, val button: String, val done: () -> Unit)

class UiController(val vm: WithYouViewModel) {
    var form by mutableStateOf<FormRequest?>(null)
    var confirm by mutableStateOf<Confirmation?>(null)
    var factCategory by mutableStateOf<Category?>(null)
    lateinit var withMic: (() -> Unit) -> Unit
    lateinit var capture: () -> Unit
    lateinit var importDocument: () -> Unit
    lateinit var savePdf: () -> Unit
    lateinit var exportBackup: (String) -> Unit
    lateinit var restoreBackup: (String) -> Unit
    lateinit var importModel: (String, String) -> Unit
    lateinit var exportSource: (Source) -> Unit
    lateinit var exportSummary: () -> Unit
    lateinit var withNotifications: (() -> Unit) -> Unit

    fun ask(title: String, label: String, initial: String = "", button: String = "Save", description: String = "", done: (String) -> Unit) {
        form = FormRequest(title, listOf(label), listOf(initial), button, description, false) { done(it[0]) }
    }
    fun fields(title: String, labels: List<String>, initial: List<String> = labels.map { "" }, button: String = "Save", description: String = "", password: Boolean = false, done: (List<String>) -> Unit) {
        form = FormRequest(title, labels, initial, button, description, password, done)
    }
    fun confirm(title: String, text: String, button: String = "Confirm", done: () -> Unit) { confirm = Confirmation(title, text, button, done) }
}

private class LocalOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
fun App(vm: WithYouViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    val scroll = rememberScrollState()
    var processingSeconds by remember { mutableLongStateOf(0) }
    LaunchedEffect(vm.busy, vm.processingStartedAt) {
        processingSeconds = 0
        while (vm.busy) {
            processingSeconds = ((android.os.SystemClock.elapsedRealtime() - vm.processingStartedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    LaunchedEffect(vm.screen, vm.visitId) { scroll.scrollTo(0) }
    val ui = remember(vm) { UiController(vm) }
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterNotification by remember { mutableStateOf<(() -> Unit)?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var modelVariant by remember { mutableStateOf("E2B") }
    var modelChecksum by remember { mutableStateOf("") }
    var pendingSource by remember { mutableStateOf<Source?>(null) }
    val capturePrefs = remember { context.getSharedPreferences("capture-navigation", android.content.Context.MODE_PRIVATE) }
    var cameraFile by remember { mutableStateOf(capturePrefs.getString("pending", null)?.let(::File)) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) afterMic?.invoke() else vm.notice = "Microphone access is off. You can type, add notes or import a paper."
        afterMic = null
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) afterNotification?.invoke() else vm.notice = "Notifications are off. Your action list is still saved here."
        afterNotification = null
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile
        if (saved && file != null) vm.importCameraFile(file) else file?.delete()
        cameraFile = null
        capturePrefs.edit().remove("pending").apply()
    }
    fun launchCamera() {
        try {
            val file = File(File(context.cacheDir, "capture").apply { mkdirs() }, "${newId()}.jpg")
            cameraFile = file
            capturePrefs.edit().putString("pending", file.path).apply()
            takePhoto.launch(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file))
        } catch (_: Exception) { vm.notice = "A camera app could not open. Import a local file instead." }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) launchCamera() else vm.notice = "Camera access is off. Import a local image or PDF instead."
    }
    val openDocument = rememberLauncherForActivityResult(LocalOpenDocument()) { uri -> if (uri != null) vm.importDocument(uri) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) vm.savePdf(uri) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword; backupPassword = ""
        if (uri != null) vm.backup(uri, password, false)
    }
    val restore = rememberLauncherForActivityResult(LocalOpenDocument()) { uri ->
        val password = backupPassword; backupPassword = ""
        if (uri != null) vm.backup(uri, password, true)
    }
    val model = rememberLauncherForActivityResult(LocalOpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri, modelVariant, modelChecksum)
        modelChecksum = ""
    }
    val sourceExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingSource; pendingSource = null
        if (uri != null && source != null) vm.exportSource(uri, source)
    }
    val summaryExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.exportSummary(uri) }
    ui.withMic = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { afterMic = action; mic.launch(Manifest.permission.RECORD_AUDIO) }
    }
    ui.withNotifications = { action ->
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) action()
        else { afterNotification = action; notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    ui.capture = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else camera.launch(Manifest.permission.CAMERA)
    }
    ui.importDocument = { openDocument.launch(arrayOf("image/*", "application/pdf")) }
    ui.savePdf = { savePdf.launch("Advo-visit-report.pdf") }
    ui.exportBackup = { password -> backupPassword = password; backup.launch("Advo-encrypted-backup.withyou") }
    ui.restoreBackup = { password -> backupPassword = password; restore.launch(arrayOf("application/octet-stream", "application/zip")) }
    ui.importModel = { variant, checksum -> modelVariant = variant; modelChecksum = checksum; model.launch(arrayOf("*/*")) }
    ui.exportSource = { source -> pendingSource = source; sourceExport.launch("Advo-source-${source.id.take(8)}.${if (source.mime == "audio/wav") "wav" else if (source.mime == "application/pdf") "pdf" else if (source.mime?.startsWith("image/") == true) "png" else "txt"}") }
    ui.exportSummary = { summaryExport.launch("Advo-visit-summary.txt") }

    DisposableEffect(vm.screen, vm.visit?.recording, vm.data.settings.keepScreenAwake) {
        if (vm.screen == Screen.DOCTOR || (vm.screen == Screen.LIVE && vm.data.settings.keepScreenAwake))
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Rust, background = Cream,
        surface = Cream, onSurface = Ink, onBackground = Ink, outline = LineColor)) {
        Scaffold(containerColor = Cream, topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (vm.screen != Screen.HOME) IconButton(onClick = { vm.back() }, modifier = Modifier.semantics { contentDescription = "Back" }) { Glyph("back") }
                    else Box(Modifier.padding(9.dp)) { androidx.compose.foundation.Canvas(Modifier.size(15.dp)) { drawCircle(Color(0xffffb78e)) } }
                    Text(if (vm.screen == Screen.HOME) "Advo" else if (vm.screen == Screen.DETAIL && vm.visit?.kind == RecordKind.SYMPTOM) "Symptom entry" else vm.screen.label,
                        modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (vm.screen in setOf(Screen.HOME, Screen.RECORDS, Screen.HEALTH, Screen.VISITS)) IconButton(onClick = { vm.navigate(Screen.SETTINGS) }, modifier = Modifier.semantics { contentDescription = "Settings" }) { Glyph("settings") }
                }
                if (vm.busy && vm.screen != Screen.PREPARE) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Rust, trackColor = Peach)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${vm.processingLabel.ifBlank { "Processing locally" }} · ${processingSeconds}s", fontSize = 12.sp, color = Muted,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                        if (vm.canStopProcessing) TextButton(onClick = { vm.stopProcessing() }) { Text("Stop", color = Rust) }
                    }
                    if (processingSeconds >= 30) Text("Your originals are saved. You can stop and retry later.", fontSize = 12.sp,
                        color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
        }, bottomBar = {
            if (vm.screen in setOf(Screen.HOME, Screen.RECORDS, Screen.HEALTH, Screen.SETTINGS, Screen.VISITS)) {
                NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                    listOf(Triple("Talk", "mic", Screen.HOME), Triple("Library", "folder", Screen.RECORDS), Triple("Visits", "paper", Screen.VISITS)).forEach { (label, icon, target) ->
                        NavigationBarItem(selected = vm.screen == target || target == Screen.RECORDS && vm.screen == Screen.HEALTH,
                            onClick = { vm.navigate(target) }, icon = { Glyph(icon) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Peach))
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scroll)
                .padding(horizontal = 23.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                if (vm.repository.openError != null) Flag(vm.repository.openError!!)
                when (vm.screen) {
                    Screen.HOME -> HomeScreen(vm, ui)
                    Screen.PREPARE -> PrepareScreen(vm, ui)
                    Screen.REVIEW -> ReviewScreen(vm, ui)
                    Screen.REPORT -> ReportScreen(vm, ui)
                    Screen.CONSENT -> ConsentScreen(vm, ui)
                    Screen.LIVE -> LiveScreen(vm, ui)
                    Screen.WRAP_UP -> WrapUpScreen(vm, ui)
                    Screen.SCAN -> ScanScreen(vm, ui)
                    Screen.EXTRACTION -> ExtractionScreen(vm, ui)
                    Screen.PLAN -> PlanScreen(vm, ui)
                    Screen.SAVE -> SaveScreen(vm, ui)
                    Screen.DOCTOR -> DoctorScreen(vm)
                    Screen.SOURCE -> SourceScreen(vm, ui)
                    Screen.RECORDS -> RecordsScreen(vm)
                    Screen.VISITS -> VisitsScreen(vm)
                    Screen.BRIEF -> BriefSelectionScreen(vm)
                    Screen.HEALTH -> HealthScreen(vm, ui)
                    Screen.DETAIL -> DetailScreen(vm, ui)
                    Screen.SETTINGS -> SettingsScreen(vm, ui)
                    Screen.RESOURCES -> ResourcesScreen(vm, ui)
                    Screen.LANGUAGE -> LanguageScreen(vm)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        vm.notice?.let { notice -> AlertDialog(onDismissRequest = { vm.notice = null }, title = { Text("Advo") },
            text = { Text(notice) }, confirmButton = { TextButton(onClick = { vm.notice = null }) { Text("Got it") } }) }
        ui.form?.let { request -> FormDialog(request, { ui.form = null }) }
        ui.confirm?.let { confirmation -> AlertDialog(onDismissRequest = { ui.confirm = null },
            title = { Text(confirmation.title) }, text = { Text(confirmation.text) },
            confirmButton = { TextButton(onClick = { ui.confirm = null; confirmation.done() }) { Text(confirmation.button) } },
            dismissButton = { TextButton(onClick = { ui.confirm = null }) { Text("Cancel") } }) }
        ui.factCategory?.let { category -> ManualFactDialog(vm, category) { ui.factCategory = null } }
    }
}

@Composable
private fun FormDialog(request: FormRequest, dismiss: () -> Unit) {
    val values = remember(request) { mutableStateListOf(*request.initial.toTypedArray()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(request.title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (request.description.isNotBlank()) Text(request.description, color = Muted)
            request.labels.forEachIndexed { index, label ->
                OutlinedTextField(values[index], { values[index] = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                    minLines = if (request.labels.size == 1 && !request.password) 3 else 1,
                    visualTransformation = if (request.password) PasswordVisualTransformation() else VisualTransformation.None)
            }
        }
    }, confirmButton = { TextButton(onClick = { dismiss(); request.done(values.toList()) }) { Text(request.button) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualFactDialog(vm: WithYouViewModel, initial: Category, dismiss: () -> Unit) {
    var category by remember { mutableStateOf(initial) }
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("") }
    var reaction by remember { mutableStateOf("") }
    var timing by remember { mutableStateOf("") }
    var uncertain by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add a detail") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Category.entries.forEach { item ->
                FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item.title) })
            } }
            OutlinedTextField(text, { text = it }, label = { Text("Exact wording · keep unknowns") }, minLines = 3)
            if (category in setOf(Category.MEDICINE, Category.PAST_MEDICINE, Category.ALLERGY, Category.MEDICINE_CHANGE)) {
                Text("Optional fields must also appear in the wording above. Leave unknown details blank.", fontSize = 13.sp, color = Muted)
                OutlinedTextField(name, { name = it }, label = { Text("Medicine / substance name") })
                if (category == Category.ALLERGY) OutlinedTextField(reaction, { reaction = it }, label = { Text("Reaction") })
                else {
                    OutlinedTextField(dose, { dose = it }, label = { Text("Dose") })
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit") })
                    OutlinedTextField(frequency, { frequency = it }, label = { Text("Frequency") })
                }
            }
            if (category == Category.ACTION) OutlinedTextField(timing, { timing = it }, label = { Text("Timing · exact wording, if known") })
            CheckRow("Some of this is unclear", uncertain, "Unclear details remain flagged after reviewing the wording") { uncertain = it }
            Text("This creates a draft for you to review. It does not change your current health profile.", color = Muted, fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.addManualFact(category, text, name, dose, unit, frequency, reaction, timing, uncertain); dismiss() }) { Text("Add draft") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
