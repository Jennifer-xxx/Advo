package com.gemmahack.symptomdiary

import org.junit.Assert.*
import org.junit.Test

class ExtractionContractTest {
    private val source = Source(id = "s1", kind = SourceKind.DOCUMENT, text = "No fever. Medicine dose unclear. Follow up in 2 weeks, possibly.")
    private fun json(quote: String = "Medicine dose unclear.", sourceId: String = "s1", extra: String = "") =
        """{"facts":[{"category":"MEDICINE","quote":"$quote","source_id":"$sourceId","name":null,"dose":null,"unit":null,"frequency":null,"reaction":null,"timing":null,"uncertain":true$extra}],"next_question":null,"suggested_questions":[]}"""

    @Test fun retainsUncertaintyNegationAndUnknownDose() {
        val result = ExtractionContract.parse(json(), source)
        assertTrue(result.facts.single().uncertain)
        assertNull(result.facts.single().dose)
        assertEquals(Review.DRAFT, result.facts.single().status)
        val negative = ExtractionContract.parse(json("No fever."), source)
        assertEquals("No fever.", negative.facts.single().quote)
    }
    @Test(expected = ModelContractException::class) fun rejectsInventedDose() {
        ExtractionContract.parse(json("Take 10 mg daily."), source)
    }
    @Test(expected = ModelContractException::class) fun rejectsFabricatedSource() {
        ExtractionContract.parse(json(sourceId = "imaginary"), source)
    }
    @Test(expected = ModelContractException::class) fun rejectsModelAttemptToConfirmFact() {
        ExtractionContract.parse(json(extra = ",\"status\":\"PATIENT_REVIEWED\""), source)
    }
    @Test(expected = ModelContractException::class) fun rejectsOptionalFieldsNotInQuote() {
        ExtractionContract.parse(json().replace("\"dose\":null", "\"dose\":\"10\""), source)
    }
    @Test(expected = ModelContractException::class) fun rejectsWrongFieldTypes() {
        ExtractionContract.parse(json().replace("\"uncertain\":true", "\"uncertain\":\"false\""), source)
    }
    @Test(expected = ModelContractException::class) fun rejectsUnexpectedRootCommands() {
        ExtractionContract.parse(json().dropLast(1) + ",\"start_recording\":true}", source)
    }
    @Test(expected = ModelContractException::class) fun rejectsDoseAsSubstringOfLargerNumber() {
        val input = source.copy(text = "Take 50 mg.")
        ExtractionContract.parse(json("Take 50 mg.").replace("\"dose\":null", "\"dose\":\"5\""), input)
    }
    @Test fun structuredSaveRoundTripKeepsProvenanceAndAllStates() {
        val fact = ExtractionContract.parse(json(), source).facts.single()
        val original = HealthStore(visits = listOf(Visit(sources = listOf(source), facts = listOf(fact))))
        assertEquals(original, StoreCodec.decode(StoreCodec.encode(original)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDanglingSourceReferencesInBackup() {
        val fact = ExtractionContract.parse(json(), source).facts.single().copy(sourceId = "missing")
        StoreCodec.validate(HealthStore(visits = listOf(Visit(sources = listOf(source), facts = listOf(fact)))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBackupPathTraversal() {
        StoreCodec.validate(HealthStore(visits = listOf(Visit(sources = listOf(source.copy(blob = "../../private"))))))
    }
}
