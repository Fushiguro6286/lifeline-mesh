package com.lifeline.mesh.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanLineTest {
    private val stops = listOf(
        PlanStop(1, "Urgent", "trapped, medical", 6, 6, 1),
        PlanStop(2, "Moderate", "water or food", 9, 15, 0),
    )

    @Test fun templateSaysTheOrderAndTimes() {
        assertEquals("First urgent: trapped, medical, 6 min (around a blocked road). Then moderate: water or food, 9 min. " +
            "All done in about 15 min.", PlanLine.template(stops))
        assertTrue(PlanLine.factLines(stops).contains("route goes around 1 blocked road"))
    }

    @Test fun modelTextWithOnlyPlanNumbersIsKept() {
        val ok = "Go to the urgent trapped and medical case first, 6 min away around a blocked road. Then the water request, arriving after 15 min."
        assertEquals(ok, PlanLine.validate(ok, stops))
    }

    @Test fun inventedNumbersWordsOrLongTextAreRejected() {
        assertNull(PlanLine.validate("Go to job 1 first, 7 min away.", stops))
        assertNull(PlanLine.validate("Go to the trapped people first, six minutes away.", stops))
        assertNull(PlanLine.validate("One. Two. Three. Four.", stops))
        assertNull(PlanLine.validate("{\"plan\": 1}", stops))
    }
}
