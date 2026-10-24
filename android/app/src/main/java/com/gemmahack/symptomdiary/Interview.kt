package com.gemmahack.symptomdiary

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// Mirrors python/extraction.py and python/interview.py. Keep the two in sync.

object JsonExtract {
    private val FENCE = Regex("```(?:json)?", RegexOption.IGNORE_CASE)
    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    /** First balanced {...} in the model output, or null. */
    fun parseObject(raw: String): JSONObject? {
        val text = FENCE.replace(raw, "")
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
            } else when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        return runCatching { JSONObject(candidate) }.getOrNull()
                            ?: runCatching { JSONObject(TRAILING_COMMA.replace(candidate, "$1")) }.getOrNull()
                    }
                }
            }
        }
        return null
    }
}

data class ExtractMeta(val valid: Boolean, val attempts: Int, val seconds: Double, val raw: String)

class Extractor(private val gemma: GemmaEngine, private val assets: AppAssets) {
    suspend fun extract(
        text: String, question: String? = null, known: String? = null, lang: String = detectLang(text), retries: Int = 1,
    ): Pair<SymptomRecord, ExtractMeta> {
        val context = if (question == null) "" else
            fill(assets.prompt("followup_context"), "question" to question, "known" to (known ?: "{}"))
        // One prompt per language (extract.zh.txt / extract.en.txt), so answers never switch language.
        val template = runCatching { assets.prompt("extract.$lang") }.getOrElse { assets.prompt("extract") }
        val prompt = fill(template, "context" to context, "text" to text.replace('"', '\''))
        var seconds = 0.0
        var raw = ""
        for (attempt in 0..retries) {
            val p = if (attempt == 0) prompt else
                "$prompt\n(Your previous output was not valid JSON. Output only the JSON object.)\nJSON:"
            val out = gemma.generate(p)
            seconds += out.seconds
            raw = out.text
            val obj = JsonExtract.parseObject(out.text) ?: continue
            val record = SymptomRecord.fromJson(obj).withoutGenericCharacter().guardSeverity(text)
            return record to ExtractMeta(true, attempt + 1, seconds, raw)
        }
        return SymptomRecord() to ExtractMeta(false, retries + 1, seconds, raw)
    }
}

object Safety {
    private val ZH_NEG = listOf("没有", "没", "不", "无")
    private val EN_NEG = listOf("no ", "not ", "never ", "without ", "denies ")

    /** keyword appears at least once without a negation right before it. */
    private fun affirmed(text: String, keyword: String): Boolean {
        val t = text.lowercase()
        val k = keyword.lowercase()
        var from = 0
        while (true) {
            val i = t.indexOf(k, from)
            if (i < 0) return false
            val zhPrefix = t.substring(maxOf(0, i - 2), i)
            val enPrefix = t.substring(maxOf(0, i - 10), i)
            if (ZH_NEG.none { it in zhPrefix } && EN_NEG.none { it in enPrefix }) return true
            from = i + 1
        }
    }

    fun redFlags(text: String, k: Knowledge): List<RedFlag> = k.redFlags.filter { f ->
        when {
            f.anyOf != null -> f.anyOf.any { affirmed(text, it) }
            f.allOf != null -> f.allOf.all { group -> group.any { affirmed(text, it) } }
            else -> false
        }
    }

    fun matchCondition(record: SymptomRecord, raw: String, k: Knowledge): Condition? {
        for (blob in listOf(record.symptom ?: "", record.location ?: "", raw)) {
            val low = blob.lowercase()
            k.conditions.firstOrNull { c -> c.match.any { it.lowercase() in low } }?.let { return it }
        }
        return null
    }
}

data class Question(val slot: String, val text: String, val options: List<String>, val multi: Boolean)

data class StepResult(val newRedFlags: List<String>, val question: Question?)

/** The model extracts and phrases; this class decides what to ask and when to stop. */
class Interview(
    private val extractor: Extractor,
    private val gemma: GemmaEngine,
    private val assets: AppAssets,
    /** The app's language ("zh" or "en"). Questions, options and phrasing all follow it. */
    val lang: String,
    /** Rewording each follow-up with Gemma costs one extra model call per question; templates read fine. */
    private val rephrase: Boolean = false,
) {
    private val k = assets.knowledge
    private val sep = if (lang == "zh") "、" else ", "
    var record = SymptomRecord()
        private set
    val texts = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val flags = mutableListOf<RedFlag>()
    var condition: Condition? = null
        private set
    var current: Question? = null
        private set
    var lastSeconds = 0.0
        private set

    val started: Boolean
        get() = texts.isNotEmpty()

    suspend fun start(text: String): StepResult {
        takeText(text, null)
        return step()
    }

    /** Chip answers fill the slot directly; free text goes through Gemma. Both null = skip. */
    suspend fun answer(text: String?, options: List<String>?): StepResult {
        val q = current
        if (!options.isNullOrEmpty() && q != null) {
            texts += options.joinToString(sep)
            val picked = options.filter { norm(it) !in k.noneAnswers }
            record = record.withSlot(q.slot, picked, sep)
        }
        if (!text.isNullOrBlank()) takeText(text, q?.text)
        return step()
    }

    fun entry(): DiaryEntry = DiaryEntry(
        id = UUID.randomUUID().toString().replace("-", "").take(12),
        createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")),
        lang = lang,
        rawText = texts.joinToString(" / "),
        record = record,
        condition = condition?.id,
        redFlags = flags.map { it.id },
        followups = asked.size,
    )

    private suspend fun takeText(text: String, question: String?) {
        texts += text.trim()
        val (new, meta) = extractor.extract(text, question, if (question != null) record.knownSummary() else null, lang)
        lastSeconds = meta.seconds
        record = record.merge(new)
    }

    private suspend fun step(): StepResult {
        val blob = texts.joinToString(" ") + " " + record.allText()
        val known = flags.map { it.id }.toSet()
        val newFlags = Safety.redFlags(blob, k).filter { it.id !in known }
        flags += newFlags
        if (condition == null) condition = Safety.matchCondition(record, texts.joinToString(" "), k)
        current = nextQuestion()
        return StepResult(newFlags.map { it.message[lang] ?: it.message.values.first() }, current)
    }

    private suspend fun nextQuestion(): Question? {
        if (asked.size >= k.maxFollowups) return null
        for (slot in k.slotOrder) {
            if (slot in asked || !record.isEmpty(slot)) continue
            val spec = k.slotQuestions[slot] ?: continue
            var text = spec.text[lang] ?: continue
            var options = spec.options[lang] ?: emptyList()
            var multi = spec.multi
            val c = condition
            if (slot == "associated" && c != null) {
                text = c.associatedQuestion[lang] ?: text
                options = c.checklist[lang] ?: options
                multi = true
            }
            if (rephrase && slot != "severity_0_10") text = rephraseQuestion(text)
            asked += slot
            return Question(slot, text, options, multi)
        }
        return null
    }

    private suspend fun rephraseQuestion(question: String): String {
        val prompt = fill(assets.prompt("rephrase"), "lang_name" to langName(lang),
            "known" to record.knownSummary(), "question" to question)
        val out = runCatching { gemma.generate(prompt) }.getOrNull() ?: return question
        val text = out.text.trim().trim('"').trim()
        return if (text.isNotEmpty() && text.length <= 140 && '{' !in text) text else question
    }
}
