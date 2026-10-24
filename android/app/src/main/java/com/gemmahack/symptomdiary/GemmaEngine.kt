package com.gemmahack.symptomdiary

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The ONLY file that touches the LiteRT-LM API.
 *
 * Written against the docs for litertlm-android 0.18.x (https://ai.google.dev/edge/litert-lm/android)
 * without compiling it. If a name differs in the version Gradle downloads, fix it here; the rest of
 * the app only calls load(), generate(), generateWithAudio() and close().
 *
 * Older releases used a different spelling. If these lines do not compile, try:
 *  - Backend.CPU() / Backend.GPU()  ->  Backend.CPU / Backend.GPU
 *  - Contents.of(...)               ->  Message.of(...)
 *  - drop audioBackend / cacheDir from EngineConfig
 * The reply text is taken from sendMessage(...).toString(); if that prints an object instead of
 * text, look for a text/contents property on the returned value.
 */
class ModelNotFoundException(val paths: List<String>) : Exception("model file not found")

class GemmaEngine(private val context: Context) {

    data class Result(val text: String, val seconds: Double)

    // One dedicated thread for every native call: GPU contexts do not like hopping threads.
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "gemma") }.asCoroutineDispatcher()
    private val lock = Mutex()
    private var engine: Engine? = null

    var modelPath: String? = null
        private set
    var backendName: String = "-"
        private set
    var audioEnabled: Boolean = false
        private set

    /** Where we look for the model, in order. Push it with adb (see README). */
    fun candidateFiles(): List<File> {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), File("/data/local/tmp/llm"))
        return dirs.flatMap { dir -> MODEL_FILES.map { File(dir, it) } }
    }

    /** Loads the model once. Returns load time in seconds. */
    suspend fun load(): Double = withContext(dispatcher) {
        lock.withLock {
            if (engine != null) return@withLock 0.0
            val file = candidateFiles().firstOrNull { it.canRead() }
                ?: throw ModelNotFoundException(candidateFiles().map { it.path })
            val t0 = System.nanoTime()
            // Try the fastest setup first; fall back if the phone or the model file cannot do it.
            val attempts = listOf("GPU" to true, "CPU" to true, "GPU" to false, "CPU" to false)
            var last: Throwable? = null
            for ((backend, audio) in attempts) {
                try {
                    engine = create(file, backend, audio)
                    backendName = backend
                    audioEnabled = audio
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "load failed with backend=$backend audio=$audio", t)
                    last = t
                }
            }
            if (engine == null) throw IllegalStateException(last?.message ?: "unknown error", last)
            modelPath = file.path
            (System.nanoTime() - t0) / 1e9
        }
    }

    private fun backend(name: String): Backend = if (name == "GPU") Backend.GPU() else Backend.CPU()

    private fun create(file: File, backendName: String, audio: Boolean): Engine {
        val config = if (audio) {
            EngineConfig(
                modelPath = file.path,
                backend = backend(backendName),
                audioBackend = Backend.CPU(), // audio input for Gemma 4 E2B / E4B
                cacheDir = context.cacheDir.path, // faster second load
            )
        } else {
            EngineConfig(modelPath = file.path, backend = backend(backendName), cacheDir = context.cacheDir.path)
        }
        val e = Engine(config)
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() } // free native memory before the next attempt
            throw t
        }
        return e
    }

    /** One stateless turn: a fresh conversation, text in, text out. */
    suspend fun generate(prompt: String): Result = turn { conversation ->
        conversation.sendMessage(Contents.of(Content.Text(prompt))).toString()
    }

    /** Audio (16 kHz mono WAV bytes) plus an instruction in, text out. */
    suspend fun generateWithAudio(wav: ByteArray, prompt: String): Result {
        if (!audioEnabled) error("audio input is not enabled for this model on this phone")
        return turn { conversation ->
            conversation.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text(prompt))).toString()
        }
    }

    private suspend fun turn(block: (Conversation) -> String): Result = withContext(dispatcher) {
        lock.withLock {
            val e = engine ?: error("model not loaded")
            val t0 = System.nanoTime()
            val conversation = e.createConversation()
            val text = try {
                block(conversation)
            } finally {
                conversation.close()
            }
            Result(text.trim(), (System.nanoTime() - t0) / 1e9)
        }
    }

    /** Waits for any running call, then frees the model. Safe to call from the main thread. */
    fun close() {
        CoroutineScope(dispatcher).launch {
            lock.withLock {
                engine?.close()
                engine = null
            }
        }
    }

    companion object {
        private const val TAG = "GemmaEngine"

        /** First file found wins: E4B (better quality) if it is on the phone, otherwise E2B. */
        val MODEL_FILES = listOf("gemma-4-E4B-it.litertlm", "gemma-4-E2B-it.litertlm")
    }
}
