package com.lifeline.mesh.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placeholder entries only: the real, vetted text is supplied separately and never written by the app. */
class FirstAidTest {
    private val file = """[
      {"id":"alpha","title":"Alpha guide","keywords":["apple","avocado"],"text":"Placeholder A","source":"Test"},
      {"id":"beta","title":"Beta guide","keywords":["banana","berry","bun"],"text":"Placeholder B","source":"Test"},
      {"id":"beta","title":"Duplicate id","keywords":[],"text":"x","source":"Test"},
      {"id":"gamma","title":"No source","keywords":[],"text":"x","source":""}
    ]"""

    @Test fun parseKeepsValidUniqueEntries() {
        val e = FirstAid.parse(file)
        assertEquals(listOf("alpha", "beta"), e.map { it.id })
        assertEquals("Placeholder B", e[1].text)
    }

    @Test fun brokenFileIsEmpty() {
        assertTrue(FirstAid.parse("not json").isEmpty())
        assertTrue(FirstAid.parse("").isEmpty())
    }

    @Test fun ruleMatchPicksMostKeywords() {
        val e = FirstAid.parse(file)
        assertEquals("beta", FirstAid.ruleMatch("a BANANA and a berry, also an apple", e)?.id)
        assertEquals("alpha", FirstAid.ruleMatch("alpha guide please", e)?.id)
        assertNull(FirstAid.ruleMatch("something else entirely", e))
    }

    @Test fun validateAcceptsOnlyExistingIdsOrNone() {
        val e = FirstAid.parse(file)
        assertEquals("beta", FirstAid.validate(" \"Beta\". ", e))
        assertEquals(FirstAid.NONE, FirstAid.validate("none", e))
        assertNull(FirstAid.validate("delta", e))
        assertNull(FirstAid.validate("The answer is beta", e))
    }

    @Test fun promptListsIdsAndTitlesOnly() {
        val r = FirstAid.request("q", FirstAid.parse(file))
        assertTrue("alpha: Alpha guide (apple, avocado)" in r.system)
        assertTrue("Placeholder" !in r.system) // guide text never goes to the model
    }
}
