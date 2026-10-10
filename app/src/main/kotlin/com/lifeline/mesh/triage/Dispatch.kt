package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Need

/** A rescue team that can take jobs: where it is now, and how it travels. */
data class DispatchTeam(val origin: Int, val name: String, val role: HelloRole, val lat: Double, val lon: Double)

/** An open alert: [takenBy] = the team that already said "help is coming" (null = nobody yet). */
data class DispatchJob(
    val alertId: Long, val rank: Int, val needs: Set<Need>, val severity: Int, val lat: Double?, val lon: Double?,
    val takenBy: Int? = null,
)

/**
 * Who should go to [job]: [team] reaches it after about [minutes] (its earlier jobs included), straight-line estimate.
 * [rightType] = the team handles this kind of need (Teams.teamFor); false = no such team in reach, the nearest other.
 * [taken] = a team already said "help is coming" (then [team] is that team, if known here).
 */
data class Assignment(val job: DispatchJob, val team: DispatchTeam?, val minutes: Int?, val rightType: Boolean, val taken: Boolean)

/**
 * Team dispatch without double-booking (10 Oct 2026): every team phone runs the same fixed rules on what it has
 * heard, so two ambulances do not both go to one person while someone else waits. Pure Kotlin; no LLM.
 *
 * Jobs a team already accepted stay with it and keep it busy. The rest go most urgent first (then by rank) to the
 * team of the right type that would get there soonest: its busy minutes (earlier jobs, [ON_SCENE_MIN] at each) plus
 * the straight-line travel estimate (Teams.etaMinutes). A general rescue team ("Other team") counts as the right
 * type for everything. Ties go to the lower origin, so every phone picks the same team.
 */
object Dispatch {
    /** Minutes a team is assumed to spend at each job before it can leave for the next. */
    const val ON_SCENE_MIN = 15

    fun split(jobs: List<DispatchJob>, teams: List<DispatchTeam>): List<Assignment> {
        val busy = teams.associate { it.origin to 0 }.toMutableMap()
        val byOrigin = teams.associateBy { it.origin }
        fun travel(t: DispatchTeam, j: DispatchJob): Int? {
            val la = j.lat ?: return null; val lo = j.lon ?: return null
            return Teams.etaMinutes(GeoMath.distanceM(t.lat, t.lon, la, lo), Teams.defaultTravel(t.role))
        }

        // Accepted jobs first: they keep their team busy.
        val taken = jobs.filter { it.takenBy != null }.map { j ->
            val t = byOrigin[j.takenBy]
            val m = t?.let { tt -> busy.getValue(tt.origin) + (travel(tt, j) ?: 0) }
            if (t != null && m != null) busy[t.origin] = m + ON_SCENE_MIN
            Assignment(j, t, m, rightType = t != null && fits(t.role, j.needs), taken = true)
        }

        val open = jobs.filter { it.takenBy == null }.sortedWith(compareByDescending<DispatchJob> { it.severity }.thenBy { it.rank })
        val given = open.map { j ->
            val right = teams.filter { fits(it.role, j.needs) }
            val pool = right.ifEmpty { teams }
            val best = pool.minWithOrNull(compareBy<DispatchTeam> { t -> busy.getValue(t.origin) + (travel(t, j) ?: 0) }.thenBy { it.origin })
            if (best == null) Assignment(j, null, null, rightType = false, taken = false)
            else {
                val m = travel(best, j)?.let { busy.getValue(best.origin) + it }
                busy[best.origin] = (m ?: busy.getValue(best.origin)) + ON_SCENE_MIN
                Assignment(j, best, m, rightType = right.isNotEmpty(), taken = false)
            }
        }
        return (given + taken).sortedBy { it.job.rank }
    }

    /** The team handles this kind of need (or anything, for a general rescue team or a need no team owns). */
    fun fits(role: HelloRole, needs: Set<Need>): Boolean {
        if (role == HelloRole.RESCUER) return true
        val want = Teams.teamFor(needs) ?: return role.isTeam
        return role == want
    }
}
