package com.lifeline.mesh.mesh

import com.lifeline.mesh.sync.FeedWarning
import com.lifeline.mesh.sync.FeedWeather

/** One step of the simulated weather: what the SOS tab shows, and the forecast warnings it raises (if any). */
data class SimWeatherStep(val weather: FeedWeather, val warnings: List<FeedWarning> = emptyList())

/**
 * The weather in simulation mode (10 Oct 2026, on request): a short storm story for rehearsals and the stage, instead of
 * the real weather. Every step is labelled "Simulated weather" on screen; dangerous steps raise the same kind of forecast
 * warning the real server would (same thresholds as server/weather.py), which then hops through the virtual phones.
 * Pure Kotlin. The service moves to the next step every [STEP_MS], or when "Next weather" is tapped.
 */
object SimWeather {
    const val STEP_MS = 75_000L

    private fun w(condition: String, code: Int, temp: Double, feels: Double, rain: Double, chance: Double, gust: Double) = FeedWeather(
        temperatureC = temp, feelsLikeC = feels, humidity = if (rain > 20) 95.0 else 60.0, rainNowMm = if (rain > 20) rain / 24 else 0.0,
        windKmh = gust * 0.6, gustKmh = gust, code = code, condition = condition, rain24hMm = rain, rainChanceMax = chance,
        gustMax24hKmh = gust, tempMax24hC = temp + 2, source = "Simulated",
    )

    private fun warning(kind: String, event: String, extreme: Boolean, headline: String) =
        FeedWarning(id = kind, event = event, severity = if (extreme) "Extreme" else "Severe", headline = headline, forecast = true)

    val STEPS = listOf(
        SimWeatherStep(w("Partly cloudy", 2, 29.0, 31.0, 2.0, 20.0, 30.0)),
        SimWeatherStep(w("Heavy rain", 65, 24.0, 25.0, 150.0, 95.0, 55.0), listOf(
            warning("rain", "Very heavy rain", false, "Forecast: 150 mm of rain in the next 24 hours (very heavy rain). Flooding is possible."))),
        SimWeatherStep(w("Thunderstorm with hail", 96, 23.0, 24.0, 90.0, 90.0, 75.0), listOf(
            warning("hail", "Thunderstorm with hail", false, "Forecast: thunderstorm with hail in the next 24 hours."))),
        SimWeatherStep(w("Violent showers", 82, 25.0, 27.0, 210.0, 100.0, 120.0), listOf(
            warning("rain", "Very heavy rain", true, "Forecast: 210 mm of rain in the next 24 hours (extremely heavy rain). Flooding is possible."),
            warning("wind", "Strong winds", true, "Forecast: wind gusts up to 120 km/h in the next 24 hours."))),
        SimWeatherStep(w("Mainly clear", 1, 30.0, 32.0, 0.0, 5.0, 25.0)),
    )

    fun step(index: Int): SimWeatherStep = STEPS[Math.floorMod(index, STEPS.size)]

    /**
     * The warnings of step [index] for one simulation session, ready for the mesh: unique per session and step (so a new
     * session raises them again), valid for 6 hours from [nowS].
     */
    fun warningsFor(index: Int, session: Long, nowS: Long): List<FeedWarning> = step(index).warnings.map {
        it.copy(id = "SIMWX-$session-${Math.floorMod(index, STEPS.size)}-${it.id}", sent = nowS, expires = nowS + 6 * 3600, area = "Simulated")
    }
}
