package com.lifeline.mesh.ai

import kotlin.math.roundToInt

/** The weather numbers the model may use, already rounded (whole numbers only, so checking them is exact). */
data class WeatherFacts(
    val condition: String,
    val temperatureC: Int?,
    val feelsLikeC: Int?,
    val rain24hMm: Int?,
    val rainChance: Int?,
    val gustMaxKmh: Int?,
) {
    val numbers: Set<String> get() = listOfNotNull(temperatureC, feelsLikeC, rain24hMm, rainChance, gustMaxKmh).map { it.toString() }.toSet()

    fun lines(): String = buildList {
        add("now: $condition")
        temperatureC?.let { add("temperature: $it °C") }
        feelsLikeC?.let { add("feels like: $it °C") }
        rain24hMm?.let { add("rain in the next 24 hours: $it mm") }
        rainChance?.let { add("highest chance of rain: $it %") }
        gustMaxKmh?.let { add("strongest wind gusts in the next 24 hours: $it km/h") }
    }.joinToString("\n")
}

/**
 * One plain sentence about the weather on the SOS screen (10 Oct 2026). The server's numbers decide everything (the
 * danger check is on the server, deterministic); the on-device model only phrases them. Its sentence is used only if
 * it is one short line and every number in it (any script's digits) is one of the facts; otherwise the computed
 * sentence is shown. English only for now: in Hindi or Kannada the card shows the translated computed text.
 */
object WeatherLine {
    internal val NUMBER_WORDS = Regex(
        "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|" +
            "eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|dozen)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun facts(condition: String, temperatureC: Double?, feelsLikeC: Double?, rain24hMm: Double?, rainChance: Double?, gustMaxKmh: Double?) =
        WeatherFacts(condition.ifBlank { "unknown" }, temperatureC?.roundToInt(), feelsLikeC?.roundToInt(), rain24hMm?.roundToInt(),
            rainChance?.roundToInt(), gustMaxKmh?.roundToInt())

    fun request(f: WeatherFacts) = LlmRequest(
        feature = "weather",
        system = "Write ONE short, calm sentence describing the weather for a person in an emergency, using ONLY these facts. " +
            "Write numbers as digits. Do not add numbers, advice or forecasts that are not listed.\nFacts:\n" + f.lines(),
        user = "Write the sentence now.",
        maxTokens = 60,
        timeoutMs = 6_000,
    )

    /** One line, at most 180 characters, no number outside the facts. Null = show the computed text. */
    fun validate(answer: String, f: WeatherFacts): String? {
        val line = answer.trim().trim('"').lines().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        if (answer.trim().lines().count { it.isNotBlank() } > 1 || line.length > 180 || line.contains('{')) return null
        if (Regex("\\p{Nd}+").findAll(line).any { m -> m.value !in f.numbers }) return null
        // A number written as a word ("thirty") cannot be checked, so it is not accepted either.
        if (NUMBER_WORDS.containsMatchIn(line)) return null
        return line
    }
}
