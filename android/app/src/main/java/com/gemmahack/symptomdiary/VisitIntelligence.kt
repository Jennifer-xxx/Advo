package com.gemmahack.symptomdiary

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ModelContractException : Exception("The local model returned details that could not be verified. The original is saved. Retry or edit manually.")
data class Extraction(val facts: List<Fact>, val nextQuestion: String?, val suggestions: List<String>)

/** Reject a whole invalid response; never coerce categories, source IDs or unsupported numbers. */
object ExtractionContract {
    fun parse(raw: String, source: Source): Extraction {
        try {
            val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val root = JsonParser.parseString(text).asJsonObject
            require(root.keySet().all { it in setOf("facts", "next_question", "suggested_questions") })
            val array = root.getAsJsonArray("facts") ?: throw ModelContractException()
            require(array.size() <= 40)
            val facts = array.map { node ->
                val o = node.asJsonObject
                require(o.keySet().all { it in setOf("category", "quote", "source_id", "name", "dose", "unit", "frequency", "reaction", "timing", "uncertain") })
                val quote = string(o, "quote")!!
                val category = Category.valueOf(string(o, "category")!!)
                require(string(o, "source_id") == source.id)
                require(quote.isNotBlank() && quote.length <= 3000 && source.text.contains(quote))
                val fields = listOf("name", "dose", "unit", "frequency", "reaction", "timing").associateWith { field ->
                    string(o, field, optional = true)?.also { value ->
                        require(value.isNotBlank() && quote.contains(value, ignoreCase = true))
                        if (field == "dose") require(Regex("(?<![0-9.])" + Regex.escape(value) + "(?![0-9.])").containsMatchIn(quote))
                        if (field in setOf("name", "unit")) require(Regex("(?<![\\p{L}])" + Regex.escape(value) + "(?![\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(quote))
                    }
                }
                val uncertainty = o.get("uncertain") ?: throw ModelContractException()
                require(uncertainty.isJsonPrimitive && uncertainty.asJsonPrimitive.isBoolean)
                Fact(id = stableId(source.id, category.name, quote), category = category, label = category.title,
                    quote = quote, sourceId = source.id, name = fields["name"], dose = fields["dose"], unit = fields["unit"],
                    frequency = fields["frequency"], reaction = fields["reaction"], timing = fields["timing"], uncertain = uncertainty.asBoolean)
            }.distinctBy { it.id }
            val next = string(root, "next_question", optional = true)?.also { require(it.length <= 220 && it.endsWith("?")) }
            val suggested = root.getAsJsonArray("suggested_questions") ?: throw ModelContractException()
            require(suggested.size() <= 3)
            val suggestions = suggested.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { question -> require(question.length <= 220 && question.endsWith("?")) } }
            return Extraction(facts, next, suggestions)
        } catch (_: Exception) { throw ModelContractException() }
    }

    private fun string(o: JsonObject, key: String, optional: Boolean = false): String? {
        val value = o.get(key)
        if (value == null || value.isJsonNull) { require(optional); return null }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
}

class VisitIntelligence(context: Context, private val engine: GemmaEngine) {
    private val assets = context.assets
    fun prompt(name: String): String = assets.open("prompts/withyou/$name.txt").bufferedReader().use { it.readText() }
    val system: String get() = prompt("safety")

    suspend fun extract(visit: Visit, source: Source, task: String): Pair<Extraction, Generation> {
        val known = JsonArray().apply { visit.currentFacts.forEach { fact -> add(JsonObject().apply {
            addProperty("category", fact.category.name); addProperty("quote", fact.quote); addProperty("review", fact.status.name)
        }) } }
        val input = JsonObject().apply {
            addProperty("task", task); addProperty("source_id", source.id); addProperty("source_kind", source.kind.name)
            addProperty("text", source.text); add("known", known)
            add("skip_questions", StoreCodec.gson.toJsonTree(visit.skippedQuestions))
        }.toString()
        var seconds = 0.0
        repeat(2) { attempt ->
            val result = engine.generate(system + "\n" + prompt("prepare-v2"),
                input + if (attempt == 1) "\nReturn only the required JSON. Every quoted field must occur verbatim in text." else "")
            seconds += result.seconds
            val parsed = runCatching { ExtractionContract.parse(result.text, source) }.getOrNull()
            if (parsed != null) return parsed to Generation(task = task, model = engine.modelName,
                promptVersion = "prepare-v2", sourceIds = listOf(source.id), seconds = seconds)
        }
        throw ModelContractException()
    }

    suspend fun transcribe(bytes: ByteArray, language: String): GemmaEngine.Result = engine.audio(bytes, system,
        "Transcribe the audio in its original language. Expected language: $language; it may be mixed. " +
            "Output only the spoken words, verbatim. Use [unclear] for indistinct words. Do not infer speaker identity, medicines, numbers or instructions.")

    suspend fun readPaper(bytes: ByteArray): GemmaEngine.Result = engine.image(bytes, system,
        "Read this document literally. Preserve wording, line breaks, medication names, doses, units, negation and dates. " +
            "Use [unclear] where illegible. Do not summarize, translate or infer missing text. Output only the document transcription.")

    suspend fun plainLanguage(visit: Visit): GemmaEngine.Result {
        val facts = visit.currentFacts.filter { it.category in setOf(Category.DISCUSSION, Category.MEDICINE_CHANGE, Category.ACTION) }
        check(facts.isNotEmpty()) { "Add or extract visit details first." }
        return engine.generate(system, prompt("follow-through-v1") + "\n" + StoreCodec.gson.toJson(facts)).also { result ->
            val allowed = Regex("\\d+(?:[./:]\\d+)*").findAll(facts.joinToString(" ") { it.quote }).map { it.value }.toSet()
            require(Regex("\\d+(?:[./:]\\d+)*").findAll(result.text).all { it.value in allowed })
            facts.flatMap { listOfNotNull(it.name, it.dose, it.unit, it.frequency, it.timing) }
                .forEach { require(result.text.contains(it, true)) }
        }
    }

    suspend fun translate(fact: Fact): GemmaEngine.Result {
        val result = engine.generate(system,
            "Translate this source wording to clear English. Preserve uncertainty and negation. Keep every medicine/substance name in its original spelling (you may add a translation in parentheses), every dose, unit and time condition. Do not add advice. " +
                "Return only JSON with a single string key translation. Source: " + StoreCodec.gson.toJson(fact.quote))
        val objectValue = JsonParser.parseString(result.text.trim().removePrefix("```json").removeSuffix("```").trim()).asJsonObject
        require(objectValue.keySet() == setOf("translation") && objectValue["translation"].asJsonPrimitive.isString)
        val translation = objectValue["translation"].asString
        val digits = Regex("\\d+(?:[./:]\\d+)*")
        require(digits.findAll(translation).map { it.value }.toList().sorted() == digits.findAll(fact.quote).map { it.value }.toList().sorted())
        listOfNotNull(fact.name, fact.dose, fact.unit, fact.frequency, fact.timing).forEach { require(translation.contains(it, true)) }
        val negated = Regex("\\b(no|not|never|without|denies|sin|niega)\\b|没有|未知|不确定|不要|不能|无", RegexOption.IGNORE_CASE).containsMatchIn(fact.quote)
        if (negated) require(Regex("\\b(no|not|never|without|unknown|uncertain|unclear|denies)\\b", RegexOption.IGNORE_CASE).containsMatchIn(translation))
        require(translation.isNotBlank() && translation.length <= 6000)
        return result.copy(text = translation)
    }
}
