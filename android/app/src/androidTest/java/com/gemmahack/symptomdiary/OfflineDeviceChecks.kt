package com.gemmahack.symptomdiary

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.core.content.FileProvider
import kotlinx.coroutines.runBlocking
import java.io.File

/** Framework-only instrumentation. Uses isolated synthetic stores and never opens the user's repository. */
class OfflineDeviceChecks : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val failures = mutableListOf<String>()
        var passed = 0
        fun test(name: String, block: () -> Unit) {
            try { block(); passed++; sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") }) }
            catch (e: Exception) { failures += "$name: ${e.javaClass.simpleName}"; sendStatus(0, Bundle().apply { putString("stream", "FAIL ${failures.last()}\n") }) }
        }
        val base = File(targetContext.cacheDir, "isolated-device-checks-${newId()}").apply { mkdirs() }
        fun isolated(name: String) = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir() = File(base, "$name/no-backup").apply { mkdirs() }
            override fun getFilesDir() = File(base, "$name/files").apply { mkdirs() }
        }
        val repository = LocalRepository(isolated("first"))
        val source = Source(id = "synthetic-source", kind = SourceKind.PATIENT_TEXT,
            text = "Headaches for 3 weeks. A band squeezing around my head. Ibuprofen 400 mg most days. Penicillin caused hives.", status = Processing.READY)
        val symptoms = Fact(id = "synthetic-symptom", category = Category.SYMPTOM, label = "Symptoms",
            quote = "Headaches for 3 weeks. A band squeezing around my head.", sourceId = source.id)
        val medicine = Fact(id = "synthetic-medicine", category = Category.MEDICINE, label = "Medicine",
            quote = "Ibuprofen 400 mg most days.", sourceId = source.id, name = "Ibuprofen", dose = "400", unit = "mg")
        val allergy = Fact(id = "synthetic-allergy", category = Category.ALLERGY, label = "Allergy",
            quote = "Penicillin caused hives.", sourceId = source.id, name = "Penicillin", reaction = "hives")
        val visit = Visit(id = "synthetic-visit", title = "Headaches for 3 weeks", sources = listOf(source), facts = listOf(symptoms, medicine, allergy),
            questions = listOf(VisitQuestion(text = "Could poor sleep be related?", origin = QuestionOrigin.PATIENT)), demo = true)
        val report = VisitRules.snapshot(visit, emptyList()).copy(id = "verification-normal")
        test("manifest excludes network and backup") {
            val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            check("android.permission.INTERNET" !in info.requestedPermissions.orEmpty())
            check(info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
        }
        test("encrypted durable store survives reopen") {
            check(repository.openError == null)
            repository.update { it.copy(visits = listOf(visit)) }
            val raw = repository.vault.file("store").readBytes()
            check(!raw.toString(Charsets.ISO_8859_1).contains("Headaches"))
            val reopened = LocalRepository(isolated("first"))
            check(reopened.state.value.visits.single().sources.single().text == source.text)
        }
        test("encrypted attachments authenticate tampering") {
            val id = repository.vault.put("Synthetic attachment".toByteArray())
            check(repository.vault.read(id).toString(Charsets.UTF_8) == "Synthetic attachment")
            val raw = repository.vault.file(id).readBytes(); raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
            repository.vault.file(id).writeBytes(raw)
            check(runCatching { repository.vault.read(id) }.isFailure)
            repository.vault.delete(id)
        }
        test("recording and processing restart recovery") {
            repository.update { it.copy(visits = listOf(visit.copy(stage = Stage.IN_VISIT, recording = Recording.RECORDING,
                consents = listOf(Consent()), sources = listOf(source.copy(status = Processing.PROCESSING))))) }
            val recovered = LocalRepository(isolated("first")).state.value.visits.single()
            check(recovered.recording == Recording.INTERRUPTED && !recovered.hasConsent)
            check(recovered.sources.single().status == Processing.FAILED)
            repository.update { it.copy(visits = listOf(visit)) }
        }
        val exports = File(targetContext.cacheDir, "exports").apply { mkdirs() }
        val backupFile = File(exports, "isolated-verification.withyou")
        val uri = FileProvider.getUriForFile(targetContext, targetContext.packageName + ".fileprovider", backupFile)
        val password = "Synthetic-password-2026".toCharArray()
        test("portable encrypted backup restores idempotently") {
            val blob = repository.vault.put("Synthetic original paper bytes".toByteArray())
            repository.updateVisit(visit.id) { it.copy(sources = it.sources + Source(kind = SourceKind.DOCUMENT, blob = blob, mime = "image/png")) }
            EncryptedBackup.export(targetContext, repository, uri, password)
            check(!backupFile.readBytes().toString(Charsets.ISO_8859_1).contains("Headaches"))
            val target = LocalRepository(isolated("restored"))
            check(EncryptedBackup.restore(targetContext, target, uri, password) == 1)
            check(EncryptedBackup.restore(targetContext, target, uri, password) == 0)
            val restored = target.state.value.visits.single().sources.last()
            check(target.vault.read(restored.blob!!).toString(Charsets.UTF_8) == "Synthetic original paper bytes")
        }
        test("wrong backup password leaves existing data untouched") {
            val target = LocalRepository(isolated("wrong-password"))
            target.update { it.copy(visits = listOf(visit.copy(id = "kept-visit"))) }
            val before = target.state.value
            check(runCatching { EncryptedBackup.restore(targetContext, target, uri, "Incorrect-password".toCharArray()) }.isFailure)
            check(before == target.state.value)
        }
        test("verified backup recovers an unreadable index while preserving old bytes") {
            val context = isolated("unreadable")
            val original = LocalRepository(context)
            val damaged = "Synthetic unreadable encrypted index".toByteArray()
            original.vault.file("store").writeBytes(damaged)
            val broken = LocalRepository(context)
            check(broken.openError != null)
            check(EncryptedBackup.restore(targetContext, broken, uri, password) == 1)
            check(broken.openError == null && broken.state.value.visits.single().id == visit.id)
            val recovery = context.noBackupFilesDir.listFiles()!!.first { it.name.startsWith("recovery-") }
            check(File(recovery, "store").readBytes().contentEquals(damaged))
        }
        test("tampered backup cannot commit") {
            val bytes = backupFile.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            backupFile.writeBytes(bytes)
            val target = LocalRepository(isolated("tampered"))
            check(runCatching { EncryptedBackup.restore(targetContext, target, uri, password) }.isFailure)
            check(target.state.value.visits.isEmpty())
        }
        test("Letter PDF and long-content continuation render") {
            val normal = ReportExport.render(targetContext, report)
            LocalFiles.withPdf(targetContext, normal.readBytes()) { check(it.pageCount == 1); it.openPage(0).use { page -> check(page.width == 612 && page.height == 792) } }
            val long = report.copy(id = "verification-overflow", symptoms = (1..40).map { symptoms.copy(id = "long-$it", quote = "$it. ${symptoms.quote} " + "Patient wording and uncertainty are preserved. ".repeat(5)) },
                questions = (1..40).map { VisitQuestion(text = "Question $it: What should I clarify about the next step and its timing?", origin = QuestionOrigin.PATIENT) })
            val overflow = ReportExport.render(targetContext, long)
            LocalFiles.withPdf(targetContext, overflow.readBytes()) { check(it.pageCount > 1) }
        }
        if (arguments.getString("gemma") == "true") {
            test("Pixel Gemma text contract and camera-paper input") { runBlocking {
                val engine = GemmaEngine(targetContext)
                try {
                    val seconds = engine.load(arguments.getString("variant") ?: "E2B")
                    val intelligence = VisitIntelligence(targetContext, engine)
                    val (extracted, generation) = intelligence.extract(visit, source, "prepare")
                    check(extracted.facts.isNotEmpty() && extracted.facts.all { source.text.contains(it.quote) })
                    sendStatus(0, Bundle().apply { putString("stream", "MODEL ${engine.modelName} ${engine.backendName}; load %.1fs; extract %.1fs; facts ${extracted.facts.size}; audio ${engine.audioEnabled}; vision ${engine.visionEnabled}\n".format(seconds, generation.seconds)) })
                    if (engine.visionEnabled) {
                        val bitmap = Bitmap.createBitmap(1300, 900, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                        canvas.drawText("After Visit Summary", 60f, 120f, paint)
                        canvas.drawText("Follow-up in 2 weeks.", 60f, 240f, paint)
                        canvas.drawText("Dose needs confirmation.", 60f, 350f, paint)
                        val result = intelligence.readPaper(LocalFiles.png(bitmap)); bitmap.recycle()
                        check(result.text.contains("2") && result.text.contains("weeks", true))
                        sendStatus(0, Bundle().apply { putString("stream", "VISION synthetic paper %.1fs; wording detected\n".format(result.seconds)) })
                    }
                } finally { engine.close() }
            } }
        }
        password.fill('\u0000'); backupFile.delete(); base.deleteRecursively()
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n$passed checks passed; ${failures.size} failed.\n" + failures.joinToString("\n"))
        })
    }
}
