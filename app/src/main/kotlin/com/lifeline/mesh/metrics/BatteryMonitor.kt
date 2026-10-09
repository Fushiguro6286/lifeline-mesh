package com.lifeline.mesh.metrics

/**
 * Battery drain while relaying (CLAUDE.md 10). The first sample on battery is the baseline ("% at relay start"); a
 * drain rate exists only after [minMs] (15 minutes) on battery. Charging, or the level going up, starts over: a rate
 * across a charge would be meaningless. Pure Kotlin; the service feeds it one sample a minute.
 */
class BatteryMonitor(private val minMs: Long = 15 * 60_000L) {
    data class Sample(val atMs: Long, val pct: Int, val charging: Boolean)

    /** [pctPerHour] from [fromPct] to [toPct] over [minutes]. Android reports whole percent, so short runs are coarse. */
    data class Drain(val pctPerHour: Double, val fromPct: Int, val toPct: Int, val minutes: Long)

    var baseline: Sample? = null
        private set

    fun onSample(s: Sample): Drain? {
        if (s.charging || s.pct !in 0..100) { baseline = null; return null }
        val b = baseline
        if (b == null || s.pct > b.pct) { baseline = s; return null }
        val elapsed = s.atMs - b.atMs
        if (elapsed < minMs) return null
        return Drain((b.pct - s.pct) / (elapsed / 3_600_000.0), b.pct, s.pct, elapsed / 60_000)
    }
}
