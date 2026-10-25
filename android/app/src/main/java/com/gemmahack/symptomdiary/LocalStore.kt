package com.gemmahack.symptomdiary

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM at rest, with a non-exportable Android Keystore key; hardware backing is device-dependent. */
class HealthVault(context: Context) {
    val directory = File(context.noBackupFilesDir, "health").apply { mkdirs() }
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("with-you-health-v2", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("with-you-health-v2", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun seal(bytes: ByteArray, name: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("WithYou:2:$name".toByteArray())
        return byteArrayOf(2) + cipher.iv + cipher.doFinal(bytes)
    }

    fun unseal(bytes: ByteArray, name: String): ByteArray {
        require(bytes.size >= 29 && bytes[0] == 2.toByte()) { "Invalid encrypted file" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("WithYou:2:$name".toByteArray())
        return cipher.doFinal(bytes, 13, bytes.size - 13)
    }

    fun writeAtomic(name: String, bytes: ByteArray) {
        val atomic = AtomicFile(file(name))
        val output = atomic.startWrite()
        try {
            output.write(seal(bytes, name))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun file(name: String): File {
        require(name == "store" || name.matches(Regex("[a-f0-9-]{36}"))) { "Invalid local file identifier" }
        return File(directory, name)
    }

    fun put(bytes: ByteArray): String = newId().also { writeAtomic(it, bytes) }
    fun read(name: String): ByteArray = AtomicFile(file(name)).openRead().use { unseal(it.readBytes(), name) }
    fun delete(name: String) = AtomicFile(file(name)).delete()
}

object StoreCodec {
    val gson = Gson()
    fun encode(store: HealthStore): ByteArray = gson.toJson(store).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): HealthStore {
        val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(json.get("schema")?.asInt == 2) { "Unsupported backup version" }
        // Additive schema-2 fields introduced during the prototype upgrade. Never default missing clinical fields.
        json.getAsJsonObject("settings")?.let { settings ->
            if (!settings.has("categoryStateSourceIds")) settings.add("categoryStateSourceIds", JsonObject())
        }
        fun defaults(fact: JsonObject) { if (!fact.has("translationReview")) fact.addProperty("translationReview", "DRAFT") }
        json.getAsJsonArray("visits")?.forEach { element ->
            val visit = element.asJsonObject
            if (!visit.has("inputDraftKind")) visit.addProperty("inputDraftKind", "PATIENT_TEXT")
            visit.getAsJsonArray("facts")?.forEach { defaults(it.asJsonObject) }
            visit.getAsJsonArray("reports")?.forEach { item ->
                val report = item.asJsonObject
                if (!report.has("sourceDetails")) report.add("sourceDetails", JsonObject())
                if (!report.has("zone")) report.addProperty("zone", java.time.ZoneId.systemDefault().id)
                listOf("symptoms", "concerns").forEach { key -> report.getAsJsonArray(key)?.forEach { defaults(it.asJsonObject) } }
                report.getAsJsonArray("records")?.forEach { defaults(it.asJsonObject.getAsJsonObject("fact")) }
            }
        }
        json.getAsJsonArray("profile")?.forEach { item ->
            val profile = item.asJsonObject
            defaults(profile.getAsJsonObject("fact"))
            if (!profile.has("changes")) profile.add("changes", JsonArray())
        }
        return gson.fromJson(json, HealthStore::class.java).also(::validate)
    }

    /** Also runs on authenticated backup input: encryption alone does not establish a valid data contract. */
    fun validate(store: HealthStore) {
        require(store.schema == 2 && store.visits.size <= 10000 && store.profile.size <= 50000)
        requireNotNull(store.settings); requireNotNull(store.settings.categoryStates)
        requireNotNull(store.settings.categoryStateSourceIds)
        require(store.visits.map { it.id }.distinct().size == store.visits.size)
        require(store.profile.map { it.id }.distinct().size == store.profile.size)
        store.visits.forEach { visit ->
            require(visit.id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
            requireNotNull(visit.stage); requireNotNull(visit.recording); requireNotNull(visit.title)
            requireNotNull(visit.consents); requireNotNull(visit.questions); requireNotNull(visit.reports)
            requireNotNull(visit.generations); requireNotNull(visit.reminders); requireNotNull(visit.skippedQuestions)
            require(visit.sources.map { it.id }.distinct().size == visit.sources.size)
            require(visit.facts.map { it.id }.distinct().size == visit.facts.size)
            visit.sources.forEach { s ->
                requireNotNull(s.kind); requireNotNull(s.status); requireNotNull(s.text); requireNotNull(s.originalText)
                require(s.text.length <= 2_000_000)
                require(s.blob == null || s.blob.matches(Regex("[a-f0-9-]{36}")))
                require(s.startMs == null || (s.startMs >= 0 && (s.endMs ?: 0) >= s.startMs))
            }
            val sourceIds = visit.sources.map { it.id }.toSet()
            visit.facts.forEach { f -> validateFact(f); require(f.sourceId in sourceIds) }
            visit.questions.forEach { q ->
                requireNotNull(q.origin); requireNotNull(q.state); require(q.text.length <= 10000)
                require(q.sourceIds.all { it in sourceIds })
            }
        }
        val globalSources = store.visits.flatMap { it.sources }.map { it.id }.toSet()
        require(store.settings.categoryStateSourceIds.values.all { it in globalSources })
        store.profile.forEach { validateFact(it.fact); requireNotNull(it.status); requireNotNull(it.changes)
            require(it.fact.sourceId in globalSources) { "Health profile source is missing" } }
    }

    private fun validateFact(f: Fact) {
        requireNotNull(f.category); requireNotNull(f.status); requireNotNull(f.translationReview); requireNotNull(f.id)
        require(f.quote.isNotBlank() && f.quote.length <= 100000 && f.label.length <= 1000)
        require(f.sourceId.isNotBlank())
    }
}

/** One process-wide repository shared by the UI and recording service. Publish only after durable write. */
class LocalRepository(context: Context) {
    val vault = HealthVault(context)
    private val mutable = MutableStateFlow(HealthStore())
    val state = mutable.asStateFlow()
    var openError: String? = null
        private set
    private val legacy = File(context.filesDir, "diary.json")

    init {
        try {
            var loaded = if (vault.file("store").exists() || File(vault.directory, "store.bak").exists())
                StoreCodec.decode(vault.read("store")) else HealthStore()
            loaded = loaded.copy(visits = loaded.visits.map(VisitRules::recover))
            if (!loaded.migratedDiary && legacy.exists()) loaded = migrate(loaded)
            vault.writeAtomic("store", StoreCodec.encode(loaded))
            mutable.value = loaded
            // Remove the obsolete plaintext only after the encrypted migration commits.
            if (loaded.migratedDiary && legacy.exists()) check(legacy.delete())
        } catch (_: Exception) {
            openError = "Your local records could not be opened. The existing files are preserved. Restart or restore a verified backup."
        }
    }

    @Synchronized
    fun update(transform: (HealthStore) -> HealthStore): HealthStore {
        check(openError == null) { "Local storage is unavailable; existing records are preserved." }
        val next = transform(mutable.value)
        StoreCodec.validate(next)
        vault.writeAtomic("store", StoreCodec.encode(next))
        mutable.value = next
        return next
    }

    fun visit(id: String): Visit = state.value.visits.first { it.id == id }
    @Synchronized
    fun replaceUnreadableIndex(restored: HealthStore) {
        check(openError != null)
        StoreCodec.validate(restored)
        // Preserve the unreadable encrypted index and all pre-existing attachments for recovery.
        val recovery = File(vault.directory.parentFile, "recovery-${System.currentTimeMillis()}").apply { mkdirs() }
        listOf("store", "store.bak").map { File(vault.directory, it) }.filter(File::exists)
            .forEach { it.copyTo(File(recovery, it.name)) }
        vault.writeAtomic("store", StoreCodec.encode(restored))
        mutable.value = restored
        openError = null
    }
    fun updateVisit(id: String, transform: (Visit) -> Visit) = update { state ->
        state.copy(visits = state.visits.map { if (it.id == id) transform(it).copy(updatedAt = now()) else it })
    }

    /** Delete the reference before the bytes. A failed deletion cannot destroy a still-referenced source. */
    fun deleteAudio(visitId: String) {
        val blobs = visit(visitId).sources.filter { it.mime == "audio/wav" }.mapNotNull { it.blob }
        updateVisit(visitId) { v ->
            check(!v.isCapturing) { "Stop recording before deleting audio." }
            v.copy(sources = v.sources.map { if (it.mime == "audio/wav") it.copy(blob = null,
                error = "Audio deleted by you. Transcript retained.") else it })
        }
        blobs.forEach(vault::delete)
    }

    private fun migrate(store: HealthStore): HealthStore {
        val entries = JsonParser.parseString(legacy.readText()).asJsonArray
        val visits = entries.map { item ->
            val o = item.asJsonObject
            val id = "legacy-" + o.get("id").asString
            val raw = o.get("raw_text")?.asString.orEmpty().ifBlank { "Original diary record" }
            val source = Source(id = stableId(id, "original"), kind = SourceKind.LEGACY,
                text = raw, originalText = item.toString(), status = Processing.IDLE)
            Visit(id = id, title = "Imported symptom diary", stage = Stage.ARCHIVED,
                sources = listOf(source), demo = o.get("demo")?.asBoolean ?: false,
                createdAt = o.get("created_at")?.asString ?: now())
        }
        return store.copy(visits = store.visits + visits.filter { v -> store.visits.none { it.id == v.id } }, migratedDiary = true)
    }
}

class WithYouApplication : Application() {
    val repository: LocalRepository by lazy { LocalRepository(this) }
    override fun onCreate() {
        super.onCreate()
        // Remove temporary captures, previews and explicit export files from a previous process.
        listOf("reports", "exports", "previews").forEach { File(cacheDir, it).deleteRecursively() }
        val pending = getSharedPreferences("capture-navigation", Context.MODE_PRIVATE).getString("pending", null)
        File(cacheDir, "capture").listFiles()?.filter { it.path != pending }?.forEach(File::delete)
    }
}
