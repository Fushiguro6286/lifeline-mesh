package com.lifeline.mesh.ai

/** One job in a rescue team's plan, as computed in Kotlin: its place in the order, how urgent, what, and the minutes. */
data class PlanStop(val order: Int, val urgency: String, val what: String, val legMinutes: Int, val arriveMinutes: Int, val avoided: Int)

/**
 * The rescue team's plan in words (10 Oct 2026, on request). Kotlin decides the order (most urgent first, then the one
 * accepted first) and every minute (road routes, around reported blockages); the on-device model only explains it in at
 * most two short sentences. Its text is used only if every number in it is one of the plan's numbers and no number is
 * written as a word; otherwise the computed sentence is shown. English only, like the rest of the Rescue tab.
 */
object PlanLine {
    fun numbers(stops: List<PlanStop>): Set<String> =
        stops.flatMap { listOf(it.order, it.legMinutes, it.arriveMinutes, it.avoided) }.map { it.toString() }.toSet()

    fun factLines(stops: List<PlanStop>): String = stops.joinToString("\n") { s ->
        "job ${s.order}: ${s.urgency}, ${s.what}; ${s.legMinutes} min from the previous place; arrive after ${s.arriveMinutes} min" +
            if (s.avoided > 0) "; route goes around ${s.avoided} blocked road" + (if (s.avoided > 1) "s" else "") else ""
    }

    /** The computed text, used when the model is missing, slow or wrong. */
    fun template(stops: List<PlanStop>): String = stops.joinToString(" ") { s ->
        (if (s.order == 1) "First" else "Then") + " ${s.urgency.lowercase()}: ${s.what}, ${s.legMinutes} min" +
            (if (s.avoided > 0) " (around a blocked road)" else "") + "."
    } + (stops.lastOrNull()?.let { " All done in about ${it.arriveMinutes} min." } ?: "")

    fun request(stops: List<PlanStop>) = LlmRequest(
        feature = "plan",
        system = "In at most two short sentences, tell a rescue team the order of their jobs and why the urgent ones come " +
            "first, using ONLY these facts. Write numbers as digits. Do not add numbers or advice.\nFacts:\n" + factLines(stops),
        user = "Write it now.",
        maxTokens = 80,
        timeoutMs = 6_000,
    )

    fun validate(answer: String, stops: List<PlanStop>): String? {
        val text = answer.trim().trim('"').lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank() || text.length > 260 || text.contains('{')) return null
        if (Regex("[.!?](\\s|$)").findAll(text).count() > 2) return null
        val allowed = numbers(stops)
        if (Regex("\\p{Nd}+").findAll(text).any { it.value !in allowed }) return null
        if (WeatherLine.NUMBER_WORDS.containsMatchIn(text)) return null
        return text
    }
}
