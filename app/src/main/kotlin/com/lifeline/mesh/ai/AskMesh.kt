package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.RankedAlert

/** An answer and the alert numbers (#n = rank) it is built from. [fromModel] false = the rule-based answer. */
data class MeshAnswer(val text: String, val cites: List<Int>, val fromModel: Boolean)

/**
 * Ask the mesh (CLAUDE.md 9.5). The prompt holds only the ranked merged alerts as numbered facts. A model answer must
 * cite at least one #n and every cited number must exist; otherwise (or on timeout) the rule-based answer is shown.
 * Written from scratch (the web prototype's code was not available); AskMeshFallbackTest pins the rules.
 */
object AskMesh {
    const val NO_MATCH = "No matching alerts."

    val QUICK = listOf("Who needs help first?", "How many people are trapped?", "Any medical cases?", "What is still unacknowledged?")

    fun line(r: RankedAlert): String {
        val a = r.alert
        return "#${r.rank} " + SituationReport.needLabels(a.needs) + " | ${a.people} people" +
            (r.distanceM?.let { " | %.1f km".format(it / 1000) } ?: " | location unknown") +
            " | " + severityWord(a.severity) + (if (a.acked) " | acknowledged" else " | not acknowledged") +
            (if (a.count > 1) " | ${a.count} reports" else "") +
            (if (a.note.isNotBlank()) " | note: \"${a.note.take(60)}\"" else "")
    }

    fun request(question: String, ranked: List<RankedAlert>) = LlmRequest(
        feature = "ask",
        system = "Answer using ONLY the numbered alerts below. Cite alerts like #1. If none match, reply \"$NO_MATCH\"\nAlerts:\n" +
            ranked.take(25).joinToString("\n") { line(it) },
        user = question,
        maxTokens = LlmLimits.ASK_TOKENS,
        timeoutMs = 10_000,
    )

    fun citations(text: String): List<Int> = Regex("#(\\d+)").findAll(text).map { it.groupValues[1].toInt() }.distinct().toList()

    /** Accept only answers that cite, and cite nothing that does not exist. */
    fun validate(answer: String, count: Int): MeshAnswer? {
        val t = answer.trim()
        if (t.isBlank() || t.length > 800) return null
        val c = citations(t)
        if (c.isEmpty() || c.any { it !in 1..count }) return null
        return MeshAnswer(t, c, fromModel = true)
    }

    // ------------------------------------------------------------------ rule-based answers

    fun ruleAnswer(question: String, ranked: List<RankedAlert>): MeshAnswer {
        if (ranked.isEmpty()) return MeshAnswer(NO_MATCH, emptyList(), false)
        val q = question.lowercase()
        fun has(vararg w: String) = w.any { q.contains(it) }
        fun list(rs: List<RankedAlert>, max: Int = 5) = rs.take(max).joinToString("; ") { short(it) }

        return when {
            has("unacknowledged", "not acknowledged", "still open", "waiting", "no reply", "unanswered", "pending") -> {
                val open = ranked.filter { !it.alert.acked }
                if (open.isEmpty()) MeshAnswer("Every alert has been acknowledged.", emptyList(), false)
                else MeshAnswer("${open.size} still open: ${list(open)}.", open.take(5).map { it.rank }, false)
            }
            has("first", "priority", "most urgent", "who needs help", "worst", "top") -> {
                val top = ranked.take(3)
                MeshAnswer("Help first: ${list(top, 3)}.", top.map { it.rank }, false)
            }
            has("how many", "number of", "count", "total") -> {
                val need = needIn(q)
                val rs = if (need != null) ranked.filter { need in it.alert.needs } else ranked
                if (rs.isEmpty()) return MeshAnswer(NO_MATCH, emptyList(), false)
                val people = rs.sumOf { it.alert.people }
                val what = need?.let { "${needWord(it)} " } ?: ""
                MeshAnswer("$people people in ${rs.size} ${what}alerts: " + rs.take(6).joinToString(", ") { "#${it.rank} (${it.alert.people})" } + ".",
                    rs.take(6).map { it.rank }, false)
            }
            needIn(q) != null -> {
                val need = needIn(q)!!
                val rs = ranked.filter { need in it.alert.needs }
                if (rs.isEmpty()) MeshAnswer(NO_MATCH, emptyList(), false)
                else MeshAnswer("${rs.size} ${needWord(need)} ${if (rs.size == 1) "alert" else "alerts"}: ${list(rs)}.", rs.take(5).map { it.rank }, false)
            }
            has("nearest", "closest", "near me", "close") -> {
                val rs = ranked.filter { it.distanceM != null }.sortedBy { it.distanceM }
                if (rs.isEmpty()) MeshAnswer(NO_MATCH, emptyList(), false) else MeshAnswer("Nearest: ${list(rs, 3)}.", rs.take(3).map { it.rank }, false)
            }
            else -> {
                // Plain words from the question found in alert notes.
                val words = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
                val rs = ranked.filter { r -> words.any { w -> r.alert.note.lowercase().contains(w) } }
                if (rs.isEmpty()) MeshAnswer(NO_MATCH, emptyList(), false) else MeshAnswer("Matching: ${list(rs)}.", rs.take(5).map { it.rank }, false)
            }
        }
    }

