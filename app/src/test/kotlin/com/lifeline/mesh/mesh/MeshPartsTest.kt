package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPartsTest {

    @Test fun seenCacheExpiresAfterSixHours() {
        val c = SeenCache()
        assertTrue(c.add(1, now = 0))
        assertFalse(c.add(1, now = 1_000))
        assertTrue(c.contains(1, now = 6 * 3_600_000L - 1))
        assertFalse(c.contains(1, now = 6 * 3_600_000L))
        assertTrue(c.add(1, now = 6 * 3_600_000L))
    }

    @Test fun seenCacheEvictsLeastRecentlyUsed() {
        val c = SeenCache(capacity = 3)
        c.add(1, 0); c.add(2, 0); c.add(3, 0)
        c.contains(1, 1) // touch 1, so 2 is now the oldest
        c.add(4, 2)
        assertEquals(3, c.size)
        assertTrue(c.contains(1, 3))
        assertFalse(c.contains(2, 3))
        assertTrue(c.contains(4, 3))
    }

    @Test fun seenCacheHolds2000() {
        val c = SeenCache()
        for (i in 1L..2_500L) c.add(i, 0)
        assertEquals(2_000, c.size)
        assertFalse(c.contains(500, 0))
        assertTrue(c.contains(501, 0))
        assertEquals(100, c.recent(100, 0).size)
    }

    @Test fun rateLimiterSlidingWindow() {
        val r = RateLimiter()
        repeat(10) { assertTrue(r.allow(7, now = it * 1_000L)) }
        assertFalse(r.allow(7, now = 10_000))
        assertTrue("other origins are independent", r.allow(8, now = 10_000))
        assertFalse(r.allow(7, now = 59_999))
        assertTrue("first one left the window", r.allow(7, now = 60_000))
    }

    @Test fun outboxKeepsSixHoursMostUrgentFirst() {
        val o = Outbox()
        o.add(1, byteArrayOf(1), priority = 4, now = 0)
        o.add(2, byteArrayOf(2), priority = 0, now = 10)
        o.add(3, byteArrayOf(3), priority = 1, now = 20)
        assertEquals(listOf(2L, 3L, 1L), o.live(30).map { it.id })
        assertEquals(listOf(2L, 3L), o.live(6 * 3_600_000L + 5).map { it.id })
        assertEquals(0, o.live(6 * 3_600_000L + 25).size)
    }

    @Test fun outboxCapacityDropsLeastImportant() {
        val o = Outbox(capacity = 2)
        o.add(1, byteArrayOf(), priority = 5, now = 0)
        o.add(2, byteArrayOf(), priority = 0, now = 1)
        o.add(3, byteArrayOf(), priority = 1, now = 2)
        assertEquals(listOf(2L, 3L), o.live(3).map { it.id })
    }

    @Test fun peerSyncOffersOnlyMissing() {
        val o = Outbox()
        o.add(1, byteArrayOf(), 1, 0); o.add(2, byteArrayOf(), 1, 0); o.add(3, byteArrayOf(), 0, 0)
        val h = Header(9, 9, 0, 0, 0)
        val missing = PeerSync.missing(Packet.Summary(h, listOf(2)), o, 10)
        assertEquals(listOf(3L, 1L), missing.map { it.id })
    }

    @Test fun summaryNeverExceedsLimitsAndIsNotRelayed() {
        val seen = SeenCache()
        for (i in 1L..500L) seen.add(i, 0)
        val s = PeerSync.summary(Header(1, 2, 5, 3, 0), seen, 0)
        assertEquals(100, s.ids.size)
        assertEquals(0, s.header.ttl)
        assertTrue(PacketCodec.encode(s).size <= PacketCodec.MAX_PAYLOAD)
    }

    @Test fun forwardBytesPatchesOnlyTtlAndHops() {
        val p = Packet.Alert(Header(1, 2, 5, 0, 100), 0, 0, 1, 1, 1, 1, "x")
        val raw = PacketCodec.encode(p)
        val fwd = PacketCodec.forwardBytes(raw)
        assertEquals(4, fwd[13].toInt())
        assertEquals(1, fwd[14].toInt())
        for (i in raw.indices) if (i != 13 && i != 14) assertEquals(raw[i], fwd[i])
    }
}
