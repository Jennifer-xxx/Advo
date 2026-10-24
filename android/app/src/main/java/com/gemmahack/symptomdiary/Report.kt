package com.gemmahack.symptomdiary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Diary entries in one JSON file inside the app's private storage. Nothing leaves the phone. */
class EntryStore(context: Context, private val assets: AppAssets) {
    private val file = File(context.filesDir, "diary.json")

    fun load(): List<DiaryEntry> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { DiaryEntry.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(entries: List<DiaryEntry>) =
        file.writeText(JSONArray(entries.map { it.toJson() }).toString(2))

    fun add(entry: DiaryEntry): List<DiaryEntry> = (load() + entry).also(::save)

    fun clear(): List<DiaryEntry> = emptyList<DiaryEntry>().also(::save)

    /** Replaces the demo entries with the synthetic set for [lang], shifted so the newest one is today. */
    fun loadDemo(lang: String): List<DiaryEntry> {
        val arr = JSONArray(assets.text(if (lang == "en") "demo/demo_entries_en.json" else "demo/demo_entries.json"))
        val demo = (0 until arr.length()).map { DiaryEntry.fromJson(arr.getJSONObject(it)) }
        val newest = demo.maxOf { LocalDateTime.parse(it.createdAt).toLocalDate() }
        val shift = ChronoUnit.DAYS.between(newest, LocalDate.now())
        val shifted = demo.map { e ->
            e.copy(createdAt = LocalDateTime.parse(e.createdAt).plusDays(shift).toString().take(16))
        }
        val kept = load().filter { !it.demo }
        return (kept + shifted).sortedBy { it.createdAt }.also(::save)
    }
}

// ---------- report: aggregation in code, narrative by Gemma, HTML for WebView / PDF ----------
// Mirrors python/report.py.

data class SymptomSummary(
    val name: String,
    val entries: Int,
    val days: Int,
    val severityAvg: Double?,
    val severityMin: Int?,
    val severityMax: Int?,
    val trend: String,
    val severityByDate: List<Pair<LocalDate, Int>>,
    val worse: List<String>,
    val better: List<String>,
    val associated: List<String>,
    val terms: List<String>,
)

data class MedSummary(val name: String, val times: Int, val effects: List<Pair<LocalDate, String>>)

private fun md(d: LocalDate) = "%02d-%02d".format(d.monthValue, d.dayOfMonth)

data class ReportSummary(
    val start: LocalDate,
    val end: LocalDate,
    val entryCount: Int,
    val symptoms: List<SymptomSummary>,
    val meds: List<MedSummary>,
    val quotes: List<Pair<LocalDate, String>>,
    val flags: List<Pair<LocalDate, String>>,
    /** Languages the entries were written in; if any differs from the report language we translate. */
    val sourceLangs: Set<String>,
) {
    /** Free-text items that came from the patient (as opposed to fixed labels). */
    fun translatableTerms(): List<String> = (
        symptoms.flatMap { listOf(it.name) + it.worse + it.better + it.associated + it.terms } +
            meds.flatMap { m -> listOf(m.name) + m.effects.map { it.second } } +
            quotes.map { it.second }
        ).distinct()

    /** Swap in translations; quotes keep the original in brackets so the doctor sees the patient's words. */
    fun translated(map: Map<String, String>): ReportSummary {
        val byNorm = map.entries.associate { norm(it.key) to it.value }
        fun tr(s: String) = byNorm[norm(s)]?.takeIf { it.isNotBlank() } ?: s
        return copy(
            symptoms = symptoms.map { s ->
                s.copy(name = tr(s.name), worse = s.worse.map(::tr), better = s.better.map(::tr),
                    associated = s.associated.map(::tr), terms = s.terms.map(::tr))
            },
            meds = meds.map { m -> m.copy(name = tr(m.name), effects = m.effects.map { (d, e) -> d to tr(e) }) },
            quotes = quotes.map { (d, q) -> d to tr(q).let { t -> if (t == q) q else "$t ($q)" } },
        )
    }

    /** Compact JSON the model sees: no per-day series, no quotes. */
    fun narrativeInput(): String = JSONObject().apply {
        put("period", JSONObject().put("start", start.toString()).put("end", end.toString())
            .put("days", ChronoUnit.DAYS.between(start, end) + 1))
        put("entry_count", entryCount)
        put("symptoms", JSONArray(symptoms.map { s ->
            JSONObject().put("name", s.name).put("entries", s.entries).put("days_with_symptom", s.days)
                .put("severity_avg", s.severityAvg ?: JSONObject.NULL).put("severity_min", s.severityMin ?: JSONObject.NULL)
                .put("severity_max", s.severityMax ?: JSONObject.NULL)
                .put("severity_trend", s.trend).put("top_aggravating", JSONArray(s.worse))
                .put("top_relieving", JSONArray(s.better)).put("top_associated", JSONArray(s.associated))
                .put("patient_terms", JSONArray(s.terms))
        }))
        put("medications", JSONArray(meds.map { m ->
            JSONObject().put("name", m.name).put("times", m.times)
                .put("effects", JSONArray(m.effects.map { (d, e) -> "${md(d)}: $e" }))
        }))
    }.toString()
}

object ReportBuilder {
    private fun date(e: DiaryEntry) = LocalDateTime.parse(e.createdAt).toLocalDate()

