package com.gemmahack.symptomdiary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: WithYouViewModel, ui: UiController) {
    Spacer(Modifier.height(20.dp))
    Title("What’s on\nyour mind?", centered = true)
    Text("Your personal health advocate, in your pocket.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    VoiceOrb(false, "Tap to talk") { ui.withMic { vm.newVisit(thenListen = true) } }
    Text("Private · Offline · On this phone", color = Muted, fontSize = 13.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp))
    Secondary("Type instead") { vm.newVisit() }
    val ongoing = vm.data.visits.lastOrNull { it.stage != Stage.ARCHIVED }
    if (ongoing != null) EntryRow("paper", "Continue your visit", ongoing.title) { vm.selectVisit(ongoing.id) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { vm.newVisit(Screen.CONSENT) }) { Text("Start a visit now", color = Ink) }
        TextButton(onClick = { vm.navigate(Screen.RECORDS) }) { Text("My records", color = Ink) }
    }
    if (!vm.modelReady) Panel {
        Text(if (vm.modelLoading) "Getting Gemma ready" else "Offline setup", fontWeight = FontWeight.Medium)
        Text(if (vm.modelLoading) "Your local model is loading. You can type and view records while you wait."
            else "Type and keep records now. Set up the local model for voice notes, paper reading and help organizing your visit.", color = Muted, fontSize = 14.sp)
        TextButton(onClick = { vm.navigate(Screen.RESOURCES) }) { Text("Offline resources", color = Rust) }
    }
}

@Composable
fun PrepareScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    Title(visit.nextQuestion, "One question at a time. Skip anything you don’t know.")
    VoiceOrb(vm.listening, if (vm.listening) "Listening · tap to pause" else "Tap to talk", large = false) {
        if (vm.listening) vm.stopDictation() else ui.withMic { vm.startDictation() }
    }
    Text(vm.voiceStatus, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(vm.input, vm::editInput, label = { Text("Your words") }, placeholder = { Text("Type instead, or edit what you said…") },
        minLines = 3, modifier = Modifier.fillMaxWidth())
    Primary("Save answer", enabled = vm.input.isNotBlank() && !vm.listening) { vm.submit() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { vm.skipQuestion() }) { Text("Skip this question", color = Muted) }
        TextButton(onClick = { vm.navigate(Screen.REVIEW) }) { Text("I’m ready to review", color = Rust) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !vm.useGemmaVoice, onClick = { vm.useGemmaVoice = false }, label = { Text("Pixel dictation") }, enabled = !vm.listening)
        FilterChip(selected = vm.useGemmaVoice, onClick = { vm.useGemmaVoice = true }, label = { Text("Gemma audio") }, enabled = !vm.listening)
    }
    if (visit.sources.isNotEmpty()) {
        SectionLabel("Saved so far")
        visit.sources.takeLast(3).forEach { source -> SourceRow(vm, source) }
    }
    Secondary("Hear the question") { vm.readAloud(visit.nextQuestion) }
}

@Composable
fun ReviewScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    var expanded by remember(visit.id) { mutableStateOf<String?>(null) }
    Title("Did I get\nthat right?", "Review what you know. Unknown details can stay unknown.")
    val symptoms = visit.currentFacts.filter { it.category in setOf(Category.SYMPTOM, Category.GOAL) }
    val recordCategories = setOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY)
    val records = visit.currentFacts.filter { it.category in recordCategories }
    val current = vm.data.profile.filter { it.status == ProfileStatus.CURRENT }
    EntryRow("health", "Your symptoms", symptoms.firstOrNull()?.quote ?: "Not organized yet · your original is saved") {
        expanded = if (expanded == "symptoms") null else "symptoms"
    }
    if (expanded == "symptoms") symptoms.forEach { FactCard(vm, ui, it) }
    EntryRow("health", "Relevant records", "Medicines, allergies & history · ${records.size + current.size} details") {
        expanded = if (expanded == "records") null else "records"
    }
    if (expanded == "records") {
        records.forEach { SectionLabel(it.category.title); FactCard(vm, ui, it, profile = true) }
        current.forEach { entry -> Panel {
            Text(entry.fact.category.title, color = Muted, fontSize = 13.sp)
            Text(entry.fact.quote)
            Text("From your profile · ${displayDate(entry.createdAt)}. Check if it is still current.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { vm.navigate(Screen.HEALTH) }) { Text("Review health profile", color = Rust) }
        } }
        if (records.isEmpty() && current.isEmpty()) Text("Not recorded. Add only what you know; missing details stay unknown.", color = Muted)
    }
    EntryRow("paper", "Your questions", "${visit.questions.count { it.selected }} selected · suggestions are optional", Lavender) {
        expanded = if (expanded == "questions") null else "questions"
    }
    if (expanded == "questions") QuestionList(vm, ui)
    if (visit.currentFacts.isEmpty()) Panel {
        Text("Your original words are saved", fontWeight = FontWeight.Medium)
        Text("Organize them with Gemma, or add a detail yourself. A draft report can still include your words.", color = Muted)
        visit.sources.forEach { source -> Text(source.text.ifBlank { source.label }); TextButton(onClick = { vm.openSource(source.id) }) { Text("Review source", color = Rust) } }
    }
    Secondary("Add a detail / correct by typing") { ui.factCategory = Category.SYMPTOM }
    Secondary("Tell me what to change") { vm.navigate(Screen.PREPARE) }
    Secondary(if (vm.speaking) "Pause read-aloud" else "Hear summary") {
        vm.readAloud((visit.currentFacts.map { it.quote + if (it.needsReview) ". Needs confirmation." else "" } +
            visit.questions.filter { it.selected }.map { it.text }).joinToString("\n").ifBlank { visit.sources.joinToString("\n") { it.text } })
    }
    Primary("Create visit report") { vm.createReport() }
    Text("Report stays a patient draft. Unreviewed details remain marked.", color = Muted, fontSize = 13.sp)
}

