package com.lifeline.mesh.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lifeline.mesh.BuildConfig
import com.lifeline.mesh.data.AppDatabase
import com.lifeline.mesh.data.MetricEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the status strip and the On-device AI card show. */
sealed interface AiStatus {
    data object Idle : AiStatus
    data class Loading(val model: String, val backend: LlmBackend) : AiStatus
    data class Testing(val model: String, val backend: LlmBackend, val stage: SelectorStage) : AiStatus
    data class Ready(val model: String, val backend: LlmBackend) : AiStatus
    data class RulesOnly(val reason: String?) : AiStatus
}

/** One measured model call, decoded from its metric row for the Benchmarks screen. */
data class LlmCallRecord(
    val feature: String, val model: String, val backend: String, val ttftMs: Long?, val tokPerSec: Double?,
    val promptTokens: Int?, val outputTokens: Int?, val totalMs: Long, val ok: Boolean, val at: Long,
)

/**
 * Owns the local model (CLAUDE.md 9.8): picks it per phone with [ModelSelector] (or reuses the saved choice), keeps it
 * loaded, runs calls one at a time with timeouts, records every call's timings as metrics, falls back to rules when
 * anything goes wrong, and listens to [AiGovernor]. Nothing in the mesh or the send flow ever waits on it.
 */
