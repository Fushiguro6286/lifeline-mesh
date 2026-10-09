package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertExtractorTest {
    @Test fun validJsonBecomesAnAiDraft() {
        val d = AlertExtractor.parse("""{"needs":["trapped","medical"],"people":5,"severity":3}""", "note")!!
        assertEquals(setOf(Need.TRAPPED, Need.MEDICAL), d.needs)
        assertEquals(5, d.people)
        assertEquals(3, d.severity)
        assertEquals(DraftSource.AI, d.source)
        assertEquals("note", d.note)
    }

    @Test fun codeFencesAndChatterAroundTheObjectAreTolerated() {
        val d = AlertExtractor.parse("Sure!\n```json\n{\"needs\":[\"fire\"],\"people\":3,\"severity\":3}\n```", "x")
        assertEquals(setOf(Need.FIRE), d!!.needs)
    }

    @Test fun anythingInvalidIsRejected() {
        val bad = listOf(
            "", "no json here", "{", """{"needs":["flood"],"people":2,"severity":1}""", // unknown label
            """{"needs":"trapped","people":2,"severity":1}""",                          // not an array
            """{"needs":["trapped"],"people":"2","severity":1}""",                      // string number
            """{"needs":["trapped"],"people":2.5,"severity":1}""",                      // not an integer
            """{"needs":["trapped"],"people":-1,"severity":1}""",
            """{"needs":["trapped"],"people":2,"severity":4}""",
            """{"needs":["trapped"],"people":300,"severity":1}""",
            """{"needs":["trapped"],"severity":1}""",                                   // missing key
            """{"needs":["panic"],"people":1,"severity":3}""",                          // panic only from the button
        )
        bad.forEach { assertNull("should reject: $it", AlertExtractor.parse(it, "x")) }
    }

    @Test fun emptyNeedsAndZeroPeopleAreValidAnswers() {
        val d = AlertExtractor.parse("""{"needs":[],"people":0,"severity":0}""", "please help us")!!
        assertEquals(emptySet<Need>(), d.needs)
        assertEquals(false, QualityGate.check(d).ok) // the gate still blocks it
    }

    @Test fun probeScoring() {
        val ok = AlertDraft(setOf(Need.TRAPPED, Need.WATER_FOOD), 3, 3, "", DraftSource.AI)
        assertEquals(true, AlertExtractor.probePasses(ok, setOf(Need.TRAPPED), 3))
        assertEquals(false, AlertExtractor.probePasses(ok, setOf(Need.TRAPPED), 4))
        assertEquals(false, AlertExtractor.probePasses(ok, setOf(Need.MEDICAL), 3))
        assertEquals(false, AlertExtractor.probePasses(null, setOf(Need.TRAPPED), 3))
    }

    @Test fun questionValidation() {
        assertEquals("How many people are with you?", ClarifyingQuestions.validate("\"How many people are with you?\"\nExtra", "en"))
        assertEquals("आपके साथ कितने लोग हैं?", ClarifyingQuestions.validate("आपके साथ कितने लोग हैं?", "hi"))
        assertNull(ClarifyingQuestions.validate("How many people are with you", "en"))          // not a question
        assertNull(ClarifyingQuestions.validate("How many people are with you?", "hi"))         // wrong language
        assertNull(ClarifyingQuestions.validate("{\"q\":\"How many?\"}", "en"))
        assertNull(ClarifyingQuestions.validate("?", "en"))
    }

    @Test fun scriptDetection() {
        assertEquals("hi", scriptLanguage("आग लगी है"))
        assertEquals("kn", scriptLanguage("ಬೆಂಕಿ"))
        assertEquals("en", scriptLanguage("Paani badh gaya"))
    }
}
