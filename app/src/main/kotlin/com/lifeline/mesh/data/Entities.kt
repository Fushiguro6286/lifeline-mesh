package com.lifeline.mesh.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val lastHeard: Long,
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
