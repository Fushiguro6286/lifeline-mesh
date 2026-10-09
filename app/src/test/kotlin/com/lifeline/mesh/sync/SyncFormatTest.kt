package com.lifeline.mesh.sync

import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.packet.ReportType
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFormatTest {
    private fun entity(type: PacketType, id: Long = -2L) = PacketEntity(
        id = id, type = type.code, origin = 0x3fa1beef, ttl = 4, hops = 2, sentAt = 1_000, receivedAt = 2_000,
        fromEndpoint = "x", rawBytes = byteArrayOf(),
    )

    @Test fun alertMapsNeedsPlaceAndHexIds() {
        val p = entity(PacketType.ALERT).copy(lat = 12.97, lon = 77.59, accuracy = 2, needs = Need.TRAPPED.bit or Need.MEDICAL.bit,
            severity = 3, people = 5, note = "पानी")
        val u = SyncFormat.toUpload(p, myOrigin = 0x0000c0de)
        assertEquals("fffffffffffffffe", u.id) // negative ids keep all 64 bits
        assertEquals("alert", u.type)
        assertEquals("3fa1beef", u.origin)
        assertEquals("0000c0de", u.uploadedBy)
        assertEquals(listOf("trapped", "medical"), u.needs)
        assertEquals(20, u.accuracyM)
        assertEquals(12.97, u.lat!!, 0.0)
        assertEquals("पानी", u.note)
    }

    @Test fun alertWithoutFixHasNoPosition() {
        val p = entity(PacketType.ALERT).copy(lat = 0.0, lon = 0.0, accuracy = Geo.ACCURACY_UNKNOWN, needs = Need.PANIC.bit, severity = 3, people = 1, note = "")
        val u = SyncFormat.toUpload(p, 1)
        assertNull(u.lat); assertNull(u.lon); assertNull(u.accuracyM); assertNull(u.note)
        assertEquals(listOf("panic"), u.needs)
    }

    @Test fun ackAndReportFields() {
        val ack = SyncFormat.toUpload(entity(PacketType.ACK).copy(refId = 255L, eta = 20, note = "Boat coming"), 1)
        assertEquals("00000000000000ff", ack.refId)
        assertEquals(20, ack.etaMinutes)
        val rep = SyncFormat.toUpload(entity(PacketType.REPORT).copy(reportType = ReportType.SHELTER_OPEN.code, lat = 1.0, lon = 2.0, accuracy = 1), 1)
        assertEquals("shelter_open", rep.reportType)
        assertEquals("check_in", SyncFormat.toUpload(entity(PacketType.CHECK_IN).copy(name = "Asha"), 1).type)
    }

    @Test fun jsonOmitsNullsAndEmptyNeeds() {
        val s = SyncFormat.json.encodeToString(listOf(SyncFormat.toUpload(entity(PacketType.CHECK_IN).copy(name = "Asha"), 1)))
        assertFalse("null" in s)
        assertFalse("needs" in s)
        assertTrue("\"name\":\"Asha\"" in s)
    }

    @Test fun urlRules() {
        assertEquals("http://192.168.1.5:8000", SyncFormat.normalizeUrl(" 192.168.1.5:8000/ "))
        assertEquals("https://example.org", SyncFormat.normalizeUrl("https://example.org"))
        assertNull(SyncFormat.normalizeUrl(""))
        assertNull(SyncFormat.normalizeUrl("ftp://x"))
        assertTrue(SyncFormat.isLocalHost("192.168.43.1"))
        assertTrue(SyncFormat.isLocalHost("10.0.0.2"))
        assertTrue(SyncFormat.isLocalHost("172.20.1.1"))
        assertFalse(SyncFormat.isLocalHost("172.32.1.1"))
        assertFalse(SyncFormat.isLocalHost("example.org"))
        assertFalse(SyncFormat.isLocalHost("127.0.0.1")) // adb reverse: must not be bound to Wi-Fi
        assertFalse(SyncFormat.isLocalHost("localhost"))
    }
}
