package com.lifeline.mesh.packet

import org.junit.Assert.assertEquals
import org.junit.Test

class NeedTest {
    @Test
    fun bitsMatchPacketSpec() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32), Need.entries.map { it.bit })
    }

    @Test
    fun maskRoundTrip() {
        val needs = setOf(Need.TRAPPED, Need.MEDICAL)
        assertEquals(3, Need.toMask(needs))
        assertEquals(needs, Need.fromMask(3))
        assertEquals(emptySet<Need>(), Need.fromMask(0))
        assertEquals(Need.entries.toSet(), Need.fromMask(63))
    }
}
