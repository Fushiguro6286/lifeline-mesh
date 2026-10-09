package com.lifeline.mesh

import com.lifeline.mesh.data.AppDatabase
import com.lifeline.mesh.data.Language
import com.lifeline.mesh.data.Role
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.data.SettingsStore
import com.lifeline.mesh.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The single place the UI reads app state from. Phase 0 exposes settings only;
 * neighbors, packets, events and metrics from MeshService are added in Phase 2.
 */
class AppRepository(
    val database: AppDatabase,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Null until the settings file has been read once. */
    val settings: StateFlow<Settings?> =
        settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, null)

    fun setDisplayName(name: String) = scope.launch { settingsStore.setDisplayName(name) }
    fun setRole(role: Role) = scope.launch { settingsStore.setRole(role) }
    fun setLanguage(language: Language) = scope.launch { settingsStore.setLanguage(language) }
    fun setTheme(mode: ThemeMode) = scope.launch { settingsStore.setTheme(mode) }
    fun setOnboardingDone() = scope.launch { settingsStore.setOnboardingDone(true) }
}
