package com.lifeline.mesh.safety

import java.util.UUID
import kotlin.math.pow

/**
 * The standard Bluetooth LE health services a watch, band, finger oximeter or blood pressure cuff can expose (Bluetooth
 * SIG GATT specifications), and decoders for their measurements (10 Oct 2026, on request). Pure Kotlin, unit-tested.
 * Most big-brand smartwatches (Samsung, Apple, vivo) do not expose these; sports watches and bands often do when "heart
 * rate broadcast/sharing" is switched on in the watch.
 */
object BleVitals {
    private fun uuid16(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val PULSE_OXIMETER_SERVICE: UUID = uuid16(0x1822)
    val PLX_SPOT_CHECK: UUID = uuid16(0x2A5E)
    val PLX_CONTINUOUS: UUID = uuid16(0x2A5F)
    val BLOOD_PRESSURE_SERVICE: UUID = uuid16(0x1810)
    val BLOOD_PRESSURE_MEASUREMENT: UUID = uuid16(0x2A35)
    /** Client Characteristic Configuration descriptor: turns notifications or indications on. */
    val CCCD: UUID = uuid16(0x2902)

    val SERVICES = listOf(HEART_RATE_SERVICE, PULSE_OXIMETER_SERVICE, BLOOD_PRESSURE_SERVICE)
    val MEASUREMENTS = listOf(HEART_RATE_MEASUREMENT, PLX_CONTINUOUS, PLX_SPOT_CHECK, BLOOD_PRESSURE_MEASUREMENT)

    /** Readings in one notification of [characteristic], timestamped [atMs] (arrival); empty if malformed or unknown. */
    fun decode(characteristic: UUID, value: ByteArray, atMs: Long): List<VitalReading> = runCatching {
        when (characteristic) {
            HEART_RATE_MEASUREMENT -> heartRate(value)?.let { listOf(VitalReading(VitalReading.Kind.HEART_RATE, it, atMs)) }
            PLX_CONTINUOUS, PLX_SPOT_CHECK -> oximeter(value)?.let { (spo2, pulse) ->
                listOfNotNull(VitalReading(VitalReading.Kind.OXYGEN, spo2, atMs),
                    pulse?.let { VitalReading(VitalReading.Kind.HEART_RATE, it, atMs) })
            }
            BLOOD_PRESSURE_MEASUREMENT -> bloodPressure(value)?.let { (sys, dia) ->
                listOf(VitalReading(VitalReading.Kind.BLOOD_PRESSURE, sys, atMs, dia))
            }
            else -> null
        }
    }.getOrNull().orEmpty()

    /** Heart Rate Measurement (0x2A37): flags bit 0 = value is uint16, else uint8. Beats per minute; 0 = no contact. */
    fun heartRate(v: ByteArray): Double? {
        if (v.size < 2) return null
        val wide = v[0].toInt() and 0x01 != 0
        val bpm = if (wide) { if (v.size < 3) return null; u8(v, 1) or (u8(v, 2) shl 8) } else u8(v, 1)
        return bpm.takeIf { it in 1..300 }?.toDouble()
    }

    /** PLX Spot-check (0x2A5E) or Continuous (0x2A5F): flags, then SpO2 % and pulse rate as SFLOAT. */
    fun oximeter(v: ByteArray): Pair<Double, Double?>? {
        if (v.size < 5) return null
        val spo2 = sfloat(v, 1) ?: return null
        if (spo2 !in 0.0..100.0) return null
        val pulse = sfloat(v, 3)?.takeIf { it in 1.0..300.0 }
        return spo2 to pulse
    }

    /** Blood Pressure Measurement (0x2A35): flags bit 0 = kPa (else mmHg), then systolic, diastolic, mean as SFLOAT. */
    fun bloodPressure(v: ByteArray): Pair<Double, Double>? {
        if (v.size < 7) return null
        val kpa = v[0].toInt() and 0x01 != 0
        val factor = if (kpa) 7.50062 else 1.0
        val sys = sfloat(v, 1) ?: return null
        val dia = sfloat(v, 3) ?: return null
        return (sys * factor) to (dia * factor)
    }

    /**
     * IEEE 11073 16-bit SFLOAT, little-endian: 4-bit signed exponent (high nibble), 12-bit signed mantissa. Null for the
     * special values (NaN, NRes, +/- infinity, reserved).
     */
    fun sfloat(v: ByteArray, at: Int): Double? {
        val raw = u8(v, at) or (u8(v, at + 1) shl 8)
        var mantissa = raw and 0x0FFF
        if (mantissa in 0x07FE..0x0802) return null
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = raw shr 12
        if (exponent >= 0x8) exponent -= 0x10
        return mantissa * 10.0.pow(exponent)
    }

    private fun u8(v: ByteArray, i: Int) = v[i].toInt() and 0xFF
}
