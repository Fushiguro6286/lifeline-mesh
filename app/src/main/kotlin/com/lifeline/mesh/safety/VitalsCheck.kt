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
 * professional yet (see docs/DEMO.md); the app says so next to the switch. Watches are noisy, so heart rate needs two
 * readings in a row, and nothing older than [MAX_AGE_MS] counts (Health Connect data can arrive minutes late).
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
    /** Two heart-rate readings this close together count as "in a row". */
    const val HEART_PAIR_MS = 5 * 60_000L

    /** The newest reason to raise the alarm among [readings] newer than [afterMs] (the last reading already alarmed on). */
    fun check(readings: List<VitalReading>, now: Long, afterMs: Long = 0): VitalsAlarm? {
        val fresh = readings.filter { now - it.atMs <= MAX_AGE_MS && it.atMs <= now }.sortedBy { it.atMs }
        val found = mutableListOf<VitalsAlarm>()
        for (r in fresh) {
            if (r.atMs <= afterMs) continue
            when (r.kind) {
                VitalReading.Kind.OXYGEN ->
                    if (r.value < OXYGEN_LOW) found += VitalsAlarm("Blood oxygen ${r.value.toInt()}%", r.atMs)
                VitalReading.Kind.BLOOD_PRESSURE -> {
                    val sys = r.value; val dia = r.value2 ?: 0.0
                    if (sys >= SYSTOLIC_HIGH || dia >= DIASTOLIC_HIGH || sys < SYSTOLIC_LOW)
                        found += VitalsAlarm("Blood pressure ${sys.toInt()}/${dia.toInt()}", r.atMs)
                }
                VitalReading.Kind.HEART_RATE -> {
                    val prev = fresh.lastOrNull { it.kind == VitalReading.Kind.HEART_RATE && it.atMs < r.atMs } ?: continue
                    val out = { v: Double -> v < HEART_LOW || v > HEART_HIGH }
                    if (r.atMs - prev.atMs <= HEART_PAIR_MS && out(r.value) && out(prev.value) && (r.value > HEART_HIGH) == (prev.value > HEART_HIGH))
                        found += VitalsAlarm("Heart rate ${r.value.toInt()}/min", r.atMs)
                }
            }
        }
        return found.maxByOrNull { it.readingAtMs }
    }
}
