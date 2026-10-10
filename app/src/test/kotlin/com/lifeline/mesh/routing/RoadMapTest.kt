package com.lifeline.mesh.routing

import com.lifeline.mesh.triage.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Uses the real bundled map (central Bengaluru), so the routes are on real roads. */
class RoadMapTest {
    companion object {
        lateinit var map: RoadMap
        @BeforeClass @JvmStatic fun load() {
            val t = System.currentTimeMillis()
            map = RoadMap.fromPmtiles(File("src/main/assets/map/venue.pmtiles").readBytes())
            println("road graph: ${map.nodes} nodes, ${map.edges} edges in ${System.currentTimeMillis() - t} ms")
        }
        // MG Road metro area to Cubbon Park (Bengaluru), about 1.3 km apart in a straight line.
        const val A_LAT = 12.9756; const val A_LON = 77.6050
        const val B_LAT = 12.9763; const val B_LON = 77.5929
    }

    @Test fun tileIdsMatchThePmtilesSpec() {
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(17L, PmTiles.tileId(2, 3, 1))
        assertEquals(1_232_480_297L, PmTiles.tileId(15, 23447, 15193))
    }

    @Test fun theMapHasARealRoadNetwork() {
        assertTrue("nodes ${map.nodes}", map.nodes > 10_000)
        assertTrue(map.edges > map.nodes)
    }

    @Test fun routeFollowsRoadsAndIsLongerThanTheStraightLine() {
        val r = map.route(A_LAT, A_LON, B_LAT, B_LON, RoadMode.VEHICLE)
        assertNotNull(r); r!!
        val straight = GeoMath.distanceM(A_LAT, A_LON, B_LAT, B_LON)
        println("route ${r.meters.toInt()} m (straight ${straight.toInt()} m), ${r.minutes} min, ${r.points.size} points")
        assertTrue(r.meters >= straight)
        assertTrue("too long: ${r.meters}", r.meters < straight * 2.5)
        assertTrue(r.points.size > 5)
        assertEquals(0, r.avoidedBlocks)
    }

    @Test fun aBlockedRoadOnTheRouteIsAvoided() {
        val first = map.route(A_LAT, A_LON, B_LAT, B_LON, RoadMode.VEHICLE)!!
        // Block the road at the middle of the first route.
        val mid = first.points[first.points.size / 2]
        val block = Block(mid.first, mid.second, 40.0)
        val second = map.route(A_LAT, A_LON, B_LAT, B_LON, RoadMode.VEHICLE, listOf(block))
        assertNotNull(second); second!!
        println("around the block: ${second.meters.toInt()} m, ${second.minutes} min (was ${first.meters.toInt()} m)")
        assertEquals(1, second.avoidedBlocks)
        assertTrue(second.meters >= first.meters)
        // No road point of the new route is inside the blocked circle (the first and last points are the endpoints).
        assertTrue(second.points.drop(1).dropLast(1).none { GeoMath.distanceM(it.first, it.second, block.lat, block.lon) <= block.radiusM })
    }

    @Test fun offTheMapThereIsNoRoute() {
        assertNull(map.route(13.5, 78.5, B_LAT, B_LON, RoadMode.VEHICLE))
    }

    @Test fun onFootPathsCanBeUsed() {
        val foot = map.route(A_LAT, A_LON, B_LAT, B_LON, RoadMode.FOOT)!!
        val car = map.route(A_LAT, A_LON, B_LAT, B_LON, RoadMode.VEHICLE)!!
        assertTrue(foot.minutes > car.minutes)
        assertTrue(foot.meters <= car.meters + 1)
    }

    @Test fun junctionsAreRebuiltSoAlmostAllRoadsConnect() {
        // The map's lines are simplified for drawing; without rebuilt junctions most streets would be islands.
        val a = map.nearest(A_LAT, A_LON)
        assertTrue(map.componentSize(a) > map.nodes * 0.95)
        // A car does not start on a footpath: the nearest road point for a vehicle has a road it can drive.
        val car = map.nearest(A_LAT, A_LON, mode = RoadMode.VEHICLE)
        assertTrue(map.edgesOf(car).any { it.second != "path" })
    }
}
