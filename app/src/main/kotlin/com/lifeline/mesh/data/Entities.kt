package com.lifeline.mesh.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lifeline.mesh.packet.Packet

/** One packet seen by this phone (sent or received). Decoded fields are null when not used by the type. */
@Entity(tableName = "packets", indices = [Index("type"), Index("receivedAt")])
data class PacketEntity(
    @PrimaryKey val id: Long,
    val type: Int,
    val origin: Int,
    val ttl: Int,
    val hops: Int,
    val sentAt: Long,
    val receivedAt: Long,
    val fromEndpoint: String?,
    val rawBytes: ByteArray,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Int? = null,
    val needs: Int? = null,
    val severity: Int? = null,
    val people: Int? = null,
    val note: String? = null,
    val refId: Long? = null,
    val eta: Int? = null,
    val reportType: Int? = null,
    val name: String? = null,
    val isMine: Boolean = false,
    val uploaded: Boolean = false,
    val acked: Boolean = false,
    val ackEta: Int? = null,
    /** For my packets: how many different direct neighbors have received it ("Passed to N nearby phones"). */
    val handedTo: Int = 0,
) {
    // Room entities with arrays need structural equality on the id only.
    override fun equals(other: Any?) = other is PacketEntity && other.id == id
    override fun hashCode() = id.hashCode()
}

/** What this phone knows about another phone, from Hello beacons. */
@Entity(tableName = "topology")
data class TopologyEntity(
    @PrimaryKey val origin: Int,
    val name: String,
    val role: Int,
    val battery: Int,
    val neighbors: String,
    /** Wall-clock ms. */
    val lastHeard: Long,
    /** Links away from this phone when last heard (1 = direct neighbor). */
    val hops: Int,
)

/** A measured number. `simulated` keeps simulated and real values apart. */
@Entity(tableName = "metrics", indices = [Index("name")])
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val name: String,
    val value: Double,
    val unit: String,
    val backend: String?,
    val simulated: Boolean,
    val timestamp: Long,
    val notes: String?,
)

/** Plain-sentence event for the timeline. Capped at [EventDao.CAP] rows. */
@Entity(tableName = "events", indices = [Index("time")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val time: Long,
    val kind: String,
    val text: String,
    val packetId: Long?,
)

/** Room row for a packet. [linkHops] = links travelled to reach this phone (0 for my own). */
fun Packet.toEntity(raw: ByteArray, fromEndpoint: String?, linkHops: Int, mine: Boolean, receivedAt: Long): PacketEntity {
    val base = PacketEntity(
        id = header.messageId,
        type = type.code,
        origin = header.origin,
        ttl = header.ttl,
        hops = linkHops,
        sentAt = header.time * 1000,
        receivedAt = receivedAt,
        fromEndpoint = fromEndpoint,
        rawBytes = raw,
        isMine = mine,
    )
    fun e6(v: Int) = v / 1_000_000.0
    return when (this) {
        is Packet.Alert -> base.copy(lat = e6(latE6), lon = e6(lonE6), accuracy = accuracy, needs = needs, severity = severity, people = people, note = note)
        is Packet.CheckIn -> base.copy(lat = e6(latE6), lon = e6(lonE6), accuracy = accuracy, name = name)
        is Packet.Ack -> base.copy(refId = refId, eta = etaMinutes, note = note)
        is Packet.Report -> base.copy(lat = e6(latE6), lon = e6(lonE6), accuracy = accuracy, reportType = reportType.code, note = note)
        is Packet.Ping -> base.copy(refId = refId)
        is Packet.Hello -> base.copy(name = name)
        is Packet.Summary -> base
    }
}
