package com.lifeline.mesh.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLineTest {
    private val f = WeatherLine.facts("Partly cloudy", 30.04, 30.6, 0.0, 2.0, 42.1)

    @Test fun factsAreRoundedWholeNumbers() {
        assertEquals(setOf("30", "31", "0", "2", "42"), f.numbers)
        assertTrue(f.lines().contains("strongest wind gusts in the next 24 hours: 42 km/h"))
    }

    @Test fun aSentenceUsingOnlyTheFactsIsKept() {
        assertEquals("Partly cloudy and 30 °C, with gusts up to 42 km/h and little chance of rain.",
            WeatherLine.validate("Partly cloudy and 30 °C, with gusts up to 42 km/h and little chance of rain.", f))
    }

    @Test fun inventedNumbersMoreLinesOrJsonAreRejected() {
        assertNull(WeatherLine.validate("Partly cloudy, 33 °C.", f))
        assertNull(WeatherLine.validate("Partly cloudy.\nStay indoors for 2 hours.", f))
        assertNull(WeatherLine.validate("{\"text\": \"cloudy\"}", f))
        assertNull(WeatherLine.validate("Cloudy, ३३ °C.", f)) // another script's digits count too
        assertNull(WeatherLine.validate("   ", f))
        // Seen on the iQOO 15: numbers written as words cannot be checked, so they fall back to the computed text.
        assertNull(WeatherLine.validate("It is partly cloudy with a temperature of thirty degrees Celsius.", f))
        assertNull(WeatherLine.validate("Partly cloudy, Forty km/h gusts.", f))
    }
}
