package com.lifeline.mesh.location

import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.triage.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFilterTest {
    private val lat = 12.9716
    private val lon = 77.5946
    private val m = 180.0 / (Math.PI * 6_371_000.0) // degrees of latitude per metre, as GeoMath measures

    private fun fix(northM: Double, eastM: Double = 0.0, acc: Float? = 10f, t: Long) =
        GeoFix(lat + northM * m, lon + eastM * m / Math.cos(Math.toRadians(lat)), acc, t)

    private fun off(f: GeoFix) = GeoMath.distanceM(lat, lon, f.lat, f.lon)

    @Test fun standingStillAveragesTheJitter() {
        val f = FixFilter()
        // Jitter of up to 8 m around the true spot, 10 m accuracy each.
        val jitter = listOf(8.0 to 0.0, -6.0 to 5.0, 3.0 to -8.0, -7.0 to -4.0, 2.0 to 7.0, 0.0 to -6.0)
        var out: GeoFix? = null
        jitter.forEachIndexed { i, (n, e) -> out = f.add(fix(n, e, t = i * 15_000L)) }
        assertTrue("averaged ${off(out!!)} m from the true spot", off(out!!) < 3.0)
        // Never claims better than the best single fix measured.
        assertTrue(out!!.accuracyM!! >= 10f)
    }

    @Test fun aWorseFixDoesNotPullTheEstimateAway() {
        val f = FixFilter()
        f.add(fix(0.0, acc = 5f, t = 0))
        val out = f.add(fix(40.0, acc = 60f, t = 15_000)) // agrees (within 60 m) but poor
        assertTrue("moved ${off(out)} m", off(out) < 2.0)
        assertTrue(out.accuracyM!! < 8f)
    }

    @Test fun aSingleJumpIsIgnoredButThreeInARowAreTrusted() {
        val f = FixFilter()
        f.add(fix(0.0, t = 0))
        f.add(fix(2.0, t = 15_000))
        // 3 km away 15 s later: 200 m/s, faster than any vehicle here.
        val far = 3_000.0
        assertTrue(off(f.add(fix(far, t = 30_000))) < 5.0)
        assertTrue(off(f.add(fix(far + 3, t = 31_000))) < 5.0)
        assertEquals(far + 5, off(f.add(fix(far + 5, t = 32_000))), 1.0)
    }

    @Test fun movingFollowsTheNewFixAtOnce() {
        val f = FixFilter()
        f.add(fix(0.0, t = 0))
        // 120 m in 15 s by road (29 km/h): a real move, not jitter.
        assertEquals(120.0, off(f.add(fix(120.0, t = 15_000))), 0.5)
    }

    @Test fun walkingSlowlyIsNotDraggedBack() {
        val f = FixFilter()
        var out: GeoFix? = null
        // 1.4 m/s, a fix every 15 s (21 m apart), 30 m accuracy: each fix agrees with the last, but not with the first.
        for (i in 0..8) out = f.add(fix(i * 21.0, acc = 30f, t = i * 15_000L))
        assertTrue("lags ${8 * 21.0 - off(out!!)} m", 8 * 21.0 - off(out!!) < 30.0)
    }

    @Test fun fixesWithoutAccuracyOrAfterASilenceStartAgain() {
        val f = FixFilter()
        f.add(fix(0.0, t = 0))
        // No accuracy: kept out while a fresh estimate exists.
        assertTrue(off(f.add(fix(50.0, acc = null, t = 5_000))) < 1.0)
        // Two minutes of silence: the next fix is used as it is.
        assertEquals(80.0, off(f.add(fix(80.0, acc = 20f, t = 125_000))), 0.5)
    }
}
