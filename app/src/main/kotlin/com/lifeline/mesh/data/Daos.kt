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

    @Query("SELECT * FROM packets ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<PacketEntity>>

    @Query("SELECT * FROM packets WHERE id = :id")
    suspend fun get(id: Long): PacketEntity?

    @Query("UPDATE packets SET acked = 1, ackEta = :eta WHERE id = :id")
    suspend fun markAcked(id: Long, eta: Int)

    @Query("UPDATE packets SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)
}

@Dao
interface TopologyDao {
    @Upsert
    suspend fun upsert(node: TopologyEntity)

    @Query("SELECT * FROM topology")
    fun observeAll(): Flow<List<TopologyEntity>>

    @Query("DELETE FROM topology WHERE lastHeard < :before")
    suspend fun expire(before: Long)
}

@Dao
interface MetricDao {
    @Insert
    suspend fun insert(metric: MetricEntity)

    @Query("SELECT * FROM metrics ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MetricEntity>>

    @Query("SELECT * FROM metrics ORDER BY timestamp")
    suspend fun all(): List<MetricEntity>
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

    @Query("SELECT * FROM events ORDER BY time DESC, rowId DESC")
    fun observeAll(): Flow<List<EventEntity>>

    companion object {
        const val CAP = 500
    }
}
