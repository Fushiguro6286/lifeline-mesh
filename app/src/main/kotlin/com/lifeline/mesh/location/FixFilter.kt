package com.lifeline.mesh.location

import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.triage.GeoMath

/**
 * A steadier, more accurate live position from raw GPS fixes (10 Oct 2026, on request). Pure Kotlin.
 *
 * - Standing still: fixes that agree with the newest one (within the two accuracies added) are averaged,
 *   weighted by 1/accuracy², over the last [windowMs]. Fixes that no longer agree drop out, so someone walking slowly
 *   is not dragged back to where they were.
 * - The accuracy reported is never better than measured: the best single fix's accuracy plus how far the average
 *   sits from that fix. Averaging removes jitter, but GPS errors are correlated over minutes, so claiming the
 *   textbook gain would be a made-up number (CLAUDE.md rule 3).
 * - A jump further than the phone could have gone ([maxSpeedMps] beyond both accuracies) is ignored, unless
 *   [jumpsToTrust] jumps in a row agree with each other: then the phone really moved, or the old estimate was wrong.
 * - A fix without an accuracy cannot be judged: it is used only when there is nothing fresher.
 * - Moving: the new fix is used as it is.
 */
class FixFilter(
    private val windowMs: Long = 120_000L,
    private val maxSpeedMps: Double = 70.0,
    private val jumpsToTrust: Int = 3,
    private val staleMs: Long = 60_000L,
) {
    private val window = ArrayList<GeoFix>()
    private val jumps = ArrayList<GeoFix>()
    var estimate: GeoFix? = null
        private set

    fun add(f: GeoFix): GeoFix {
        val e = estimate
        val acc = f.accuracyM?.toDouble()
        if (e == null || f.timeMs - e.timeMs > staleMs) return reset(f)
        if (acc == null) return e
        val eAcc = e.accuracyM?.toDouble() ?: return reset(f)
        val d = GeoMath.distanceM(e.lat, e.lon, f.lat, f.lon)
        val dt = (f.timeMs - e.timeMs).coerceAtLeast(0L) / 1000.0

        if (d > eAcc + acc + maxSpeedMps * dt) {
            val prev = jumps.lastOrNull()
            if (prev != null && GeoMath.distanceM(prev.lat, prev.lon, f.lat, f.lon) > (prev.accuracyM ?: 0f) + acc) jumps.clear()
            jumps += f
            return if (jumps.size >= jumpsToTrust) reset(f) else e
        }
        jumps.clear()

        window.removeAll { w ->
            f.timeMs - w.timeMs > windowMs ||
                GeoMath.distanceM(w.lat, w.lon, f.lat, f.lon) > (w.accuracyM ?: 0f) + acc
        }
        window += f
        return average().also { estimate = it }
    }

    private fun reset(f: GeoFix): GeoFix {
        window.clear(); jumps.clear()
        if (f.accuracyM != null) window += f
        estimate = f
        return f
    }

    private fun average(): GeoFix {
        val newest = window.last()
        if (window.size == 1) return newest
        var sw = 0.0; var sLat = 0.0; var sLon = 0.0
        for (w in window) {
            val a = (w.accuracyM ?: 1_000f).toDouble().coerceAtLeast(1.0)
            val wt = 1.0 / (a * a)
            sw += wt; sLat += w.lat * wt; sLon += w.lon * wt
        }
        val lat = sLat / sw; val lon = sLon / sw
        // The most accurate fix; the newest of equals, so a slow walker's accuracy is counted from where they are.
        val best = window.asReversed().minBy { it.accuracyM ?: Float.MAX_VALUE }
        val accuracy = (best.accuracyM ?: 0f) + GeoMath.distanceM(lat, lon, best.lat, best.lon).toFloat()
        return GeoFix(lat, lon, accuracy, newest.timeMs)
    }
}
