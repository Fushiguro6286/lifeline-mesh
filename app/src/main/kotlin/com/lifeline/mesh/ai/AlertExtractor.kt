package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * LLM extraction (CLAUDE.md 9.1): strict JSON, validated; used only if it parses and every field is valid.
 * Otherwise the caller keeps the [RuleExtractor] draft. The model returns labels; Kotlin maps them to [Need]s.
 */
object AlertExtractor {
    private val json = Json { isLenient = false }

    /** Parses the model's answer. Null unless it is one JSON object with exactly-typed, in-range fields. */
    fun parse(answer: String, note: String): AlertDraft? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try { json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject } catch (e: Exception) { null } ?: return null

        val needsEl = obj["needs"] as? JsonArray ?: return null
        val needs = LinkedHashSet<Need>()
        for (e in needsEl) {
            val label = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            needs += LABELS[label] ?: return null
        }
        val people = (obj["people"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        val severity = (obj["severity"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        if (people !in 0..255 || severity !in 0..3) return null
        return AlertDraft(needs, people, severity, note.trim(), DraftSource.AI)
    }

    fun request(text: String, backend: LlmBackend) = LlmRequest(
        feature = "extract",
        system = Prompts.EXTRACT_SYSTEM,
        user = text,
        maxTokens = LlmLimits.EXTRACT_TOKENS,
        timeoutMs = LlmLimits.timeoutFor(backend),
        jsonSchema = Prompts.EXTRACT_SCHEMA,
    )

    /** Runs the model; null (use rules) on timeout, error or invalid output. */
    suspend fun extract(engine: LlmEngine, text: String): Pair<AlertDraft?, LlmResult> {
        val r = engine.generate(request(text, engine.backend))
        return (if (r.ok) parse(r.text, text) else null) to r
    }

    private val LABELS = mapOf(
        "trapped" to Need.TRAPPED, "medical" to Need.MEDICAL, "water_food" to Need.WATER_FOOD,
        "fire" to Need.FIRE, "road_blocked" to Need.ROAD_BLOCKED,
    )

    /**
     * Language probe sentences (docs/MODEL_SELECTOR.md 4c): two per language, NOT the demo sentences.
     * A language passes when both answers are valid, include the expected needs, and give the expected count.
     */
    val PROBES: Map<String, List<Triple<String, Set<Need>, Int>>> = mapOf(
        "en" to listOf(
            Triple("Our house is flooded, 3 people are trapped upstairs", setOf(Need.TRAPPED), 3),
            Triple("My father fell and is bleeding badly, we are 2 people", setOf(Need.MEDICAL), 2),
        ),
        "hi" to listOf(
            Triple("घर में पानी भर गया है, 3 लोग ऊपर फंसे हैं", setOf(Need.TRAPPED), 3),
            Triple("मेरे पिता गिर गए हैं और खून बह रहा है, हम 2 लोग हैं", setOf(Need.MEDICAL), 2),
        ),
        "kn" to listOf(
            Triple("ಮನೆಗೆ ನೀರು ನುಗ್ಗಿದೆ, 3 ಜನ ಮೇಲೆ ಸಿಲುಕಿದ್ದಾರೆ", setOf(Need.TRAPPED), 3),
            Triple("ನನ್ನ ತಂದೆ ಬಿದ್ದಿದ್ದಾರೆ, ರಕ್ತ ಬರುತ್ತಿದೆ, ನಾವು 2 ಜನ ಇದ್ದೇವೆ", setOf(Need.MEDICAL), 2),
        ),
    )

    fun probePasses(draft: AlertDraft?, expectNeeds: Set<Need>, expectPeople: Int): Boolean =
        draft != null && draft.needs.containsAll(expectNeeds) && draft.people == expectPeople
}

/**
 * One calm question for the first missing field (CLAUDE.md 9.3), written by the model in the sender's language
 * (4 s timeout). Null means "use the fixed string for that language".
 */
object ClarifyingQuestions {
    fun request(language: String, missing: QualityGate.Missing) = LlmRequest(
        feature = "question",
        system = Prompts.question(Prompts.languageName(language), Prompts.missingText(missing)),
        user = Prompts.missingText(missing),
        maxTokens = LlmLimits.QUESTION_TOKENS,
        timeoutMs = LlmLimits.QUESTION_TIMEOUT_MS,
    )

    /** One line, a real question, short, no JSON or markup, and in the expected script. */
    fun validate(answer: String, language: String): String? {
        val line = answer.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '“', '”', '*')?.trim() ?: return null
        if (line.length !in 5..160) return null
        if (line.any { it == '{' || it == '}' || it == '#' || it == '<' }) return null
        if (!line.contains('?') && !line.contains('？')) return null
        if (scriptLanguage(line) != language) return null
        return line
    }

    suspend fun ask(engine: LlmEngine, language: String, missing: QualityGate.Missing): Pair<String?, LlmResult> {
        val r = engine.generate(request(language, missing))
        return (if (r.ok) validate(r.text, language) else null) to r
    }
}
