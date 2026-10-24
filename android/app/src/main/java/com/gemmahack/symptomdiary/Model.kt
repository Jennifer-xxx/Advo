package com.gemmahack.symptomdiary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// ---------- text helpers (same rules as python/common.py) ----------

private val PUNCT_OR_SPACE = Regex("[\\p{P}\\p{S}\\s]")
private val CJK = Regex("[\\u4e00-\\u9fff]")
private val NULLISH = setOf("null", "none", "n/a", "na", "unknown", "无", "未知", "未提及", "不详")
private val NUMBER_IN_TEXT = Regex(
    "\\d|[零一二两三四五六七八九十]\\s*分|\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten)\\b", RegexOption.IGNORE_CASE)
private val GENERIC_CHARACTER = setOf("疼", "痛", "疼痛", "很疼", "很痛", "特别疼", "特别痛", "有点疼", "有点痛", "难受", "不舒服",
    "hurt", "hurts", "hurting", "pain", "painful", "sore", "uncomfortable").map { norm(it) }.toSet()

fun norm(s: String): String = PUNCT_OR_SPACE.replace(Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(), "")

fun detectLang(text: String): String = if (CJK.containsMatchIn(text)) "zh" else "en"

fun langName(lang: String) = if (lang == "zh") "Simplified Chinese" else "English"

fun fill(template: String, vararg values: Pair<String, String>): String =
    values.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

private fun JSONObject.strMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

private fun JSONObject.listMap(): Map<String, List<String>> = keys().asSequence().associateWith { getJSONArray(it).strings() }

// ---------- assets: prompts + knowledge from ../../shared ----------

class AppAssets(context: Context) {
    private val am = context.assets
    fun text(path: String): String = am.open(path).bufferedReader().use { it.readText() }
    fun prompt(name: String): String = text("prompts/$name.txt")
    val knowledge: Knowledge = Knowledge(JSONObject(text("knowledge/symptom_knowledge.json")))
}

data class SlotQuestion(val text: Map<String, String>, val options: Map<String, List<String>>, val multi: Boolean)

data class Condition(
    val id: String,
    val label: Map<String, String>,
    val match: List<String>,
    val associatedQuestion: Map<String, String>,
    val checklist: Map<String, List<String>>,
)

data class RedFlag(val id: String, val anyOf: List<String>?, val allOf: List<List<String>>?, val message: Map<String, String>)

class Knowledge(json: JSONObject) {
    val maxFollowups: Int = json.getInt("max_followups")
    val slotOrder: List<String> = json.getJSONArray("slot_order").strings()
    val noneAnswers: Set<String> = json.getJSONArray("none_answers").strings().map(::norm).toSet()

    val slotQuestions: Map<String, SlotQuestion> = json.getJSONObject("slot_questions").let { o ->
        o.keys().asSequence().associateWith { slot ->
            val q = o.getJSONObject(slot)
            SlotQuestion(
                text = mapOf("zh" to q.getString("zh"), "en" to q.getString("en")),
                options = q.getJSONObject("options").listMap(),
                multi = q.getBoolean("multi"),
            )
        }
    }

    val conditions: List<Condition> = json.getJSONArray("conditions").let { arr ->
        (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Condition(
                id = c.getString("id"),
                label = c.getJSONObject("label").strMap(),
                match = c.getJSONArray("match").strings(),
                associatedQuestion = c.getJSONObject("associated_question").strMap(),
                checklist = c.getJSONObject("associated_checklist").listMap(),
            )
        }
    }

    val redFlags: List<RedFlag> = json.getJSONArray("red_flags").let { arr ->
        (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            RedFlag(
                id = f.getString("id"),
                anyOf = f.optJSONArray("any_of")?.strings(),
                allOf = f.optJSONArray("all_of")?.let { g -> (0 until g.length()).map { g.getJSONArray(it).strings() } },
                message = f.getJSONObject("message").strMap(),
            )
        }
    }
}

// ---------- the record Gemma fills in ----------

data class Medication(val name: String, val effect: String?)

