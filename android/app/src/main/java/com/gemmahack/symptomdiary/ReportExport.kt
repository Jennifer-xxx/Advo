package com.gemmahack.symptomdiary

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Native selectable text. Letter size, fixed three sections, readable overflow rather than clipped screenshots. */
object ReportExport {
    private const val WIDTH = 612
    private const val HEIGHT = 792
    private const val INK = 0xff172238.toInt()
    private const val BLUE = 0xff275db0.toInt()
    private data class Line(val text: String, val style: Int = 0, val check: Boolean = false, val highlight: Boolean = false)
    private data class DrawResult(val rest: List<Line>, val bottom: Float)

    fun render(context: Context, report: ReportSnapshot): File {
        val file = File(File(context.cacheDir, "reports").apply { mkdirs() }, "Advo-${report.id}-v${report.version}.pdf")
        val symptoms = (if (report.communicationSummary.isNotBlank()) listOf(report.communicationSummary) else report.symptoms.map { factLine(it) }).ifEmpty {
            report.originalWords.map { "Patient's words: $it" }.ifEmpty { listOf("Not recorded. Discuss with the patient.") }
        }.map { Line(it, highlight = true) }
        val concerns = listOf(Line("ALSO WANTS TO DISCUSS", 2)) +
            report.concerns.map { Line(factLine(it)) }.ifEmpty { listOf(Line("Not recorded.")) }
        val recordLines = mutableListOf<Line>()
        listOf(Category.MEDICINE, Category.ALLERGY, Category.PAST_MEDICINE, Category.HISTORY).forEach { category ->
            recordLines += Line(category.title.uppercase(), 2)
            recordLines += report.records.filter { it.fact.category == category }.map { entry ->
                Line(factLine(entry.fact) + " [${report.sourceDetails[entry.fact.sourceId] ?: "Health profile; ${displayDate(entry.createdAt, report.zone)}"}]")
            }.ifEmpty { listOf(Line(when (report.categoryStates[category.name]) {
                KnowledgeState.NONE_CONFIRMED -> "Patient reports none."
                KnowledgeState.UNKNOWN -> "Unknown per patient."
                else -> "Not recorded / unknown."
            })) }
        }
        val questionLines = report.questions.map { Line(it.text + if (it.origin == QuestionOrigin.SUGGESTED) " [patient-selected suggestion]" else "", check = true) }
            .ifEmpty { listOf(Line("No questions selected.")) }
        val pdf = PdfDocument()
        try {
            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber).create())
            var canvas = page.canvas
            val headerOverflow = header(canvas, report, continuation = false)
            heading(canvas, "1", "Communication summary", 34f, 119f)
            val topLeft = draw(canvas, symptoms, 38f, 140f, 330, 337f)
            val topRight = draw(canvas, concerns, 388f, 140f, 185, 337f)
            val lowerTop = maxOf(topLeft.bottom, topRight.bottom, 225f) + 27f
            val border = Paint().apply { color = 0xffe4e6eb.toInt(); strokeWidth = 1f }
            canvas.drawLine(34f, lowerTop - 10f, 578f, lowerTop - 10f, border)
            heading(canvas, "2", "Relevant records", 34f, lowerTop + 9f)
            heading(canvas, "3", "Questions to ask the doctor", 316f, lowerTop + 9f)
            val bottomLeft = draw(canvas, recordLines, 38f, lowerTop + 29f, 250, 723f)
            val bottomRight = draw(canvas, questionLines, 320f, lowerTop + 29f, 254, 723f)
            var remainingLeft = headerOverflow + if (topLeft.rest.isNotEmpty() || topRight.rest.isNotEmpty())
                listOf(Line("1 COMMUNICATION SUMMARY (CONTINUED)", 2)) + topLeft.rest + topRight.rest +
                    listOf(Line("2 RELEVANT RECORDS (CONTINUED)", 2)) + bottomLeft.rest else bottomLeft.rest
            var remainingRight = bottomRight.rest
            footer(canvas, report, pageNumber, remainingLeft.isNotEmpty() || remainingRight.isNotEmpty())
            pdf.finishPage(page)
            while (remainingLeft.isNotEmpty() || remainingRight.isNotEmpty()) {
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber).create())
                canvas = page.canvas
                header(canvas, report, continuation = true)
                val left = draw(canvas, listOf(Line("SOURCE DETAILS (CONTINUED)", 2)) + remainingLeft, 38f, 123f, 250, 723f)
                val right = draw(canvas, listOf(Line("QUESTIONS (CONTINUED)", 2)) + remainingRight, 320f, 123f, 254, 723f)
                remainingLeft = left.rest; remainingRight = right.rest
                footer(canvas, report, pageNumber, remainingLeft.isNotEmpty() || remainingRight.isNotEmpty())
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    private fun factLine(f: Fact): String = (f.english?.let { "$it [English translation${if (f.translationReview != Review.PATIENT_REVIEWED) " NEEDS REVIEW" else "; patient reviewed"}]. Original: ${f.quote}" }
        ?: f.quote) + (if (f.needsReview) " [NEEDS CONFIRMATION]" else " [patient reviewed]")

    private fun header(canvas: Canvas, r: ReportSnapshot, continuation: Boolean): List<Line> {
        text(canvas, "PRE-VISIT REPORT${if (continuation) " - CONTINUED" else ""}", 34f, 39f, 9f, BLUE, true)
        val title = draw(canvas, listOf(Line(r.title, 1)), 34f, 49f, 320, 104f)
        val identity = listOfNotNull(r.patientName.takeIf(String::isNotBlank), r.dateOfBirth.takeIf(String::isNotBlank)?.let { "DOB $it" },
            r.appointment.takeIf(String::isNotBlank), r.doctor.takeIf(String::isNotBlank), r.clinic.takeIf(String::isNotBlank))
        val fields = draw(canvas, identity.map { Line(it, 3) }, 383f, 40f, 192, 104f)
        canvas.drawLine(34f, 106f, 578f, 106f, Paint().apply { color = BLUE; strokeWidth = 1f })
        return if (!continuation && (title.rest.isNotEmpty() || fields.rest.isNotEmpty()))
            listOf(Line("REPORT HEADER (CONTINUED)", 2)) + title.rest + fields.rest else emptyList()
    }

    private fun heading(canvas: Canvas, number: String, label: String, x: Float, y: Float) {
        canvas.drawCircle(x + 8f, y - 3f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE })
        text(canvas, number, x + 5f, y, 9f, Color.WHITE, true)
        text(canvas, label, x + 23f, y, 10f, INK, true)
    }

    private fun draw(canvas: Canvas, lines: List<Line>, x: Float, top: Float, width: Int, bottom: Float): DrawResult {
        var y = top
        for ((index, line) in lines.withIndex()) {
            val size = when (line.style) { 1 -> 19f; 2 -> 8.5f; 3 -> 8.5f; else -> 10f }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = if (line.style == 2) BLUE else INK
                typeface = Typeface.create("sans-serif", if (line.style in 1..2) Typeface.BOLD else Typeface.NORMAL) }
            val indent = if (line.check) 14 else 0
            val layout = StaticLayout.Builder.obtain(line.text, 0, line.text.length, paint, width - indent)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).setIncludePad(false).build()
            val available = bottom - y
            val fit = (0 until layout.lineCount).takeWhile { layout.getLineBottom(it) <= available }
            if (fit.isEmpty()) return DrawResult(lines.drop(index), y)
            val consumed = fit.size
            canvas.save(); canvas.translate(x + indent, y)
            canvas.clipRect(0f, 0f, (width - indent).toFloat(), layout.getLineBottom(consumed - 1).toFloat())
            if (line.highlight) for (row in 0 until consumed) canvas.drawRect(0f, layout.getLineTop(row).toFloat(),
                layout.getLineWidth(row).coerceAtMost((width - indent).toFloat()), layout.getLineBottom(row).toFloat(),
                Paint().apply { color = 0xffffeadb.toInt() })
            layout.draw(canvas); canvas.restore()
            if (line.check) canvas.drawRect(x, y + 2f, x + 8f, y + 10f, Paint().apply { color = INK; style = Paint.Style.STROKE; strokeWidth = 0.8f })
            y += layout.getLineBottom(consumed - 1) + if (line.style == 2) 7f else 9f
            if (consumed < layout.lineCount) {
                val remaining = line.copy(text = line.text.substring(layout.getLineStart(consumed)), check = false)
                return DrawResult(listOf(remaining) + lines.drop(index + 1), y)
            }
        }
        return DrawResult(emptyList(), y)
    }

    private fun footer(canvas: Canvas, report: ReportSnapshot, page: Int, continued: Boolean) {
        text(canvas, "${report.reviewLabel} | v${report.version} | ${displayDate(report.createdAt, report.zone)}", 34f, 746f, 8f, INK)
        text(canvas, "Communication aid. Verify with your clinician. Original sources remain in Advo.", 34f, 759f, 8f, INK)
        text(canvas, "Page $page${if (continued) " - continued on next page" else ""}", 34f, 774f, 8f, INK)
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        c.drawText(value, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) })
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Your visit report", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share your visit report"))
    }

    fun print(context: Context, file: File) {
        context.getSystemService(PrintManager::class.java).print("Advo visit report", object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancellation: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancellation?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellation: CancellationSignal?, callback: WriteResultCallback) {
                if (cancellation?.isCanceled == true) { callback.onWriteCancelled(); return }
                try {
                    java.io.FileOutputStream(destination.fileDescriptor).use { out -> file.inputStream().use { it.copyTo(out) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (_: Exception) { callback.onWriteFailed("The report could not be printed. The saved report is unchanged.") }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build())
    }
}
