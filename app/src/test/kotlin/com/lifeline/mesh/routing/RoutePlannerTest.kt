package com.lifeline.mesh.routing

import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.Teams
import com.lifeline.mesh.triage.Travel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private val map get() = TestRoads.map
    private val a = RoadMapTest.A_LAT to RoadMapTest.A_LON
    private val b = RoadMapTest.B_LAT to RoadMapTest.B_LON
    private val urgent = 12.9790 to 77.6000 // between them, a little north

    @Test fun byRoadWhenTheMapHasRoads() {
        val p = RoutePlanner.plan(map, a, emptyList(), b, Travel.VEHICLE)
        assertTrue(p.byRoad)
        assertEquals(map.route(a.first, a.second, b.first, b.second, RoadMode.VEHICLE)!!.minutes, p.minutes)
    }

    @Test fun throughTheUrgentJobFirstThenOnToTheNextPlace() {
        val direct = RoutePlanner.plan(map, a, emptyList(), b, Travel.VEHICLE)
        val via = RoutePlanner.plan(map, a, listOf(urgent), b, Travel.VEHICLE)
        assertTrue(via.byRoad)
        assertTrue(via.minutes >= direct.minutes)
        assertTrue(via.meters > direct.meters)
        // The drawn line goes near the urgent job on the way.
        assertTrue(via.points.any { GeoMath.distanceM(it.first, it.second, urgent.first, urgent.second) < 300 })
    }

    @Test fun boatsAndNoMapFallBackToStraightLines() {
        val boat = RoutePlanner.plan(map, a, emptyList(), b, Travel.BOAT)
        assertFalse(boat.byRoad)
        assertEquals(Teams.etaMinutes(GeoMath.distanceM(a.first, a.second, b.first, b.second), Travel.BOAT), boat.minutes)
        val noMap = RoutePlanner.plan(null, a, emptyList(), b, Travel.VEHICLE)
        assertFalse(noMap.byRoad)
        assertEquals(listOf(a, b), noMap.points)
    }

    @Test fun aBlockOnTheWayIsCountedAndAvoided() {
        val plain = RoutePlanner.plan(map, a, emptyList(), b, Travel.VEHICLE)
        val mid = plain.points[plain.points.size / 2]
        val around = RoutePlanner.plan(map, a, emptyList(), b, Travel.VEHICLE, listOf(Block(mid.first, mid.second)))
        assertEquals(1, around.avoidedBlocks)
        assertTrue(around.meters >= plain.meters)
    }

    @Test fun aBlockAtTheDestinationItselfDoesNotStopHelpGettingThere() {
        val p = RoutePlanner.plan(map, a, emptyList(), b, Travel.VEHICLE, listOf(Block(b.first, b.second)))
        assertTrue(p.byRoad)
        assertEquals(0, p.avoidedBlocks)
    }
}
