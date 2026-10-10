package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.triage.RankedAlert

/** Every number in the report, computed in Kotlin from stored alerts (CLAUDE.md 9.4). */
data class SituationFacts(
    val active: Int,
    val people: Int,
    val urgent: Int,
    val needs: Map<Need, Int>,
    /** "#1: trapped, medical · 5 people · 300 m", or null with no alerts. */
    val topPriority: String?,
    val checkIns: Int,
    val acknowledged: Int,
    val open: Int,
) {
    /** All numbers that may appear in a phrased report. */
    val numbers: Set<String> by lazy {
        val all = buildList {
            addAll(listOf(active, people, urgent, checkIns, acknowledged, open).map { it.toString() })
            addAll(needs.values.map { it.toString() })
            topPriority?.let { Regex("\\d+").findAll(it).forEach { m -> add(m.value) } }
        }
        all.toSet()
    }
}

/**
 * Situation report (CLAUDE.md 9.4): Kotlin computes every number; the model only phrases them in three lines. The answer
 * is used only if it has three lines and every number in it appears in the facts; otherwise the computed template is shown.
 */
object SituationReport {
    fun facts(ranked: List<RankedAlert>, checkIns: Int, distanceText: (Double) -> String = { "${it.toInt()} m" }): SituationFacts {
        val needs = Need.entries.associateWith { n -> ranked.count { n in it.alert.needs } }.filterValues { it > 0 }
        val top = ranked.firstOrNull()?.let { r ->
            "#${r.rank}: " + needLabels(r.alert.needs) + " · ${r.alert.people} people" + (r.distanceM?.let { " · " + distanceText(it) } ?: "")
        }
        val acked = ranked.count { it.alert.acked }
        return SituationFacts(
            active = ranked.size,
            people = ranked.sumOf { it.alert.people },
            urgent = ranked.count { it.alert.severity >= 3 },
            needs = needs,
            topPriority = top,
            checkIns = checkIns,
            acknowledged = acked,
            open = ranked.size - acked,
        )
    }

    /** Plain words for a need: "water or food", "road blocked". */
    fun needWord(n: Need): String = when (n) {
        Need.WATER_FOOD -> "water or food"
        Need.AMBULANCE -> "ambulance requested"
        else -> n.label.replace('_', ' ')
    }

    fun needLabels(needs: Set<Need>): String =
        if (Need.PANIC in needs) "SOS" else needs.joinToString(", ") { needWord(it) }.ifBlank { "unspecified" }

    /** Facts as "key: value" lines for the prompt. */
    fun factLines(f: SituationFacts): String = buildString {
        appendLine("Active alerts: ${f.active}")
        appendLine("People needing help: ${f.people}")
        appendLine("Urgent alerts: ${f.urgent}")
        if (f.needs.isNotEmpty()) appendLine("Needs: " + f.needs.entries.joinToString(", ") { "${needWord(it.key)} ${it.value}" })
        f.topPriority?.let { appendLine("Top priority: $it") }
        appendLine("Safe check-ins: ${f.checkIns}")
        appendLine("Acknowledged: ${f.acknowledged}")
        append("Still open: ${f.open}")
    }

    /** The computed version, shown when the model is missing, slow, or its answer fails validation. */
    fun template(f: SituationFacts): List<String> {
        if (f.active == 0) return listOf(
            "No active alerts.",
            "Safe check-ins: ${f.checkIns}.",
            "Nothing waiting for a response.",
        )
        return listOf(
            "${f.active} active alerts, ${f.people} people, ${f.urgent} urgent.",
            "Top priority ${f.topPriority}." + if (f.needs.isNotEmpty()) " Needs: " + f.needs.entries.joinToString(", ") { "${needWord(it.key)} ${it.value}" } + "." else "",
            "${f.acknowledged} acknowledged, ${f.open} still open; ${f.checkIns} safe check-in${if (f.checkIns == 1) "" else "s"}.",
        )
    }

    fun request(f: SituationFacts) = LlmRequest(
        feature = "report",
        system = "Write exactly 3 short lines for a rescue team leader using ONLY these facts. Do not add numbers.\nFacts:\n" + factLines(f),
        user = "Write the 3 lines now.",
        maxTokens = LlmLimits.REPORT_TOKENS,
        timeoutMs = 8_000,
    )

    /** Three lines, and no number that is not in the facts. Null = use [template]. */
    fun validate(answer: String, f: SituationFacts): List<String>? {
        val lines = answer.lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s+"), "").trim() }
            .filter { it.isNotBlank() }
        if (lines.size < 3) return null
        val three = lines.take(3)
        if (three.any { it.length > 200 || it.contains('{') }) return null
        val used = three.flatMap { line -> Regex("\\p{Nd}+").findAll(line).map { it.value }.toList() }
        if (used.any { it !in f.numbers }) return null
        // A number written as a word ("Two people") cannot be checked against the facts, so it is not accepted.
        if (three.any { WeatherLine.NUMBER_WORDS.containsMatchIn(it) }) return null
        return three
    }
}
