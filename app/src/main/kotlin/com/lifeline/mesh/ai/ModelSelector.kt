package com.lifeline.mesh.ai

import kotlinx.serialization.Serializable

/** What happened to one candidate during selection, for the "Why this model?" list. */
@Serializable
data class Attempt(
    val modelId: String,
    val modelName: String,
    val backend: LlmBackend,
    val installed: Boolean,
    /** "Chosen", "Not installed", "Failed to load: …", "Too slow: TTFT 3.1 s, 8 tok/s". */
    val outcome: String,
    val ttftMs: Long? = null,
    val decodeTokPerSec: Double? = null,
    val languages: Map<String, Boolean> = emptyMap(),
)

/** The saved result of a selection run. [chosenId] null = rules only. */
@Serializable
data class Selection(
    val chosenId: String?,
    val chosenName: String?,
    val backend: LlmBackend?,
    val ttftMs: Long?,
    val decodeTokPerSec: Double?,
    /** Language tag → passed the probe. Only passed languages use the model for extraction and questions. */
    val languages: Map<String, Boolean>,
    val attempts: List<Attempt>,
    val rejected: List<String>,
    /** A better tier that fits this phone but is not installed. */
    val betterId: String? = null,
    val betterName: String? = null,
    val betterSizeBytes: Long? = null,
    val betterDownloadable: Boolean = false,
    val atMs: Long = 0,
) {
    val rulesOnly get() = chosenId == null
}

/** Progress for the status line. */
enum class SelectorStage { LOADING, SPEED_PROBE, LANGUAGE_PROBE }

/**
 * The selection algorithm (docs/MODEL_SELECTOR.md section 4), with model loading behind [open] so it is unit-tested
 * with a fake engine. Loads and probes installed candidates best-first, never two at once, and keeps the first that
 * passes the speed probe; then runs the language probe on it. Nothing passes → rules only.
 */
object ModelSelector {
    class Outcome(val selection: Selection, val engine: LlmEngine?)

    suspend fun select(
        profile: DeviceProfile,
        catalog: List<ModelSpec>,
        installedIds: Set<String>,
        open: suspend (ModelSpec, LlmBackend) -> LlmEngine,
        now: Long = System.currentTimeMillis(),
        onProbe: (Attempt) -> Unit = {},
        onStage: (ModelSpec, LlmBackend, SelectorStage) -> Unit = { _, _, _ -> },
    ): Outcome {
        val shortlist = ModelShortlist.build(profile, catalog, installedIds)
        val attempts = ArrayList<Attempt>()
        var chosen: Pair<LlmEngine, Attempt>? = null

        for (c in shortlist.candidates) {
            if (chosen != null) break
            if (!c.installed) {
                attempts += Attempt(c.model.id, c.model.name, c.backend, false, "Not installed")
                continue
            }
            onStage(c.model, c.backend, SelectorStage.LOADING)
            val engine = try {
                open(c.model, c.backend)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val a = Attempt(c.model.id, c.model.name, c.backend, true, "Failed to load: ${e.message ?: e.javaClass.simpleName}")
                attempts += a; onProbe(a)
                continue
            }
            onStage(c.model, c.backend, SelectorStage.SPEED_PROBE)
            val probe = speedProbe(engine)
            if (!probe.passesSpeed()) {
                engine.close()
                val why = probe.error?.let { "Probe failed: $it" }
                    ?: "Too slow: TTFT %.1f s, %.0f tok/s".format(probe.ttftMs / 1000.0, probe.decodeTokPerSec)
                val a = Attempt(c.model.id, c.model.name, c.backend, true, why, probe.ttftMs, probe.decodeTokPerSec)
                attempts += a; onProbe(a)
                continue
            }
            onStage(c.model, c.backend, SelectorStage.LANGUAGE_PROBE)
            val langs = languageProbe(engine)
            val a = Attempt(c.model.id, c.model.name, c.backend, true, "Chosen", probe.ttftMs, probe.decodeTokPerSec, langs)
            attempts += a; onProbe(a)
            chosen = engine to a
        }

        // Best tier that fits but is not installed, if it beats what was chosen.
        val chosenTier = chosen?.first?.model?.tier ?: Int.MAX_VALUE
        val better = shortlist.candidates.filter { !it.installed && it.model.tier < chosenTier }.minByOrNull { it.model.tier }?.model

        val a = chosen?.second
        val selection = Selection(
            chosenId = a?.modelId, chosenName = a?.modelName, backend = a?.backend,
            ttftMs = a?.ttftMs, decodeTokPerSec = a?.decodeTokPerSec,
            languages = a?.languages ?: emptyMap(),
            attempts = attempts,
            rejected = shortlist.rejected.map { r -> "${r.model.name}${r.backend?.let { " · $it" } ?: ""}: ${r.reason}" },
            betterId = better?.id, betterName = better?.name, betterSizeBytes = better?.sizeBytes,
            betterDownloadable = better != null && !better.gated && better.downloadUrl != null,
            atMs = now,
        )
        return Outcome(selection, chosen?.first)
    }

    /** One fixed short prompt, 32 output tokens; TTFT and decode rate as measured by the runtime. */
    suspend fun speedProbe(engine: LlmEngine): ProbeResult {
        val r = engine.generate(LlmRequest("probe", Prompts.PROBE_SYSTEM, Prompts.PROBE_USER, maxTokens = 32, timeoutMs = 20_000))
        if (!r.ok) return ProbeResult(r.ttftMs ?: r.totalMs, r.decodeTokPerSec ?: 0.0, loaded = true, error = r.error ?: if (r.timedOut) "timed out" else "no output")
        return ProbeResult(r.ttftMs ?: r.totalMs, r.decodeTokPerSec ?: 0.0, loaded = true)
    }

    /** Both test sentences per language must give valid, correct JSON. */
    suspend fun languageProbe(engine: LlmEngine): Map<String, Boolean> =
        AlertExtractor.PROBES.mapValues { (_, cases) ->
            cases.all { (text, needs, people) ->
                val r = engine.generate(AlertExtractor.request(text, engine.backend).copy(timeoutMs = 15_000))
                AlertExtractor.probePasses(if (r.ok) AlertExtractor.parse(r.text, text) else null, needs, people)
            }
        }
}
