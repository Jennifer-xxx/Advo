package com.gemmahack.symptomdiary

import com.google.gson.JsonParser

/** Answers are a separate conversation, never a clinical fact or a doctor's confirmation. */
object ClarificationRules {
    const val UNKNOWN = "That is not clear in the saved parts of this visit. Please ask your doctor to explain."
    data class Passage(val source_id: String, val text: String)
    data class Answer(val text: String, val evidence: List<AnswerEvidence>)

    // Use the latest processed source wording, not another AI summary or an older symptom diary.
    fun passages(visit: Visit, limit: Int = 9000): List<Passage> {
        var remaining = limit
        return visit.sources.asReversed().filter { it.text.isNotBlank() && it.parentId == null }.mapNotNull {
            // Never cut through a medicine name, number or negation to fit the context.
            if (it.text.length > remaining) null else {
                val text = it.text
                remaining -= text.length
                Passage(it.id, text)
            }
        }.asReversed()
    }

    fun parse(raw: String, passages: List<Passage>): Answer {
        try {
            val root = JsonParser.parseString(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).asJsonObject
            require(root.keySet() == setOf("answer", "evidence"))
            require(root["answer"].isJsonPrimitive && root["answer"].asJsonPrimitive.isString)
            val evidence = root.getAsJsonArray("evidence")
            require(evidence.size() <= 4)
            val quotes = evidence.map { node ->
                val item = node.asJsonObject
                require(item.keySet() == setOf("source_id", "quote"))
                require(item["source_id"].asJsonPrimitive.isString && item["quote"].asJsonPrimitive.isString)
                val id = item["source_id"].asString
                val quote = item["quote"].asString
                require(quote.isNotBlank() && quote.length <= 1600 && passages.any { it.source_id == id && it.text.contains(quote) })
                AnswerEvidence(id, quote)
            }
            if (quotes.isEmpty()) return Answer(UNKNOWN, emptyList())
            val answer = root["answer"].asString
            require(answer.isNotBlank() && answer.length <= 1800)
            SummaryGrounding.verify(answer, quotes.joinToString("\n") { it.quote })
            return Answer(answer, quotes)
        } catch (_: Exception) { throw ModelContractException() }
    }
}

object CarePlanRules {
    val profileCategories = setOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY)
    fun actions(visit: Visit): List<Fact> = visit.currentFacts.filter { it.category == Category.ACTION }
    fun updates(store: HealthStore, visit: Visit): List<Fact> = visit.currentFacts.filter {
        it.category in profileCategories && store.profile.none { entry -> entry.fact.id == it.id }
    }
    fun complete(visit: Visit, factId: String, done: Boolean): Visit {
        val fact = actions(visit).first { it.id == factId }
        require(!done || !fact.needsReview) { "Review this action first." }
        return visit.copy(completedActionIds = (visit.completedActionIds.filter { it != factId } + if (done) listOf(factId) else emptyList()).distinct())
    }
    fun confirmProfileUpdate(store: HealthStore, visit: Visit, factId: String): HealthStore {
        val fact = visit.currentFacts.first { it.id == factId }
        require(fact.category in profileCategories && !fact.uncertain && fact.status != Review.UNRESOLVED) {
            "Resolve unclear or conflicting wording before adding it as current information."
        }
        val reviewed = VisitRules.review(visit, factId, Review.PATIENT_REVIEWED)
        return VisitRules.promote(store.copy(visits = store.visits.map { if (it.id == visit.id) reviewed else it }), reviewed, setOf(factId))
    }
}
