package com.gemmahack.symptomdiary

import org.junit.Assert.*
import org.junit.Test

class BriefSummaryTest {
    @Test fun equivalentSeverityFormattingDoesNotRejectAnOtherwiseGroundedSummary() {
        SummaryGrounding.verify("Headache severity is 5 out of 10.", "Headache 5/10.")
        SummaryGrounding.verify("Severity is 5/10.", "About five out of ten.")
    }
    @Test fun decimalAndWordNumberFormsRemainGrounded() {
        SummaryGrounding.verify("Symptoms lasted 21 days; dose 0.5 mg.", "Twenty-one days. Dose .5 mg.")
        SummaryGrounding.verify("1000 mg", "1,000 milligrams")
    }
    @Test(expected = BriefException::class) fun inventedSeverityStillFails() {
        SummaryGrounding.verify("Pain 8 out of 10.", "Pain 5/10.")
    }
    @Test(expected = BriefException::class) fun inventedSeverityScaleStillFails() {
        SummaryGrounding.verify("Pain 4 out of 10.", "The pain is four.")
    }
    @Test(expected = BriefException::class) fun changedDoseUnitsStillFail() {
        SummaryGrounding.verify("400 mcg daily.", "400 mg daily.")
    }
    @Test(expected = BriefException::class) fun unitConversionIsNotPerformedImplicitly() {
        SummaryGrounding.verify("1 g daily", "1000 mg daily")
    }
    @Test(expected = BriefException::class) fun percentageCannotReplaceMedicineUnit() {
        SummaryGrounding.verify("5%", "5 mg")
    }
    @Test fun ordinaryNarrativeWithoutNewNumbersIsAccepted() {
        SummaryGrounding.verify("Intermittent headaches; timing remains unclear. No fever reported.", "Headaches sometimes. Timing unclear. No fever.")
    }
    @Test fun longHistoryBatchesPreserveEveryCharacterAndDate() {
        val text = "This is a dated symptom sentence. ".repeat(2000)
        val passage = BriefInput.Passage("2026-10-24", text)
        val batches = BriefInput.batches(listOf(passage), maxChars = 2000)
        assertTrue(batches.size > 1)
        assertEquals(text, batches.flatten().joinToString("") { it.text })
        assertTrue(batches.all { batch -> batch.sumOf { it.text.length + 100 } <= 2000 })
        assertTrue(batches.flatten().all { it.date == passage.date })
    }
    @Test fun briefPayloadExcludesDuplicatedFactsAndAssistantQuestions() {
        val source = Source(id = "words", kind = SourceKind.PATIENT_VOICE, text = "A headache today.")
        val audio = source.copy(id = "segment", parentId = source.id, text = "A headache today.")
        val fact = Fact(id = "fact", category = Category.SYMPTOM, label = "Symptoms", quote = source.text, sourceId = source.id)
        val record = Visit(kind = RecordKind.SYMPTOM, sources = listOf(source, audio), facts = listOf(fact))
        val passages = BriefInput.passages(listOf(record))
        assertEquals(1, passages.size)
        assertEquals(source.text, passages.single().text)
        val payload = StoreCodec.gson.toJson(passages)
        assertFalse(payload.contains("sourceId")); assertFalse(payload.contains("draft_facts"))
    }
    @Test fun restartNeverLeavesAProcessingSpinnerForAJobThatNoLongerExists() {
        val source = Source(kind = SourceKind.PATIENT_VOICE, text = "My full dictation.", summaryStatus = Processing.PROCESSING)
        val v = Visit(sources = listOf(source), briefStatus = Processing.PROCESSING)
        val recovered = VisitRules.recover(v)
        assertEquals(Processing.FAILED, recovered.briefStatus)
        assertEquals(Processing.FAILED, recovered.sources.single().summaryStatus)
        assertEquals(source.text, recovered.sources.single().text)
        assertTrue(recovered.briefError!!.contains("interrupted"))
    }
}