@Composable
fun QuestionList(vm: WithYouViewModel, ui: UiController, live: Boolean = false) {
    val questions = vm.visit?.questions.orEmpty()
    if (questions.isEmpty()) Text("What would you like to ask? Add your own question below.", color = Muted)
    questions.filter { !live || it.state != QuestionState.DISMISSED }.forEach { q -> Panel {
        CheckRow(q.text, q.selected, if (q.origin == QuestionOrigin.PATIENT) "Your question" else "Suggested · include only if you choose") {
            vm.changeQuestion(q.id, selected = it)
        }
        if (live) {
            Text("Status: ${q.state.name.lowercase().replace('_', ' ')}", color = Muted, fontSize = 13.sp)
            Secondary("Show doctor") { vm.showDoctor(q.text, q.reason) }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                TextButton(onClick = { vm.changeQuestion(q.id, selected = true, state = QuestionState.ASKED) }) { Text("Asked", color = Rust) }
                TextButton(onClick = { vm.changeQuestion(q.id, state = QuestionState.LATER) }) { Text("Later", color = Muted) }
                TextButton(onClick = { vm.changeQuestion(q.id, state = QuestionState.DISMISSED) }) { Text("Dismiss", color = Muted) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = { vm.changeQuestion(q.id, move = -1) }) { Text("Move up", color = Muted) }
                TextButton(onClick = { ui.ask("Edit question", "Question", q.text) { vm.changeQuestion(q.id, text = it) } }) { Text("Edit", color = Muted) }
                TextButton(onClick = { vm.changeQuestion(q.id, delete = true) }) { Text("Remove", color = Muted) }
            }
        }
    } }
    Secondary("Add your question") { ui.ask("Your question", "What do you want to ask?") { vm.addQuestion(it) } }
}