data class SymptomRecord(
    val symptom: String? = null,
    val location: String? = null,
    val onset: String? = null,
    val duration: String? = null,
    val character: List<String> = emptyList(),
    val severity: Int? = null,
    val timing: String? = null,
    val aggravating: List<String> = emptyList(),
    val relieving: List<String> = emptyList(),
    val associated: List<String> = emptyList(),
    val medication: List<Medication> = emptyList(),
    val patientQuote: String? = null,
) {
    fun isEmpty(slot: String): Boolean = when (slot) {
        "symptom" -> symptom == null
        "location" -> location == null
        "onset" -> onset == null
        "duration" -> duration == null
        "character" -> character.isEmpty()
        "severity_0_10" -> severity == null
        "timing" -> timing == null
        "aggravating" -> aggravating.isEmpty()
        "relieving" -> relieving.isEmpty()
        "associated" -> associated.isEmpty()
        "medication" -> medication.isEmpty()
        else -> true
    }

    /** Fill gaps from [other]; lists are unioned; earlier single values win. */
    fun merge(other: SymptomRecord) = SymptomRecord(
        symptom = symptom ?: other.symptom,
        location = location ?: other.location,
        onset = onset ?: other.onset,
        duration = duration ?: other.duration,
        character = union(character, other.character),
        severity = severity ?: other.severity,
        timing = timing ?: other.timing,
        aggravating = union(aggravating, other.aggravating),
        relieving = union(relieving, other.relieving),
        associated = union(associated, other.associated),
        medication = medication + other.medication.filter { m -> medication.none { norm(it.name) == norm(m.name) } },
        patientQuote = patientQuote ?: other.patientQuote,
    )

    /** Apply chip answers directly to one slot (no model call). */
    fun withSlot(slot: String, picked: List<String>, sep: String = "、"): SymptomRecord {
        if (picked.isEmpty()) return this
        val joined = picked.joinToString(sep)
        return when (slot) {
            "severity_0_10" -> copy(severity = picked.first().toIntOrNull() ?: severity)
            "character" -> copy(character = union(character, picked))
            "aggravating" -> copy(aggravating = union(aggravating, picked))
            "relieving" -> copy(relieving = union(relieving, picked))
            "associated" -> copy(associated = union(associated, picked))
            "medication" -> merge(SymptomRecord(medication = picked.map { Medication(it, null) }))
            "location" -> copy(location = location ?: joined)
            "onset" -> copy(onset = onset ?: joined)
            "duration" -> copy(duration = duration ?: joined)
            "timing" -> copy(timing = timing ?: joined)
            else -> this
        }
    }

    /** Safety rule: keep a 0-10 score only if the patient's words contain a number (mirrors python guard_severity). */
    fun guardSeverity(text: String): SymptomRecord =
        if (severity != null && !NUMBER_IN_TEXT.containsMatchIn(text)) copy(severity = null) else this

    /** Drop "it hurts"-type words from character (mirrors python drop_generic_character). */
    fun withoutGenericCharacter(): SymptomRecord = copy(character = character.filter { norm(it) !in GENERIC_CHARACTER })

    fun allText(): String = listOfNotNull(symptom, location, onset, duration, timing, patientQuote)
        .plus(character + aggravating + relieving + associated)
        .plus(medication.map { it.name + " " + (it.effect ?: "") })
        .joinToString(" ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("symptom", symptom ?: JSONObject.NULL)
        put("location", location ?: JSONObject.NULL)
        put("onset", onset ?: JSONObject.NULL)
        put("duration", duration ?: JSONObject.NULL)
        put("character", JSONArray(character))
        put("severity_0_10", severity ?: JSONObject.NULL)
        put("timing", timing ?: JSONObject.NULL)
        put("aggravating", JSONArray(aggravating))
        put("relieving", JSONArray(relieving))
        put("associated", JSONArray(associated))
        put("medication", JSONArray(medication.map { JSONObject().put("name", it.name).put("effect", it.effect ?: JSONObject.NULL) }))
        put("patient_quote", patientQuote ?: JSONObject.NULL)
    }

    /** Non-empty fields only, used as context in follow-up prompts. */
    fun knownSummary(): String {
        val full = toJson()
        val out = JSONObject()
        for (key in full.keys()) {
            val v = full.get(key)
            if (v != JSONObject.NULL && !(v is JSONArray && v.length() == 0)) out.put(key, v)
        }
        return out.toString()
    }

    companion object {
        private fun union(a: List<String>, b: List<String>): List<String> {
            val seen = a.map(::norm).toMutableSet()
            return a + b.filter { seen.add(norm(it)) }
        }

        fun cleanStr(v: Any?): String? {
            if (v == null || v == JSONObject.NULL) return null
            val s = (if (v is JSONArray) cleanList(v).joinToString("，") else v.toString()).trim()
            return if (s.isEmpty() || s.lowercase() in NULLISH) null else s
        }

        fun cleanList(v: Any?): List<String> {
            val raw = when (v) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> (0 until v.length()).map { v.opt(it) }
                else -> listOf(v)
            }
            val seen = mutableSetOf<String>()
            return raw.mapNotNull { cleanStr(it) }.filter { seen.add(norm(it)) }
        }

        fun cleanSeverity(v: Any?): Int? {
            val n = when (v) {
                null, JSONObject.NULL, is Boolean -> return null
                is Number -> v.toDouble()
                else -> Regex("\\d+(\\.\\d+)?").find(v.toString())?.value?.toDouble() ?: return null
            }
            val i = Math.round(n).toInt()
            return if (i in 0..10) i else null
        }

        fun cleanMeds(v: Any?): List<Medication> {
            val raw = when (v) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> (0 until v.length()).map { v.opt(it) }
                else -> listOf(v)
            }
            val seen = mutableSetOf<String>()
            return raw.mapNotNull { item ->
                if (item is JSONObject) cleanStr(item.opt("name"))?.let { Medication(it, cleanStr(item.opt("effect"))) }
                else cleanStr(item)?.let { Medication(it, null) }
            }.filter { seen.add(norm(it.name)) }
        }

        fun fromJson(o: JSONObject) = SymptomRecord(
            symptom = cleanStr(o.opt("symptom")),
            location = cleanStr(o.opt("location")),
            onset = cleanStr(o.opt("onset")),
            duration = cleanStr(o.opt("duration")),
            character = cleanList(o.opt("character")),
            severity = cleanSeverity(o.opt("severity_0_10")),
            timing = cleanStr(o.opt("timing")),
            aggravating = cleanList(o.opt("aggravating")),
            relieving = cleanList(o.opt("relieving")),
            associated = cleanList(o.opt("associated")),
            medication = cleanMeds(o.opt("medication")),
            patientQuote = cleanStr(o.opt("patient_quote")),
        )
    }
}

data class DiaryEntry(
    val id: String,
    val createdAt: String, // local time, "yyyy-MM-ddTHH:mm"
    val lang: String,
    val rawText: String,
    val record: SymptomRecord,
    val condition: String?,
    val redFlags: List<String>,
    val followups: Int,
    val demo: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("created_at", createdAt); put("lang", lang); put("raw_text", rawText)
        put("record", record.toJson()); put("condition", condition ?: JSONObject.NULL)
        put("red_flags", JSONArray(redFlags)); put("followups", followups); put("demo", demo)
    }

    companion object {
        fun fromJson(o: JSONObject) = DiaryEntry(
            id = o.getString("id"),
            createdAt = o.getString("created_at"),
            lang = o.optString("lang", "zh"),
            rawText = o.optString("raw_text", ""),
            record = SymptomRecord.fromJson(o.getJSONObject("record")),
            condition = if (o.isNull("condition")) null else o.optString("condition"),
            redFlags = o.optJSONArray("red_flags")?.strings() ?: emptyList(),
            followups = o.optInt("followups", 0),
            demo = o.optBoolean("demo", false),
        )
    }
}
