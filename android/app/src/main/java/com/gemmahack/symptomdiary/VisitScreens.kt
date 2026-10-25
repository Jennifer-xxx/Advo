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
    val ongoing = vm.data.visits.lastOrNull { it.kind == RecordKind.SYMPTOM && it.talks.lastOrNull()?.finishedAt == null && it.talks.isNotEmpty() }
    if (ongoing != null) EntryRow("paper", "Continue", ongoing.title) { vm.selectVisit(ongoing.id) }
    TextButton(onClick = { vm.openBriefSelection() }) { Text("Prepare for a visit", color = Muted) }
    if (!vm.modelReady) Panel {
        Text(if (vm.modelLoading) "Getting Gemma ready" else "Offline setup", fontWeight = FontWeight.Medium)
        Text(if (vm.modelLoading) "Your local model is loading. You can type and view records while you wait."
            else "Type and keep records now. Set up the local model for voice notes, paper reading and help organizing your visit.", color = Muted, fontSize = 14.sp)
        TextButton(onClick = { vm.navigate(Screen.RESOURCES) }) { Text("Offline resources", color = Rust) }
    }
}

@Composable
fun PrepareScreen(vm: WithYouViewModel, ui: UiController) {
    val talk = vm.currentTalk
    val phase = vm.talkPhase
    val working = phase in setOf(TalkPhase.STARTING, TalkPhase.THINKING, TalkPhase.FINISHING)
    val lastReply = talk?.turns?.lastOrNull { it.role == TalkRole.ADVO }?.text
    var transcript by remember(vm.visitId) { mutableStateOf(false) }
    Title(if (vm.talkTyped) "Let’s talk" else "I’m here to listen", centered = true)
    if (!vm.talkTyped) {
        Spacer(Modifier.height(12.dp))
        VoiceOrb(phase in setOf(TalkPhase.LISTENING, TalkPhase.SPEAKING),
            when (phase) {
                TalkPhase.LISTENING -> "Listening"
                TalkPhase.SPEAKING -> "Advo is speaking · tap to interrupt"
                TalkPhase.THINKING -> "Thinking…"
                TalkPhase.STARTING -> "Getting ready…"
                TalkPhase.FINISHING -> "Saving your conversation…"
                else -> "Tap to resume"
            }) { ui.withMic { vm.interruptTalk() } }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 75.dp), color = Rust, trackColor = Peach)
        Text(lastReply ?: "Tell me what’s on your mind.", fontSize = 22.sp, lineHeight = 30.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
        if (vm.talkPartial.isNotBlank()) Text(vm.talkPartial, color = Muted, fontSize = 17.sp, lineHeight = 24.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (phase == TalkPhase.PAUSED && vm.talkMessage != "Paused") Text(vm.talkMessage, color = Muted,
            fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    } else {
        talk?.turns?.takeLast(8)?.forEach { turn -> Panel {
            Text(if (turn.role == TalkRole.PATIENT) "You" else "Advo", color = Muted, fontSize = 12.sp)
            Text(turn.text, fontSize = 18.sp, lineHeight = 26.sp)
        } }
        if (phase == TalkPhase.THINKING) Text("Advo is thinking…", color = Muted)
        OutlinedTextField(vm.input, vm::editInput, label = { Text("Message Advo") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Primary("Send", enabled = vm.input.isNotBlank() && !working) { vm.sendTalkText() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            Secondary(if (vm.talkTyped) "Use voice" else if (phase in setOf(TalkPhase.PAUSED, TalkPhase.IDLE, TalkPhase.FINISHED)) "Resume" else "Pause", enabled = phase != TalkPhase.FINISHING) {
                if (vm.talkTyped || phase in setOf(TalkPhase.PAUSED, TalkPhase.IDLE, TalkPhase.FINISHED)) ui.withMic { vm.beginTalk() } else vm.pauseTalk()
            }
        }
        Box(Modifier.weight(1f)) { Primary("Done", enabled = phase != TalkPhase.FINISHING) { vm.finishTalk() } }
    }
    if (!vm.talkTyped) TextButton(onClick = { vm.typeTalk() }, enabled = phase != TalkPhase.FINISHING) { Text("Type instead", color = Muted) }
    if (!vm.talkTyped && talk?.turns?.isNotEmpty() == true) {
        TextButton(onClick = { transcript = !transcript }) { Text(if (transcript) "Hide conversation" else "View conversation", color = Muted) }
        if (transcript) talk.turns.forEach { turn -> Text("${if (turn.role == TalkRole.PATIENT) "You" else "Advo"}: ${turn.text}", fontSize = 15.sp, lineHeight = 23.sp) }
    }
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
            Processing.READY -> "Processed · review your words"
            Processing.FAILED -> "Original saved · retry or edit manually"
        }, if (source.mime == "audio/wav") Lavender else Peach) { vm.openSource(source.id) }
}

@Composable
fun ConsentScreen(vm: WithYouViewModel, ui: UiController) {
    var agreed by remember(vm.visitId) { mutableStateOf(false) }
    Title("Record this visit?", "Ask everyone in the room first.")
    Panel {
        Text("Recording is optional", fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Text("Audio stays on this phone. You can pause, stop, or withdraw consent at any time.", color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
    }
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
        Recording.RECORDING -> "Your visit"
        Recording.PAUSED -> "Recording paused"
        Recording.INTERRUPTED -> "Recording interrupted"
        else -> "Here with you"
    }, if (active) "Recording privately on this phone." else "Your questions and notes are here.")
    if (visit.recording in setOf(Recording.RECORDING, Recording.PAUSED)) {
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("%02d:%02d".format(seconds / 60, seconds % 60), fontSize = 30.sp, fontWeight = FontWeight.Medium)
                    Text(if (active) "Recording" else "Paused", color = Muted, fontSize = 14.sp)
                }
                TextButton(onClick = { vm.pauseVisitRecording() }) { Text(if (active) "Pause" else "Resume", color = Rust) }
            }
        }
    } else {
        if (visit.recording == Recording.INTERRUPTED) Flag("Saved segments are kept. Start a new consented recording when ready.")
        Secondary("Start a recording") { vm.navigate(Screen.CONSENT) }
    }
    val pending = visit.sources.count { it.status in setOf(Processing.QUEUED, Processing.PROCESSING, Processing.FAILED) }
    if (pending > 0) Text(if (active) "Saved audio is being organized while recording continues" else "Some saved audio still needs organizing", color = Muted, fontSize = 13.sp)
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
    EntryRow("paper", "Ask Advo", "Clarify something from this visit") { vm.navigate(Screen.VISIT_CHAT) }
    if (visit.reports.isNotEmpty()) EntryRow("paper", "Your appointment brief", "Symptoms you chose to discuss") { vm.navigate(Screen.REPORT) }
    else TextButton(onClick = { vm.openBriefSelection(forCurrentVisit = true) }) { Text("Choose symptoms to discuss", color = Rust) }
    Primary("Before I leave") { vm.prepareWrapUp() }
    TextButton(onClick = { vm.navigate(Screen.QUESTIONS) }) { Text("My doctor questions", color = Muted) }
    var more by remember(visit.id) { mutableStateOf(false) }
    TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "More visit tools", color = Muted) }
    if (more) {
    if (visit.isCapturing) {
        Secondary("Stop recording") { vm.stopVisitRecording() }
        TextButton(onClick = { vm.stopVisitRecording(true) }) { Text("Withdraw consent", color = Rust) }
    }
    Secondary("Add a note") { ui.ask("Visit note", "What do you want to remember?", description = "Saved as patient recollection, not as a doctor's recorded words.") { text -> vm.editInput(text); vm.submit(SourceKind.PATIENT_RECOLLECTION) } }
    Text("Medicine interactions cannot be checked: no validated local drug reference is installed. An absent prompt does not establish safety.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun WrapUpScreen(vm: WithYouViewModel, ui: UiController) = VisitConfirmationScreen(vm, ui)

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
    TextButton(onClick = { vm.navigate(if (visit.kind == RecordKind.VISIT) Screen.PLAN else Screen.DETAIL) }) { Text("Continue without a paper", color = Muted) }
}

@Composable
fun ExtractionScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    var index by remember(visit.id) { mutableIntStateOf(0) }
    val facts = visit.currentFacts
    Title("Check one detail", "Compare the wording with its original. You can leave a detail unresolved.")
    val pending = visit.sources.filter { source -> source.status != Processing.READY ||
        (source.text.isNotBlank() && visit.facts.none { it.sourceId == source.id }) }
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
    if (visit.kind == RecordKind.SYMPTOM) Primary("Back to symptom entry") { vm.navigate(Screen.DETAIL) }
    else if (visit.kind == RecordKind.PROFILE) Primary("Review profile update") { vm.navigate(Screen.DETAIL) }
    else Primary("See my plan") { vm.navigate(Screen.PLAN) }
}

@Composable
fun PlanDetailsScreen(vm: WithYouViewModel, ui: UiController) {
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
                    if (category == Category.ACTION && !fact.needsReview) Secondary("Set a reminder") { ui.remind(fact) }
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
