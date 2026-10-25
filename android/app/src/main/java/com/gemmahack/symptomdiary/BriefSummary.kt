package com.gemmahack.symptomdiary

import java.math.BigDecimal

enum class BriefFailure(val explanation: String) {
    MODEL_NOT_READY("The local model is not ready. Open Offline resources to load or import it."),
    NO_TEXT("The selected records do not have readable text yet. Transcribe their audio or papers first."),
    UNVERIFIED_OUTPUT("The summary changed a number or medicine unit that could not be verified. Your original records are preserved."),
    MODEL_ERROR("The local model could not finish the summary. Your records are saved; retry when ready."),
    STOPPED("You stopped local processing. Your records are saved; retry when ready."),
}
class BriefException(val reason: BriefFailure) : Exception(reason.explanation)

/** Equivalent typography is allowed; a new value or a changed dose/unit is still rejected. */
object SummaryGrounding {
    private val number = Regex("(?<![\\p{L}\\d])(?:\\d+(?:\\.\\d+)?|\\.\\d+)")
    private val words = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val wordPattern = Regex("\\b(" + words.keys.joinToString("|") + ")(?:[ -](one|two|three|four|five|six|seven|eight|nine))?\\b", RegexOption.IGNORE_CASE)
    private val dose = Regex("(?<![\\p{L}\\d])([0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)\\s*(mmol/l|mg/dl|mmhg|mcg|µg|μg|mg|kg|g|ml|l|iu|units|%)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    private fun normalized(text: String): String = wordPattern.replace(text.lowercase().replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
        .replace(Regex("\\bmilligrams?\\b"), "mg").replace(Regex("\\bmicrograms?\\b"), "mcg")
        .replace(Regex("\\bmilliliters?\\b|\\bmillilitres?\\b"), "ml")) { match ->
        val first = words.getValue(match.groupValues[1].lowercase())
        val extra = match.groupValues[2].takeIf { first >= 20 }?.let { words[it.lowercase()] } ?: 0
        if (match.groupValues[2].isNotEmpty() && first < 20) match.value else (first + extra).toString()
    }
    private fun decimal(value: String): String = BigDecimal(value).stripTrailingZeros().toPlainString()
    private fun values(text: String): Set<String> = number.findAll(normalized(text)).map { decimal(it.value) }.toSet()
    private fun doses(text: String): Set<Pair<String, String>> = dose.findAll(normalized(text)).map {
        decimal(it.groupValues[1]) to it.groupValues[2].replace("µg", "mcg").replace("μg", "mcg")
    }.toSet()

    fun verify(summary: String, evidence: String) {
        if (summary.isBlank()) throw BriefException(BriefFailure.MODEL_ERROR)
        if (!values(evidence).containsAll(values(summary)) || !doses(evidence).containsAll(doses(summary)))
            throw BriefException(BriefFailure.UNVERIFIED_OUTPUT)
    }
}

object BriefInput {
    data class Passage(val date: String, val text: String)
    fun passages(records: List<Visit>): List<Passage> = records.flatMap { record ->
        record.sources.filter { it.parentId == null && it.text.isNotBlank() }.map { Passage(displayDate(record.createdAt), it.text) }
    }
    /** Split at whitespace where possible, without dropping words or silently omitting old entries. */
    fun batches(passages: List<Passage>, maxChars: Int = 10000): List<List<Passage>> {
        require(maxChars >= 512)
        val pieces = passages.flatMap { passage ->
            val result = mutableListOf<Passage>()
            var remaining = passage.text
            while (remaining.length > maxChars - 100) {
                val limit = maxChars - 100
                val split = remaining.lastIndexOf(' ', limit).takeIf { it >= limit / 2 } ?: limit
                result += passage.copy(text = remaining.substring(0, split))
                remaining = remaining.substring(split)
            }
            if (remaining.isNotEmpty()) result += passage.copy(text = remaining)
            result
        }
        val result = mutableListOf<List<Passage>>()
        var batch = mutableListOf<Passage>()
        var size = 0
        pieces.forEach { piece ->
            if (size + piece.text.length + 100 > maxChars && batch.isNotEmpty()) { result += batch; batch = mutableListOf(); size = 0 }
            batch += piece; size += piece.text.length + 100
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }
}
