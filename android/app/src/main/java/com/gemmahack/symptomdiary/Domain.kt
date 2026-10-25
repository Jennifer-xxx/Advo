package com.gemmahack.symptomdiary

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): String = Instant.now().toString()
fun displayDate(instant: String, zone: String = ZoneId.systemDefault().id): String = runCatching {
    Instant.parse(instant).atZone(ZoneId.of(zone)).toLocalDate().toString()
}.getOrElse { instant.take(10) }
fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

enum class Stage { PREPARING, READY, IN_VISIT, WRAP_UP, POST_VISIT, ARCHIVED }
enum class RecordKind { SYMPTOM, VISIT, PROFILE }
enum class Processing { IDLE, QUEUED, PROCESSING, READY, FAILED }
enum class Review { DRAFT, PATIENT_REVIEWED, UNRESOLVED }
enum class Recording { NOT_STARTED, RECORDING, PAUSED, STOPPED, INTERRUPTED }
enum class SourceKind { PATIENT_TEXT, PATIENT_VOICE, PATIENT_RECOLLECTION, VISIT_AUDIO, DOCUMENT, LEGACY }
enum class Category(val title: String) {
    SYMPTOM("Your symptoms"), MEDICINE("Current medicines & supplements"),
    ALLERGY("Allergies & reactions"), PAST_MEDICINE("Past medicines"),
    HISTORY("Conditions & recent history"), GOAL("Also wants to discuss"),
    DISCUSSION("We discussed"), MEDICINE_CHANGE("Medicine changes"),
    ACTION("Next steps & follow-up"), OTHER("Other details")
}
enum class QuestionOrigin { PATIENT, SUGGESTED }
enum class QuestionState { TO_ASK, ASKED, LATER, DISMISSED }
enum class ProfileStatus { CURRENT, HISTORICAL }
enum class KnowledgeState { NOT_RECORDED, UNKNOWN, NONE_CONFIRMED, RECORDED }

data class Source(
    val id: String = newId(), val kind: SourceKind, val createdAt: String = now(),
    val text: String = "", val originalText: String = "", val blob: String? = null,
    val mime: String? = null, val page: Int? = null, val startMs: Long? = null,
    val endMs: Long? = null, val status: Processing = Processing.IDLE,
    val speaker: String = "Unknown speaker", val editedAt: String? = null,
    val parentId: String? = null, val error: String? = null,
    val summary: String = "", val summaryStatus: Processing = Processing.IDLE,
) {
    val label: String get() = when (kind) {
        SourceKind.PATIENT_TEXT -> "Patient's words"
        SourceKind.PATIENT_VOICE -> "Patient dictation"
        SourceKind.PATIENT_RECOLLECTION -> "Patient recollection"
        SourceKind.VISIT_AUDIO -> "Visit audio · ${((startMs ?: 0) / 1000)}–${((endMs ?: 0) / 1000)} s · $speaker"
        SourceKind.DOCUMENT -> "Paper · page ${page ?: 1}"
        SourceKind.LEGACY -> "Imported diary · original preserved"
    }
}

data class Fact(
    val id: String, val category: Category, val label: String,
    val quote: String, val sourceId: String, val status: Review = Review.DRAFT,
    val name: String? = null, val dose: String? = null, val unit: String? = null,
    val frequency: String? = null, val reaction: String? = null, val timing: String? = null,
    val uncertain: Boolean = false, val reviewNote: String = "", val reviewedAt: String? = null,
    val doctorCheckedAt: String? = null, val supersedes: String? = null,
    val superseded: Boolean = false,
    val english: String? = null, val translationReview: Review = Review.DRAFT,
) {
    val needsReview: Boolean get() = status != Review.PATIENT_REVIEWED || uncertain
}

data class VisitQuestion(
    val id: String = newId(), val text: String, val origin: QuestionOrigin,
    val selected: Boolean = origin == QuestionOrigin.PATIENT,
    val state: QuestionState = QuestionState.TO_ASK, val reason: String = "",
    val sourceIds: List<String> = emptyList(),
)

data class Consent(
    val id: String = newId(), val agreedAt: String = now(),
    val endedAt: String? = null, val withdrawnAt: String? = null,
) { val active: Boolean get() = endedAt == null && withdrawnAt == null }

data class Generation(
    val id: String = newId(), val task: String, val model: String,
    val promptVersion: String, val sourceIds: List<String>, val seconds: Double,
    val createdAt: String = now(), val valid: Boolean = true,
)

