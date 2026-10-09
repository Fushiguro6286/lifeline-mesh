package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Need
import kotlin.math.ceil

/**
 * How a team moves, for the travel-time estimate. [detour] turns straight-line distance into a rough route length
 * (roads and paths are rarely straight; water is closer to it). The numbers are assumptions, shown with the estimate.
 */
enum class Travel(val kmh: Double, val detour: Double, val label: String) {
    FOOT(4.0, 1.3, "on foot"),
    VEHICLE(25.0, 1.3, "by road"),
    BOAT(8.0, 1.0, "by boat"),
}

/**
 * Rescue teams (Phase 10, CLAUDE.md 12): who sees which positions, which needs each team handles first, which team an
 * alert goes to over the internet, and the travel-time estimate. Pure Kotlin; no LLM is involved in any number here.
 */
object Teams {
    fun defaultTravel(role: HelloRole): Travel = when (role) {
        HelloRole.FIREFIGHTER, HelloRole.AMBULANCE, HelloRole.POLICE -> Travel.VEHICLE
        HelloRole.NDRF -> Travel.BOAT
        else -> Travel.FOOT
    }

    /** Minutes to cover [distanceM] straight-line metres: distance × detour at the mode's speed, at least 1. */
    fun etaMinutes(distanceM: Double, travel: Travel): Int =
        ceil(distanceM * travel.detour / 1000.0 / travel.kmh * 60.0).toInt().coerceAtLeast(1)

    /** The needs a team sees first on the Rescue tab; null = everything (volunteers, other teams, people in need). */
    fun focus(role: HelloRole): Set<Need>? = when (role) {
        HelloRole.FIREFIGHTER -> setOf(Need.FIRE)
        HelloRole.AMBULANCE -> setOf(Need.AMBULANCE, Need.MEDICAL)
        HelloRole.NDRF -> setOf(Need.TRAPPED)
        HelloRole.POLICE -> setOf(Need.ROAD_BLOCKED)
        else -> null
    }

    /**
     * The team type an alert is sent to over the internet: an ambulance request first, then fire, medical, trapped,
     * road blocked. Null = any team (panic SOS, water or food).
     */
    fun teamFor(needs: Set<Need>): HelloRole? = when {
        Need.AMBULANCE in needs -> HelloRole.AMBULANCE
        Need.FIRE in needs -> HelloRole.FIREFIGHTER
        Need.MEDICAL in needs -> HelloRole.AMBULANCE
        Need.TRAPPED in needs -> HelloRole.NDRF
        Need.ROAD_BLOCKED in needs -> HelloRole.POLICE
        else -> null
    }

    /** Teams see everyone's position; everyone else sees only teams' (and their own). */
    fun canSeePosition(viewer: HelloRole, subject: HelloRole, mine: Boolean): Boolean = mine || viewer.isTeam || subject.isTeam
}