@Composable
fun FactCard(vm: WithYouViewModel, ui: UiController, fact: Fact, doctorCheck: Boolean = false, profile: Boolean = false) {
    Panel {
        if (fact.english != null) {
            Text(fact.english, fontSize = 18.sp, lineHeight = 26.sp)
            if (fact.translationReview != Review.PATIENT_REVIEWED) {
                Flag("English translation · needs review")
                Secondary("I reviewed the English wording") { vm.reviewTranslation(fact.id) }
            }
            SectionLabel("Original wording")
        }
        Text(fact.quote, fontSize = 18.sp, lineHeight = 26.sp)
        if (fact.needsReview) Flag(if (fact.status == Review.UNRESOLVED || fact.uncertain) "Unresolved · needs confirmation" else "Draft · needs review")
        else Text("Patient reviewed${if (fact.doctorCheckedAt != null) " · you said you checked with the doctor" else ""}", fontSize = 13.sp, color = Muted)
        if (fact.reviewNote.isNotBlank()) Text(fact.reviewNote, color = Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { vm.openSource(fact.sourceId) }) { Text("View source", color = Rust) }
            TextButton(onClick = { ui.ask("Correct this detail", "Your wording", fact.quote, description = "A correction gets its own source. Earlier wording stays available.") { vm.correctFact(fact.id, it) } }) { Text("Correct", color = Muted) }
        }
        if (fact.english == null) TextButton(enabled = vm.modelReady && !vm.busy, onClick = { vm.translateFact(fact.id) }) { Text("Translate to English", color = Muted) }
        if (fact.status != Review.PATIENT_REVIEWED) Secondary("I reviewed this wording") { vm.reviewFact(fact.id, Review.PATIENT_REVIEWED) }
        TextButton(onClick = { vm.reviewFact(fact.id, Review.UNRESOLVED) }) { Text("Leave unresolved", color = Muted) }
        if (doctorCheck) Secondary("I checked this with the doctor") {
            ui.confirm("Record your confirmation?", "This records that you say you checked this wording with the doctor. It is not a clinician signature or certification.", "Record confirmation") {
                vm.reviewFact(fact.id, Review.PATIENT_REVIEWED, doctorChecked = true)
            }
        }
        if (profile && !fact.needsReview) TextButton(onClick = {
            ui.confirm("Add to your health profile?", "Save this reviewed detail as current information. Existing entries and their history are retained. Review your current list for duplicates or older information.", "Add to profile") { vm.promoteFact(fact.id) }
        }) { Text("Add to health profile", color = Rust) }
    }
}

@Composable
fun SourceRow(vm: WithYouViewModel, source: Source) {
    EntryRow(if (source.mime == "audio/wav") "play" else "paper", source.label,
        when (source.status) {
            Processing.IDLE -> "Original saved · not processed"
            Processing.QUEUED -> "Saved · waiting for local processing"
            Processing.PROCESSING -> "Processing on this phone"
            Processing.READY -> "Extracted · patient review still needed"
            Processing.FAILED -> "Original saved · retry or edit manually"
        }, if (source.mime == "audio/wav") Lavender else Peach) { vm.openSource(source.id) }
}

