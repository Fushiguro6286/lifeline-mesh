package com.lifeline.mesh.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled assets/firstaid.json: it parses, it is honest about review, and everyday questions find the right guide. */
class FirstAidGuidesTest {
    private val raw = File("src/main/assets/firstaid.json").readText()
    private val guides = FirstAid.parse(raw)

    @Test fun everyGuideParses() {
        assertEquals(17, guides.size)
        assertEquals(guides.size, Regex("\"id\":").findAll(raw).count()) // none dropped as invalid
    }

    @Test fun everyGuideHasSourceAndIsMarkedUnreviewed() {
        guides.forEach { g ->
            assertTrue(g.id, g.source.startsWith("Based on"))
            assertFalse("${g.id} must stay unreviewed until a medical professional checks it", g.reviewed)
        }
    }

    @Test fun guidesNamedInsideOtherGuidesExist() {
        val titles = guides.map { it.title }
        Regex("use the '([^']+)' guide").findAll(guides.joinToString("\n") { it.text }).forEach { m ->
            val name = m.groupValues[1]
            assertTrue("'$name' is referenced but missing", titles.any { it.startsWith(name) })
        }
    }

    @Test fun everydayQuestionsFindTheRightGuide() {
        mapOf(
            "my son cut his finger on a knife" to "cuts_scrapes",
            "she is bleeding a lot from the leg" to "heavy_bleeding",
            "hot water fell on his hand, burn" to "burns",
            "haath jal gaya" to "burns",
            "twisted my ankle while running" to "sprain",
            "I think his arm is broken" to "broken_bone",
            "nose bleeding since 5 minutes" to "nosebleed",
            "bee sting on the arm" to "bites_stings",
            "a street dog bit my leg" to "animal_bite",
            "कुत्ते ने काटा" to "animal_bite",
            "dust in my eye" to "eye",
            "he hit his head on the door" to "head_bump",
            "she fainted in the queue" to "fainting",
            "child is choking on food" to "choking",
            "got an electric shock from the switch" to "electric_shock",
            "he is not breathing" to "cpr",
            "मोच आ गई" to "sprain",
            "ನಾಯಿ ಕಚ್ಚಿದೆ" to "animal_bite",
        ).forEach { (q, id) -> assertEquals(q, id, FirstAid.ruleMatch(q, guides)?.id) }
    }
}
