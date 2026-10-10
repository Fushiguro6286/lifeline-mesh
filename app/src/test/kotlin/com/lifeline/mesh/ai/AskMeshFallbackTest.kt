package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.Need.FIRE
import com.lifeline.mesh.packet.Need.MEDICAL
import com.lifeline.mesh.packet.Need.TRAPPED
import com.lifeline.mesh.packet.Need.WATER_FOOD
import com.lifeline.mesh.triage.AlertReport
import com.lifeline.mesh.triage.MergedAlert
import com.lifeline.mesh.triage.RankedAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskMeshFallbackTest {
    private fun r(rank: Int, needs: Set<Need>, people: Int, severity: Int = 3, acked: Boolean = false, dist: Double? = rank * 300.0, note: String = "") =
        RankedAlert(rank, MergedAlert(listOf(AlertReport(rank.toLong(), rank, 12.0, 77.0, 10, needs, severity, people, note, 0, 1, acked, if (acked) 20 else null))), dist, dist?.let { 0.0 })

    private val alerts = listOf(
        r(1, setOf(TRAPPED, MEDICAL), 5, note = "Water rising, elderly man ill"),
        r(2, setOf(FIRE), 3),
        r(3, setOf(TRAPPED), 4, acked = true),
        r(4, setOf(WATER_FOOD), 8, severity = 1, note = "Need drinking water"),
        r(5, setOf(MEDICAL), 1, severity = 2, dist = null),
    )

    // ------------------------------------------------------------------ the four quick questions

    @Test fun whoNeedsHelpFirst() {
        val a = AskMesh.ruleAnswer("Who needs help first?", alerts)
        assertEquals(listOf(1, 2, 3), a.cites)
        assertTrue(a.text.startsWith("Help first: #1 trapped, medical, 5 people"))
        assertFalse(a.fromModel)
    }

    @Test fun howManyTrapped() {
        val a = AskMesh.ruleAnswer("How many people are trapped?", alerts)
        assertEquals("9 people in 2 trapped alerts: #1 (5), #3 (4).", a.text)
        assertEquals(listOf(1, 3), a.cites)
    }

    @Test fun anyMedical() {
        val a = AskMesh.ruleAnswer("Any medical cases?", alerts)
        assertTrue(a.text.startsWith("2 medical alerts: #1"))
        assertEquals(listOf(1, 5), a.cites)
    }

    @Test fun stillUnacknowledged() {
        val a = AskMesh.ruleAnswer("What is still unacknowledged?", alerts)
        assertTrue(a.text.startsWith("4 still open:"))
        assertEquals(listOf(1, 2, 4, 5), a.cites)
    }

    // ------------------------------------------------------------------ other questions

    @Test fun noAlertsOrNoMatch() {
        assertEquals(AskMesh.NO_MATCH, AskMesh.ruleAnswer("Who needs help first?", emptyList()).text)
        assertEquals(AskMesh.NO_MATCH, AskMesh.ruleAnswer("any road blocked?", alerts).text)
        assertEquals(AskMesh.NO_MATCH, AskMesh.ruleAnswer("xyz qwerty", alerts).text)
    }

    @Test fun nearestAndNoteWords() {
        assertEquals(listOf(1, 2, 3), AskMesh.ruleAnswer("Which is nearest?", alerts).cites)
        assertEquals(listOf(4), AskMesh.ruleAnswer("anyone asking for drinking water?", alerts).cites)
        assertEquals(listOf(1), AskMesh.ruleAnswer("elderly", alerts).cites)
    }

    @Test fun totals() {
        assertEquals("21 people in 5 alerts: #1 (5), #2 (3), #3 (4), #4 (8), #5 (1).", AskMesh.ruleAnswer("How many people in total?", alerts).text)
    }

    @Test fun allAcknowledged() {
        val acked = alerts.map { RankedAlert(it.rank, MergedAlert(it.alert.reports.map { r -> r.copy(acked = true) }), it.distanceM, it.bearingDeg) }
        assertEquals("Every alert has been acknowledged.", AskMesh.ruleAnswer("What is still unacknowledged?", acked).text)
    }

    // ------------------------------------------------------------------ model answer validation

    @Test fun modelAnswersMustCiteRealAlerts() {
        assertEquals(listOf(1, 3), AskMesh.validate("Alerts #1 and #3 have trapped people.", 5)!!.cites)
        assertNull(AskMesh.validate("Several people are trapped.", 5))       // cites none
        assertNull(AskMesh.validate("See #7.", 5))                           // cites a number that does not exist
        assertNull(AskMesh.validate("#0 is first.", 5))
        assertNull(AskMesh.validate(AskMesh.NO_MATCH, 5))                   // -> rule answer instead
        assertNull(AskMesh.validate("", 5))
    }

    @Test fun promptHoldsOnlyNumberedAlerts() {
        val req = AskMesh.request("Who first?", alerts)
        assertTrue(req.system.contains("#1 trapped, medical | 5 people | 0.3 km | urgent | not acknowledged | note: \"Water rising, elderly man ill\""))
        assertTrue(req.system.contains("#5 medical | 1 people | location unknown | high"))
        assertEquals("Who first?", req.user)
    }
}