data class ProfileEntry(
    val id: String = newId(), val fact: Fact, val status: ProfileStatus = ProfileStatus.CURRENT,
    val createdAt: String = now(), val previousId: String? = null,
    val changes: List<ProfileChange> = listOf(ProfileChange(action = "Added after explicit patient review")),
)
data class ProfileChange(val at: String = now(), val action: String)

data class ReportSnapshot(
    val id: String = newId(), val version: Int, val createdAt: String = now(),
    val title: String, val patientName: String = "", val dateOfBirth: String = "",
    val appointment: String = "", val doctor: String = "", val clinic: String = "",
    val symptoms: List<Fact>, val concerns: List<Fact>, val records: List<ProfileEntry>,
    val questions: List<VisitQuestion>, val originalWords: List<String>,
    val generations: List<Generation>, val reviewLabel: String,
    val categoryStates: Map<String, KnowledgeState> = emptyMap(),
    val sourceDetails: Map<String, String> = emptyMap(), val zone: String = ZoneId.systemDefault().id,
    val communicationSummary: String = "", val recordCount: Int = 0,
)

data class ActionReminder(val id: String, val factId: String, val at: String, val zone: String)

data class Visit(
    val id: String = newId(), val createdAt: String = now(), val updatedAt: String = now(),
    val title: String = "New visit", val stage: Stage = Stage.PREPARING,
    val language: String = "en", val sources: List<Source> = emptyList(),
    val facts: List<Fact> = emptyList(), val questions: List<VisitQuestion> = emptyList(),
    val reports: List<ReportSnapshot> = emptyList(), val consents: List<Consent> = emptyList(),
    val recording: Recording = Recording.NOT_STARTED, val recordedMs: Long = 0,
    val generations: List<Generation> = emptyList(), val nextQuestion: String = "What would you like the doctor to know?",
    val skippedQuestions: List<String> = emptyList(), val summary: String = "",
    val summaryReview: Review = Review.DRAFT, val savedSummary: Boolean = false,
    val reminders: List<ActionReminder> = emptyList(), val archivedAt: String? = null,
    val patientName: String = "", val dateOfBirth: String = "", val appointment: String = "",
    val doctor: String = "", val clinic: String = "", val demo: Boolean = false,
    val inputDraft: String = "", val savedSourceIds: List<String> = emptyList(), val savedFactIds: List<String> = emptyList(),
    val replacingProfileId: String? = null,
    val inputDraftKind: SourceKind = SourceKind.PATIENT_TEXT,
    val talks: List<TalkSession> = emptyList(),
    val kind: RecordKind = RecordKind.VISIT, val relatedRecordIds: List<String> = emptyList(),
    val briefSummary: String = "",
    val briefSourceFingerprint: String = "",
    val briefStatus: Processing = Processing.IDLE, val briefError: String? = null,
) {
    val currentFacts: List<Fact> get() = facts.filter { !it.superseded }
    val unresolved: List<Fact> get() = currentFacts.filter { it.needsReview }
    val hasConsent: Boolean get() = consents.lastOrNull()?.active == true
    val isCapturing: Boolean get() = recording == Recording.RECORDING || recording == Recording.PAUSED
}

data class Settings(
    val appLanguage: String = "device", val voiceLanguage: String = "en",
    val modelPreference: String = "E2B", val keepScreenAwake: Boolean = true,
    val categoryStates: Map<String, KnowledgeState> = emptyMap(),
    val categoryStateSourceIds: Map<String, String> = emptyMap(),
)

data class HealthStore(
    val schema: Int = 2, val visits: List<Visit> = emptyList(),
    val profile: List<ProfileEntry> = emptyList(), val settings: Settings = Settings(),
    val migratedDiary: Boolean = false,
)

/** Pure business rules. Model output cannot authorize recording, promotion or doctor confirmation. */
object VisitRules {
    fun mayRecord(visit: Visit): Boolean = visit.hasConsent && visit.stage == Stage.IN_VISIT

    fun review(visit: Visit, factId: String, status: Review, doctorChecked: Boolean = false): Visit =
        visit.copy(facts = visit.facts.map { fact ->
            if (fact.id != factId) fact else fact.copy(status = status,
                reviewedAt = now(), doctorCheckedAt = if (doctorChecked) now() else fact.doctorCheckedAt)
        })

