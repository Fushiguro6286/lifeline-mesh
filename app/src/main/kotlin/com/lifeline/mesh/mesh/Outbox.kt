package com.lifeline.mesh.mesh

/**
 * Store and forward (CLAUDE.md 7.3): packets this phone should pass on to any phone that shows up later,
 * kept for [keepMs]. Bytes are stored ready to send (my packets as created, relayed ones already with
 * TTL − 1 and hops + 1). Hellos and pings are never stored: they are only meaningful live.
 * Not thread-safe; the [Router] guards it.
 */
class Outbox(
    private val keepMs: Long = 6 * 60 * 60 * 1000L,
    private val capacity: Int = 1_000,
) {
    class Entry(val id: Long, val bytes: ByteArray, val priority: Int, val storedAt: Long)

    private val entries = LinkedHashMap<Long, Entry>()

    fun add(id: Long, bytes: ByteArray, priority: Int, now: Long, storedAt: Long = now) {
        prune(now)
        if (now - storedAt >= keepMs || id in entries) return
        entries[id] = Entry(id, bytes, priority, storedAt)
        if (entries.size > capacity) {
            // Drop the least important, oldest packet.
            val victim = entries.values.maxWith(compareBy<Entry> { it.priority }.thenByDescending { it.storedAt })
            entries.remove(victim.id)
        }
    }

    /** Live entries, most important first, oldest first within a priority. */
    fun live(now: Long): List<Entry> {
        prune(now)
        return entries.values.sortedWith(compareBy<Entry> { it.priority }.thenBy { it.storedAt })
    }

    operator fun contains(id: Long) = id in entries

    val size: Int get() = entries.size

    fun prune(now: Long) {
        entries.values.removeAll { now - it.storedAt >= keepMs }
    }
}