@Composable
fun ConsentScreen(vm: WithYouViewModel, ui: UiController) {
    var agreed by remember(vm.visitId) { mutableStateOf(false) }
    Title("May I record?", "Ask everyone in the room first.", centered = true)
    Spacer(Modifier.height(10.dp))
    VoiceOrb(false, "Nothing is recording", large = false) { }
    Text("Audio stays on this phone. You can pause, stop, or withdraw consent at any time.", color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
    Panel { CheckRow("Everyone agreed", agreed, "For this recording only") { agreed = it } }
    Primary("Start recording", enabled = agreed && vm.visit?.isCapturing != true) {
        ui.withMic { vm.startVisitRecording(agreed) }
    }
    Secondary("Continue without recording") { vm.manualVisit() }
    Text("You can still use your questions, take notes, review your understanding and import papers.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable
fun LiveScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    val active = visit.recording == Recording.RECORDING
    var tick by remember(visit.recordedMs, visit.recording) { mutableLongStateOf(0) }
    LaunchedEffect(visit.recordedMs, visit.recording) { while (active) { delay(1000); tick++ } }
    val seconds = visit.recordedMs / 1000 + tick
    Title(when (visit.recording) {
        Recording.RECORDING -> "I’m listening"
        Recording.PAUSED -> "Recording paused"
        Recording.INTERRUPTED -> "Recording interrupted"
        else -> "Here with you"
    }, if (active) "Quiet support, only when there’s something to ask." else "Your questions and notes are here.", centered = true)
    if (visit.recording in setOf(Recording.RECORDING, Recording.PAUSED)) {
        VoiceOrb(active, "%02d:%02d · %s".format(seconds / 60, seconds % 60, if (active) "Recording" else "Paused"), large = false) { vm.pauseVisitRecording() }
        Text("Audio saved in 12-second segments", color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Secondary(if (active) "Pause recording" else "Resume recording") { vm.pauseVisitRecording() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.stopVisitRecording() }) { Text("Stop recording", color = Ink) }
            TextButton(onClick = { vm.stopVisitRecording(true) }) { Text("Withdraw consent", color = Rust) }
        }
    } else {
        if (visit.recording == Recording.INTERRUPTED) Flag("Saved segments are kept. Start a new consented recording when ready.")
        Secondary("Start a recording") { vm.navigate(Screen.CONSENT) }
    }
    val pending = visit.sources.count { it.status in setOf(Processing.QUEUED, Processing.PROCESSING, Processing.FAILED) }
    if (pending > 0) Text("$pending source segments waiting / processing. You can keep taking notes.", color = Muted, fontSize = 13.sp)
    if (vm.thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE) Flag("Phone is warm. Live processing is paused; audio keeps saving.")
    val allergies = vm.data.profile.filter { it.status == ProfileStatus.CURRENT && it.fact.category == Category.ALLERGY && !it.fact.needsReview && it.fact.name != null && it.fact.reaction != null }
    val medicine = visit.currentFacts.filter { it.category in setOf(Category.MEDICINE, Category.MEDICINE_CHANGE) && !it.needsReview && it.name != null }
    val matches = allergies.flatMap { allergy -> medicine.filter { it.name.equals(allergy.fact.name, true) }.map { allergy to it } }
    matches.firstOrNull()?.let { (allergy, candidate) -> Panel {
        Flag("One thing to check")
        Text("Does this medicine fit my allergy history?", fontSize = 23.sp, lineHeight = 29.sp)
        Text("The same exact reviewed name appears in your saved allergy and this visit. This only checks literal names.", color = Muted, fontSize = 14.sp)
        Text("Allergy: ${allergy.fact.quote}\nVisit: ${candidate.quote}", fontSize = 15.sp)
        Secondary("Why this came up / show doctor") { vm.showDoctor("Does this medicine fit my allergy history?", "Saved allergy: ${allergy.fact.quote}\nVisit wording: ${candidate.quote}\nRule: literal-name-v1. No drug-class or interaction database is installed.") }
    } }
    val next = visit.questions.firstOrNull { it.selected && it.state in setOf(QuestionState.TO_ASK, QuestionState.LATER) }
        ?: visit.questions.firstOrNull { it.state == QuestionState.TO_ASK }
    if (next != null) Panel {
        Text(if (next.origin == QuestionOrigin.SUGGESTED) "Suggested question" else "Your next question", color = Muted, fontSize = 13.sp)
        Text(next.text, fontSize = 21.sp, lineHeight = 28.sp)
        Secondary("Show doctor") { vm.showDoctor(next.text, next.reason) }
        Row {
            TextButton(onClick = { vm.changeQuestion(next.id, selected = true, state = QuestionState.ASKED) }) { Text("Asked", color = Rust) }
            TextButton(onClick = { vm.changeQuestion(next.id, state = QuestionState.LATER) }) { Text("Later", color = Muted) }
            TextButton(onClick = { vm.changeQuestion(next.id, state = QuestionState.DISMISSED) }) { Text("Dismiss", color = Muted) }
        }
    }
    Primary("Before I leave") { vm.setStage(Stage.WRAP_UP, Screen.WRAP_UP) }
    Secondary("Add a note") { ui.ask("Visit note", "What do you want to remember?", description = "Saved as patient recollection, not as a doctor's recorded words.") { text -> vm.editInput(text); vm.submit(SourceKind.PATIENT_RECOLLECTION) } }
    Secondary("All questions") { vm.navigate(Screen.REVIEW) }
    Text("Medicine interactions cannot be checked: no validated local drug reference is installed. An absent prompt does not establish safety.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
fun WrapUpScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    Title("Did I understand?", "Draft from this visit. Check each detail you want to confirm.")
    listOf(Category.DISCUSSION, Category.MEDICINE_CHANGE, Category.ACTION).forEach { category ->
        SectionLabel(category.title)
        val items = visit.currentFacts.filter { it.category == category }
        if (items.isEmpty()) EntryRow("paper", "${category.title} — please confirm", "Not recorded yet. Add what you understood.") { ui.factCategory = category }
        items.forEach { FactCard(vm, ui, it, doctorCheck = true) }
    }
    val unanswered = visit.questions.filter { it.selected && it.state != QuestionState.ASKED && it.state != QuestionState.DISMISSED }
    if (unanswered.isNotEmpty()) Panel {
        SectionLabel("Questions still to ask")
        unanswered.forEach { q -> Text(q.text); TextButton(onClick = { vm.showDoctor(q.text) }) { Text("Show doctor", color = Rust) } }
    }
    Secondary(if (vm.speaking) "Pause read-aloud" else "Read aloud") {
        vm.readAloud(visit.facts.filter { it.category in setOf(Category.DISCUSSION, Category.MEDICINE_CHANGE, Category.ACTION) }
            .joinToString("\n") { it.quote + if (it.needsReview) ". Please confirm." else "" })
    }
    Secondary("Add something I understood") { ui.factCategory = Category.ACTION }
    Primary("Continue to papers & plan") { vm.setStage(Stage.POST_VISIT, Screen.SCAN) }
    TextButton(onClick = { vm.setStage(Stage.POST_VISIT, Screen.SCAN) }) { Text("Finish without confirming", color = Muted) }
    if (visit.unresolved.isNotEmpty()) Flag("${visit.unresolved.size} details still need review. They will stay flagged.")
}

@Composable
fun ScanScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    Title("Save your\ninstructions", "Add every page of your visit summary, medicine list or follow-up instructions.")
    Panel {
        Glyph("paper", Rust, 52)
        Text("Keep the original", fontSize = 23.sp, fontWeight = FontWeight.Medium)
        Text("Take a clear photo in good light. You can retake it or crop a copy before extracting the wording.", color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
    }
    Primary("Take photo / add page") { ui.capture() }
    Secondary("Import local images or PDF") { ui.importDocument() }
    Text("Choose Downloads or internal storage. Cloud document providers are excluded.", color = Muted, fontSize = 12.sp)
    visit.sources.filter { it.kind == SourceKind.DOCUMENT }.forEach { SourceRow(vm, it) }
    Secondary("Add a memory by voice") { vm.navigate(Screen.PREPARE); ui.withMic { vm.startDictation() } }
    Secondary("Add a written memory") { ui.ask("Patient recollection", "What do you remember?") { vm.editInput(it); vm.submit(SourceKind.PATIENT_RECOLLECTION) } }
    Primary("Review saved details") { vm.navigate(Screen.EXTRACTION) }
    TextButton(onClick = { vm.navigate(Screen.PLAN) }) { Text("Continue without a paper", color = Muted) }
}

@Composable
fun ExtractionScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    var index by remember(visit.id) { mutableIntStateOf(0) }
    val facts = visit.currentFacts
    Title("Check one detail", "Compare the wording with its original. You can leave a detail unresolved.")
    val pending = visit.sources.filter { it.status != Processing.READY }
    if (pending.isNotEmpty()) {
        Text("${pending.size} sources still need processing or manual review.", color = Muted)
        Primary("Organize saved sources", enabled = vm.modelReady && !vm.busy) { vm.organizePending() }
        pending.forEach { SourceRow(vm, it) }
    }
    if (facts.isNotEmpty()) {
        index = index.coerceAtMost(facts.lastIndex)
        val fact = facts[index]
        SectionLabel("${index + 1} of ${facts.size} · ${fact.category.title}")
        FactCard(vm, ui, fact)
        val related = facts.filter { it.id != fact.id && it.category == fact.category && it.sourceId != fact.sourceId &&
            (it.name ?: it.label).equals(fact.name ?: fact.label, true) }
        if (related.isNotEmpty()) Panel {
            SectionLabel("Other source wording · compare")
            related.forEach { other ->
                Text(other.quote, fontSize = 17.sp)
                TextButton(onClick = { vm.openSource(other.sourceId) }) { Text("View original", color = Rust) }
            }
            Text("Choosing a source records your preference; it does not establish medical correctness.", color = Muted, fontSize = 12.sp)
        }
        Secondary("Keep this wording") { vm.reviewFact(fact.id, Review.PATIENT_REVIEWED); if (index < facts.lastIndex) index++ }
        Secondary("Leave unresolved") { vm.reviewFact(fact.id, Review.UNRESOLVED); if (index < facts.lastIndex) index++ }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = index > 0, onClick = { index-- }) { Text("Previous") }
            TextButton(enabled = index < facts.lastIndex, onClick = { index++ }) { Text("Next detail") }
        }
    } else Panel { Text("No extracted details yet."); Text("Your originals remain available. Extract them locally or add what you understood.", color = Muted) }
    Secondary("Add a detail manually") { ui.factCategory = Category.ACTION }
    Primary("See my plan") { vm.navigate(Screen.PLAN) }
}

