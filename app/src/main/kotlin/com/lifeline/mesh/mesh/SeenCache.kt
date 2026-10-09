package com.lifeline.mesh.mesh

/**
 * Message IDs this phone has already handled (CLAUDE.md 7.2): least-recently-used, [capacity] entries,
 * each forgotten [expiryMs] after it was first seen. Times are on the router's monotonic clock.
 * Not thread-safe; the [Router] guards it.
 */
class SeenCache(
    private val capacity: Int = 2_000,
    private val expiryMs: Long = 6 * 60 * 60 * 1000L,
) {
    private val firstSeen = object : LinkedHashMap<Long, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>) = size > capacity
    }

    /** True if [id] was seen within the expiry window. Counts as a use for LRU. */
    fun contains(id: Long, now: Long): Boolean {
        val t = firstSeen[id] ?: return false
        if (now - t >= expiryMs) {
            firstSeen.remove(id)
            return false
        }
        return true
    }

    /** Records [id]. Returns false if it was already there (and not expired). */
    fun add(id: Long, now: Long, seenAt: Long = now): Boolean {
        if (contains(id, now)) return false
        firstSeen[id] = seenAt
        return true
    }

    /** Up to [n] most recently used IDs that have not expired, newest last. */
    fun recent(n: Int, now: Long): List<Long> =
        firstSeen.entries.filter { now - it.value < expiryMs }.map { it.key }.takeLast(n)

    val size: Int get() = firstSeen.size
}
