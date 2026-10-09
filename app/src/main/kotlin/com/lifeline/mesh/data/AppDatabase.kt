package com.lifeline.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PacketEntity::class, TopologyEntity::class, MetricEntity::class, EventEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packets(): PacketDao
    abstract fun topology(): TopologyDao
    abstract fun metrics(): MetricDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lifeline.db")
                // Hackathon build: schema changes wipe local data instead of needing migrations.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