@Composable
fun PlanScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    var plain by remember { mutableStateOf(true) }
    Title("Here’s your plan", "Based on saved sources. Confirm instructions with your clinician.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(plain, { plain = true }, label = { Text("Plain language") })
        FilterChip(!plain, { plain = false }, label = { Text("Original wording") })
    }
    if (plain && visit.summary.isNotBlank()) Panel {
        Flag(if (visit.summaryReview == Review.PATIENT_REVIEWED) "Patient-reviewed explanation" else "Draft explanation · please review")
        Text(visit.summary, fontSize = 18.sp, lineHeight = 27.sp)
        if (visit.summaryReview != Review.PATIENT_REVIEWED) Secondary("I reviewed this explanation") { vm.reviewSummary() }
    } else {
        listOf(Category.DISCUSSION, Category.MEDICINE_CHANGE, Category.ACTION).forEach { category ->
            SectionLabel(category.title)
            val facts = visit.currentFacts.filter { it.category == category }
            if (facts.isEmpty()) Panel { Text("Not recorded · needs confirmation", color = Muted) }
            facts.forEach { fact ->
                Panel {
                    Text(fact.quote, fontSize = 18.sp, lineHeight = 25.sp)
                    if (fact.needsReview) Flag("Needs confirmation")
                    if (category == Category.ACTION && fact.timing == null) Text("Timing needs confirmation.", color = Muted, fontSize = 13.sp)
                    TextButton(onClick = { vm.openSource(fact.sourceId) }) { Text("View source", color = Rust) }
                    if (category == Category.ACTION && !fact.needsReview) Secondary("Set a local reminder") {
                        ui.ask("Choose your reminder", "Local date and time · YYYY-MM-DDTHH:MM", description = "Choose a time yourself. The app does not infer a date from this instruction. Time zone: ${java.time.ZoneId.systemDefault()}. Delivery is approximate.") { time ->
                            ui.withNotifications { vm.scheduleReminder(fact.id, time) }
                        }
                    }
                }
            }
        }
    }
    Secondary(if (visit.summary.isBlank()) "Explain in plain English" else "Regenerate explanation", enabled = !vm.busy && vm.modelReady) { vm.explainPlan() }
    if (visit.unresolved.isNotEmpty()) Flag("${visit.unresolved.size} details still need confirmation")
    SectionLabel("Sources kept in this visit")
    visit.sources.forEach { SourceRow(vm, it) }
    Secondary(if (vm.speaking) "Pause read-aloud" else "Read aloud") {
        vm.readAloud(if (plain && visit.summary.isNotBlank()) visit.summary else visit.facts.joinToString("\n") { it.quote + if (it.needsReview) ". Needs confirmation." else "" })
    }
    Primary("Review & save") { vm.navigate(Screen.SAVE) }
}

