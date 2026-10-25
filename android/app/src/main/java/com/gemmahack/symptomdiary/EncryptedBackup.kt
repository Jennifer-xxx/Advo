package com.gemmahack.symptomdiary

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** User-directed portable backups, separate from the device-bound Keystore encryption. */
object EncryptedBackup {
    private val magic = "WITHYOU2".toByteArray()
    private const val ROUNDS = 210000
    private const val MAX_ENTRY = 40 * 1024 * 1024
    private const val MAX_TOTAL = 4L * 1024 * 1024 * 1024

    fun requireLocal(uri: Uri, context: Context) {
        require(uri.scheme == "content" && uri.authority in setOf("com.android.externalstorage.documents",
            "com.android.providers.downloads.documents", "com.android.providers.media.documents", context.packageName + ".fileprovider")) {
            "Choose On this device, Downloads or internal storage. Cloud document providers are not supported."
        }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        require(password.size >= 12) { "Use a backup password with at least 12 characters." }
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(magic); key.fill(0)
        }
    }

    fun export(context: Context, repository: LocalRepository, uri: Uri, password: CharArray) {
        requireLocal(uri, context)
        val snapshot = repository.state.value
        check(snapshot.visits.none { it.isCapturing }) { "Stop recording before exporting a backup." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            val header = DataOutputStream(stream)
            header.write(magic); header.write(salt); header.write(iv); header.flush()
            ZipOutputStream(CipherOutputStream(stream, cipher)).use { zip ->
                zip.putNextEntry(ZipEntry("store.json")); zip.write(StoreCodec.encode(snapshot)); zip.closeEntry()
                snapshot.visits.flatMap { it.sources }.mapNotNull { it.blob }.distinct().forEach { id ->
                    zip.putNextEntry(ZipEntry("blobs/$id")); zip.write(repository.vault.read(id)); zip.closeEntry()
                }
            }
        } ?: error("The backup destination could not be opened.")
    }

    /** Validate password, authentication tag, contract and every blob before changing the live database. */
    fun restore(context: Context, repository: LocalRepository, uri: Uri, password: CharArray): Int {
        requireLocal(uri, context)
        check(repository.state.value.visits.none { it.isCapturing }) { "Stop recording before restoring a backup." }
        val staged = mutableMapOf<String, String>()
        var committed = false
        try {
            var imported: HealthStore? = null
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = DataInputStream(input)
                val marker = ByteArray(magic.size).also(header::readFully)
                require(marker.contentEquals(magic)) { "This is not a Advo encrypted backup." }
                val salt = ByteArray(16).also(header::readFully)
                val iv = ByteArray(12).also(header::readFully)
                CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, password, salt, iv)).use { decrypted ->
                    val zip = ZipInputStream(decrypted)
                    var total = 0L
                    val seen = mutableSetOf<String>()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(seen.add(entry.name) && seen.size <= 50001) { "Duplicate or excessive backup entries." }
                        require(entry.name == "store.json" || entry.name.matches(Regex("blobs/[a-f0-9-]{36}")))
                        val bytes = ByteArrayOutputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                require(out.size().toLong() + count <= MAX_ENTRY && total <= MAX_TOTAL) { "Backup exceeds supported limits." }
                                check(repository.vault.directory.usableSpace > count + 64L * 1024 * 1024) { "Not enough space to restore safely." }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        if (entry.name == "store.json") imported = StoreCodec.decode(bytes)
                        else staged[entry.name.removePrefix("blobs/")] = repository.vault.put(bytes)
                        zip.closeEntry()
                    }
                    // Read to authenticated EOF before any database mutation (ZIP end alone is insufficient).
                    val tail = ByteArray(8192)
                    while (decrypted.read(tail) != -1) { /* authentication completes here */ }
                }
            } ?: error("This backup could not be opened.")
            val data = imported ?: error("Backup has no record index.")
            val required = data.visits.flatMap { it.sources }.mapNotNull { it.blob }.toSet()
            require(staged.keys == required) { "Backup sources are incomplete or unexpected." }
            val restored = data.visits.map { v -> VisitRules.recover(v).copy(sources = v.sources.map { source ->
                source.copy(blob = source.blob?.let { staged.getValue(it) })
            }) }
            var added = 0
            if (repository.openError != null) {
                repository.replaceUnreadableIndex(data.copy(visits = restored))
                added = restored.size
            } else repository.update { existing ->
                val fresh = restored.filter { candidate -> existing.visits.none { it.id == candidate.id } }
                added = fresh.size
                val sourceIds = (existing.visits + fresh).flatMap { it.sources }.map { it.id }.toSet()
                existing.copy(visits = existing.visits + fresh,
                    profile = existing.profile + data.profile.filter { p -> p.fact.sourceId in sourceIds && existing.profile.none { it.id == p.id || it.fact.id == p.fact.id } })
            }
            committed = true
            val referenced = repository.state.value.visits.flatMap { it.sources }.mapNotNull { it.blob }.toSet()
            staged.values.filter { it !in referenced }.forEach(repository.vault::delete)
            return added
        } finally { if (!committed) staged.values.forEach(repository.vault::delete) }
    }
}