class SituationReportTest {
    private val alerts = listOf(
        RankedAlert(1, MergedAlert(listOf(AlertReport(1, 1, 12.0, 77.0, 10, setOf(TRAPPED, MEDICAL), 3, 5, "", 0, 1, false, null))), 300.0, 0.0),
        RankedAlert(2, MergedAlert(listOf(AlertReport(2, 2, 12.0, 77.0, 10, setOf(FIRE), 3, 3, "", 0, 1, true, 20))), 800.0, 90.0),
        RankedAlert(3, MergedAlert(listOf(AlertReport(3, 3, 12.0, 77.0, 10, setOf(WATER_FOOD), 1, 8, "", 0, 1, false, null))), null, null),
    )

    @Test fun factsAreComputedInKotlin() {
        val f = SituationReport.facts(alerts, checkIns = 2)
        assertEquals(3, f.active); assertEquals(16, f.people); assertEquals(2, f.urgent)
        assertEquals(1, f.acknowledged); assertEquals(2, f.open); assertEquals(2, f.checkIns)
        assertEquals(mapOf(TRAPPED to 1, MEDICAL to 1, FIRE to 1, WATER_FOOD to 1), f.needs)
        assertEquals("#1: trapped, medical · 5 people · 300 m", f.topPriority)
    }

    @Test fun phrasedReportKeepsOnlyKnownNumbers() {
        val f = SituationReport.facts(alerts, 2)
        val good = "3 active alerts with 16 people, 2 urgent.\nTop priority is #1: 5 people trapped and ill, 300 m away.\n1 acknowledged, 2 still open, 2 safe check-ins."
        assertEquals(3, SituationReport.validate(good, f)!!.size)
        assertNull(SituationReport.validate(good.replace("16 people", "17 people"), f)) // invented number
        assertNull(SituationReport.validate("3 active alerts.\nOnly two lines.", f))
        // Seen on the iQOO 15: numbers written as words cannot be checked, so the computed lines are shown instead.
        assertNull(SituationReport.validate(good.replace("2 urgent", "two urgent"), f))
        assertEquals("3 active alerts.", SituationReport.validate("- 3 active alerts.\n- 2 urgent.\n- 2 open.\n- extra", f)!!.first())
    }

    @Test fun templateUsesTheFacts() {
        val t = SituationReport.template(SituationReport.facts(alerts, 2))
        assertEquals(3, t.size)
        assertEquals("3 active alerts, 16 people, 2 urgent.", t[0])
        assertEquals(3, SituationReport.template(SituationReport.facts(emptyList(), 0)).size)
    }

    // Seen on the iQOO 15: "Top priority #1: fire · 1 people".
    @Test fun onePersonIsSingular() {
        val one = listOf(RankedAlert(1, MergedAlert(listOf(AlertReport(1, 1, 12.0, 77.0, 10, setOf(FIRE), 3, 1, "", 0, 1, false, null))), 1100.0, 0.0))
        val f = SituationReport.facts(one, 1)
        assertEquals("#1: fire · 1 person · 1100 m", f.topPriority)
        val t = SituationReport.template(f)
        assertEquals("1 active alert, 1 person, 1 urgent.", t[0])
        assertTrue(t[1].startsWith("Top priority #1: fire · 1 person"))
    }

    // Seen on the iQOO 15: the report named the fire (rank 1) while the team's plan started with the trapped job.
    @Test fun teamReportNamesItsFirstJob() {
        val f = SituationReport.facts(alerts, 2, firstJob = alerts[1])
        assertEquals("#2: fire · 3 people · 800 m", f.topPriority)
        assertTrue(f.topIsMyJob)
        assertTrue(SituationReport.template(f)[1].startsWith("Your first job #2: fire"))
        assertTrue(SituationReport.factLines(f).contains("Your first job: #2: fire"))
        assertTrue("2" in f.numbers && "800" in f.numbers)
        assertFalse(SituationReport.facts(alerts, 2).topIsMyJob)
    }
}

class TranslatorTest {
    @Test fun validation() {
        assertEquals("Water is rising, 4 people on the roof", Translator.validate("\"Water is rising, 4 people on the roof\"", "पानी बढ़ रहा है, छत पर 4 लोग", "en"))
        assertEquals("मदद आ रही है", Translator.validate("मदद आ रही है", "Help is coming", "hi"))
        assertNull(Translator.validate("Help is coming", "Help is coming", "hi"))      // unchanged
        assertNull(Translator.validate("Help is coming", "Help is coming", "en"))      // same text
        assertNull(Translator.validate("Boat", "ನಾವು ನಾಲ್ಕು ಜನ ಮಾಳಿಗೆಯ ಮೇಲೆ ಇದ್ದೇವೆ ದಯವಿಟ್ಟು ಬನ್ನಿ ಬೇಗ", "en")) // far too short
        assertNull(Translator.validate("", "x", "en"))
    }
}
