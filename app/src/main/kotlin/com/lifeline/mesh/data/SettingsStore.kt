package com.lifeline.mesh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lifeline.mesh.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

/** Values match the Hello packet role byte. */
enum class Role(val code: Int) { NEEDS_HELP(1), VOLUNTEER(2), RESCUER(3) }

enum class Language(val tag: String) { EN("en"), HI("hi"), KN("kn") }

enum class ThemeMode { SYSTEM, NIGHT, DAYLIGHT }

data class Settings(
    val originId: Int,
    val displayName: String,
    val role: Role,
    val language: Language,
    val serverUrl: String,
    val llmBackend: String,
    val simulationMode: Boolean,
    val demoMode: Boolean,
    val theme: ThemeMode,
    val onboardingDone: Boolean,
) {
    /** Last 4 hex digits of the origin, used in endpoint names like "Asha-3fa1". */
    val originShort: String get() = "%08x".format(originId).takeLast(4)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val ORIGIN = intPreferencesKey("origin_id")
        val NAME = stringPreferencesKey("display_name")
        val ROLE = stringPreferencesKey("role")
        val LANGUAGE = stringPreferencesKey("language")
        val SERVER = stringPreferencesKey("server_url")
        val LLM_BACKEND = stringPreferencesKey("llm_backend")
        val SIMULATION = booleanPreferencesKey("simulation_mode")
        val DEMO = booleanPreferencesKey("demo_mode")
        val THEME = stringPreferencesKey("theme")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
    }

    private val store get() = context.dataStore

    /** Settings, creating the random per-install origin ID on first use. */
    val settings: Flow<Settings> = flow {
        ensureOrigin()
        emitAll(store.data.map(::toSettings))
    }

    private suspend fun ensureOrigin() {
        if (store.data.first()[Keys.ORIGIN] != null) return
        store.edit { prefs ->
            if (prefs[Keys.ORIGIN] == null) {
                var id = 0
                while (id == 0) id = SecureRandom().nextInt()
                prefs[Keys.ORIGIN] = id
            }
        }
    }

    private fun toSettings(p: Preferences) = Settings(
        originId = p[Keys.ORIGIN] ?: 0,
        displayName = p[Keys.NAME] ?: "",
        role = p[Keys.ROLE]?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: Role.NEEDS_HELP,
        language = languageOf(p[Keys.LANGUAGE]),
        serverUrl = p[Keys.SERVER] ?: BuildConfig.SERVER_URL_DEFAULT,
        llmBackend = p[Keys.LLM_BACKEND] ?: "GPU",
        simulationMode = p[Keys.SIMULATION] ?: false,
        demoMode = p[Keys.DEMO] ?: false,
        theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        onboardingDone = p[Keys.ONBOARDED] ?: false,
    )

    suspend fun setDisplayName(name: String) = store.edit { it[Keys.NAME] = name.trim().take(16) }
    suspend fun setRole(role: Role) = store.edit { it[Keys.ROLE] = role.name }
    suspend fun setLanguage(language: Language) = store.edit { it[Keys.LANGUAGE] = language.tag }
    suspend fun setServerUrl(url: String) = store.edit { it[Keys.SERVER] = url.trim() }
    suspend fun setLlmBackend(backend: String) = store.edit { it[Keys.LLM_BACKEND] = backend }
    suspend fun setSimulationMode(on: Boolean) = store.edit { it[Keys.SIMULATION] = on }
    suspend fun setDemoMode(on: Boolean) = store.edit { it[Keys.DEMO] = on }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[Keys.ONBOARDED] = done }

    /**
     * The chosen language, read synchronously. Only for Activity.attachBaseContext, which must
     * pick the locale before any UI exists; the read is a single small local file.
     */
    fun languageBlocking(): Language = runBlocking { languageOf(store.data.first()[Keys.LANGUAGE]) }

    private fun languageOf(tag: String?) = Language.entries.firstOrNull { it.tag == tag } ?: Language.EN
}
