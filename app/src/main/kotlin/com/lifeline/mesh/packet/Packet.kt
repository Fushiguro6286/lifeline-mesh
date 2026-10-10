package com.lifeline.mesh.packet

/**
 * Wire packets from CLAUDE.md section 6. Pure Kotlin, no Android imports.
 *
 * Coordinates are int32 micro-degrees on the wire and kept that way here, so a decode/encode round trip
 * never drifts. Use [Geo] to convert.
 */

/** Low nibble of byte 0. */
enum class PacketType(val code: Int, val relayable: Boolean) {
    ALERT(1, true),
    CHECK_IN(2, true),
    ACK(3, true),
    REPORT(4, true),
    PING(5, true),
    HELLO(6, true),
    /** Live position beacon (Phase 10): relayed like a Hello, never stored or uploaded. */
    POSITION(7, true),
    /** Official disaster warning (10 Oct 2026), brought in by an online phone from the sync server; stored, never uploaded. */
    WARNING(8, true),
    SUMMARY(15, false);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/** The 19-byte header. [time] is unix seconds on the sender's clock (unsigned 32-bit on the wire). */
data class Header(
    val messageId: Long,
    val origin: Int,
    val ttl: Int,
    val hops: Int,
    val time: Long,
) {
    init {
        require(ttl in 0..255) { "ttl out of range: $ttl" }
        require(hops in 0..255) { "hops out of range: $hops" }
        require(time in 0..0xFFFF_FFFFL) { "time out of range: $time" }
    }
}

/** Community report kinds (type 4 body). */
enum class ReportType(val code: Int) {
    ROAD_BLOCKED(1), WATER_AVAILABLE(2), SHELTER_OPEN(3), SHELTER_FULL(4);

