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
    fun start(finished: (ByteArray?) -> Unit) {
        check(!active)
        val rate = VisitRecordingService.RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        check(record.state == AudioRecord.STATE_INITIALIZED)
        record.startRecording(); active = true
        worker = Thread {
            val out = ByteArrayOutputStream()
            var success = true
            try {
                val buffer = ByteArray(4096)
                while (active && out.size() < rate * 2 * 12) {
                    val read = record.read(buffer, 0, buffer.size)
                    check(read >= 0)
                    if (read > 0) out.write(buffer, 0, read)
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
