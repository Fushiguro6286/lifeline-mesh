package com.lifeline.mesh.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * [LlmEngine] on LiteRT-LM 0.18 (API checked against the AAR, 9 Oct 2026): `Engine(EngineConfig(…))`, `initialize()`,
 * one `Conversation` per call with the feature's token cap, thinking off and greedy sampling, streamed with
 * `sendMessageAsync`, stopped with `cancelProcess()` on timeout. Timings come from the runtime's own `BenchmarkInfo`.
 */
// BenchmarkInfo is marked experimental in 0.18; if it changes, timings fall back to our own stopwatch (firstAt).
@OptIn(ExperimentalApi::class)
class LiteRtEngine private constructor(
    override val model: ModelSpec,
    override val backend: LlmBackend,
    private val engine: Engine,
    /** Time `initialize()` took, measured here. */
    val initMs: Long,
) : LlmEngine {

    private val lock = Mutex()
    /** Constrained JSON is tried once; if the model or runtime rejects it, prompts alone are used from then on. */
    @Volatile private var responseFormatWorks = true

    override suspend fun generate(request: LlmRequest): LlmResult = lock.withLock {
        withContext(Dispatchers.IO) { run(request, useSchema = request.jsonSchema != null && responseFormatWorks).copy(feature = request.feature) }
    }

    private suspend fun run(request: LlmRequest, useSchema: Boolean): LlmResult {
        val t0 = System.nanoTime()
        fun since() = (System.nanoTime() - t0) / 1_000_000
        val conv = try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(request.system),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                    maxOutputToken = request.maxTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    enableResponseFormat = useSchema,
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "createConversation failed", e)
            return LlmResult("", null, null, null, null, since(), error = e.message ?: e.javaClass.simpleName)
        }
        val sb = StringBuilder()
        var firstAt = -1L
        var chunks = 0
        return try {
            val done = withTimeoutOrNull(request.timeoutMs) {
                val flow = if (useSchema) conv.sendMessageAsync(request.user, responseFormat = ResponseFormat.json(request.jsonSchema!!))
                else conv.sendMessageAsync(request.user)
                flow.collect { m ->
                    val piece = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (piece.isEmpty()) return@collect
                    chunks++
                    if (firstAt < 0) firstAt = since()
                    // Chunks are deltas; guard against a runtime that sends the whole text so far.
                    if (sb.isNotEmpty() && piece.length > sb.length && piece.startsWith(sb)) { sb.setLength(0) }
                    sb.append(piece)
                }
                true
            }
            if (done == null) runCatching { conv.cancelProcess() }
            val total = since()
            // Measured on 9 Oct 2026 (iQOO 15): getBenchmarkInfo() is empty unless benchmark mode is on, and the stream
            // delivers one token per chunk (32 chunks for a 32-token cap). So timings are our own stopwatch:
            // TTFT = first chunk; decode rate = tokens after the first / time after the first.
            val bi = runCatching { conv.getBenchmarkInfo() }.getOrNull()
            val convTokens = runCatching { conv.getTokenCount() }.getOrNull()
            val decodeTps = bi?.lastDecodeTokensPerSecond?.takeIf { it > 0 }
                ?: if (chunks >= 2 && total > firstAt) (chunks - 1) * 1000.0 / (total - firstAt) else null
            LlmResult(
                text = sb.toString(),
                ttftMs = bi?.timeToFirstTokenInSecond?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: firstAt.takeIf { it >= 0 },
                decodeTokPerSec = decodeTps,
                promptTokens = bi?.lastPrefillTokenCount?.takeIf { it > 0 } ?: convTokens?.let { (it - chunks).coerceAtLeast(0) },
                outputTokens = bi?.lastDecodeTokenCount?.takeIf { it > 0 } ?: chunks,
                totalMs = total,
                timedOut = done == null,
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) { runCatching { conv.cancelProcess() }; throw e }
            Log.w(TAG, "generate failed (schema=$useSchema)", e)
            if (useSchema) {
                responseFormatWorks = false
                Log.i(TAG, "constrained JSON not available; using the prompt alone from now on")
            }
            LlmResult(sb.toString(), null, null, null, null, since(), error = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conv.close() }
        }
    }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        private const val TAG = "LiteRtEngine"

        /** Loads [file] on [backend]. Blocking native work: call off the main thread. Throws on failure. */
        suspend fun open(context: Context, model: ModelSpec, file: File, backend: LlmBackend): LiteRtEngine = withContext(Dispatchers.IO) {
            val b: Backend = when (backend) {
                LlmBackend.GPU -> Backend.GPU()
                LlmBackend.CPU -> Backend.CPU()
                LlmBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            }
            val engine = Engine(EngineConfig(modelPath = file.absolutePath, backend = b, maxNumTokens = 2048, cacheDir = context.cacheDir.path))
            val t0 = System.nanoTime()
            try {
                engine.initialize()
            } catch (e: Throwable) {
                runCatching { engine.close() }
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "loaded ${model.id} on $backend in $ms ms")
            LiteRtEngine(model, backend, engine, ms)
        }
    }
}
