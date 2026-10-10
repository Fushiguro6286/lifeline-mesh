package com.lifeline.mesh.mesh

/**
 * The mesh path between two phones (11 Oct 2026, on request): the fewest hops through the live Hello graph (about
 * 3 hops around this phone), which is how a message between them travels right now. Breadth-first, with neighbours
 * taken in origin order, so the same graph always gives the same path on every phone. Pure Kotlin.
 */
object MeshPath {
    /** Origins from [from] to [to], both included; null if [to] is not reachable in the graph this phone knows. */
    fun shortest(from: Int, to: Int, links: List<Pair<Int, Int>>): List<Int>? {
        if (from == to) return listOf(from)
        val adj = HashMap<Int, MutableSet<Int>>()
        for ((a, b) in links) if (a != b) { adj.getOrPut(a) { sortedSetOf() } += b; adj.getOrPut(b) { sortedSetOf() } += a }
        val prev = HashMap<Int, Int>()
        val seen = hashSetOf(from)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in adj[u].orEmpty()) {
                if (!seen.add(v)) continue
                prev[v] = u
                if (v == to) {
                    val path = arrayListOf(to)
                    var c = to
                    while (c != from) { c = prev.getValue(c); path += c }
                    return path.asReversed()
                }
                queue.addLast(v)
            }
        }
        return null
    }
}
