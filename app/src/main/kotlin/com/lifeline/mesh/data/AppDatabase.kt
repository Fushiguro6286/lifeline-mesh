package com.lifeline.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PacketEntity::class, TopologyEntity::class, MetricEntity::class, EventEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packets(): PacketDao
    abstract fun topology(): TopologyDao
    abstract fun metrics(): MetricDao
    abstract fun events(): EventDao

    companion object {
        /** 3 → 4: "reached a rescue team" receipts on my alerts. Keeps every stored alert and reply. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE packets ADD COLUMN teamSeenRole INTEGER")
                db.execSQL("ALTER TABLE packets ADD COLUMN teamSeenHops INTEGER")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lifeline.db")
                // Hackathon build: schema changes wipe local data instead of needing migrations.
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
