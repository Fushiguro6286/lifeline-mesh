package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.Need.FIRE
import com.lifeline.mesh.packet.Need.MEDICAL
import com.lifeline.mesh.packet.Need.ROAD_BLOCKED
import com.lifeline.mesh.packet.Need.TRAPPED
import com.lifeline.mesh.packet.Need.WATER_FOOD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleExtractorTest {

    private fun check(text: String, needs: Set<Need>, people: Int, severity: Int? = null) {
        val d = RuleExtractor.extract(text)
        assertEquals("needs for: $text", needs, d.needs)
        assertEquals("people for: $text", people, d.people)
        if (severity != null) assertEquals("severity for: $text", severity, d.severity)
        assertEquals(DraftSource.RULES, d.source)
    }

    // ------------------------------------------------------------------ the demo sentences (PROMPTS.md, Phase 4)

    @Test fun english() = check("Water is rising, 5 people on the roof, one elderly person is ill", setOf(TRAPPED, MEDICAL), 5, 3)

    @Test fun hinglish() = check("Paani badh gaya hai, chhat par 5 log hain, ek bujurg beemar hain", setOf(TRAPPED, MEDICAL), 5, 3)

    @Test fun kannada() = check("ಮನೆಯಲ್ಲಿ 4 ಜನ ಸಿಲುಕಿದ್ದಾರೆ, ನೀರು ಬೇಕು", setOf(TRAPPED, WATER_FOOD), 4)

    @Test fun hindiWithDevanagariDigits() = check("आग लगी है, ३ लोग अंदर हैं", setOf(FIRE), 3, 3)

    @Test fun pleaseHelpUsIsBlocked() {
        val d = RuleExtractor.extract("please help us")
        assertTrue(d.needs.isEmpty())
        assertEquals(0, d.people)
        val gate = QualityGate.check(d)
        assertFalse(gate.ok)
        assertEquals(QualityGate.Missing.NEEDS, gate.ask)
        assertEquals(listOf(QualityGate.Missing.NEEDS, QualityGate.Missing.PEOPLE), gate.missing)
    }

    // ------------------------------------------------------------------ numbers

    @Test fun numberWords() {
        check("five of us are trapped", setOf(TRAPPED), 5)
        check("teen log phanse hain", setOf(TRAPPED), 3)
        check("तीन लोग फंसे हैं", setOf(TRAPPED), 3)
        check("ಮೂರು ಜನ ಸಿಲುಕಿದ್ದಾರೆ", setOf(TRAPPED), 3)
    }

    @Test fun nativeDigits() {
        assertEquals(12, RuleExtractor.extract("१२ लोग फंसे हैं").people)
        assertEquals(7, RuleExtractor.extract("೭ ಜನ ಸಿಲುಕಿದ್ದಾರೆ").people)
        assertEquals(4, RuleExtractor.extract("4ಜನ ಸಿಲುಕಿದ್ದಾರೆ").people)
    }

    @Test fun kannadaCountingWords() {
        assertEquals(2, RuleExtractor.extract("ಇಬ್ಬರು ಗಾಯಗೊಂಡಿದ್ದಾರೆ").people)
        assertEquals(setOf(MEDICAL), RuleExtractor.extract("ಇಬ್ಬರು ಗಾಯಗೊಂಡಿದ್ದಾರೆ").needs)
    }

    @Test fun largestCountWinsAndOnlyNextToPeopleWords() {
        assertEquals(6, RuleExtractor.extract("6 people here, 2 children are hurt").people)
        assertEquals(0, RuleExtractor.extract("road blocked near house 12").people)
        assertEquals(0, RuleExtractor.extract("2 of the houses collapsed").people)
        assertEquals(4, RuleExtractor.extract("family of 4 needs food").people)
    }

    @Test fun meMeansOne() {
        assertEquals(1, RuleExtractor.extract("help me I am trapped").people)
        assertEquals(1, RuleExtractor.extract("मुझे चोट लगी है").people)
    }

    // ------------------------------------------------------------------ needs

    @Test fun risingWaterIsNotAWaterNeed() {
        assertFalse(WATER_FOOD in RuleExtractor.extract("water is rising fast").needs)
        assertTrue(WATER_FOOD in RuleExtractor.extract("we need water and food for 3 people").needs)
        assertTrue(WATER_FOOD in RuleExtractor.extract("2 log hain, paani chahiye").needs)
        assertTrue(WATER_FOOD in RuleExtractor.extract("पीने का पानी चाहिए").needs)
    }

    @Test fun wordBoundariesForEnglish() {
        // "ill" inside "will" / "still" is not a medical need.
        assertTrue(RuleExtractor.extract("we will wait, still 3 people here").needs.isEmpty())
    }

    @Test fun roadAndFire() {
        check("tree fell, road blocked", setOf(ROAD_BLOCKED), 0, 1)
        check("aag lagi hai, dhuan bhar gaya", setOf(FIRE), 0, 2)
        check("ಬೆಂಕಿ ಹೊತ್ತಿದೆ, 2 ಜನ ಒಳಗಿದ್ದಾರೆ", setOf(FIRE), 2, 3)
    }

    @Test fun severityLevels() {
        assertEquals(0, RuleExtractor.extract("hello").severity)
        assertEquals(1, RuleExtractor.extract("need food for 2 people").severity)
        assertEquals(2, RuleExtractor.extract("3 people trapped in the house").severity)
        assertEquals(3, RuleExtractor.extract("urgent, 3 people trapped in the house").severity)
    }

    @Test fun noteKeepsTheOriginalText() {
        assertEquals("Paani badh gaya hai", RuleExtractor.extract("  Paani badh gaya hai ").note)
    }
}