@Composable
fun SaveScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    var summary by remember { mutableStateOf(visit.summary.isNotBlank()) }
    var originals by remember { mutableStateOf(true) }
    var reviewed by remember { mutableStateOf(true) }
    val promoted = remember(visit.id) { mutableStateListOf<String>() }
    Title("Save to\nyour records?", "You control what’s saved. Current medicines and allergies change only when you explicitly choose.", centered = true)
    Panel {
        CheckRow("Visit summary", summary, if (visit.summaryReview == Review.PATIENT_REVIEWED) "Patient-reviewed explanation" else "Draft · still needs review") { summary = it }
        CheckRow("Originals", originals, "Paper pages, recorded segments and original wording") { originals = it }
        CheckRow("Reviewed details", reviewed, "${visit.facts.count { !it.needsReview }} patient-reviewed items") { reviewed = it }
    }
    val eligible = visit.currentFacts.filter { !it.needsReview && it.category in setOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY) }
    if (eligible.isNotEmpty()) Panel {
        SectionLabel("Optional health profile updates")
        Text("Nothing selected by default. Review current entries for older or duplicate information.", color = Muted, fontSize = 13.sp)
        eligible.forEach { fact -> CheckRow(fact.quote, fact.id in promoted, fact.category.title) { selected -> if (selected) promoted.add(fact.id) else promoted.remove(fact.id) } }
    }
    Flag("${visit.unresolved.size} details need confirmation; ${visit.sources.count { it.status != Processing.READY }} sources await processing or review. These statuses cannot be switched off.")
    Primary("Save selected", enabled = !visit.isCapturing) { vm.archive(summary, originals, reviewed, promoted.toSet()) }
    Secondary("Review again") { vm.navigate(Screen.EXTRACTION) }
    Text("Unselected material stays in this visit’s draft. Saving again does not duplicate profile entries.", color = Muted, fontSize = 13.sp)
}

@Composable
fun DoctorScreen(vm: WithYouViewModel) {
    Spacer(Modifier.height(25.dp))
    SectionLabel("A question from the patient")
    Text(vm.doctorQuestion, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 26.dp))
    if (vm.doctorReason.isNotBlank()) Panel { Text("Why this came up", fontWeight = FontWeight.Medium); Text(vm.doctorReason, fontSize = 17.sp, lineHeight = 25.sp) }
    Text("Shown only on this phone. Nothing was sent.", color = Muted, fontSize = 14.sp)
    Secondary("Back to my visit") { vm.back() }
}
