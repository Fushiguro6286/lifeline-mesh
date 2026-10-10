package com.lifeline.mesh.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimWeatherTest {
    @Test fun storyCalmStormClearAndWrapsAround() {
        assertEquals(listOf("Partly cloudy", "Heavy rain", "Thunderstorm with hail", "Violent showers", "Mainly clear"),
            SimWeather.STEPS.map { it.weather.condition })
        assertEquals("Partly cloudy", SimWeather.step(SimWeather.STEPS.size).weather.condition)
        assertTrue(SimWeather.STEPS.all { it.weather.source == "Simulated" })
    }

    @Test fun dangerousStepsMatchTheServerThresholds() {
        // Same rules as server/weather.py: rain >= 115.6 mm, gusts >= 89 km/h, hail; calm steps raise nothing.
        SimWeather.STEPS.forEach { s ->
            val w = s.weather
            val expected = ((w.rain24hMm ?: 0.0) >= 115.6) || ((w.gustMax24hKmh ?: 0.0) >= 89.0) || w.code in setOf(96, 99)
            assertEquals(w.condition, expected, s.warnings.isNotEmpty())
        }
    }

    @Test fun warningsAreUniquePerSessionAndStep() {
        val a = SimWeather.warningsFor(3, session = 100, nowS = 1_000)
        assertEquals(2, a.size)
        assertTrue(a.all { it.forecast && it.expires == 1_000L + 6 * 3600 && it.id.startsWith("SIMWX-100-3-") })
        assertTrue(SimWeather.warningsFor(3, session = 101, nowS = 1_000).none { w -> a.any { it.id == w.id } })
        assertTrue(SimWeather.warningsFor(0, 100, 1_000).isEmpty())
    }
}
