package com.lifeline.mesh.safety

import kotlin.math.max
import kotlin.math.min

/**
 * Fall and crash detection from the accelerometer. Pure Kotlin, unit-tested; no model involved.
 * Feed every sample's magnitude (m/s², gravity included, so a phone lying still reads about 9.8) with its time.
 * [onSample] returns true once, when it has seen:
 *
 * 1. an impact: a free fall (under [FREE_FALL] for at least [FREE_FALL_MS]) followed within [IMPACT_WINDOW_MS] by a hit
 *    over [SOFT_IMPACT] (a fall, or a dropped phone), or a hit over [HARD_IMPACT] on its own (a crash);
 * 2. then stillness: after [SETTLE_MS] for bounces, [STILL_MS] in which the reading moves less than [STILL_RANGE].
 *
 * Stillness is what separates a fall from running or jumping: someone who keeps moving after a hit is not asked.
 * A phone thrown onto a bed can still match; the countdown with "I'm OK" is there for those cases.
 */
class FallDetector {
    private var freeFallStart: Long? = null
    private var freeFallEnd: Long? = null
    private var impactAt: Long? = null
    private var stillStart: Long? = null
    private var stillMin = 0.0
    private var stillMax = 0.0

    fun onSample(magnitude: Double, timeMs: Long): Boolean {
        val impact = impactAt
        if (impact != null) {
            if (timeMs - impact < SETTLE_MS) return false
            val start = stillStart
            if (start == null) {
                stillStart = timeMs; stillMin = magnitude; stillMax = magnitude
                return false
            }
            stillMin = min(stillMin, magnitude)
            stillMax = max(stillMax, magnitude)
            if (stillMax - stillMin > STILL_RANGE) { reset(); return false } // moving again: got up, or kept running
            if (timeMs - start >= STILL_MS) { reset(); return true }
            return false
        }

        if (magnitude < FREE_FALL) {
            if (freeFallStart == null) freeFallStart = timeMs
        } else {
            val start = freeFallStart
            if (start != null && timeMs - start >= FREE_FALL_MS) freeFallEnd = timeMs
            freeFallStart = null
        }
        val afterFall = freeFallEnd?.let { timeMs - it <= IMPACT_WINDOW_MS } == true
        if (magnitude >= HARD_IMPACT || (afterFall && magnitude >= SOFT_IMPACT)) {
            impactAt = timeMs
            freeFallEnd = null
            stillStart = null
        }
        return false
    }

    fun reset() {
        freeFallStart = null; freeFallEnd = null; impactAt = null; stillStart = null
    }

    companion object {
        const val G = 9.81
        const val FREE_FALL = 0.4 * G
        const val FREE_FALL_MS = 80L
        const val SOFT_IMPACT = 1.8 * G
        const val HARD_IMPACT = 4.0 * G
        const val IMPACT_WINDOW_MS = 1_000L
        const val SETTLE_MS = 1_000L
        const val STILL_MS = 2_000L
        const val STILL_RANGE = 0.3 * G
    }
}
