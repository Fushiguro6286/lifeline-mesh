package com.lifeline.mesh.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleVitalsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun heartRateUint8AndUint16() {
        assertEquals(72.0, BleVitals.heartRate(bytes(0x00, 72))!!, 0.0)
        assertEquals(72.0, BleVitals.heartRate(bytes(0x16, 72, 0x10, 0x03))!!, 0.0) // contact + RR intervals after
        assertEquals(300.0, BleVitals.heartRate(bytes(0x01, 0x2C, 0x01))!!, 0.0) // 300 as uint16
        assertNull(BleVitals.heartRate(bytes(0x00, 0))) // no skin contact
        assertNull(BleVitals.heartRate(bytes(0x01, 0x48))) // says uint16, one byte
        assertNull(BleVitals.heartRate(bytes(0x00)))
    }

    @Test fun sfloatValues() {
        // 97 % = mantissa 97, exponent 0; 98.5 = 985 × 10^-1 (0xF3D9); negative mantissa; NaN.
        assertEquals(97.0, BleVitals.sfloat(bytes(97, 0x00), 0)!!, 1e-9)
        assertEquals(98.5, BleVitals.sfloat(bytes(0xD9, 0xF3), 0)!!, 1e-9)
        assertEquals(-1.0, BleVitals.sfloat(bytes(0xFF, 0x0F), 0)!!, 1e-9)
        assertNull(BleVitals.sfloat(bytes(0xFF, 0x07), 0))
    }

    @Test fun oximeterSpo2AndPulse() {
        val r = BleVitals.decode(BleVitals.PLX_CONTINUOUS, bytes(0x00, 95, 0x00, 80, 0x00), 1000)
        assertEquals(listOf(VitalReading(VitalReading.Kind.OXYGEN, 95.0, 1000), VitalReading(VitalReading.Kind.HEART_RATE, 80.0, 1000)), r)
        assertTrue(BleVitals.decode(BleVitals.PLX_CONTINUOUS, bytes(0x00, 0xFF, 0x07, 80, 0x00), 1000).isEmpty()) // SpO2 NaN
        assertTrue(BleVitals.decode(BleVitals.PLX_SPOT_CHECK, bytes(0x00, 95), 1000).isEmpty()) // too short
    }

    @Test fun bloodPressureMmHgAndKpa() {
        assertEquals(120.0 to 80.0, BleVitals.bloodPressure(bytes(0x00, 120, 0x00, 80, 0x00, 93, 0x00)))
        // 16 kPa / 10.7 kPa (107 × 10^-1) → about 120 / 80 mmHg.
        val (s, d) = BleVitals.bloodPressure(bytes(0x01, 16, 0x00, 107, 0xF0, 120, 0xF0))!!
        assertEquals(120.0, s, 0.1); assertEquals(80.3, d, 0.1)
        val r = BleVitals.decode(BleVitals.BLOOD_PRESSURE_MEASUREMENT, bytes(0x00, 185, 0x00, 95, 0x00, 125, 0x00), 5)
        assertEquals(VitalReading(VitalReading.Kind.BLOOD_PRESSURE, 185.0, 5, 95.0), r.single())
    }

    @Test fun unknownOrGarbageIsIgnored() {
        assertTrue(BleVitals.decode(java.util.UUID.randomUUID(), bytes(0, 72), 1).isEmpty())
        assertTrue(BleVitals.decode(BleVitals.BLOOD_PRESSURE_MEASUREMENT, bytes(0x00, 1), 1).isEmpty())
    }
}
