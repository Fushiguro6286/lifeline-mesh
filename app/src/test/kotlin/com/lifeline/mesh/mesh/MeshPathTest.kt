package com.lifeline.mesh.mesh

import com.lifeline.mesh.ai.PathFacts
import com.lifeline.mesh.ai.PathLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeshPathTest {
    // The simulated layout: Me(1) - Ravi(2) - Meena(3) - Farhan(5), Ravi - Joseph(4); Me - Divya(6) - Kiran(7).
    private val links = listOf(1 to 2, 2 to 3, 3 to 5, 2 to 4, 1 to 6, 6 to 7)

    @Test fun fewestHopsThroughTheGraph() {
        assertEquals(listOf(1, 6, 7), MeshPath.shortest(1, 7, links))
        assertEquals(listOf(1, 2, 3, 5), MeshPath.shortest(1, 5, links))
        assertEquals(listOf(7, 6, 1, 2, 4), MeshPath.shortest(7, 4, links))
        assertEquals(listOf(1), MeshPath.shortest(1, 1, links))
    }

    @Test fun unreachableIsNull() {
        assertNull(MeshPath.shortest(1, 99, links))
        assertNull(MeshPath.shortest(1, 7, listOf(1 to 2)))
    }

    @Test fun tiesAlwaysGoTheSameWay() {
        // Two equal routes 1-2-4 and 1-3-4: the lower origin wins on every phone.
        val square = listOf(1 to 3, 3 to 4, 1 to 2, 2 to 4)
        assertEquals(listOf(1, 2, 4), MeshPath.shortest(1, 4, square))
        assertEquals(listOf(1, 2, 4), MeshPath.shortest(1, 4, square.reversed()))
    }

    private val coming = PathFacts("Kiran (Ambulance)", "Kiran", "you", listOf("Divya", "Ravi"), mine = true)

    @Test fun computedSentences() {
        assertEquals("Your alert reaches Kiran (Ambulance) through Divya and Ravi: 3 hops, no internet.", PathLine.template(coming))
        assertEquals("Messages between you and Meena go directly: 1 hop, no internet.",
            PathLine.template(PathFacts("you (Ambulance)", "Meena", "Meena", emptyList(), mine = false)))
    }

    @Test fun modelSentenceIsChecked() {
        assertNotNull(PathLine.validate("Your alert travels from you through Divya and Ravi to Kiran in 3 hops.", coming))
        assertNull(PathLine.validate("Your alert reaches Kiran in 4 hops.", coming))            // wrong number
        assertNull(PathLine.validate("Your alert reaches Kiran in three hops.", coming))        // number as a word
        assertNull(PathLine.validate("Your alert reaches the ambulance in 3 hops.", coming))    // team not named
        assertNull(PathLine.validate("It reaches Kiran. Help is near.", coming))                // two sentences
    }
}
