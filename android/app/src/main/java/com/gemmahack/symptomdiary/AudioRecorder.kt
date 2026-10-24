package com.gemmahack.symptomdiary

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records 16 kHz mono 16-bit PCM and returns it as WAV bytes. Stops itself after MAX_SECONDS. */
class AudioRecorder {
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    var isRecording = false
        private set

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling start()
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("麦克风不可用")
        }
        synchronized(pcm) { pcm.reset() }
        recorder = rec
        isRecording = true
        rec.startRecording()
        worker = Thread {
            val buf = ByteArray(4096)
            while (isRecording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
                if (pcm.size() >= MAX_SECONDS * SAMPLE_RATE * 2) isRecording = false
            }
        }.also { it.start() }
    }

    fun stop(): ByteArray {
        isRecording = false
        worker?.join(1000)
        recorder?.run {
            stop()
            release()
        }
        recorder = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        return wav(data)
    }

    private fun wav(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 30
    }
}