    private fun short(r: RankedAlert): String {
        val a = r.alert
        return "#${r.rank} ${SituationReport.needLabels(a.needs)}, ${a.people} ${if (a.people == 1) "person" else "people"}" +
            (r.distanceM?.let { d -> ", " + (if (d < 1000) "${(d / 10).toInt() * 10} m" else "%.1f km".format(d / 1000)) + (r.bearingDeg?.let { " " + GeoMath.compass(it) } ?: "") } ?: "") +
            (if (a.severity >= 3) " (urgent)" else "")
    }

    private fun needIn(q: String): Need? = when {
        listOf("trapped", "stuck", "roof", "flood").any { q.contains(it) } -> Need.TRAPPED
        listOf("medical", "injur", "hurt", "sick", "ill", "doctor", "bleed").any { q.contains(it) } -> Need.MEDICAL
        listOf("fire", "smoke", "burn").any { q.contains(it) } -> Need.FIRE
        listOf("water", "food", "hungry", "thirst").any { q.contains(it) } -> Need.WATER_FOOD
        listOf("road", "blocked", "bridge").any { q.contains(it) } -> Need.ROAD_BLOCKED
        listOf("ambulance").any { q.contains(it) } -> Need.AMBULANCE
        listOf("sos", "panic").any { q.contains(it) } -> Need.PANIC
        else -> null
    }

    private fun needWord(n: Need) = when (n) {
        Need.TRAPPED -> "trapped"; Need.MEDICAL -> "medical"; Need.WATER_FOOD -> "water or food"
        Need.FIRE -> "fire"; Need.ROAD_BLOCKED -> "road blocked"; Need.PANIC -> "SOS"
        Need.AMBULANCE -> "ambulance requested"
    }

    private fun severityWord(s: Int) = when (s) { 3 -> "urgent"; 2 -> "high"; 1 -> "moderate"; else -> "info" }
}

/**
 * Translate (CLAUDE.md 9.6): alert notes for the rescuer, reply notes for the sender. Shown labelled
 * "Machine translated"; on any doubt the original stays.
 */
object Translator {
    fun request(text: String, target: String) = LlmRequest(
        feature = "translate",
        system = "Translate to ${Prompts.languageName(target)}. Output only the translation.",
        user = text,
        maxTokens = LlmLimits.TRANSLATE_TOKENS,
        timeoutMs = 8_000,
    )

    /** Plausible translation: in the target script, not the same text, sane length, one paragraph. */
    fun validate(answer: String, source: String, target: String): String? {
        val t = answer.trim().trim('"', '“', '”').trim()
        if (t.isBlank() || t.equals(source.trim(), ignoreCase = true)) return null
        if (t.length > source.length * 4 + 20 || t.length * 4 + 10 < source.length) return null
        if (t.lines().count { it.isNotBlank() } > 3) return null
        if (scriptLanguage(t) != target) return null
        return t
    }
}
