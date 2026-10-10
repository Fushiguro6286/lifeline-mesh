package com.lifeline.mesh.safety

import com.lifeline.mesh.safety.FallDetector.Companion.G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic accelerometer traces at 50 Hz (20 ms apart): falls, crashes, and everyday movement that must not count. */
class FallDetectorTest {
    private class Trace {
        val samples = mutableListOf<Double>()
        fun hold(value: Double, ms: Int) = apply { repeat(ms / 20) { samples += value } }
        /** Small wobble around [value], like a phone lying on the floor or in a still pocket. */
        fun still(ms: Int, value: Double = G) = apply { repeat(ms / 20) { i -> samples += value + 0.15 * sin(i * 0.7) } }
        fun spike(peak: Double) = apply { samples += peak; samples += peak * 0.6; samples += G * 0.7; samples += G * 1.3 }
        fun walk(ms: Int, amp: Double = 4.0, hz: Double = 2.0) = apply {
            repeat(ms / 20) { i -> samples += G + amp * sin(2 * PI * hz * i * 0.02) }
        }
        /** Running: short airborne moments (~0.1 g) and landings of [landing] every 350 ms. */
        fun run(ms: Int, landing: Double = 2.6 * G) = apply {
            repeat(ms / 350) { hold(0.1 * G, 100); spike(landing); walk(170, amp = 3.0) }
        }
    }

    /** Times (ms) at which the detector fired. */
    private fun fires(t: Trace): List<Long> {
        val d = FallDetector()
        return t.samples.mapIndexedNotNull { i, m -> (i * 20L).takeIf { d.onSample(m, it) } }
    }

    @Test fun droppedPhoneThatStaysStillIsAFall() {
        val t = Trace().still(1_000).hold(0.05 * G, 400).spike(6 * G).still(4_000)
        val at = fires(t)
        assertEquals(1, at.size)
        // Impact at ~1.4 s, 1 s to settle, 2 s still.
        assertTrue("fired at ${at.single()}", at.single() in 4_300L..4_700L)
    }

    @Test fun softLandingAfterFreeFallStillCounts() {
        // Onto a cushion: a smaller hit, but after a real free fall.
        assertEquals(1, fires(Trace().still(500).hold(0.1 * G, 300).spike(2.2 * G).still(4_000)).size)
    }

    @Test fun hardCrashWithoutFreeFallCounts() {
        assertEquals(1, fires(Trace().walk(2_000, amp = 1.0).spike(5 * G).still(4_000)).size)
    }

    @Test fun movingAgainAfterTheHitIsNotAFall() {
        assertTrue(fires(Trace().still(500).hold(0.05 * G, 400).spike(6 * G).still(1_200).walk(4_000)).isEmpty())
    }

    @Test fun walkingAndRunningAreNotFalls() {
        assertTrue(fires(Trace().walk(30_000)).isEmpty())
        assertTrue(fires(Trace().run(30_000)).isEmpty())
    }

    @Test fun moderateBumpWithoutFreeFallIsNotAFall() {
        // Phone put down firmly on a table: about 2.5 g, no free fall, then still.
        assertTrue(fires(Trace().walk(1_000, amp = 1.0).spike(2.5 * G).still(5_000)).isEmpty())
    }

    @Test fun freeFallWithoutImpactIsNotAFall() {
        // Tossed and caught gently.
        assertTrue(fires(Trace().still(500).hold(0.05 * G, 300).spike(1.4 * G).still(4_000)).isEmpty())
    }

    @Test fun firesOnceThenWatchesAgain() {
        val fall = Trace().still(500).hold(0.05 * G, 400).spike(6 * G).still(4_000)
        val twice = Trace().apply { samples += fall.samples; samples += fall.samples }
        assertEquals(2, fires(twice).size)
        assertFalse(fires(Trace().still(10_000)).isNotEmpty())
    }
}
