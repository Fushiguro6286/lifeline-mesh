package com.lifeline.mesh.ai

/**
 * One mesh path in words, as computed in Kotlin: [team] (its name and kind, e.g. "Kiran (Ambulance)"), [other] (the
 * other end: "you" or a person's name), the phones [via] in between, in order, and [mine] = the other end is this
 * phone's own alert (the team is coming to me).
 */
data class PathFacts(val team: String, val teamName: String, val other: String, val via: List<String>, val mine: Boolean) {
    val hops get() = via.size + 1
}

/**
 * The mesh path explained in one sentence (11 Oct 2026). Kotlin finds the path; the on-device model only says it in
 * plain words. Its sentence is used only if it names the team, every number in it is the hop count, no number is
 * written as a word, and it is one short sentence; otherwise the computed sentence is shown. English only.
 */
object PathLine {
    private fun list(names: List<String>) = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    fun template(f: PathFacts): String {
        val via = if (f.via.isEmpty()) "directly" else "through ${list(f.via)}"
        val hops = if (f.hops == 1) "1 hop" else "${f.hops} hops"
        return if (f.mine) "Your alert reaches ${f.team} $via: $hops, no internet."
        else "Messages between you and ${f.other} go $via: $hops, no internet."
    }

    fun factLines(f: PathFacts): String = buildString {
        appendLine("rescue team phone: ${f.team}")
        appendLine("other end: ${if (f.mine) "the person reading this (their own alert)" else f.other}")
        appendLine("phones in between, in order: ${if (f.via.isEmpty()) "none (direct link)" else f.via.joinToString(", ")}")
        append("hops: ${f.hops}; no internet is used")
    }

    fun request(f: PathFacts) = LlmRequest(
        feature = "path",
        system = "Write ONE short, calm sentence telling " +
            (if (f.mine) "a person waiting for help how their alert travels from their phone to the rescue team"
            else "a rescue team how its messages travel from its phone to the person") +
            ", using ONLY these facts. Start from the reader's phone and name the phones in order. Write numbers as " +
            "digits. Do not add numbers or advice.\nFacts:\n" + factLines(f),
        user = "Write the sentence now.",
        maxTokens = 60,
        timeoutMs = 6_000,
    )

    fun validate(answer: String, f: PathFacts): String? {
        val text = answer.trim().trim('"').lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank() || text.length > 200 || text.contains('{')) return null
        if (Regex("[.!?](\\s|$)").findAll(text).count() > 1) return null
        if (!text.contains(f.teamName, ignoreCase = true)) return null
        // The names must come in path order, from this phone outwards (seen on the iQOO 15: "travels from Kiran to
        // Divya and then to your phone", the right phones in the wrong direction).
        val order = (f.via + f.teamName).map { n -> text.indexOf(n, ignoreCase = true) }.filter { it >= 0 }
        if (order.zipWithNext().any { (a, b) -> a > b }) return null
        if (Regex("\\p{Nd}+").findAll(text).any { it.value != f.hops.toString() }) return null
        if (WeatherLine.NUMBER_WORDS.containsMatchIn(text)) return null
        return text
    }
}