    companion object {
        fun fromCode(code: Int): ReportType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Role byte (Hello and Position). Same codes as [com.lifeline.mesh.data.Role], kept separate so this package stays pure.
 * 3 and up are rescue teams; 3 is "other rescue team".
 */
enum class HelloRole(val code: Int) {
    NEEDS_HELP(1), VOLUNTEER(2), RESCUER(3), FIREFIGHTER(4), AMBULANCE(5), POLICE(6), NDRF(7);

    val isTeam: Boolean get() = code >= RESCUER.code

    companion object {
        fun fromCode(code: Int): HelloRole? = entries.firstOrNull { it.code == code }
    }
}

sealed interface Packet {
    val header: Header
    val type: PacketType

    data class Alert(
        override val header: Header,
        val latE6: Int,
        val lonE6: Int,
        /** 10 m steps; [Geo.ACCURACY_UNKNOWN] = unknown. */
        val accuracy: Int,
        val needs: Int,
        val severity: Int,
        val people: Int,
        val note: String = "",
    ) : Packet {
        override val type get() = PacketType.ALERT
        init {
            require(accuracy in 0..255 && needs in 0..255 && people in 0..255) { "byte field out of range" }
            require(severity in 0..3) { "severity out of range: $severity" }
        }
    }

    data class CheckIn(
        override val header: Header,
        val latE6: Int,
        val lonE6: Int,
        val accuracy: Int,
        val name: String = "",
    ) : Packet {
        override val type get() = PacketType.CHECK_IN
        init { require(accuracy in 0..255) }
    }

    data class Ack(
        override val header: Header,
        val refId: Long,
        val etaMinutes: Int,
        val note: String = "",
    ) : Packet {
        override val type get() = PacketType.ACK
        init { require(etaMinutes in 0..255) }
    }

    data class Report(
        override val header: Header,
        val latE6: Int,
        val lonE6: Int,
        val accuracy: Int,
        val reportType: ReportType,
        val note: String = "",
    ) : Packet {
        override val type get() = PacketType.REPORT
        init { require(accuracy in 0..255) }
    }

    data class Ping(
        override val header: Header,
        val refId: Long,
        val echo: Boolean,
    ) : Packet {
        override val type get() = PacketType.PING
    }

    data class Hello(
        override val header: Header,
        val battery: Int,
        val role: HelloRole,
        val neighbors: List<Int>,
        val name: String = "",
    ) : Packet {
        override val type get() = PacketType.HELLO
        init {
            require(battery in 0..255)
            require(neighbors.size <= PacketCodec.MAX_HELLO_NEIGHBORS) { "too many neighbors: ${neighbors.size}" }
        }
    }

    /**
     * Where a phone is now (Phase 10). Sent every 30 s while it moves, every 2 min otherwise; TTL 5 like an alert.
     * Teams see every position; other phones show only teams' (the filter is in the UI, not cryptographic).
     */
    data class Position(
        override val header: Header,
        val latE6: Int,
        val lonE6: Int,
        val accuracy: Int,
        val role: HelloRole,
        val name: String = "",
    ) : Packet {
        override val type get() = PacketType.POSITION
        init { require(accuracy in 0..255) }
    }

    /**
     * An official disaster warning (NDMA SACHET via the sync server). [level] is the issuer's CAP severity (0 Minor,
     * 1 Moderate, 2 Severe, 3 Extreme); [expires] unix seconds; [drill] = a rehearsal from the server laptop. The header's
     * message ID is derived from the CAP identifier ([Warnings.messageId]), so every phone that brings in the same
     * warning makes the same packet and the mesh drops the copies. Links are not authenticated: a phone cannot prove
     * a warning came from the server, so the app says how it arrived.
     */
    data class Warning(
        override val header: Header,
        val level: Int,
        val drill: Boolean,
        val expires: Long,
        val event: String,
        val headline: String,
    ) : Packet {
        override val type get() = PacketType.WARNING
        init {
            require(level in 0..3) { "level out of range: $level" }
            require(expires in 0..0xFFFF_FFFFL) { "expires out of range: $expires" }
        }
    }

    /** Control packet sent once per new link; never relayed. */
    data class Summary(
        override val header: Header,
        val ids: List<Long>,
    ) : Packet {
        override val type get() = PacketType.SUMMARY
        init { require(ids.size <= PacketCodec.MAX_SUMMARY_IDS) { "too many ids: ${ids.size}" } }
    }
}

/** Same packet with a different header (used when forwarding: ttl-1, hops+1). */
fun Packet.withHeader(h: Header): Packet = when (this) {
    is Packet.Alert -> copy(header = h)
    is Packet.CheckIn -> copy(header = h)
    is Packet.Ack -> copy(header = h)
    is Packet.Report -> copy(header = h)
    is Packet.Ping -> copy(header = h)
    is Packet.Hello -> copy(header = h)
    is Packet.Position -> copy(header = h)
    is Packet.Warning -> copy(header = h)
    is Packet.Summary -> copy(header = h)
}

/** Official warnings (10 Oct 2026): fixed origin and deterministic IDs, so copies from many phones are one packet. */
object Warnings {
    /** Shown as the sender of every warning; no phone uses it (origins are random per install). */
    const val ORIGIN = 0x5AC4E700
    /** CAP severity words to [Packet.Warning.level]. */
    fun level(severity: String): Int = when (severity.trim().lowercase()) { "extreme" -> 3; "severe" -> 2; "moderate" -> 1; else -> 0 }
    /** First 8 bytes of SHA-256 of the CAP identifier. */
    fun messageId(capIdentifier: String): Long =
        java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(capIdentifier.toByteArray(Charsets.UTF_8))).long
}

object Geo {
    const val ACCURACY_UNKNOWN = 255

    fun toE6(degrees: Double): Int = Math.round(degrees * 1_000_000.0).toInt()
    fun fromE6(e6: Int): Double = e6 / 1_000_000.0

    /** Metres to 10 m steps, rounded up so we never claim better accuracy than we have. 2,540 m or worse caps at 254. */
    fun accuracyStep(meters: Float?): Int {
        if (meters == null || meters.isNaN() || meters < 0f) return ACCURACY_UNKNOWN
        return kotlin.math.ceil(meters / 10f).toInt().coerceIn(0, 254)
    }

    /** Upper bound in metres, or null when unknown. */
    fun accuracyMeters(step: Int): Int? = if (step == ACCURACY_UNKNOWN) null else step * 10
}
