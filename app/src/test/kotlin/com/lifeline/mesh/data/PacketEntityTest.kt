package com.lifeline.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PacketEntityTest {
    private val row = PacketEntity(
        id = 7, type = 1, origin = 2, ttl = 5, hops = 0, sentAt = 1, receivedAt = 2, fromEndpoint = null,
        rawBytes = byteArrayOf(1, 2, 3), isMine = true,
    )

    /** Regression: the UI must see delivery updates (StateFlow drops "equal" lists). */
    @Test fun deliveryChangesMakeRowsDifferent() {
        assertNotEquals(row, row.copy(handedTo = 1))
        assertNotEquals(row, row.copy(acked = true, ackEta = 20))
        assertNotEquals(listOf(row), listOf(row.copy(handedTo = 1)))
    }

    @Test fun sameContentIsEqual() {
        assertEquals(row, row.copy(rawBytes = byteArrayOf(1, 2, 3)))
        assertEquals(row.hashCode(), row.copy(rawBytes = byteArrayOf(1, 2, 3)).hashCode())
    }
}