class AiRuntime(private val context: Context, private val database: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val governor = AiGovernor(context)
    val installer = ModelInstaller(context)
    val catalog: List<ModelSpec> by lazy { ModelCatalog.parse(context.assets.open("models.json").bufferedReader().use { it.readText() }) }

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Idle)
    val status: StateFlow<AiStatus> = _status
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection
    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device
    private val _installed = MutableStateFlow<Map<String, File>>(emptyMap())
    val installed: StateFlow<Map<String, File>> = _installed

    @Volatile private var engine: LlmEngine? = null
    /** Held while selecting, loading or benchmarking; model calls skip (rules) instead of waiting behind it. */
    private val busy = Mutex()
    private var job: Job? = null
    private var failures = 0
    private val unhealthy = HashSet<Pair<String, LlmBackend>>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        governor.start()
        warmUp(force = false)
    }

    /** Select and load in the background. [force] = re-run the probes ("Re-test", new model, removed model). */
    fun warmUp(force: Boolean) {
        if (job?.isActive == true && !force) return
        job?.cancel()
        job = scope.launch { busy.withLock { select(force) } }
    }

    private suspend fun select(force: Boolean) {
        val info = DeviceProfiler.read(context).also { _device.value = it }
        val files = installer.scan(catalog).also { _installed.value = it }
        if (!force && governor.state.value.lowBatteryPowerSave) {
            _status.value = AiStatus.RulesOnly("Battery saver: AI warm-up skipped")
            return
        }
        closeEngine()
        val key = key(info, files)
        val saved = if (force) null else loadSaved(key)
        if (saved != null) {
            _selection.value = saved
            val m = saved.chosenId?.let { id -> catalog.firstOrNull { it.id == id } }
            val f = m?.let { files[it.id] }
            if (saved.rulesOnly || m == null || f == null || saved.backend == null) {
                _status.value = AiStatus.RulesOnly(null)
                return
            }
            _status.value = AiStatus.Loading(m.name, saved.backend)
            try {
                engine = LiteRtEngine.open(context, m, f, saved.backend)
                _status.value = AiStatus.Ready(m.name, saved.backend)
                return
            } catch (e: Throwable) {
                Log.w(TAG, "saved choice no longer loads; re-testing", e)
            }
        }
        val out = ModelSelector.select(
            profile = info.profile,
            catalog = catalog,
            installedIds = files.keys,
            open = { m, b ->
                if ((m.id to b) in unhealthy) error("failed repeatedly earlier in this session")
                LiteRtEngine.open(context, m, files.getValue(m.id), b)
            },
            onProbe = ::recordProbe,
            onStage = { m, b, s -> _status.value = AiStatus.Testing(m.name, b, s) },
        )
        engine = out.engine
        _selection.value = out.selection
        save(key, out.selection)
        _status.value = out.engine?.let { AiStatus.Ready(it.model.name, it.backend) } ?: AiStatus.RulesOnly(null)
        Log.i(TAG, "selection: ${out.selection.chosenName ?: "rules only"} ${out.selection.backend ?: ""} langs=${out.selection.languages}")
    }

    // ------------------------------------------------------------------ calls

    /** True if the model may be used for text in [language] (it passed that language's probe). */
    fun canUse(language: String): Boolean =
        engine != null && _selection.value?.languages?.get(language) == true && !governor.state.value.memoryCritical

    /** LLM extraction; null = use the rules draft. */
    suspend fun extract(text: String): AlertDraft? {
        if (!canUse(scriptLanguage(text))) return null
        return call { e -> AlertExtractor.extract(e, text) }
    }

    /** A model-written clarifying question in [language]; null = use the fixed string. */
    suspend fun question(language: String, missing: QualityGate.Missing): String? {
        if (!canUse(language)) return null
        return call { e -> ClarifyingQuestions.ask(e, language, missing) }
    }

    /**
     * For non-essential features (situation report, ask, translate; Phase 8): refused while the governor pauses AI.
     * [block] returns the validated value (or null) and the raw result for timing.
     */
    suspend fun <T> nonEssential(block: suspend (LlmEngine) -> Pair<T?, LlmResult>): T? {
        if (governor.state.value.pauseNonEssential) return null
        return call(block)
    }

    private suspend fun <T> call(block: suspend (LlmEngine) -> Pair<T?, LlmResult>): T? {
        val e = engine ?: return null
        if (busy.isLocked) return null // selecting or benchmarking: don't make the user wait
        val (value, r) = try { block(e) } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null to LlmResult("", null, null, null, null, 0, error = t.message)
        }
        record(r, e, value != null)
        if (r.error != null || r.timedOut) {
            if (++failures >= 3) {
                // Mark this model/backend unhealthy for the session; re-select (CPU, then the next tier).
                Log.w(TAG, "3 failures in a row on ${e.model.id} ${e.backend}; falling back")
                unhealthy += e.model.id to e.backend
                failures = 0
                warmUp(force = true)
            }
        } else failures = 0
        return value
    }

    /** Three phrased report lines, validated against the facts; null = show the computed template. */
    suspend fun report(f: SituationFacts): List<String>? = nonEssential { e ->
        val r = e.generate(SituationReport.request(f))
        (if (r.ok) SituationReport.validate(r.text, f) else null) to r
    }

    /** One phrased weather sentence, validated against the facts; null = show the computed text. English only. */
    suspend fun weather(f: WeatherFacts): String? {
        if (!canUse("en")) return null
        return nonEssential { e ->
            val r = e.generate(WeatherLine.request(f))
            (if (r.ok) WeatherLine.validate(r.text, f) else null) to r
        }
    }

    /** A model answer that cites existing alerts; null = use the rule-based answer. */
    suspend fun ask(question: String, ranked: List<com.lifeline.mesh.triage.RankedAlert>): MeshAnswer? = nonEssential { e ->
        val r = e.generate(AskMesh.request(question, ranked))
        (if (r.ok) AskMesh.validate(r.text, minOf(ranked.size, 25)) else null) to r
    }

    /** Translation into [target]; null = keep the original. */
    suspend fun translate(text: String, target: String): String? {
        if (!canUse(target)) return null
        return nonEssential { e ->
            val r = e.generate(Translator.request(text, target))
            (if (r.ok) Translator.validate(r.text, text, target) else null) to r
        }
    }

    /** The first-aid entry id the model picks for [question] ([FirstAid.NONE] for none); null = use the keyword match. */
    suspend fun firstAid(question: String, entries: List<FirstAidEntry>): String? {
        if (entries.isEmpty() || !canUse(scriptLanguage(question))) return null
        return nonEssential { e ->
            val r = e.generate(FirstAid.request(question, entries))
            (if (r.ok) FirstAid.validate(r.text, entries) else null) to r
        }
    }

    /** One demo sentence through rules and the model, compared with the expected answer (PROMPTS.md, Phase 4). */
    data class DemoResult(val text: String, val expected: AlertDraft, val rules: AlertDraft, val ai: AlertDraft?, val usedAi: Boolean) {
        private fun right(d: AlertDraft?) = d != null && d.needs == expected.needs && d.people == expected.people
        val rulesCorrect get() = right(rules)
        val aiCorrect get() = right(ai)
    }

    /** Runs the four demo sentences (calls are recorded like any other). Languages that failed the probe use rules only. */
    suspend fun testDemoSentences(): List<DemoResult> = DEMO.map { (text, expected) ->
        val rules = RuleExtractor.extract(text)
        val usable = canUse(scriptLanguage(text))
        DemoResult(text, expected, rules, if (usable) extract(text) else null, usable)
    }

    // ------------------------------------------------------------------ memory, install, benchmark

    fun onTrimMemory(level: Int) {
        if (governor.onTrimMemory(level)) scope.launch {
            busy.withLock { closeEngine() }
            _status.value = AiStatus.RulesOnly("Model unloaded: low memory")
        }
    }

    fun onForeground() {
        if (governor.state.value.memoryCritical) {
            governor.memoryRecovered()
            warmUp(force = false)
        }
    }

    suspend fun import(uri: Uri, progress: (Long, Long) -> Unit): Result<ModelSpec> =
        installer.import(uri, catalog, progress).also { if (it.isSuccess) warmUp(force = true) }

    suspend fun download(model: ModelSpec, progress: (Long, Long) -> Unit): Result<ModelSpec> =
        installer.download(model, progress).also { if (it.isSuccess) warmUp(force = true) }

    fun remove(model: ModelSpec) {
        scope.launch {
            busy.withLock { if (engine?.model?.id == model.id) closeEngine() }
            installer.remove(model)
            warmUp(force = true)
        }
    }

    /**
     * Benchmarks screen: the chosen model on [backend], a fixed prompt [runs] times, each call recorded as a metric
     * (feature "benchmark"). The chosen engine is reloaded afterwards.
     */
    fun benchmark(backend: LlmBackend, runs: Int = 5, done: (String?) -> Unit) {
        scope.launch {
            val sel = _selection.value
            val m = sel?.chosenId?.let { id -> catalog.firstOrNull { it.id == id } }
            val f = m?.let { _installed.value[it.id] }
            if (m == null || f == null) { done("No model installed and chosen"); return@launch }
            busy.withLock {
                val chosenBackend = engine?.backend
                closeEngine()
                _status.value = AiStatus.Testing(m.name, backend, SelectorStage.SPEED_PROBE)
                var err: String? = null
                try {
                    val e = LiteRtEngine.open(context, m, f, backend)
                    try {
                        repeat(runs) {
                            val r = e.generate(LlmRequest("benchmark", Prompts.PROBE_SYSTEM, Prompts.PROBE_USER, maxTokens = 32, timeoutMs = 30_000))
                            record(r, e, r.ok)
                        }
                    } finally { e.close() }
                } catch (t: Throwable) {
                    err = "${m.name} would not load on $backend: ${t.message}"
                    recordFailure("benchmark", m, backend, t.message)
                }
                // Restore the chosen engine.
                if (chosenBackend != null) {
                    runCatching { engine = LiteRtEngine.open(context, m, f, chosenBackend) }
                    _status.value = engine?.let { AiStatus.Ready(it.model.name, it.backend) } ?: AiStatus.RulesOnly(null)
                } else _status.value = AiStatus.RulesOnly(null)
                done(err)
            }
        }
    }

    private fun closeEngine() {
        engine?.close()
        engine = null
    }

    // ------------------------------------------------------------------ metrics

    private fun record(r: LlmResult, e: LlmEngine, ok: Boolean) {
        val feature = r.feature.ifBlank { "?" }
        write(MetricEntity(
            name = "llm", value = r.totalMs.toDouble(), unit = "ms", backend = e.backend.name, simulated = false,
            timestamp = System.currentTimeMillis(),
            notes = "feature=$feature;model=${e.model.id};ttft=${r.ttftMs ?: ""};tps=${r.decodeTokPerSec?.let { "%.1f".format(it) } ?: ""};" +
                "prompt=${r.promptTokens ?: ""};out=${r.outputTokens ?: ""};ok=$ok;timeout=${r.timedOut}",
        ))
    }

    private fun recordFailure(feature: String, m: ModelSpec, b: LlmBackend, why: String?) = write(MetricEntity(
        name = "llm", value = 0.0, unit = "ms", backend = b.name, simulated = false, timestamp = System.currentTimeMillis(),
        notes = "feature=$feature;model=${m.id};ttft=;tps=;prompt=;out=;ok=false;timeout=false;error=${why ?: ""}",
    ))

    private fun recordProbe(a: Attempt) = write(MetricEntity(
        name = "probe", value = (a.ttftMs ?: 0).toDouble(), unit = "ms", backend = a.backend.name, simulated = false,
        timestamp = System.currentTimeMillis(),
        notes = "model=${a.modelId};tps=${a.decodeTokPerSec?.let { "%.1f".format(it) } ?: ""};outcome=${a.outcome};langs=${a.languages}",
    ))

    private fun write(m: MetricEntity) {
        scope.launch(Dispatchers.IO) { runCatching { database.metrics().insert(m) } }
    }

    // ------------------------------------------------------------------ saved choice

    @Serializable
    private data class Saved(val key: String, val selection: Selection)

    private val json = Json { ignoreUnknownKeys = true }
    private val savedFile get() = File(context.filesDir, "ai_selection.json")

    /** Re-test when the phone, its RAM, the app version or the installed models change. */
    private fun key(info: DeviceInfo, files: Map<String, File>) =
        "${info.phone}|${info.chip}|${info.profile.totalRamMb / 1000}|${BuildConfig.VERSION_CODE}|p${Prompts.VERSION}|" +
            files.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.length()}" }

    private fun loadSaved(key: String): Selection? = runCatching {
        json.decodeFromString<Saved>(savedFile.readText()).takeIf { it.key == key }?.selection
    }.getOrNull()

    private fun save(key: String, s: Selection) {
        runCatching { savedFile.writeText(json.encodeToString(Saved.serializer(), Saved(key, s))) }
    }

    companion object {
        private const val TAG = "AiRuntime"

        /** The four demo sentences and what they must give (PROMPTS.md, Phase 4). */
        val DEMO: List<Pair<String, AlertDraft>> = listOf(
            "Water is rising, 5 people on the roof, one elderly person is ill" to
                AlertDraft(setOf(com.lifeline.mesh.packet.Need.TRAPPED, com.lifeline.mesh.packet.Need.MEDICAL), 5, 3, ""),
            "Paani badh gaya hai, chhat par 5 log hain, ek bujurg beemar hain" to
                AlertDraft(setOf(com.lifeline.mesh.packet.Need.TRAPPED, com.lifeline.mesh.packet.Need.MEDICAL), 5, 3, ""),
            "ಮನೆಯಲ್ಲಿ 4 ಜನ ಸಿಲುಕಿದ್ದಾರೆ, ನೀರು ಬೇಕು" to
                AlertDraft(setOf(com.lifeline.mesh.packet.Need.TRAPPED, com.lifeline.mesh.packet.Need.WATER_FOOD), 4, 2, ""),
            "आग लगी है, ३ लोग अंदर हैं" to
                AlertDraft(setOf(com.lifeline.mesh.packet.Need.FIRE), 3, 3, ""),
        )

        /** Parses an "llm" metric row. */
        fun decode(m: MetricEntity): LlmCallRecord? {
            if (m.name != "llm") return null
            val kv = m.notes.orEmpty().split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return LlmCallRecord(
                feature = kv["feature"] ?: "?", model = kv["model"] ?: "?", backend = m.backend ?: "?",
                ttftMs = kv["ttft"]?.toLongOrNull(), tokPerSec = kv["tps"]?.toDoubleOrNull(),
                promptTokens = kv["prompt"]?.toIntOrNull(), outputTokens = kv["out"]?.toIntOrNull(),
                totalMs = m.value.toLong(), ok = kv["ok"] == "true", at = m.timestamp,
            )
        }
    }
}
