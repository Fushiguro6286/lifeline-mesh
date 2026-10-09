package com.lifeline.mesh.ai

/** One call to the local model. [jsonSchema] asks the runtime for constrained JSON when it supports it. */
data class LlmRequest(
    val feature: String,
    val system: String,
    val user: String,
    val maxTokens: Int,
    val timeoutMs: Long,
    val jsonSchema: String? = null,
)

/**
 * What came back, with timings measured on this phone (CLAUDE.md 9.8: feature, model, backend, prompt tokens, time to
 * first token, tokens/s, total time). [text] is empty when the call failed or timed out.
 */
data class LlmResult(
    val text: String,
    val ttftMs: Long?,
    val decodeTokPerSec: Double?,
    val promptTokens: Int?,
    val outputTokens: Int?,
    val totalMs: Long,
    val timedOut: Boolean = false,
    val error: String? = null,
) {
    val ok get() = !timedOut && error == null && text.isNotBlank()
}

/**
 * A loaded local model. The LiteRT-LM implementation is [LiteRtEngine]; tests use a fake. One request at a time:
 * callers go through [AiRuntime], which serializes them. Every call has a timeout and returns rather than throws.
 */
interface LlmEngine : AutoCloseable {
    val model: ModelSpec
    val backend: LlmBackend
    suspend fun generate(request: LlmRequest): LlmResult
}

/** Per-call timeouts and token budgets (CLAUDE.md 9.8). */
object LlmLimits {
    const val GPU_TIMEOUT_MS = 6_000L
    const val CPU_TIMEOUT_MS = 10_000L
    const val QUESTION_TIMEOUT_MS = 4_000L
    const val EXTRACT_TOKENS = 80
    const val QUESTION_TOKENS = 40
    const val REPORT_TOKENS = 120
    const val ASK_TOKENS = 150
    const val TRANSLATE_TOKENS = 100

    fun timeoutFor(backend: LlmBackend) = if (backend == LlmBackend.GPU) GPU_TIMEOUT_MS else CPU_TIMEOUT_MS
}

/** The language a message is written in, by script: Devanagari = Hindi, Kannada script = Kannada, else English. */
fun scriptLanguage(text: String): String = when {
    text.any { it in 'ऀ'..'ॿ' } -> "hi"
    text.any { it in 'ಀ'..'೿' } -> "kn"
    else -> "en"
}
