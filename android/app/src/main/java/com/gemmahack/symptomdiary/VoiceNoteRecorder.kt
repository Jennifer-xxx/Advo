package com.gemmahack.symptomdiary

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** An explicitly started, short patient voice note. Visit audio uses the separate consented service. */
class VoiceNoteRecorder {
    @Volatile private var active = false
    private var worker: Thread? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start(autoEnd: Boolean = false, finished: (ByteArray?) -> Unit) {
        check(!active)
        val rate = VisitRecordingService.RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Microphone unavailable") }
        try { record.startRecording() } catch (e: Exception) { record.release(); throw e }
        active = true
        worker = Thread {
            val out = ByteArrayOutputStream()
            var success = true
            try {
                val buffer = ByteArray(4096)
                var heardSpeech = false
                var quietBytes = 0
                while (active && out.size() < rate * 2 * if (autoEnd) 30 else 12) {
                    val read = record.read(buffer, 0, buffer.size)
                    check(read >= 0)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        if (autoEnd) {
                            var total = 0L
                            for (i in 0 until read - 1 step 2) {
                                val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                                total += kotlin.math.abs(sample)
                            }
                            val voiced = total / maxOf(1, read / 2) > 260
                            if (voiced) { heardSpeech = true; quietBytes = 0 } else quietBytes += read
                            if (heardSpeech && quietBytes >= rate * 2 * 1.5 && out.size() >= rate * 2 * 2) break
                            if (!heardSpeech && out.size() >= rate * 2 * 8) { success = false; break }
                        }
                    }
                }
            } catch (_: Exception) { success = false }
            finally {
                active = false; runCatching { record.stop() }; record.release()
                finished(if (success && out.size() >= 3200) VisitRecordingService.wav(out.toByteArray()) else null)
            }
        }.also { it.start() }
    }
    fun stop() { active = false }
}
