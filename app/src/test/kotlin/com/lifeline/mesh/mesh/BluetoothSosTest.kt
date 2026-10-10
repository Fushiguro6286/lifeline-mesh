package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothSosTest {
    @Test fun needsPeopleAndName() {
        assertEquals("SOS · trapped+medical · 3 ppl · Asha", BluetoothSos.name(setOf(Need.TRAPPED, Need.MEDICAL), 3, "Asha"))
    }

    @Test fun panicOrNoAlertSaysNeedsHelpAndOnePersonIsNotCounted() {
        assertEquals("SOS · needs help · Asha", BluetoothSos.name(setOf(Need.PANIC), 1, "Asha"))
        assertEquals("SOS · needs help", BluetoothSos.name(emptySet(), 0, " "))
    }

    @Test fun cutAtFortyCharactersOnWholeCharacters() {
        val long = BluetoothSos.name(setOf(Need.TRAPPED, Need.MEDICAL, Need.WATER_FOOD, Need.FIRE), 12, "आशा देवी")
        assertTrue(long.codePointCount(0, long.length) <= BluetoothSos.MAX_CHARS)
        assertTrue(long.startsWith("SOS · trapped+medical+water+fire"))
        val hindi = BluetoothSos.name(setOf(Need.TRAPPED), 2, "आशा".repeat(20))
        assertTrue(hindi.codePointCount(0, hindi.length) <= BluetoothSos.MAX_CHARS)
        assertTrue(hindi.toByteArray().decodeToString() == hindi) // no broken character
    }
}
