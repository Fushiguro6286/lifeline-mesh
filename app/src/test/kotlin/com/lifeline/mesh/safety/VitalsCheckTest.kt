package com.lifeline.mesh.safety

import com.lifeline.mesh.safety.VitalReading.Kind.BLOOD_PRESSURE
import com.lifeline.mesh.safety.VitalReading.Kind.HEART_RATE
import com.lifeline.mesh.safety.VitalReading.Kind.OXYGEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VitalsCheckTest {
    private val now = 10_000_000L
    private val min = 60_000L

    @Test fun normalReadingsRaiseNothing() {
        val r = listOf(VitalReading(OXYGEN, 97.0, now - min), VitalReading(HEART_RATE, 72.0, now - 2 * min),
            VitalReading(HEART_RATE, 80.0, now - min), VitalReading(BLOOD_PRESSURE, 120.0, now - min, 80.0))
        assertNull(VitalsCheck.check(r, now))
    }

    @Test fun lowOxygenAlarms() {
        assertEquals("Blood oxygen 86%", VitalsCheck.check(listOf(VitalReading(OXYGEN, 86.0, now - min)), now)?.reason)
        assertNull(VitalsCheck.check(listOf(VitalReading(OXYGEN, 90.0, now - min)), now))
    }

    @Test fun bloodPressureCrisisOrLowAlarms() {
        assertEquals("Blood pressure 185/95", VitalsCheck.check(listOf(VitalReading(BLOOD_PRESSURE, 185.0, now, 95.0)), now)?.reason)
        assertEquals("Blood pressure 150/125", VitalsCheck.check(listOf(VitalReading(BLOOD_PRESSURE, 150.0, now, 125.0)), now)?.reason)
        assertEquals("Blood pressure 82/50", VitalsCheck.check(listOf(VitalReading(BLOOD_PRESSURE, 82.0, now, 50.0)), now)?.reason)
    }

    @Test fun heartRateNeedsTwoReadingsInARowOnTheSameSide() {
        val one = listOf(VitalReading(HEART_RATE, 170.0, now - min))
        assertNull(VitalsCheck.check(one, now))
        val two = one + VitalReading(HEART_RATE, 175.0, now)
        assertEquals("Heart rate 175/min", VitalsCheck.check(two, now)?.reason)
        // Too far apart, or one high and one low (a glitch), or back to normal: nothing.
        assertNull(VitalsCheck.check(listOf(VitalReading(HEART_RATE, 170.0, now - 10 * min), VitalReading(HEART_RATE, 175.0, now)), now))
        assertNull(VitalsCheck.check(listOf(VitalReading(HEART_RATE, 30.0, now - min), VitalReading(HEART_RATE, 175.0, now)), now))
        assertNull(VitalsCheck.check(listOf(VitalReading(HEART_RATE, 170.0, now - min), VitalReading(HEART_RATE, 90.0, now)), now))
        assertEquals("Heart rate 35/min", VitalsCheck.check(listOf(VitalReading(HEART_RATE, 38.0, now - min), VitalReading(HEART_RATE, 35.0, now)), now)?.reason)
    }

    @Test fun oldOrAlreadyHandledReadingsAreIgnored() {
        val old = VitalReading(OXYGEN, 80.0, now - VitalsCheck.MAX_AGE_MS - 1)
        assertNull(VitalsCheck.check(listOf(old), now))
        val r = VitalReading(OXYGEN, 85.0, now - min)
        assertNull(VitalsCheck.check(listOf(r), now, afterMs = r.atMs))
        // The newest of several reasons wins.
        val a = VitalsCheck.check(listOf(r, VitalReading(BLOOD_PRESSURE, 190.0, now, 100.0)), now)
        assertEquals("Blood pressure 190/100", a?.reason)
    }

    @Test fun oneReadingOutOfRangeForDisplay() {
        assertEquals(true, VitalsCheck.outOfRange(VitalReading(HEART_RATE, 160.0, now)))
        assertEquals(false, VitalsCheck.outOfRange(VitalReading(HEART_RATE, 75.0, now)))
        assertEquals(true, VitalsCheck.outOfRange(VitalReading(BLOOD_PRESSURE, 120.0, now, 125.0)))
        assertEquals(false, VitalsCheck.outOfRange(VitalReading(OXYGEN, 95.0, now)))
    }

    /** One reading a second, like a Bluetooth watch. */
    private fun stream(kind: VitalReading.Kind, values: List<Double>, endAt: Long = now) =
        values.mapIndexed { i, v -> VitalReading(kind, v, endAt - (values.size - 1 - i) * 1_000L) }

    @Test fun liveHeartRateMustStayHighForThirtySeconds() {
        assertNull(VitalsCheck.check(stream(HEART_RATE, List(10) { 160.0 }), now)) // 9 s: a spike, no alarm
        assertNull(VitalsCheck.check(stream(HEART_RATE, List(20) { 160.0 } + 90.0 + List(20) { 160.0 }), now)) // broken run
        assertEquals("Heart rate 160/min", VitalsCheck.check(stream(HEART_RATE, List(31) { 160.0 }), now)?.reason)
    }

    @Test fun liveOxygenMustStayLowForFifteenSecondsButASpotCheckCountsAtOnce() {
        assertNull(VitalsCheck.check(stream(OXYGEN, List(20) { 97.0 } + List(5) { 86.0 }), now)) // 4 s low: probably motion
        assertEquals("Blood oxygen 86%", VitalsCheck.check(stream(OXYGEN, List(20) { 97.0 } + List(16) { 86.0 }), now)?.reason)
        assertEquals("Blood oxygen 86%", VitalsCheck.check(listOf(VitalReading(OXYGEN, 97.0, now - 20 * min), VitalReading(OXYGEN, 86.0, now)), now)?.reason)
    }

    @Test fun quietPeriodAfterAnAlarm() {
        val r = stream(OXYGEN, List(30) { 85.0 })
        val first = VitalsCheck.check(r, now)!!
        // The service skips readings up to the alarm time plus QUIET_MS; the same low stream does not ring again.
        assertNull(VitalsCheck.check(r, now, afterMs = first.readingAtMs + VitalsCheck.QUIET_MS))
    }

    @Test fun aLongHighStreamRingsAgainAfterTheQuietPeriod() {
        // 15 minutes at 165 per minute, one reading a second; first alarm, then the quiet period, then it must ring again.
        val r = stream(HEART_RATE, List(15 * 60) { 165.0 })
        val first = VitalsCheck.check(r.filter { it.atMs <= now - 14 * min }, now - 14 * min)!!
        val again = VitalsCheck.check(r, now, afterMs = first.readingAtMs + VitalsCheck.QUIET_MS)
        assertEquals("Heart rate 165/min", again?.reason)
    }
}
