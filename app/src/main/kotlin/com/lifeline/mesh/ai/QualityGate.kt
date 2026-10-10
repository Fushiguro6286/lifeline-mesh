package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need

/** A position from GPS. [accuracyM] is the 68% radius in metres; [timeMs] wall clock. */
data class GeoFix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

/** The position an alert will carry. [approximate] = last known or worse than 50 m. [fix] null = unknown. */
data class LocationChoice(val fix: GeoFix?, val approximate: Boolean)

/**
 * Deterministic checks before an alert may be sent (CLAUDE.md 9.2). No model involved.
 * - Blocks sending while needs are empty or people is 0, and says which to ask about first.
 * - Panic SOS bypasses the gate.
 * - GPS: a fresh fix within 8 s and 50 m is exact; otherwise use the last known position, marked approximate.
 */
object QualityGate {
    const val GPS_WAIT_MS = 8_000L
    const val GOOD_ACCURACY_M = 50f
    /** The service's filtered live fix counts as fresh for this long (FixFilter, 10 Oct 2026). */
    const val LIVE_MAX_AGE_MS = 30_000L

    enum class Missing { NEEDS, PEOPLE }

    data class Result(val missing: List<Missing>) {
        val ok get() = missing.isEmpty()
        /** The one question to ask now (CLAUDE.md 9.3: the first missing field). */
        val ask get() = missing.firstOrNull()
    }

    fun check(d: AlertDraft): Result {
        if (d.panic) return Result(emptyList())
        return Result(buildList {
            if (d.needs.none { it != Need.PANIC }) add(Missing.NEEDS)
            if (d.people <= 0) add(Missing.PEOPLE)
        })
    }

    /** Panic SOS: needs = panic, severity 3, people 1 (CLAUDE.md 9.2). */
    fun panicDraft(): AlertDraft = AlertDraft(setOf(Need.PANIC), people = 1, severity = 3, note = "")

    /** [fresh] = what GPS gave within [GPS_WAIT_MS] (null if nothing), [lastKnown] = the phone's last fix. */
    fun chooseLocation(fresh: GeoFix?, lastKnown: GeoFix?, live: GeoFix? = null, nowMs: Long = 0L): LocationChoice {
        // The live fix (averaged while still) competes with the one-shot fix when it is recent: the more accurate wins.
        val recentLive = live?.takeIf { it.accuracyM != null && nowMs - it.timeMs in 0..LIVE_MAX_AGE_MS }
        val best = listOfNotNull(fresh, recentLive).minByOrNull { it.accuracyM ?: Float.MAX_VALUE }
        return pick(best, lastKnown)
    }

    private fun pick(fresh: GeoFix?, lastKnown: GeoFix?): LocationChoice = when {
        fresh != null && (fresh.accuracyM ?: Float.MAX_VALUE) <= GOOD_ACCURACY_M -> LocationChoice(fresh, approximate = false)
        lastKnown != null && (fresh == null || (lastKnown.accuracyM ?: Float.MAX_VALUE) < (fresh.accuracyM ?: Float.MAX_VALUE)) ->
            LocationChoice(lastKnown, approximate = true)
        fresh != null -> LocationChoice(fresh, approximate = true)
        else -> LocationChoice(null, approximate = true)
    }
}
