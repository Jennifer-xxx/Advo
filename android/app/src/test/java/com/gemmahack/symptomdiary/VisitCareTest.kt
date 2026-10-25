package com.gemmahack.symptomdiary

import org.junit.Assert.*
import org.junit.Test

class VisitCareTest {
    private val source = Source(id = "visit-audio", kind = SourceKind.VISIT_AUDIO,
        text = "Follow up in 2 weeks. Penicillin caused hives. Take 5 mg daily.", status = Processing.READY)
    private val action = Fact(id = "follow-up", category = Category.ACTION, label = "Follow-up",
        quote = "Follow up in 2 weeks.", sourceId = source.id, status = Review.PATIENT_REVIEWED)
    private val allergy = Fact(id = "allergy", category = Category.ALLERGY, label = "Allergy",
        quote = "Penicillin caused hives.", sourceId = source.id, name = "Penicillin", reaction = "hives")
    private val visit = Visit(kind = RecordKind.VISIT, sources = listOf(source), facts = listOf(action, allergy))
    private val passages = ClarificationRules.passages(visit)
    private fun response(answer: String, id: String = source.id, quote: String = "Follow up in 2 weeks.") =
        StoreCodec.gson.toJson(mapOf("answer" to answer, "evidence" to listOf(mapOf("source_id" to id, "quote" to quote))))

    @Test fun visitAnswerCitesExactSourceWithoutCreatingAFact() {
        val answer = ClarificationRules.parse(response("The saved wording says to follow up in 2 weeks."), passages)
        assertEquals(source.id, answer.evidence.single().sourceId)
        assertEquals(2, visit.facts.size)
        assertTrue(visit.clarifications.isEmpty())
    }

    @Test fun unsupportedAnswerAlwaysAbstains() {
        val answer = ClarificationRules.parse("""{"answer":"Take 50 mg safely.","evidence":[]}""", passages)
        assertEquals(ClarificationRules.UNKNOWN, answer.text)
    }

    @Test(expected = ModelContractException::class) fun inventedSourceIsRejected() {
        ClarificationRules.parse(response("Follow up in 2 weeks.", id = "invented"), passages)
    }

    @Test(expected = ModelContractException::class) fun paraphrasedEvidenceIsRejected() {
        ClarificationRules.parse(response("Follow up in 2 weeks.", quote = "Come back in two weeks."), passages)
    }

    @Test(expected = ModelContractException::class) fun changedDoseIsRejected() {
        ClarificationRules.parse(response("Take 50 mg daily.", quote = "Take 5 mg daily."), passages)
    }

    @Test fun latestVisitContextIsBoundedAndNeverUsesSymptomSummary() {
        val old = source.copy(id = "old", text = "a".repeat(9500), summary = "Invented summary")
        val current = visit.copy(sources = listOf(old, source))
        val context = ClarificationRules.passages(current)
        assertTrue(context.sumOf { it.text.length } <= 9000)
        assertFalse(context.any { it.source_id == old.id })
        assertEquals(source.text, context.last().text)
        assertFalse(context.any { it.text.contains("Invented summary") })
    }

    @Test fun reviewedActionCompletionPersistsAndDoesNotChangeHealthProfile() {
        val completed = CarePlanRules.complete(visit, action.id, true)
        val state = StoreCodec.decode(StoreCodec.encode(HealthStore(visits = listOf(completed))))
        assertEquals(listOf(action.id), state.visits.single().completedActionIds)
        assertTrue(state.profile.isEmpty())
        assertTrue(CarePlanRules.complete(completed, action.id, false).completedActionIds.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun unreviewedActionCannotBeMarkedDone() {
        CarePlanRules.complete(visit.copy(facts = listOf(action.copy(status = Review.DRAFT))), action.id, true)
    }

    @Test fun explicitProfileConfirmationIsAtomicAndIdempotent() {
        val first = CarePlanRules.confirmProfileUpdate(HealthStore(visits = listOf(visit)), visit, allergy.id)
        assertEquals(Review.PATIENT_REVIEWED, first.visits.single().facts.last().status)
        assertEquals("Penicillin", first.profile.single().fact.name)
        val second = CarePlanRules.confirmProfileUpdate(first, first.visits.single(), allergy.id)
        assertEquals(1, second.profile.size)
        assertTrue(CarePlanRules.updates(second, second.visits.single()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun conflictingAllergyCannotBecomeCurrent() {
        val unclear = visit.copy(facts = listOf(allergy.copy(status = Review.UNRESOLVED)))
        CarePlanRules.confirmProfileUpdate(HealthStore(visits = listOf(unclear)), unclear, allergy.id)
    }

    @Test fun interruptedAnswersBecomeRetryableAndKeepTheQuestion() {
        val question = VisitClarification(question = "When should I return?")
        val recovered = VisitRules.recover(visit.copy(clarifications = listOf(question)))
        assertEquals(Processing.FAILED, recovered.clarifications.single().status)
        assertEquals(question.question, recovered.clarifications.single().question)
        assertTrue(recovered.clarifications.single().answer.isEmpty())
    }

    @Test fun additiveMigrationRetainsOldSourcesWithoutInventingActionsOrAnswers() {
        val root = StoreCodec.gson.toJsonTree(HealthStore(visits = listOf(visit))).asJsonObject
        val oldVisit = root.getAsJsonArray("visits")[0].asJsonObject
        oldVisit.remove("clarifications"); oldVisit.remove("completedActionIds")
        val migrated = StoreCodec.decode(root.toString().toByteArray()).visits.single()
        assertEquals(source, migrated.sources.single())
        assertTrue(migrated.clarifications.isEmpty())
        assertTrue(migrated.completedActionIds.isEmpty())
    }
}
