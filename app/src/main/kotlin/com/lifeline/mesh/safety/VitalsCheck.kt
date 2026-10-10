package com.lifeline.mesh.safety

/** One reading a smartwatch synced into Health Connect. Blood pressure uses [value] = systolic, [value2] = diastolic. */
data class VitalReading(val kind: Kind, val value: Double, val atMs: Long, val value2: Double? = null) {
    enum class Kind { OXYGEN, HEART_RATE, BLOOD_PRESSURE }
}

/** Why the watch alarm goes off: a short English line for the alarm, the request note and the log, and when it was read. */
data class VitalsAlarm(val reason: String, val readingAtMs: Long)

/**
 * Decides when a watch reading is far enough outside normal to start the 15 s alarm (10 Oct 2026, on request). Pure
 * Kotlin, deterministic, no model. The limits are commonly published emergency thresholds, NOT reviewed by a medical
 * professional yet (see docs/DEMO.md); the app says so next to the switch. Watches are noisy: heart rate must stay out
 * of range for [HEART_SUSTAIN_MS] (two readings in a row at least), a live Bluetooth oxygen stream must stay low for
 * [OXYGEN_SUSTAIN_MS], and nothing older than [MAX_AGE_MS] counts (Health Connect data can arrive minutes late).
 */
object VitalsCheck {
    /** Blood oxygen (SpO2) below this, in percent. */
    const val OXYGEN_LOW = 90.0
    /** Heart rate outside this range, in beats per minute, on two readings in a row. */
    const val HEART_LOW = 40.0
    const val HEART_HIGH = 150.0
    /** Blood pressure: systolic at or above / diastolic at or above (hypertensive crisis), or systolic below. */
    const val SYSTOLIC_HIGH = 180.0
    const val DIASTOLIC_HIGH = 120.0
    const val SYSTOLIC_LOW = 90.0
    const val MAX_AGE_MS = 30 * 60_000L
    /** Two readings this close together count as "in a row". */
    const val HEART_PAIR_MS = 5 * 60_000L
    /** Heart rate must stay outside the range at least this long (two readings minutes apart, or a live stream). */
    const val HEART_SUSTAIN_MS = 30_000L
    /** A live oxygen stream must stay low at least this long (a single spot check counts at once). */
    const val OXYGEN_SUSTAIN_MS = 15_000L
    /** Readings closer than this to the previous one of their kind are a live stream (Bluetooth), not spot checks. */
    const val STREAM_GAP_MS = 60_000L
    /** After an alarm (answered or not), readings are not alarmed on again for this long. */
    const val QUIET_MS = 5 * 60_000L

    /** One reading outside the limits, for colouring it on screen (the alarm itself needs [check], e.g. two heart rates). */
    fun outOfRange(r: VitalReading): Boolean = when (r.kind) {
        VitalReading.Kind.OXYGEN -> r.value < OXYGEN_LOW
        VitalReading.Kind.HEART_RATE -> r.value < HEART_LOW || r.value > HEART_HIGH
        VitalReading.Kind.BLOOD_PRESSURE -> r.value >= SYSTOLIC_HIGH || (r.value2 ?: 0.0) >= DIASTOLIC_HIGH || r.value < SYSTOLIC_LOW
    }

    /** The newest reason to raise the alarm among [readings] newer than [afterMs] (the last reading already alarmed on). */
    fun check(readings: List<VitalReading>, now: Long, afterMs: Long = 0): VitalsAlarm? {
        val fresh = readings.filter { now - it.atMs <= MAX_AGE_MS && it.atMs <= now }.sortedBy { it.atMs }
        val found = mutableListOf<VitalsAlarm>()
        for (r in fresh) {
            if (r.atMs <= afterMs) continue
            when (r.kind) {
                VitalReading.Kind.OXYGEN -> {
                    if (r.value >= OXYGEN_LOW) continue
                    // A spot check (Health Connect) counts on its own; a live stream (Bluetooth) must stay low for a while.
                    val streaming = fresh.any { it.kind == r.kind && it.atMs < r.atMs && r.atMs - it.atMs <= STREAM_GAP_MS }
                    if (!streaming || runSpan(fresh, r) { it.value < OXYGEN_LOW } >= OXYGEN_SUSTAIN_MS)
                        found += VitalsAlarm("Blood oxygen ${r.value.toInt()}%", r.atMs)
                }
                VitalReading.Kind.BLOOD_PRESSURE -> {
                    val sys = r.value; val dia = r.value2 ?: 0.0
                    if (sys >= SYSTOLIC_HIGH || dia >= DIASTOLIC_HIGH || sys < SYSTOLIC_LOW)
                        found += VitalsAlarm("Blood pressure ${sys.toInt()}/${dia.toInt()}", r.atMs)
                }
                VitalReading.Kind.HEART_RATE -> {
                    if (r.value in HEART_LOW..HEART_HIGH) continue
                    val high = r.value > HEART_HIGH
                    // At least two readings in a row on the same side, lasting at least HEART_SUSTAIN_MS.
                    val same = { v: VitalReading -> if (high) v.value > HEART_HIGH else v.value < HEART_LOW }
                    if (runSpan(fresh, r, same) >= HEART_SUSTAIN_MS) found += VitalsAlarm("Heart rate ${r.value.toInt()}/min", r.atMs)
                }
            }
        }
        return found.maxByOrNull { it.readingAtMs }
    }

    /**
     * How long the unbroken run of [r]'s kind matching [bad] that ends at [r] lasts (ms), counting only readings at most
     * [HEART_PAIR_MS] apart. 0 if [r] is the only one.
     */
    private fun runSpan(fresh: List<VitalReading>, r: VitalReading, bad: (VitalReading) -> Boolean): Long {
        val same = fresh.filter { it.kind == r.kind && it.atMs <= r.atMs }
        var start = r.atMs
        var next = r.atMs
        for (v in same.asReversed().drop(1)) {
            if (!bad(v) || next - v.atMs > HEART_PAIR_MS) break
            start = v.atMs; next = v.atMs
        }
        return r.atMs - start
    }
}
