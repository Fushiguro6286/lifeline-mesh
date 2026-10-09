package com.lifeline.mesh.metrics

import com.lifeline.mesh.data.MetricEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMonitorTest {
    private val min = 60_000L

    @Test fun noRateBeforeFifteenMinutes() {
        val m = BatteryMonitor()
        assertNull(m.onSample(BatteryMonitor.Sample(0, 90, false)))
        assertNull(m.onSample(BatteryMonitor.Sample(14 * min, 89, false)))
        val d = m.onSample(BatteryMonitor.Sample(30 * min, 88, false))
        assertNotNull(d)
        assertEquals(4.0, d!!.pctPerHour, 1e-9)
        assertEquals(90, d.fromPct); assertEquals(88, d.toPct); assertEquals(30, d.minutes)
    }

    @Test fun chargingStartsOver() {
        val m = BatteryMonitor()
        m.onSample(BatteryMonitor.Sample(0, 90, false))
        assertNull(m.onSample(BatteryMonitor.Sample(20 * min, 92, true)))
        assertNull(m.baseline)
        m.onSample(BatteryMonitor.Sample(25 * min, 92, false))
        assertNull(m.onSample(BatteryMonitor.Sample(35 * min, 91, false))) // only 10 min since unplugging
        assertEquals(6.0, m.onSample(BatteryMonitor.Sample(45 * min, 90, false))!!.pctPerHour, 1e-9)
    }

    @Test fun levelGoingUpResetsBaseline() {
        val m = BatteryMonitor()
        m.onSample(BatteryMonitor.Sample(0, 50, false))
        m.onSample(BatteryMonitor.Sample(5 * min, 55, false))
        assertEquals(55, m.baseline!!.pct)
    }

    @Test fun unknownLevelIgnored() {
        val m = BatteryMonitor()
        assertNull(m.onSample(BatteryMonitor.Sample(0, 255, false)))
        assertNull(m.baseline)
    }

    @Test fun csvQuotesAndSeparatesSimulated() {
        val rows = listOf(
            MetricEntity(name = "rtt_ms", value = 412.0, unit = "ms", backend = null, simulated = false, timestamp = 0, notes = "to 3fa1, over 2 hops"),
            MetricEntity(name = "hops", value = 2.0, unit = "hops", backend = null, simulated = true, timestamp = 0, notes = "say \"hi\""),
        )
        val lines = Metrics.csv(rows).trim().lines()
        assertEquals(Metrics.HEADER, lines[0])
        assertEquals("1970-01-01T00:00:00.000Z,rtt_ms,412.0,ms,,no,\"to 3fa1, over 2 hops\"", lines[1])
        assertTrue(lines[2].endsWith(",yes,\"say \"\"hi\"\"\""))
    }
}
