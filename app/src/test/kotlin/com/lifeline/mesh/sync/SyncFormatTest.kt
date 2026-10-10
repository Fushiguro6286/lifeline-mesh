package com.lifeline.mesh.sync

import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.packet.ReportType
import kotlinx.serialization.decodeFromString
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

    @Test fun rawBytesAndPhoneOnlyOnMyPackets() {
        val raw = byteArrayOf(0x11, 1, 2, 3)
        val mine = SyncFormat.toUpload(entity(PacketType.ALERT).copy(rawBytes = raw, isMine = true), 1, myPhone = "+919800000000")
        assertEquals(java.util.Base64.getEncoder().encodeToString(raw), mine.raw)
        assertEquals("+919800000000", mine.contactPhone)
        val relayed = SyncFormat.toUpload(entity(PacketType.ALERT).copy(rawBytes = raw, isMine = false), 1, myPhone = "+919800000000")
        assertNull(relayed.contactPhone) // someone else's packet never carries my number
        assertNull(SyncFormat.toUpload(entity(PacketType.ALERT), 1).raw)
    }

    @Test fun feedQueryCarriesPositionOnlyWhenGiven() {
        assertEquals("origin=0000c0de&role=5&since=7&name=Kiran+A&lat=12.97&lon=77.59",
            SyncFormat.feedQuery(0xc0de, 5, "Kiran A", 12.97, 77.59, 7))
        assertEquals("origin=0000c0de&role=1&since=0", SyncFormat.feedQuery(0xc0de, 1, "", null, null, 0))
    }

    @Test fun feedResponseParses() {
        val f = SyncFormat.json.decodeFromString<FeedResponse>("""{"now":5,"packets":[{"id":"00000000000000a1","raw":"EQ==","assigned":true,"extra":1}]}""")
        assertEquals(5L, f.now)
        assertTrue(f.packets.single().assigned)
    }

    @Test fun feedWarningBecomesTheSameMeshPacketOnEveryPhone() {
        val w = FeedWarning(id = "IN-42_1", event = "Flood", severity = "Severe", headline = "Flood warning", sent = 1_791_600_000, expires = 1_791_603_600)
        val a = SyncFormat.warningPacket(w); val b = SyncFormat.warningPacket(w)
        assertEquals(a, b) // two online phones inject one packet; the mesh drops the copy
        assertEquals(com.lifeline.mesh.packet.Warnings.ORIGIN, a.header.origin)
        assertEquals(com.lifeline.mesh.packet.PacketCodec.TTL_DEFAULT, a.header.ttl)
        assertEquals(2, a.level); assertEquals(1_791_603_600L, a.expires); assertFalse(a.drill)
        val feed = SyncFormat.json.decodeFromString<FeedResponse>(
            """{"now":1,"packets":[],"warnings":[{"id":"DRILL-1","event":"Flood","severity":"Severe","headline":"h","sent":5,"expires":9,"drill":true,"area":"Drill"}]}""")
        assertTrue(SyncFormat.warningPacket(feed.warnings.single()).drill)
        // An older server without warnings still parses.
        assertTrue(SyncFormat.json.decodeFromString<FeedResponse>("""{"now":1,"packets":[]}""").warnings.isEmpty())
    }
}
