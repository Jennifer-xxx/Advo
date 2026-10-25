package com.gemmahack.symptomdiary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object LocalFiles {
    const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024

    fun readDocument(context: Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_DOCUMENT_BYTES) { "Choose a document smaller than 32 MB or split it into pages." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } ?: error("This local file could not be opened.")

    fun type(bytes: ByteArray): String = when {
        bytes.take(4) == "%PDF".toByteArray().toList() -> "application/pdf"
        else -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 80_000_000)
            options.outMimeType ?: "image/jpeg"
        }
    }

    fun image(bytes: ByteArray, maxEdge: Int = 1800): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= maxEdge * 2 }
        var decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("This image could not be read.")
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        if (!matrix.isIdentity) decoded = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { rotated ->
            if (rotated !== decoded) decoded.recycle()
        }
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale == 1f) return decoded
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
    }

    /** PdfRenderer needs a seekable descriptor; private scratch is removed in every exit path and on next launch. */
    fun <T> withPdf(context: Context, bytes: ByteArray, action: (PdfRenderer) -> T): T {
        val dir = File(context.cacheDir, "previews").apply { mkdirs() }
        val scratch = File(dir, "${newId()}.pdf")
        try {
            scratch.writeBytes(bytes)
            return ParcelFileDescriptor.open(scratch, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use(action)
            }
        } finally { scratch.delete() }
    }

    fun pdfPage(renderer: PdfRenderer, page: Int): Bitmap = renderer.openPage(page).use { p ->
        val width = 1500
        Bitmap.createBitmap(width, (width.toFloat() / p.width * p.height).toInt(), Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    fun preview(context: Context, bytes: ByteArray, mime: String, page: Int = 0): Bitmap =
        if (mime == "application/pdf") withPdf(context, bytes) { pdfPage(it, page) } else image(bytes)

    fun play(bytes: ByteArray, complete: () -> Unit): MediaPlayer = MediaPlayer().apply {
        setDataSource(object : MediaDataSource() {
            override fun getSize(): Long = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val count = minOf(size, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
            override fun close() { bytes.fill(0) }
        })
        setOnCompletionListener { release(); complete() }
        setOnErrorListener { _, _, _ -> release(); complete(); true }
        prepare(); start()
    }
}

object ModelProvisioner {
    /** Local SAF import, checksum, space check and atomic replacement. Existing model survives a failed import. */
    @android.annotation.SuppressLint("ApplySharedPref") // off the UI thread; checksum registration must be durable
    fun import(context: Context, uri: Uri, variant: String, expectedSha: String, progress: (Long) -> Unit): String {
        require(variant in setOf("E2B", "E4B"))
        require(expectedSha.matches(Regex("[a-fA-F0-9]{64}"))) { "Enter the publisher's 64-character SHA-256 checksum." }
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val destination = File(directory, "gemma-4-$variant-it.litertlm")
        val partial = File(directory, "model-import.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(directory.usableSpace > n + 32L * 1024 * 1024) { "Not enough space. The existing model is unchanged." }
                    output.write(buffer, 0, n); digest.update(buffer, 0, n); total += n; progress(total)
                }
                output.fd.sync()
            } } ?: error("Choose a model file stored on this device.")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expectedSha, true) && partial.length() > 1_000_000) { "Checksum mismatch. The existing model is unchanged." }
            check(partial.renameTo(destination)) { "Could not commit the model. Retry the import." }
            context.getSharedPreferences("model-integrity", Context.MODE_PRIVATE).edit()
                .putString(destination.name, actual).putLong(destination.name + ":size", destination.length()).commit()
            return actual
        } finally { partial.delete() }
    }
}
