package com.lifeline.mesh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lifeline.mesh.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

/** Values match the Hello/Position role byte. RESCUER = "other rescue team"; 3 and up are teams (Phase 10). */
enum class Role(val code: Int) {
    NEEDS_HELP(1), VOLUNTEER(2), RESCUER(3), FIREFIGHTER(4), AMBULANCE(5), POLICE(6), NDRF(7);

    val isTeam: Boolean get() = code >= RESCUER.code
    val hello: com.lifeline.mesh.packet.HelloRole get() = com.lifeline.mesh.packet.HelloRole.fromCode(code)!!
}

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
    /** Read gate questions and replies aloud (DESIGN.md 10). */
    val readAloud: Boolean = true,
    /** Optional; never sent over the mesh, only with this phone's own uploads to the sync server (Phase 10). */
    val phone: String = "",
    /** How I travel to help, for travel-time estimates; null = the default for my role. */
    val travelChoice: com.lifeline.mesh.triage.Travel? = null,
) {
    val travel: com.lifeline.mesh.triage.Travel get() = travelChoice ?: com.lifeline.mesh.triage.Teams.defaultTravel(role.hello)

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
        val READ_ALOUD = booleanPreferencesKey("read_aloud")
        val PHONE = stringPreferencesKey("phone")
        val TRAVEL = stringPreferencesKey("travel")
        val LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
        val LAST_UPLOAD_COUNT = intPreferencesKey("last_upload_count")
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
        readAloud = p[Keys.READ_ALOUD] ?: true,
        phone = p[Keys.PHONE] ?: "",
        travelChoice = p[Keys.TRAVEL]?.let { runCatching { com.lifeline.mesh.triage.Travel.valueOf(it) }.getOrNull() },
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
    suspend fun setReadAloud(on: Boolean) = store.edit { it[Keys.READ_ALOUD] = on }
    suspend fun setPhone(phone: String) = store.edit { it[Keys.PHONE] = phone.filter { c -> c.isDigit() || c == '+' }.take(16) }
    suspend fun setTravel(travel: com.lifeline.mesh.triage.Travel) = store.edit { it[Keys.TRAVEL] = travel.name }

    /** Last successful upload (time ms, packets sent). Kept out of [Settings] so an upload doesn't recompose the app. */
    val lastUpload: Flow<Pair<Long, Int>?> = store.data.map { p -> p[Keys.LAST_UPLOAD_AT]?.let { it to (p[Keys.LAST_UPLOAD_COUNT] ?: 0) } }
        .distinctUntilChanged()

    suspend fun setLastUpload(at: Long, count: Int) = store.edit { it[Keys.LAST_UPLOAD_AT] = at; it[Keys.LAST_UPLOAD_COUNT] = count }

    /**
     * The chosen language, read synchronously. Only for Activity.attachBaseContext, which must
     * pick the locale before any UI exists; the read is a single small local file.
     */
    fun languageBlocking(): Language = runBlocking { languageOf(store.data.first()[Keys.LANGUAGE]) }

    private fun languageOf(tag: String?) = Language.entries.firstOrNull { it.tag == tag } ?: Language.EN
}
