package com.gemmahack.symptomdiary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun DoctorQuestionsScreen(vm: WithYouViewModel, ui: UiController) {
    Title("My doctor questions", "Keep track of what you want to ask.")
    QuestionList(vm, ui, live = true)
}

@Composable
fun ProfileUpdateScreen(vm: WithYouViewModel, ui: UiController) {
    val record = vm.visit ?: return
    Title("Update your\nhealth profile", "Check the wording before adding it as current information.")
    val updates = CarePlanRules.updates(vm.data, record)
    updates.forEach { fact -> Panel {
        Text(fact.category.title, color = Muted, fontSize = 12.sp)
        Text(fact.quote, fontSize = 18.sp, lineHeight = 26.sp)
        if (fact.uncertain || fact.status == Review.UNRESOLVED) {
            Flag("Wording needs clarification")
            TextButton(onClick = { vm.navigate(Screen.EXTRACTION) }) { Text("Review detail", color = Rust) }
        } else Secondary("Confirm & add") {
            ui.confirm("Add this current information?", fact.quote, "Confirm & add") { vm.confirmProfileUpdate(fact.id) }
        }
    } }
    val unfinished = record.talks.lastOrNull()?.finishedAt == null && record.talks.isNotEmpty()
    if (unfinished) Primary("Continue conversation") { vm.selectVisit(record.id, Screen.PREPARE) }
    else Primary("Back to health profile") { vm.navigate(Screen.HEALTH) }
    if (updates.isEmpty() && !vm.busy) Text("No pending profile updates. Your original words and papers are kept below.", color = Muted)
    TextButton(onClick = { vm.organizePending() }, enabled = !vm.busy && vm.modelReady) { Text("Organize saved sources", color = Rust) }
    TextButton(onClick = { ui.factCategory = Category.MEDICINE }) { Text("Add a detail manually", color = Muted) }
    record.sources.forEach { SourceRow(vm, it) }
}