    private fun trend(series: List<Int>): String {
        if (series.size < 3) return "n/a"
        val half = series.size / 2
        val first = series.take(half).average()
        val second = series.drop(half).average()
        return when {
            second - first >= 1 -> "rising"
            first - second >= 1 -> "falling"
            else -> "stable"
        }
    }

    private fun top(items: List<String>, n: Int = 3): List<String> {
        val display = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>() // first-seen order breaks ties, like Python's Counter
        for (it in items) {
            display.putIfAbsent(norm(it), it)
            counts[norm(it)] = (counts[norm(it)] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(n).map { display.getValue(it.key) }
    }

    fun aggregate(all: List<DiaryEntry>, k: Knowledge, lang: String, days: Int): ReportSummary? {
        if (all.isEmpty()) return null
        val end = all.maxOf(::date)
        val start = end.minusDays(days - 1L)
        val chosen = all.filter { date(it) in start..end }.sortedBy { it.createdAt }
        val other = if (lang == "zh") "其他" else "Other"

        val groups = chosen.groupBy { e ->
            Safety.matchCondition(e.record, e.rawText, k)?.label?.get(lang) ?: e.record.symptom ?: other
        }
        val symptoms = groups.map { (name, es) ->
            val sev = es.mapNotNull { it.record.severity }
            val byDate = es.filter { it.record.severity != null }
                .groupBy(::date).mapValues { (_, v) -> v.maxOf { it.record.severity!! } }
                .toSortedMap().map { (d, s) -> d to s }
            SymptomSummary(
                name = name, entries = es.size, days = es.map(::date).toSet().size,
                severityAvg = if (sev.isEmpty()) null else Math.round(sev.average() * 10) / 10.0,
                severityMin = sev.minOrNull(), severityMax = sev.maxOrNull(), trend = trend(byDate.map { it.second }), severityByDate = byDate,
                worse = top(es.flatMap { it.record.aggravating }), better = top(es.flatMap { it.record.relieving }),
                associated = top(es.flatMap { it.record.associated }), terms = top(es.flatMap { it.record.character }, 4),
            )
        }.sortedByDescending { it.entries }

        val meds = chosen.flatMap { e -> e.record.medication.map { date(e) to it } }
            .groupBy { norm(it.second.name) }
            .map { (_, list) ->
                MedSummary(list.first().second.name, list.size, list.mapNotNull { (d, m) -> m.effect?.let { d to it } })
            }.sortedByDescending { it.times }

        val quotes = chosen.sortedByDescending { it.record.severity ?: -1 }.take(3)
            .map { date(it) to (it.record.patientQuote ?: it.rawText.take(80)) }
        val flagMsgs = k.redFlags.associate { it.id to (it.message[lang] ?: it.id) }
        val flags = chosen.flatMap { e -> e.redFlags.map { date(e) to (flagMsgs[it] ?: it) } }
        return ReportSummary(start, end, chosen.size, symptoms, meds, quotes, flags, chosen.map { it.lang }.toSet())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun chart(s: SymptomSummary, start: LocalDate, days: Int): String {
        val w = 640.0; val h = 180.0; val l = 36.0; val r = 12.0; val t = 12.0; val b = 28.0
        fun x(i: Long) = l + (w - l - r) * i / maxOf(days - 1, 1)
        fun y(v: Int) = t + (h - t - b) * (1 - v / 10.0)
        val sb = StringBuilder("<svg viewBox=\"0 0 640 180\" class=\"chart\">")
        for (v in listOf(0, 5, 10)) {
            sb.append("<line x1=\"$l\" x2=\"${w - r}\" y1=\"${y(v)}\" y2=\"${y(v)}\" class=\"g\"/>")
            sb.append("<text x=\"${l - 6}\" y=\"${y(v) + 4}\" text-anchor=\"end\">$v</text>")
        }
        val tick = maxOf(1, days / 7)
        for (i in 0 until days step tick) {
            val d = start.plusDays(i.toLong())
            sb.append("<text x=\"${x(i.toLong())}\" y=\"${h - 8}\" text-anchor=\"middle\">%02d-%02d</text>".format(d.monthValue, d.dayOfMonth))
        }
        val pts = s.severityByDate.map { (d, v) -> Triple(x(ChronoUnit.DAYS.between(start, d)), y(v), v) }
        sb.append("<polyline class=\"l\" points=\"${pts.joinToString(" ") { "${it.first},${it.second}" }}\"/>")
        for ((px, py, v) in pts) {
            sb.append("<circle cx=\"$px\" cy=\"$py\" r=\"4\"/><text x=\"$px\" y=\"${py - 8}\" text-anchor=\"middle\">$v</text>")
        }
        return sb.append("</svg>").toString()
    }

    fun html(s: ReportSummary, narrative: String, lang: String, questions: List<String>): String {
        val zh = lang == "zh"
        fun L(zhText: String, enText: String) = if (zh) zhText else enText
        val sep = L("、", ", ")
        val none = L("无", "None")
        fun joined(xs: List<String>) = if (xs.isEmpty()) none else esc(xs.joinToString(sep))
        val trendWord = mapOf(
            "rising" to L("加重", "worsening"), "falling" to L("减轻", "improving"),
            "stable" to L("大致平稳", "roughly stable"), "n/a" to L("数据不足", "not enough data"),
        )
        val days = ChronoUnit.DAYS.between(s.start, s.end).toInt() + 1

        val rows = s.symptoms.joinToString("") { x ->
            "<tr><td><b>${esc(x.name)}</b></td><td>${x.entries}</td><td>${x.days}</td>" +
                "<td>${x.severityAvg ?: "-"} / ${x.severityMax ?: "-"} ${L("（", "(")}${trendWord[x.trend]}${L("）", ")")}</td>" +
                "<td>${joined(x.worse)}</td><td>${joined(x.better)}</td><td>${joined(x.associated)}</td><td>${joined(x.terms)}</td></tr>"
        }
        val main = s.symptoms.firstOrNull { it.severityByDate.isNotEmpty() }
        val chartHtml = main?.let { "<h2>${L("严重度变化", "Severity over time")} · ${esc(it.name)}</h2>${chart(it, s.start, days)}" } ?: ""
        val medRows = s.meds.joinToString("") { m ->
            "<tr><td>${esc(m.name)}</td><td>${m.times}</td><td>${joined(m.effects.map { (d, e) -> "${md(d)}: $e" })}</td></tr>"
        }
        val medsHtml = if (s.meds.isEmpty()) "" else
            "<h2>${L("用药记录", "Medicines")}</h2><table><tr><th>${L("药物 / 方法", "Medicine / measure")}</th>" +
                "<th>${L("次数", "Times")}</th><th>${L("效果", "Effect")}</th></tr>$medRows</table>"
        val quotes = s.quotes.joinToString("") { (d, q) -> "<li><span class='d'>${md(d)}</span> “${esc(q)}”</li>" }
        val flags = s.flags.joinToString("") { (d, m) -> "<li><span class='d'>${md(d)}</span> ${esc(m)}</li>" }
        val qs = questions.filter { it.isNotBlank() }.joinToString("") { "<li>${esc(it)}</li>" }

        return """<!doctype html><html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${L("症状记录报告", "Symptom Diary Report")}</title>
<style>
 body{font-family:sans-serif;color:#1f2328;margin:16px;line-height:1.5}
 h1{font-size:20px;margin:0} h2{font-size:15px;margin:20px 0 8px;border-bottom:1px solid #d0d7de;padding-bottom:4px}
 .meta,.note{color:#57606a;font-size:12px} table{border-collapse:collapse;width:100%;font-size:12px}
 th,td{border:1px solid #d0d7de;padding:4px 6px;text-align:left;vertical-align:top} th{background:#f6f8fa}
 .d{color:#57606a;margin-right:6px} .chart{width:100%;height:auto} .chart text{font-size:10px;fill:#57606a}
 .chart .g{stroke:#eaeef2} .chart .l{fill:none;stroke:#0969da;stroke-width:2} .chart circle{fill:#0969da}
 .flags li{color:#cf222e} footer{margin-top:20px;color:#57606a;font-size:11px;border-top:1px solid #d0d7de;padding-top:8px}
</style></head><body>
<h1>${L("症状记录报告", "Symptom Diary Report")}</h1>
<div class="meta">${L("记录时间", "Period")}: ${s.start} – ${s.end} · ${s.entryCount} ${L("条记录", "entries")}</div>
<h2>${L("病情陈述", "History of present illness")}</h2><p>${esc(narrative)}</p>
<div class="note">${L("由手机上的 AI 根据记录整理，患者已审阅。", "Drafted by on-device AI from the diary and reviewed by the patient.")}</div>
<h2>${L("症状概览", "Overview")}</h2>
<table><tr><th>${L("症状", "Symptom")}</th><th>${L("次数", "Times")}</th><th>${L("天数", "Days")}</th>
<th>${L("严重度（平均 / 最高）", "Severity (avg / max)")}</th><th>${L("加重因素", "Worse with")}</th>
<th>${L("缓解方式", "Better with")}</th><th>${L("伴随症状", "Associated")}</th><th>${L("患者用词", "Patient's words")}</th></tr>$rows</table>
$chartHtml$medsHtml
<h2>${L("患者原话", "In the patient's words")}</h2><ul>$quotes</ul>
${if (flags.isEmpty()) "" else "<h2>${L("危险信号提示", "Red-flag alerts")}</h2><ul class='flags'>$flags</ul>"}
${if (qs.isEmpty()) "" else "<h2>${L("想问医生的问题", "Questions for the doctor")}</h2><ul>$qs</ul>"}
<footer>${L("本报告由患者在手机上记录，设备端 AI 整理，数据未离开手机。不构成诊断或治疗建议。",
            "Recorded by the patient on their phone and summarized by on-device AI; no data left the device. Not a diagnosis or treatment advice.")}</footer>
</body></html>"""
    }
}
