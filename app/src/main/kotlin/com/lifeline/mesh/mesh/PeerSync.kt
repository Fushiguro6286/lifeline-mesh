package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec

/**
 * Store-and-forward handshake (CLAUDE.md 7.3): on every new link both phones send a Summary of the IDs they
 * already have, and each then sends only the stored packets the other lacks.
 */
object PeerSync {
    /** Summary (TTL 0, never relayed) of the most recent IDs this phone has seen. */
    fun summary(header: Header, seen: SeenCache, now: Long): Packet.Summary =
        Packet.Summary(header.copy(ttl = 0, hops = 0), seen.recent(PacketCodec.MAX_SUMMARY_IDS, now))

    /** Stored packets the peer did not list, most important first. */
    fun missing(peerHas: Packet.Summary, outbox: Outbox, now: Long): List<Outbox.Entry> {
        val has = peerHas.ids.toHashSet()
        return outbox.live(now).filter { it.id !in has }
    }
}