@Composable
fun VisitChatScreen(vm: WithYouViewModel) {
    val visit = vm.visit ?: return
    var question by remember(visit.id) { mutableStateOf("") }
    Title("Ask about\nthis visit", "Advo explains the latest saved wording it can read. Confirm important details with your doctor.")
    val waiting = visit.sources.count { it.kind == SourceKind.VISIT_AUDIO && it.status != Processing.READY }
    if (waiting > 0) Text("Some audio is still being organized. Answers can only use the parts already transcribed.", color = Muted, fontSize = 14.sp)
    if (visit.clarifications.isEmpty()) Panel {
        Text("What would you like explained?", fontSize = 21.sp)
        Text("For example: What follow-up did we discuss?", color = Muted)
    }
    visit.clarifications.takeLast(12).forEach { item ->
        Panel {
            Text("You", color = Muted, fontSize = 12.sp)
            Text(item.question, fontSize = 19.sp)
            Text("Advo", color = Rust, fontSize = 12.sp)
            when (item.status) {
                Processing.PROCESSING -> Text("Checking the saved visit…", color = Muted)
                Processing.FAILED -> Text(item.error ?: "Ask your doctor or try again.", color = Muted)
                else -> Text(item.answer, fontSize = 18.sp, lineHeight = 26.sp)
            }
            var evidence by remember(item.id) { mutableStateOf(false) }
            if (item.evidence.isNotEmpty()) {
                Text("Draft explanation · check with your doctor", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { evidence = !evidence }) { Text(if (evidence) "Hide source wording" else "Source wording", color = Rust) }
                if (evidence) item.evidence.forEach { quote ->
                    Text(quote.quote, fontSize = 15.sp)
                    TextButton(onClick = { vm.openSource(quote.sourceId) }) { Text("Open original", color = Rust) }
                }
            }
            TextButton(onClick = { vm.showDoctor(item.question) }) { Text("Show question to doctor", color = Rust) }
            if (visit.questions.none { it.text.equals(item.question, true) }) TextButton(onClick = { vm.addQuestion(item.question) }) {
                Text("Keep in my doctor questions", color = Muted)
            }
        }
    }
    OutlinedTextField(question, { question = it.take(2000) }, label = { Text("Ask about this visit") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    Primary("Ask Advo", enabled = question.isNotBlank() && !vm.busy && vm.modelReady) {
        vm.askVisitQuestion(question); question = ""
    }
    if (question.isNotBlank()) TextButton(onClick = { vm.showDoctor(question) }) { Text("Ask the doctor directly", color = Rust) }
    if (!vm.modelReady) Text("The local model is unavailable. You can still show your question to the doctor.", color = Muted)
}

@Composable
fun VisitConfirmationScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    Title("Before you leave", "Check your understanding with the doctor. Unchecked details stay marked for review.")
    if (visit.summary.isNotBlank()) Panel { Text("Visit summary", fontWeight = FontWeight.Medium); Text(visit.summary, fontSize = 18.sp, lineHeight = 26.sp) }
    val facts = visit.currentFacts.filter { it.category in setOf(Category.DISCUSSION, Category.MEDICINE_CHANGE, Category.MEDICINE, Category.ALLERGY, Category.ACTION) }
    if (facts.isEmpty()) Panel {
        Text(if (vm.busy) "Organizing your visit…" else "What happens next?", fontSize = 22.sp)
        Text("Your audio and notes stay saved. Add any instruction you want to confirm.", color = Muted)
    }
    facts.forEach { fact -> Panel {
        Text(fact.category.title, color = Muted, fontSize = 12.sp)
        Text(fact.quote, fontSize = 18.sp, lineHeight = 26.sp)
        if (fact.uncertain || fact.status == Review.UNRESOLVED) {
            Flag("Wording needs clarification")
            TextButton(onClick = { vm.navigate(Screen.EXTRACTION) }) { Text("Review this detail", color = Rust) }
        } else CheckRow("I checked this with the doctor", fact.doctorCheckedAt != null && !fact.needsReview) {
            vm.reviewFact(fact.id, if (it) Review.PATIENT_REVIEWED else Review.UNRESOLVED, doctorChecked = it)
        }
        TextButton(onClick = { vm.openSource(fact.sourceId) }) { Text("Source", color = Muted) }
    } }
    val unanswered = visit.questions.filter { it.selected && it.state in setOf(QuestionState.TO_ASK, QuestionState.LATER) }
    if (unanswered.isNotEmpty()) Panel {
        Text("Still to ask", fontWeight = FontWeight.Medium)
        unanswered.forEach { q -> EntryRow("paper", q.text) { vm.showDoctor(q.text) } }
    }
    val pending = visit.sources.any { it.status in setOf(Processing.QUEUED, Processing.FAILED) }
    if (pending && !vm.busy) Secondary("Organize remaining audio & notes", enabled = vm.modelReady) { vm.prepareWrapUp() }
    Primary("Finish visit") { vm.setStage(Stage.POST_VISIT, Screen.PLAN) }
    TextButton(onClick = { ui.factCategory = Category.ACTION }) { Text("Add an instruction", color = Rust) }
    TextButton(onClick = { vm.navigate(Screen.VISIT_CHAT) }) { Text("Ask Advo", color = Muted) }
}

@Composable
fun CarePlanScreen(vm: WithYouViewModel, ui: UiController) {
    val visit = vm.visit ?: return
    var more by remember(visit.id) { mutableStateOf(false) }
    Title("Your care plan", "Your visit is saved. Here’s what to do next.")
    if (visit.summary.isNotBlank()) Panel {
        Text("Visit summary", color = Muted, fontSize = 12.sp)
        Text(visit.summary, fontSize = 18.sp, lineHeight = 26.sp)
        if (visit.summaryReview != Review.PATIENT_REVIEWED) Text("Draft · confirm the wording", color = Muted, fontSize = 12.sp)
    }
    SectionLabel("Next steps")
    val actions = CarePlanRules.actions(visit)
    if (actions.isEmpty()) Panel {
        Text("No next steps recorded yet", fontSize = 19.sp)
        Text("Add your instructions or organize the visit audio. Advo won’t invent a follow-up date.", color = Muted)
        TextButton(onClick = { ui.factCategory = Category.ACTION }) { Text("Add an instruction", color = Rust) }
    }
    actions.forEach { fact -> Panel {
        val done = fact.id in visit.completedActionIds
        if (!fact.needsReview) CheckRow(fact.quote, done, if (done) "Completed" else fact.timing ?: "Timing not recorded") { vm.completeAction(fact.id, it) }
        else {
            Text(fact.quote, fontSize = 18.sp, lineHeight = 26.sp)
            Flag("Confirm this instruction")
            if (!fact.uncertain && fact.status != Review.UNRESOLVED) Secondary("I reviewed this instruction") {
                vm.reviewFact(fact.id, Review.PATIENT_REVIEWED)
            } else TextButton(onClick = { vm.navigate(Screen.EXTRACTION) }) { Text("Clarify wording", color = Rust) }
        }
        val reminder = visit.reminders.firstOrNull { it.factId == fact.id }
        if (reminder != null) {
            Text("Reminder: ${reminder.at} · ${reminder.zone}", color = Muted, fontSize = 13.sp)
            TextButton(onClick = { vm.cancelReminder(reminder.id) }) { Text("Remove reminder", color = Muted) }
        } else if (!fact.needsReview && !done) TextButton(onClick = { ui.remind(fact) }) { Text("Remind me", color = Rust) }
    } }
    SectionLabel("Medicines & allergies")
    val updates = CarePlanRules.updates(vm.data, visit)
    updates.forEach { fact -> Panel {
        Text(fact.category.title, color = Muted, fontSize = 12.sp)
        Text(fact.quote, fontSize = 18.sp, lineHeight = 26.sp)
        if (fact.uncertain || fact.status == Review.UNRESOLVED) {
            Flag("Clarify this detail before updating your profile")
            TextButton(onClick = { vm.navigate(Screen.EXTRACTION) }) { Text("Review wording", color = Rust) }
        } else Secondary("Confirm & add to health profile") {
            ui.confirm("Add this to your health profile?", "${fact.quote}\n\nConfirm that this is accurate current information. Existing entries and their history are kept; check your list for older or duplicate entries.", "Confirm & add") { vm.confirmProfileUpdate(fact.id) }
        }
        TextButton(onClick = { vm.openSource(fact.sourceId) }) { Text("Source", color = Muted) }
    } }
    val changes = visit.currentFacts.filter { it.category == Category.MEDICINE_CHANGE }
    if (changes.isNotEmpty()) Panel {
        Text("Medicine changes to review", fontWeight = FontWeight.Medium)
        changes.forEach { Text(it.quote, fontSize = 17.sp) }
        Text("Check your current medicines before adding or moving an entry to history.", color = Muted, fontSize = 13.sp)
    }
    EntryRow("health", "Health profile", if (updates.isEmpty()) "View saved medicines, allergies and history" else "Review your current list") { vm.navigate(Screen.HEALTH) }
    Primary("Done") { vm.finishCarePlan() }
    TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "Papers, audio & more", color = Muted) }
    if (more) {
        Secondary("Add a paper or photo") { vm.navigate(Screen.SCAN) }
        Secondary("Review all details") { vm.navigate(Screen.EXTRACTION) }
        Secondary("Original wording & explanation") { vm.navigate(Screen.PLAN_DETAILS) }
        Secondary("Organize saved sources", enabled = vm.modelReady && !vm.busy) { vm.organizePending() }
        Secondary(if (visit.summary.isBlank()) "Summarize visit" else "Update summary", enabled = vm.modelReady && !vm.busy) { vm.explainPlan() }
        Secondary(if (vm.speaking) "Stop reading" else "Read aloud") { vm.readAloud(visit.summary.ifBlank { visit.currentFacts.joinToString("\n") { it.quote } }) }
        if (visit.summary.isNotBlank() && visit.summaryReview != Review.PATIENT_REVIEWED) Secondary("I reviewed the explanation") { vm.reviewSummary() }
        visit.sources.forEach { SourceRow(vm, it) }
        Secondary("Choose archive contents") { vm.navigate(Screen.SAVE) }
    }
}