    fun promote(store: HealthStore, visit: Visit, selectedIds: Set<String>): HealthStore {
        val eligible = visit.currentFacts.filter { it.id in selectedIds && !it.needsReview &&
            it.category in setOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY) }
        val existing = store.profile.map { it.fact.id }.toSet()
        val fresh = eligible.filter { it.id !in existing }
        val previous = store.profile.find { it.id == visit.replacingProfileId }
        val replacements = fresh.filter { it.category == previous?.fact?.category }
        require(replacements.size <= 1) { "Choose one reviewed detail to replace the earlier profile entry." }
        val replacing = replacements.isNotEmpty()
        val profile = store.profile.map { if (replacing && it.id == previous?.id) it.copy(status = ProfileStatus.HISTORICAL,
            changes = it.changes + ProfileChange(action = "Replaced by an explicitly selected reviewed correction")) else it } + fresh.map {
            ProfileEntry(fact = it, previousId = if (replacing && it.category == previous?.fact?.category) previous?.id else null)
        }
        return store.copy(profile = profile, settings = store.settings.copy(categoryStates = store.settings.categoryStates +
            fresh.associate { it.category.name to KnowledgeState.RECORDED }))
    }

    fun recover(visit: Visit): Visit = visit.copy(
        recording = if (visit.isCapturing) Recording.INTERRUPTED else visit.recording,
        consents = visit.consents.map { if (it.active) it.copy(endedAt = now()) else it },
        sources = visit.sources.map { source ->
            val restored = if (source.status == Processing.PROCESSING) source.copy(status = Processing.FAILED,
                error = "Processing was interrupted. Retry when ready.") else source
            if (restored.summaryStatus == Processing.PROCESSING) restored.copy(summaryStatus = Processing.FAILED) else restored
        },
        briefStatus = if (visit.briefStatus == Processing.PROCESSING) Processing.FAILED else visit.briefStatus,
        briefError = if (visit.briefStatus == Processing.PROCESSING) "Brief generation was interrupted. Your records are safe; retry when ready." else visit.briefError,
    )

    fun mergeFacts(old: List<Fact>, incoming: List<Fact>): List<Fact> {
        val ids = old.map { it.id }.toSet()
        val added = incoming.filter { it.id !in ids }
        val all = old + added
        // Deliberately conservative: different sources discussing the same field require comparison.
        val comparable = setOf(Category.MEDICINE, Category.MEDICINE_CHANGE, Category.ALLERGY, Category.ACTION)
        val conflicts = all.filter { !it.superseded }.groupBy { it.category to (it.name ?: it.label).lowercase().trim() }
            .values.filter { group -> group.first().category in comparable && group.map { it.sourceId }.distinct().size > 1 &&
                group.map { it.quote.trim().lowercase() }.distinct().size > 1 && group.any { f -> added.any { it.id == f.id } } }
            .flatten().map { it.id }.toSet()
        return all.map { if (it.id in conflicts) it.copy(status = Review.UNRESOLVED,
            reviewNote = "Different source wording. Compare the originals; this is not a medical conclusion.") else it }
    }

    fun snapshot(visit: Visit, profile: List<ProfileEntry>): ReportSnapshot = ReportSnapshot(
        version = (visit.reports.maxOfOrNull { it.version } ?: 0) + 1,
        title = visit.title, patientName = visit.patientName, dateOfBirth = visit.dateOfBirth,
        appointment = visit.appointment, doctor = visit.doctor, clinic = visit.clinic,
        symptoms = visit.currentFacts.filter { it.category == Category.SYMPTOM },
        concerns = visit.currentFacts.filter { it.category == Category.GOAL },
        records = profile.filter { it.status == ProfileStatus.CURRENT } + visit.currentFacts.filter {
            it.category in setOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY) &&
                profile.none { entry -> entry.fact.id == it.id }
        }.map { ProfileEntry(id = it.id, fact = it) },
        questions = visit.questions.filter { it.selected && it.state != QuestionState.DISMISSED },
        originalWords = visit.sources.filter { it.kind in setOf(SourceKind.PATIENT_TEXT, SourceKind.PATIENT_VOICE, SourceKind.LEGACY) }
            .map { it.text }, generations = visit.generations.toList(),
        reviewLabel = if (visit.unresolved.isEmpty() && visit.currentFacts.isNotEmpty() && visit.sources.all { it.status == Processing.READY })
            "Patient-reviewed draft" else "Draft · includes details needing review",
    )
}
