package com.gemmahack.symptomdiary

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** One microphone, bounded 12-second chunks, encrypted before publishing their source references. */
class VisitRecordingService : Service() {
    private val repo get() = (application as WithYouApplication).repository
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var requestedEnd = Recording.STOPPED
    private var audio: AudioRecord? = null
    private var visitId: String? = null
    private var focus: AudioFocusRequest? = null
    private val manager get() = getSystemService(AudioManager::class.java)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("visit") ?: visitId
        when (intent?.action) {
            "pause" -> { paused = true; id?.let { executorSafeUpdate(it, Recording.PAUSED) } }
            "resume" -> { if (id != null && repo.visit(id).hasConsent) { paused = false; executorSafeUpdate(id, Recording.RECORDING) } }
            "stop", "withdraw" -> end(Recording.STOPPED)
            else -> if (!running && id != null) startCapture(id)
        }
        return START_NOT_STICKY
    }

    private fun executorSafeUpdate(id: String, status: Recording) {
        // Do not queue behind the capture loop on its executor.
        Thread { runCatching { repo.updateVisit(id) { if (running && it.hasConsent) it.copy(recording = status) else it } } }.start()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startCapture(id: String) {
        if (!VisitRules.mayRecord(repo.visit(id)) || ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        visitId = id
        try {
            val channel = NotificationChannel("visit-recording", "Visit recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("visit", id).putExtra("live", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, channel.id).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Advo · microphone active").setContentText("Recording on this phone. Tap Stop to finish.")
                .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build()
            ServiceCompat.startForeground(this, 42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, RATE))
            audio = rec
            check(rec.state == AudioRecord.STATE_INITIALIZED)
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ change -> if (change < 0) end(Recording.INTERRUPTED) }, mainHandler).build()
            check(manager.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            rec.registerAudioRecordingCallback(mainExecutor, object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.any { it.clientAudioSessionId == rec.audioSessionId && it.isClientSilenced }) end(Recording.INTERRUPTED)
                }
            })
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            running = true; paused = false
            executor.execute { capture(id, rec) }
        } catch (_: Exception) {
            requestedEnd = Recording.INTERRUPTED
            runCatching { audio?.release() }; audio = null
            runCatching { repo.updateVisit(id) { v -> v.copy(recording = Recording.INTERRUPTED,
                consents = v.consents.map { if (it.active) it.copy(endedAt = now()) else it }) } }
            stopSelf()
        }
    }

    private fun capture(id: String, rec: AudioRecord) {
        val chunk = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var wasPaused = false
        try {
            repo.updateVisit(id) { it.copy(recording = Recording.RECORDING) }
            while (running) {
                if (paused) {
                    if (!wasPaused) { rec.stop(); flush(id, chunk); wasPaused = true }
                    Thread.sleep(80)
                    continue
                }
                if (wasPaused) { rec.startRecording(); wasPaused = false }
                val read = rec.read(buffer, 0, buffer.size)
                if (read < 0) error("Microphone interrupted")
                if (read > 0) chunk.write(buffer, 0, read)
                if (chunk.size() >= RATE * 2 * CHUNK_SECONDS) flush(id, chunk)
            }
        } catch (_: Exception) { requestedEnd = Recording.INTERRUPTED }
        finally {
            runCatching { flush(id, chunk) }.onFailure { requestedEnd = Recording.INTERRUPTED }
            runCatching { rec.stop() }; rec.release(); audio = null
            focus?.let { manager.abandonAudioFocusRequest(it) }
            runCatching { repo.updateVisit(id) { v -> v.copy(recording = requestedEnd,
                consents = v.consents.map { if (it.active) it.copy(endedAt = now()) else it }) } }
            mainHandler.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun flush(id: String, chunk: ByteArrayOutputStream) {
        if (chunk.size() == 0) return
        check(repo.vault.directory.usableSpace > 16 * 1024 * 1024) { "Storage is full" }
        val bytes = chunk.toByteArray()
        val duration = bytes.size.toLong() * 1000 / (RATE * 2)
        val blob = repo.vault.put(wav(bytes))
        try {
            repo.updateVisit(id) { v -> v.copy(recordedMs = v.recordedMs + duration,
                sources = v.sources + Source(kind = SourceKind.VISIT_AUDIO, blob = blob, mime = "audio/wav",
                    startMs = v.recordedMs, endMs = v.recordedMs + duration, status = Processing.QUEUED)) }
        } catch (e: Exception) { repo.vault.delete(blob); throw e }
        chunk.reset()
    }

    private fun end(status: Recording) { requestedEnd = status; running = false; if (audio == null) stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { end(Recording.INTERRUPTED); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { end(Recording.INTERRUPTED); executor.shutdown(); super.onDestroy() }

    companion object {
        const val RATE = 16000
        const val CHUNK_SECONDS = 12
        fun command(context: Context, visitId: String, action: String) {
            val intent = Intent(context, VisitRecordingService::class.java).setAction(action).putExtra("visit", visitId)
            if (action == "start") ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
        fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array() + pcm
    }
}
