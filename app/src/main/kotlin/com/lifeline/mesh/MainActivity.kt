package com.lifeline.mesh

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lifeline.mesh.data.Language
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.data.ThemeMode
import com.lifeline.mesh.ui.MainScaffold
import com.lifeline.mesh.ui.onboarding.OnboardingScreen
import com.lifeline.mesh.ui.onboarding.SignInScreen
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** The app language this activity's resources were created with. */
    private var attachedLanguage = Language.EN

    override fun attachBaseContext(newBase: Context) {
        val app = newBase.applicationContext as LifelineApp
        attachedLanguage = app.settingsStore.languageBlocking()
        super.attachBaseContext(newBase.withLocale(attachedLanguage.tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = (application as LifelineApp).repository
        // Select and load the local model in the background (CLAUDE.md 9.8 warm-up); the UI never waits for it.
        (application as LifelineApp).ai.start()

        // Changing the language in onboarding or Settings recreates the activity with the new locale.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settings.filterNotNull().map { it.language }.distinctUntilChanged().collect {
                    if (it != attachedLanguage) recreate()
                }
            }
        }

        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle()
            LifelineRoot(settings, repository)
        }
    }

    @Composable
    private fun LifelineRoot(settings: Settings?, repository: AppRepository) {
        val mode = settings?.theme ?: ThemeMode.SYSTEM
        val night = when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.NIGHT -> true
            ThemeMode.DAYLIGHT -> false
        }
        // Status and navigation bar icons must follow the in-app theme, not only the system setting.
        DisposableEffect(night) {
            val style = if (night) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose { }
        }
        // Signed out with profiles on the phone: sign in, unless the person chose to create a new profile.
        var creating by rememberSaveable { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(settings?.onboardingDone) { if (settings?.onboardingDone == true) creating = false }
        LifelineTheme(mode) {
            when {
                settings == null -> Box(Modifier.fillMaxSize().background(Lifeline.colors.bg))
                !settings.onboardingDone && settings.profiles.isNotEmpty() && !creating -> SignInScreen(
                    profiles = settings.profiles,
                    onSignIn = repository::signIn,
                    onCreate = { creating = true },
                )
                !settings.onboardingDone -> OnboardingScreen(
                    settings = settings,
                    onName = repository::setDisplayName,
                    onLanguage = repository::setLanguage,
                    onRole = repository::setRole,
                    onPhone = repository::setPhone,
                    onDone = { password -> repository.createProfile(password) },
                    onSignIn = if (settings.profiles.isNotEmpty()) { { creating = false } } else null,
                )
                else -> MainScaffold(settings, repository)
            }
        }
    }
}

internal fun Context.withLocale(tag: String): Context {
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))
    return createConfigurationContext(config)
}
