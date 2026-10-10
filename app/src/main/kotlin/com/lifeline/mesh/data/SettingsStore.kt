package com.lifeline.mesh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
    /** The signed-in profile; null when signed out, or on an install from before profiles that has no password yet. */
    val activeProfileId: String? = null,
    /** Every profile on this phone, for the sign-in screen. */
    val profiles: List<ProfileInfo> = emptyList(),
    /** Fall and crash detection while the mesh runs (off until the user turns it on). */
    val fallDetection: Boolean = false,
    /** Low-battery survival mode (10 Oct 2026): below 20 % this phone passes on only what saves lives. On by default. */
    val survivalMode: Boolean = true,
    /** Watch vitals alarm from Health Connect while the mesh runs (off until the user turns it on). */
    val watchVitals: Boolean = false,
    /** The Bluetooth watch to keep connected (address, or SIMULATED_WATCH in simulation mode); null = none. */
    val watchAddress: String? = null,
    val watchName: String = "",
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
        val PROFILES = stringPreferencesKey("profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val FALL_DETECTION = booleanPreferencesKey("fall_detection")
        val SURVIVAL = booleanPreferencesKey("survival_mode")
        val WATCH_VITALS = booleanPreferencesKey("watch_vitals")
        val WATCH_ADDRESS = stringPreferencesKey("watch_address")
        val WATCH_NAME = stringPreferencesKey("watch_name")
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
        activeProfileId = p[Keys.ACTIVE_PROFILE],
        profiles = Profiles.decode(p[Keys.PROFILES]).map { it.info },
        fallDetection = p[Keys.FALL_DETECTION] ?: false,
        survivalMode = p[Keys.SURVIVAL] ?: true,
        watchVitals = p[Keys.WATCH_VITALS] ?: false,
        watchAddress = p[Keys.WATCH_ADDRESS],
        watchName = p[Keys.WATCH_NAME] ?: "",
    )

    suspend fun setDisplayName(name: String) = store.edit { it[Keys.NAME] = name.trim().take(16); it.saveActiveProfile() }
    suspend fun setRole(role: Role) = store.edit { it[Keys.ROLE] = role.name; it.saveActiveProfile() }
    suspend fun setLanguage(language: Language) = store.edit { it[Keys.LANGUAGE] = language.tag }
    suspend fun setServerUrl(url: String) = store.edit { it[Keys.SERVER] = url.trim() }
    suspend fun setLlmBackend(backend: String) = store.edit { it[Keys.LLM_BACKEND] = backend }
    suspend fun setSimulationMode(on: Boolean) = store.edit { it[Keys.SIMULATION] = on }
    suspend fun setDemoMode(on: Boolean) = store.edit { it[Keys.DEMO] = on }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setReadAloud(on: Boolean) = store.edit { it[Keys.READ_ALOUD] = on }
    suspend fun setFallDetection(on: Boolean) = store.edit { it[Keys.FALL_DETECTION] = on }
    suspend fun setSurvivalMode(on: Boolean) = store.edit { it[Keys.SURVIVAL] = on }
    suspend fun setWatchVitals(on: Boolean) = store.edit { it[Keys.WATCH_VITALS] = on }
    suspend fun setWatch(address: String?, name: String) = store.edit {
        if (address == null) { it.remove(Keys.WATCH_ADDRESS); it.remove(Keys.WATCH_NAME) }
        else { it[Keys.WATCH_ADDRESS] = address; it[Keys.WATCH_NAME] = name.take(40) }
    }
    suspend fun setPhone(phone: String) = store.edit { it[Keys.PHONE] = phone.filter { c -> c.isDigit() || c == '+' }.take(16); it.saveActiveProfile() }
    suspend fun setTravel(travel: com.lifeline.mesh.triage.Travel) = store.edit { it[Keys.TRAVEL] = travel.name; it.saveActiveProfile() }

    // ------------------------------------------------------------------ profiles (on this phone only)
    // The current profile's name, phone, role and travel live in the usual keys; the profile list keeps a copy of each.

    /** Copies the current name, phone, role and travel into the signed-in profile's record. */
    private fun MutablePreferences.saveActiveProfile() {
        val id = this[Keys.ACTIVE_PROFILE] ?: return
        val list = Profiles.decode(this[Keys.PROFILES])
        this[Keys.PROFILES] = Profiles.encode(list.map { p ->
            if (p.id != id) p
            else p.copy(name = this[Keys.NAME] ?: "", phone = this[Keys.PHONE] ?: "", role = this[Keys.ROLE] ?: Role.NEEDS_HELP.name, travel = this[Keys.TRAVEL])
        })
    }

    /**
     * Saves the current details as a new password-protected profile and signs it in (end of onboarding, or
     * Set password on an install from before profiles). Does nothing if a profile is already signed in.
     */
    suspend fun createProfile(password: String) {
        val (salt, hash) = Passwords.protect(password)
        store.edit { prefs ->
            if (prefs[Keys.ACTIVE_PROFILE] != null) return@edit
            val profile = Profile(
                id = java.util.UUID.randomUUID().toString(),
                name = prefs[Keys.NAME] ?: "", phone = prefs[Keys.PHONE] ?: "",
                role = prefs[Keys.ROLE] ?: Role.NEEDS_HELP.name, travel = prefs[Keys.TRAVEL],
                salt = salt, hash = hash,
            )
            prefs[Keys.PROFILES] = Profiles.encode(Profiles.decode(prefs[Keys.PROFILES]) + profile)
            prefs[Keys.ACTIVE_PROFILE] = profile.id
            prefs[Keys.ONBOARDED] = true
        }
    }

    /** Signs in to profile [id] if [password] matches. False for a wrong password or an unknown profile. */
    suspend fun signIn(id: String, password: String): Boolean {
        val profile = Profiles.decode(store.data.first()[Keys.PROFILES]).firstOrNull { it.id == id } ?: return false
        if (!Passwords.matches(password, profile)) return false
        store.edit { prefs ->
            prefs[Keys.NAME] = profile.name
            prefs[Keys.PHONE] = profile.phone
            prefs[Keys.ROLE] = profile.role
            if (profile.travel != null) prefs[Keys.TRAVEL] = profile.travel else prefs.remove(Keys.TRAVEL)
            prefs[Keys.ACTIVE_PROFILE] = profile.id
            prefs[Keys.ONBOARDED] = true
        }
        return true
    }

    /** Signs out: the profile stays in the list; the app returns to the sign-in screen. Language and theme stay. */
    suspend fun signOut() = store.edit { prefs ->
        prefs.saveActiveProfile()
        listOf(Keys.NAME, Keys.PHONE, Keys.ROLE, Keys.TRAVEL, Keys.ACTIVE_PROFILE).forEach { prefs.remove(it) }
        prefs[Keys.ONBOARDED] = false
    }

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
