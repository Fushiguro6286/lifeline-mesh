package com.lifeline.mesh.mesh

/**
 * At most [limit] new packets per origin in any sliding [windowMs] (CLAUDE.md 7.2: more than 10 per minute
 * is dropped). Not thread-safe; the [Router] guards it.
 */
class RateLimiter(
    private val limit: Int = 10,
    private val windowMs: Long = 60_000L,
) {
    private val times = HashMap<Int, ArrayDeque<Long>>()

    /** Records one packet from [origin] at [now] and returns true, or returns false if over the limit. */
    fun allow(origin: Int, now: Long): Boolean {
        val q = times.getOrPut(origin) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        if (times.size > 1_000) prune(now)
        return true
    }

    private fun prune(now: Long) {
        times.entries.removeAll { (_, q) -> q.isEmpty() || now - q.last() >= windowMs }
    }
}
