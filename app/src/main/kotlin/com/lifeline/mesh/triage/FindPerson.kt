package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.Need

/** An alert a person sent, as this phone knows it: when, what, and who said "help is coming" (names). */
data class PersonAlert(val at: Long, val needs: Set<Need>, val people: Int, val severity: Int, val hops: Int, val helpers: List<String>)

/**
 * Everything this phone has heard from one phone ([origin]): the names it used, its newest "I'm safe" check-in, its
 * newest alert, and when it was last heard at all (hello or position). Times are wall-clock ms; hops = links travelled.
 */
data class HeardPerson(
    val origin: Int,
    val names: Set<String>,
    val safeAt: Long? = null,
    val safeHops: Int? = null,
    val alert: PersonAlert? = null,
    val lastHeardAt: Long? = null,
    val lastHeardHops: Int? = null,
    val isTeam: Boolean = false,
) {
    val name: String get() = names.firstOrNull { it.isNotBlank() } ?: "%08x".format(origin).takeLast(4)
}

/**
 * "Find someone" (11 Oct 2026): family search over what this phone has heard. No new packet: check-ins and alerts
 * flood the mesh, so most phones hold them. Pure Kotlin; the answer is computed text, never from the model.
 */
object FindPerson {
    /** Names shorter than this match too much ("a"). */
    const val MIN_QUERY = 2

    /** People whose name contains every word of [query] (any case, any order); safest-first is not implied. */
    fun search(query: String, people: List<HeardPerson>): List<HeardPerson> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.joinToString("").length < MIN_QUERY) return emptyList()
        return people.filter { p -> p.names.any { n -> words.all { w -> n.lowercase().contains(w) } } }
            .sortedByDescending { latest(it) }
    }

    private fun latest(p: HeardPerson) = listOfNotNull(p.safeAt, p.alert?.at, p.lastHeardAt).maxOrNull() ?: 0L

    /** Safe = checked in after their last alert (or with no alert); help = an alert newer than any check-in. */
    enum class Status { SAFE, NEEDS_HELP, HEARD }

    fun status(p: HeardPerson): Status = when {
        p.alert != null && (p.safeAt == null || p.alert.at > p.safeAt) -> Status.NEEDS_HELP
        p.safeAt != null -> Status.SAFE
        else -> Status.HEARD
    }

    /** One or two plain sentences about this person. [ago] turns a time into "12 min ago". */
    fun summary(p: HeardPerson, ago: (Long) -> String): String {
        fun hops(h: Int?) = h?.let { if (it <= 1) ", 1 hop away" else ", $it hops away" } ?: ""
        return when (status(p)) {
            Status.SAFE -> "${p.name} checked in safe ${ago(p.safeAt!!)}${hops(p.safeHops)}."
            Status.NEEDS_HELP -> {
                val a = p.alert!!
                val what = com.lifeline.mesh.ai.SituationReport.needLabels(a.needs) +
                    if (a.people > 0) ", ${a.people} ${if (a.people == 1) "person" else "people"}" else ""
                "${p.name} asked for help ${ago(a.at)}: $what${hops(a.hops)}. " +
                    if (a.helpers.isEmpty()) "No team has answered yet." else "${a.helpers.joinToString(" and ")} said help is coming."
            }
            Status.HEARD -> "${p.name}'s phone was last heard ${ago(p.lastHeardAt ?: 0)}${hops(p.lastHeardHops)}. No alert and no check-in from them."
        }
    }
}
