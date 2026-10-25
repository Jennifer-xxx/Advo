package com.gemmahack.symptomdiary

import org.junit.Assert.*
import org.junit.Test

class TalkAndRecordsTest {
    private fun conversation(): Visit {
        val talk = TalkSession(id = "talk", sourceId = "dictation")
        return Visit(kind = RecordKind.SYMPTOM, talks = listOf(talk),
            sources = listOf(Source(id = talk.sourceId, kind = SourceKind.PATIENT_VOICE)))
    }
    private fun patient(id: String, text: String) = TalkTurn(id = id, role = TalkRole.PATIENT, text = text)

    @Test fun wholeConversationCreatesOnePatientDictation() {
        var record = TalkRules.append(conversation(), "talk", patient("one", "Headaches since Monday."))
        record = TalkRules.append(record, "talk", TalkTurn(id = "reply", role = TalkRole.ADVO, text = "How severe are they?"))
        record = TalkRules.append(record, "talk", patient("two", "I am not sure how severe."))
        assertEquals(1, record.sources.size)
        assertEquals("Headaches since Monday.\n\nI am not sure how severe.", record.sources.single().text)
        assertFalse(record.sources.single().text.contains("How severe"))
        assertEquals(3, record.talks.single().turns.size)
    }

    @Test fun lateDuplicateSpeechCallbackDoesNotDuplicateTheDictation() {
        val turn = patient("capture", "No fever.")
        val once = TalkRules.append(conversation(), "talk", turn)
        assertEquals(once, TalkRules.append(once, "talk", turn))
        assertEquals("No fever.", once.sources.single().originalText)
    }

    @Test fun finishIsIdempotentAndRejectsLateAssistantOrPatientCallbacks() {
        val first = TalkRules.append(conversation(), "talk", patient("capture", "Dose is unclear."))
        val ended = TalkRules.finish(first, "talk")
        assertEquals(ended, TalkRules.finish(ended, "talk"))
        assertEquals(ended, TalkRules.append(ended, "talk", patient("late", "Anything else")))
        assertNotNull(ended.talks.single().finishedAt)
    }

    @Test fun repeatedActualUtterancesRemainWhenCaptureIdsDiffer() {
        var record = TalkRules.append(conversation(), "talk", patient("one", "I don't know."))
        record = TalkRules.append(record, "talk", patient("two", "I don't know."))
        assertEquals(2, record.talks.single().turns.size)
    }

    @Test fun pauseAndResumeInvalidateOldCallbacksWithoutAcceptingNewOnes() {
        val guard = TalkGuard()
        val first = guard.start()
        assertTrue(guard.accepts(first))
        guard.stop()
        assertFalse(guard.accepts(first))
        val second = guard.start()
        assertFalse(guard.accepts(first))
        assertTrue(guard.accepts(second))
    }

    @Test fun incidentalWordsDoNotEndTheConversation() {
        assertTrue(TalkRules.isDoneRequest("That's all."))
        assertFalse(TalkRules.isDoneRequest("I am done with my medicine but the pain is still there."))
    }

    @Test fun symptomLibraryNeverContainsVisitsOrProfileMaintenance() {
        val symptom = TalkRules.append(conversation(), "talk", patient("one", "A cough today."))
        val visit = Visit(kind = RecordKind.VISIT, title = "Doctor visit")
        val profile = Visit(kind = RecordKind.PROFILE, title = "Health essentials updated")
        val store = HealthStore(visits = listOf(symptom, visit, profile))
        assertEquals(listOf(symptom), RecordRules.symptoms(store))
        assertEquals(listOf(visit), RecordRules.visits(store))
    }

    @Test fun selectingSymptomHistoryDoesNotChangeOrConvertOriginalEntries() {
        val first = TalkRules.append(conversation(), "talk", patient("one", "Cough on Monday."))
        val second = first.copy(id = "second", sources = listOf(Source(kind = SourceKind.PATIENT_TEXT, text = "Cough worse on Friday.")), talks = emptyList())
        val store = HealthStore(visits = listOf(first, second))
        val selected = RecordRules.selected(store, listOf(first.id, second.id))
        val appointment = Visit(kind = RecordKind.VISIT, relatedRecordIds = selected.map { it.id })
        val context = RecordRules.context(appointment, selected)
        assertEquals(2, context.sources.size)
        assertEquals(RecordKind.SYMPTOM, store.visits.first().kind)
        assertEquals("Cough on Monday.", store.visits.first().sources.single().text)
        val snapshot = VisitRules.snapshot(context, emptyList())
        assertTrue(snapshot.originalWords.contains("Cough on Monday."))
        assertTrue(snapshot.originalWords.contains("Cough worse on Friday."))
    }

    @Test(expected = IllegalArgumentException::class) fun actualVisitsCannotBeSelectedAsSymptomHistory() {
        val appointment = Visit(kind = RecordKind.VISIT)
        RecordRules.selected(HealthStore(visits = listOf(appointment)), listOf(appointment.id))
    }

    @Test fun conversationAndSeparationSurviveSerialization() {
        val record = TalkRules.append(conversation(), "talk", patient("one", "Maybe headaches. No fever."))
        val state = HealthStore(visits = listOf(record))
        assertEquals(state, StoreCodec.decode(StoreCodec.encode(state)))
    }

    @Test fun oldRecordsMigrateWithoutInventingTalkOrLosingOriginals() {
        val source = Source(kind = SourceKind.PATIENT_TEXT, text = "Original symptom words")
        val root = StoreCodec.gson.toJsonTree(HealthStore(visits = listOf(Visit(sources = listOf(source))))).asJsonObject
        val record = root.getAsJsonArray("visits")[0].asJsonObject
        listOf("kind", "talks", "relatedRecordIds", "briefSummary", "briefSourceFingerprint").forEach(record::remove)
        val migrated = StoreCodec.decode(root.toString().toByteArray()).visits.single()
        assertEquals(RecordKind.SYMPTOM, migrated.kind)
        assertTrue(migrated.talks.isEmpty())
        assertEquals(source.text, migrated.sources.single().text)
    }
}
