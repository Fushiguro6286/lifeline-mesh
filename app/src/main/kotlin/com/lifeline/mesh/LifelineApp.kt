package com.lifeline.mesh

import android.app.Application
import com.lifeline.mesh.data.AppDatabase
import com.lifeline.mesh.data.SettingsStore

/** Holds the app-wide singletons. No DI framework: screens reach these through [repository]. */
class LifelineApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val settingsStore by lazy { SettingsStore(this) }
    val repository by lazy { AppRepository(database, settingsStore) }
    val ai by lazy { com.lifeline.mesh.ai.AiRuntime(this, database) }
    val readAloud by lazy { com.lifeline.mesh.ai.ReadAloud(this) }
    val uploader by lazy { com.lifeline.mesh.sync.ServerUploader(this, database, repository, settingsStore) }

    override fun onCreate() {
        super.onCreate()
        uploader.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ai.onTrimMemory(level)
    }
}
