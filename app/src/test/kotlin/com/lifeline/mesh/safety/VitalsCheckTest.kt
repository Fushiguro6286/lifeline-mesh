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
}
