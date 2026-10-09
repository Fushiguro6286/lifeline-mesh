package com.lifeline.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {
    /** Returns -1 when the packet id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(packet: PacketEntity): Long

    @Query("SELECT * FROM packets WHERE simulated = :simulated ORDER BY receivedAt DESC")
    fun observe(simulated: Boolean): Flow<List<PacketEntity>>

    @Query("SELECT * FROM packets WHERE id = :id")
    suspend fun get(id: Long): PacketEntity?

    @Query("UPDATE packets SET acked = 1, ackEta = :eta WHERE id = :id")
    suspend fun markAcked(id: Long, eta: Int)

    @Query("UPDATE packets SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM packets WHERE simulated = 1")
    suspend fun clearSimulated()

    @Query("UPDATE packets SET handedTo = MAX(handedTo, :count) WHERE id = :id")
    suspend fun setHandedTo(id: Long, count: Int)

    /** Packets the router should remember after a restart (store and forward keeps 6 hours). */
    @Query("SELECT * FROM packets WHERE receivedAt >= :since AND simulated = :simulated ORDER BY receivedAt")
    suspend fun since(since: Long, simulated: Boolean): List<PacketEntity>
}

@Dao
interface TopologyDao {
    @Upsert
    suspend fun upsert(node: TopologyEntity)

    @Query("SELECT * FROM topology")
    fun observeAll(): Flow<List<TopologyEntity>>

    @Query("DELETE FROM topology WHERE lastHeard < :before")
    suspend fun expire(before: Long)

    @Query("DELETE FROM topology")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<TopologyEntity>)

    /** The router hands over its whole table each time; keep Room identical to it. */
    @Transaction
    suspend fun replaceAll(nodes: List<TopologyEntity>) {
        clear()
        insertAll(nodes)
    }
}

@Dao
interface MetricDao {
    @Insert
    suspend fun insert(metric: MetricEntity)

    @Query("DELETE FROM metrics WHERE simulated = 1")
    suspend fun clearSimulated()

    @Query("SELECT * FROM metrics WHERE simulated = :simulated ORDER BY timestamp DESC")
    fun observe(simulated: Boolean): Flow<List<MetricEntity>>

    @Query("SELECT * FROM metrics ORDER BY timestamp")
    suspend fun all(): List<MetricEntity>

    /** Real measurements only (never simulated), newest first: the Benchmarks screen. */
    @Query("SELECT * FROM metrics WHERE simulated = 0 ORDER BY timestamp DESC LIMIT 1000")
    fun observeReal(): Flow<List<MetricEntity>>
}

@Dao
interface EventDao {
    @Insert
    suspend fun insertRow(event: EventEntity)

    @Query("DELETE FROM events WHERE rowId NOT IN (SELECT rowId FROM events ORDER BY time DESC, rowId DESC LIMIT :cap)")
    suspend fun trim(cap: Int)

    @Transaction
    suspend fun insert(event: EventEntity) {
        insertRow(event)
        trim(CAP)
    }

    @Query("DELETE FROM events WHERE simulated = 1")
    suspend fun clearSimulated()

    @Query("SELECT * FROM events WHERE simulated = :simulated ORDER BY time DESC, rowId DESC")
    fun observe(simulated: Boolean): Flow<List<EventEntity>>

    companion object {
        const val CAP = 500
    }
}
