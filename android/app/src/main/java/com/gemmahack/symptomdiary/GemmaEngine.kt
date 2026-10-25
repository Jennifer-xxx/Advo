package com.gemmahack.symptomdiary

import android.content.Context
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The only LiteRT-LM adapter. All native operations, including close, use the same GPU thread. */
class GemmaEngine(private val context: Context) {
    data class Result(val text: String, val seconds: Double)
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "with-you-gemma") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var engine: Engine? = null
    var modelName = "Not loaded"; private set
    var backendName = "Unavailable"; private set
    var audioEnabled = false; private set
    var visionEnabled = false; private set
    val ready: Boolean get() = engine != null

    fun candidateFiles(preference: String): List<File> {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val names = if (preference == "E4B") listOf("E4B", "E2B") else listOf("E2B", "E4B")
        return names.map { File(directory, "gemma-4-$it-it.litertlm") }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun load(preference: String): Double = withContext(dispatcher) {
        mutex.withLock {
            if (engine != null) return@withLock 0.0
            val file = candidateFiles(preference).firstOrNull { it.isFile && it.length() > 1_000_000 }
                ?: error("Import a Gemma model in Offline resources. Text and records remain available.")
            context.getSharedPreferences("model-integrity", Context.MODE_PRIVATE).getString(file.name, null)?.let { expected ->
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val bytes = ByteArray(1024 * 1024)
                    while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
                }
                check(hash.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "The model checksum changed. Reimport the original model." }
            }
            Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
            val started = System.nanoTime()
            val attempts = listOf(Triple("GPU", true, true), Triple("GPU", true, false),
                Triple("GPU", false, false), Triple("CPU", true, true), Triple("CPU", false, false))
            for ((backend, audio, vision) in attempts) {
                val candidate = Engine(EngineConfig(modelPath = file.path,
                    backend = if (backend == "GPU") Backend.GPU() else Backend.CPU(),
                    audioBackend = if (audio) Backend.CPU() else null,
                    visionBackend = if (vision) Backend.GPU() else null,
                    maxNumTokens = 8192, maxNumImages = 1, cacheDir = context.cacheDir.path))
                try {
                    candidate.initialize()
                    engine = candidate; backendName = backend; modelName = file.name
                    audioEnabled = audio; visionEnabled = vision
                    return@withLock (System.nanoTime() - started) / 1e9
                } catch (_: Exception) { runCatching { candidate.close() } }
            }
            error("This model could not initialize on this device. Retry or import a compatible E2B model.")
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun turn(system: String, contents: Contents, maxOutput: Int = 1400): Result = withContext(dispatcher) {
        mutex.withLock {
            val e = engine ?: error("Gemma is not ready. Your input has been saved for later.")
            val started = System.nanoTime()
            val config = ConversationConfig(systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 1, topP = 0.9, temperature = 0.1),
                maxOutputToken = maxOutput, enableSpeculativeDecoding = backendName == "GPU")
            e.createConversation(config).use { conversation ->
                Result(conversation.sendMessage(contents).toString().trim(), (System.nanoTime() - started) / 1e9)
            }
        }
    }

    suspend fun generate(system: String, prompt: String): Result {
        require(prompt.length <= 26000) { "This source is too long for one pass. Split it or review it manually." }
        return turn(system, Contents.of(prompt))
    }

    suspend fun audio(wav: ByteArray, system: String, prompt: String): Result {
        check(audioEnabled) { "Gemma audio is unavailable for this model configuration." }
        return turn(system, Contents.of(Content.AudioBytes(wav), Content.Text(prompt)), 1000)
    }

    suspend fun image(bytes: ByteArray, system: String, prompt: String): Result {
        check(visionEnabled) { "Vision is unavailable. Type the paper wording or retry another model." }
        return turn(system, Contents.of(Content.ImageBytes(bytes), Content.Text(prompt)), 1800)
    }

    suspend fun unload() = withContext(dispatcher) {
        mutex.withLock { engine?.close(); engine = null; audioEnabled = false; visionEnabled = false }
    }

    suspend fun close() { unload(); dispatcher.close() }
}
