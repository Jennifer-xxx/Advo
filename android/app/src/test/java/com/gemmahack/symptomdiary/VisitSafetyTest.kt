package com.gemmahack.symptomdiary

import org.junit.Assert.*
import org.junit.Test

class VisitSafetyTest {
    private val source = Source(id = "source-1", kind = SourceKind.PATIENT_TEXT, text = "Maybe headaches. Penicillin caused hives. Follow up in 2 weeks, I think.")
    private fun fact(id: String = "fact-1", review: Review = Review.DRAFT) = Fact(id = id, category = Category.ALLERGY,
        label = "Allergies", quote = "Penicillin caused hives.", sourceId = source.id, status = review,
        name = "Penicillin", reaction = "hives")

    @Test fun consentIsVisitSpecificAndCannotBeReusedAfterRecovery() {
        val first = Visit(stage = Stage.IN_VISIT, consents = listOf(Consent()), recording = Recording.RECORDING)
        assertTrue(VisitRules.mayRecord(first))
        assertFalse(VisitRules.mayRecord(Visit(stage = Stage.IN_VISIT)))
        assertFalse(VisitRules.mayRecord(first.copy(stage = Stage.PREPARING)))
        val recovered = VisitRules.recover(first)
        assertEquals(Recording.INTERRUPTED, recovered.recording)
        assertFalse(recovered.hasConsent)
        assertFalse(VisitRules.mayRecord(recovered))
    }

    @Test fun withdrawnConsentNeverAuthorizesRecording() {
        assertFalse(VisitRules.mayRecord(Visit(stage = Stage.IN_VISIT, consents = listOf(Consent(withdrawnAt = now())))))
    }

    @Test fun onlyExplicitlySelectedReviewedFactsEnterProfileAndSaveIsIdempotent() {
        val visit = Visit(sources = listOf(source), facts = listOf(fact()))
        val initial = HealthStore(visits = listOf(visit))
        assertTrue(VisitRules.promote(initial, visit, setOf("fact-1")).profile.isEmpty())
        val reviewed = VisitRules.review(visit, "fact-1", Review.PATIENT_REVIEWED)
        assertTrue(VisitRules.promote(initial, reviewed, emptySet()).profile.isEmpty())
        val saved = VisitRules.promote(initial, reviewed, setOf("fact-1"))
        assertEquals(1, saved.profile.size)
        assertEquals(saved, VisitRules.promote(saved, reviewed, setOf("fact-1")))
        assertEquals("hives", saved.profile.single().fact.reaction)
    }

    @Test fun patientReviewAndReportedDoctorCheckRemainDifferent() {
        val visit = Visit(sources = listOf(source), facts = listOf(fact(), fact("fact-2")))
        val reviewed = VisitRules.review(visit, "fact-1", Review.PATIENT_REVIEWED)
        assertNull(reviewed.facts.first().doctorCheckedAt)
        assertEquals(1, reviewed.unresolved.size)
        val checked = VisitRules.review(reviewed, "fact-1", Review.PATIENT_REVIEWED, doctorChecked = true)
        assertNotNull(checked.facts.first().doctorCheckedAt)
        assertEquals(Review.DRAFT, checked.facts.last().status)
    }

    @Test fun sourceConflictsAreRetainedWithoutReplacingReviewedInformation() {
        val existing = fact(review = Review.PATIENT_REVIEWED)
        val incoming = existing.copy(id = "other", sourceId = "paper", quote = "Penicillin reaction unclear.")
        val merged = VisitRules.mergeFacts(listOf(existing), listOf(incoming))
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.status == Review.UNRESOLVED })
        assertEquals(existing.quote, merged.first().quote)
        assertEquals(merged, VisitRules.mergeFacts(merged, listOf(incoming)))
    }

    @Test fun historicalReportRetainsItsFactsAndQuestionOrdering() {
        val reviewed = fact(review = Review.PATIENT_REVIEWED)
        val entry = ProfileEntry(fact = reviewed)
        val visit = Visit(sources = listOf(source), facts = listOf(reviewed), questions = listOf(
            VisitQuestion(text = "My first question?", origin = QuestionOrigin.PATIENT),
            VisitQuestion(text = "Unselected suggestion?", origin = QuestionOrigin.SUGGESTED),
            VisitQuestion(text = "My second question?", origin = QuestionOrigin.PATIENT)))
        val report = VisitRules.snapshot(visit, listOf(entry))
        val changed = entry.copy(status = ProfileStatus.HISTORICAL, fact = reviewed.copy(quote = "Different later wording"))
        val next = VisitRules.snapshot(visit.copy(reports = listOf(report)), listOf(changed))
        assertEquals(1, report.version); assertEquals(2, next.version)
        assertEquals(reviewed.quote, report.records.single().fact.quote)
        assertEquals(listOf("My first question?", "My second question?"), report.questions.map { it.text })
    }

    @Test fun interruptedProcessingIsRetryableAndOriginalIsNotLost() {
        val audio = source.copy(kind = SourceKind.VISIT_AUDIO, status = Processing.PROCESSING, originalText = "Original literal wording")
        val recovered = VisitRules.recover(Visit(sources = listOf(audio)))
        assertEquals(Processing.FAILED, recovered.sources.single().status)
        assertEquals("Original literal wording", recovered.sources.single().originalText)
    }

    @Test fun emptyAllergyRecordIsNotInterpretedAsNoAllergy() {
        val store = HealthStore()
        assertEquals(KnowledgeState.NOT_RECORDED, store.settings.categoryStates[Category.ALLERGY.name] ?: KnowledgeState.NOT_RECORDED)
        assertTrue(store.profile.isEmpty())
    }

    @Test fun reviewingUnclearWordingNeverConvertsItToCertaintyOrPromotesIt() {
        val unclear = fact().copy(uncertain = true)
        val v = Visit(sources = listOf(source), facts = listOf(unclear))
        val reviewed = VisitRules.review(v, unclear.id, Review.PATIENT_REVIEWED, doctorChecked = true)
        assertTrue(reviewed.facts.single().uncertain)
        assertEquals(1, reviewed.unresolved.size)
        assertTrue(VisitRules.promote(HealthStore(visits = listOf(v)), reviewed, setOf(unclear.id)).profile.isEmpty())
    }

    @Test fun explicitProfileCorrectionRetainsHistoryAndDoesNotDuplicateOnRetry() {
        val old = ProfileEntry(fact = fact(review = Review.PATIENT_REVIEWED))
        val corrected = old.fact.copy(id = "corrected", sourceId = source.id, quote = "Penicillin caused hives; reaction reviewed.")
        val v = Visit(sources = listOf(source), facts = listOf(corrected), replacingProfileId = old.id)
        val store = HealthStore(visits = listOf(v), profile = listOf(old))
        val saved = VisitRules.promote(store, v, setOf(corrected.id))
        assertEquals(ProfileStatus.HISTORICAL, saved.profile.first().status)
        assertEquals(old.id, saved.profile.last().previousId)
        assertEquals(saved, VisitRules.promote(saved, v, setOf(corrected.id)))
    }

    @Test fun reportExcludesSupersededFactsButRetainsOriginalSources() {
        val old = fact().copy(category = Category.SYMPTOM, superseded = true)
        val corrected = old.copy(id = "new", superseded = false, quote = "New reviewed wording")
        val v = Visit(sources = listOf(source), facts = listOf(old, corrected))
        val report = VisitRules.snapshot(v, emptyList())
        assertEquals(listOf(corrected), report.symptoms)
        assertEquals(listOf(source.text), report.originalWords)
    }
}
