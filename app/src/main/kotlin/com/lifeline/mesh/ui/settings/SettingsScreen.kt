package com.lifeline.mesh.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.BuildConfig
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Language
import com.lifeline.mesh.data.Role as UserRole
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.data.ThemeMode
import com.lifeline.mesh.ui.onboarding.OptionCard
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch

/**
 * Settings (DESIGN.md 8.6): You, Appearance, Mesh, On-device AI, Sync, Privacy, Developer.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    repository: AppRepository,
    onPermissions: () -> Unit,
    onGallery: () -> Unit,
    onDebug: () -> Unit,
    onBenchmarks: () -> Unit,
) {
    val c = Lifeline.colors
    val focus = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf(settings.displayName) }
    val nameChanged = name.trim() != settings.displayName

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Section(stringResource(R.string.settings_you))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(16) },
            label = { Text(stringResource(R.string.settings_name)) },
            singleLine = true,
            textStyle = Lifeline.type.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { repository.setDisplayName(name); focus.clearFocus() }),
            trailingIcon = if (nameChanged) {
                { TextButton(onClick = { repository.setDisplayName(name); focus.clearFocus() }) { Text(stringResource(R.string.save), color = c.link) } }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        Label(stringResource(R.string.settings_role))
        OptionCard(stringResource(R.string.role_needs_help), settings.role == UserRole.NEEDS_HELP, { repository.setRole(UserRole.NEEDS_HELP) }, icon = R.drawable.ic_person)
        OptionCard(stringResource(R.string.role_volunteer), settings.role == UserRole.VOLUNTEER, { repository.setRole(UserRole.VOLUNTEER) }, icon = R.drawable.ic_volunteer_activism)
        OptionCard(stringResource(R.string.role_rescuer), settings.role == UserRole.RESCUER, { repository.setRole(UserRole.RESCUER) }, icon = R.drawable.ic_health_and_safety)

        Label(stringResource(R.string.settings_language))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(Language.EN to R.string.lang_en, Language.HI to R.string.lang_hi, Language.KN to R.string.lang_kn).forEach { (lang, label) ->
                OptionCard(stringResource(label), settings.language == lang, { repository.setLanguage(lang) }, Modifier.weight(1f), compact = true)
            }
        }

        Section(stringResource(R.string.settings_appearance))
        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.NIGHT to R.string.theme_night,
            ThemeMode.DAYLIGHT to R.string.theme_daylight,
        ).forEach { (mode, label) ->
            OptionCard(stringResource(label), settings.theme == mode, { repository.setTheme(mode) }, icon = R.drawable.ic_dark_mode)
        }

        Section(stringResource(R.string.settings_mesh))
        LinkRow(R.drawable.ic_verified_user, stringResource(R.string.settings_permissions), stringResource(R.string.settings_permissions_body), onPermissions)
        SwitchRow(R.drawable.ic_widgets, stringResource(R.string.settings_simulation), stringResource(R.string.settings_simulation_body),
            settings.simulationMode, repository::setSimulationMode)
        SwitchRow(R.drawable.ic_speed, stringResource(R.string.settings_demo), stringResource(R.string.settings_demo_body),
            settings.demoMode, repository::setDemoMode)
        SwitchRow(R.drawable.ic_volume_up, "Read aloud", "Reads the question on the review sheet and \"Help is coming\" replies out loud.",
            settings.readAloud, repository::setReadAloud)

        Section("On-device AI")
        AiSettingsCard(onBenchmarks)

        Section("Sync")
        SyncCard(settings, repository)

        Section(stringResource(R.string.settings_privacy))
        Text(stringResource(R.string.privacy_body), style = Lifeline.type.body, color = c.ink)

        Section(stringResource(R.string.settings_developer))
        LinkRow(R.drawable.ic_hub, stringResource(R.string.settings_debug), stringResource(R.string.settings_debug_body), onDebug)
        LinkRow(R.drawable.ic_widgets, stringResource(R.string.settings_gallery), stringResource(R.string.settings_gallery_body), onGallery)

        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, "%08x".format(settings.originId)),
            style = Lifeline.type.caption, color = c.inkMuted, modifier = Modifier.padding(vertical = Space.l),
        )
    }
}

/**
 * Sync (DESIGN.md 8.6): server URL, what is waiting, last upload, and Upload now. Uploads start by themselves on
 * validated internet; Upload now also works on a Wi-Fi without internet (a laptop hotspot).
 */
@Composable
private fun SyncCard(settings: Settings, repository: AppRepository) {
    val c = Lifeline.colors
    val focus = LocalFocusManager.current
    val uploader = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.lifeline.mesh.LifelineApp).uploader
    val st by uploader.state.collectAsStateWithLifecycle()
    val last by repository.lastUpload.collectAsStateWithLifecycle()
    var url by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    val changed = url.trim() != settings.serverUrl
    fun save() { repository.setServerUrl(url); focus.clearFocus() }

    Column(
        Modifier.fillMaxWidth().clip(Shapes.card).background(c.surface).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.take(100) },
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.10:8000") },
            singleLine = true,
            textStyle = Lifeline.type.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
            keyboardActions = KeyboardActions(onDone = { save() }),
            trailingIcon = if (changed) {
                { TextButton(onClick = { save() }) { Text(stringResource(R.string.save), color = c.link) } }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (st.internet) "Internet: available. Uploads start by themselves." else "Internet: none. Packets wait here until a network appears.",
            style = Lifeline.type.body, color = c.ink,
        )
        Text("Waiting to upload: ${st.pending}", style = Lifeline.type.body, color = c.ink)
        Text(
            last?.let { (at, n) -> "Last upload: " + java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.US).format(java.util.Date(at)) + " · $n packets" }
                ?: "Last upload: never",
            style = Lifeline.type.body, color = c.inkMuted,
        )
        st.lastError?.let { Text(it, style = Lifeline.type.body, color = c.warn) }
        if (settings.simulationMode) Text("Simulated packets are never uploaded.", style = Lifeline.type.caption, color = c.inkMuted)
        com.lifeline.mesh.ui.components.SecondaryButton(
            if (st.uploading) "Uploading…" else "Upload now",
            { if (!st.uploading) { if (changed) save(); uploader.uploadNow() } },
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Section(text: String) = Text(
    text, style = Lifeline.type.title, color = Lifeline.colors.ink,
    modifier = Modifier.padding(top = Space.l).semantics { heading() },
)

@Composable
private fun Label(text: String) = Text(text, style = Lifeline.type.label, color = Lifeline.colors.inkMuted)

@Composable
private fun SwitchRow(@DrawableRes icon: Int, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lifeline.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.standard)
            .clip(Shapes.card)
            .background(c.surface)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(painterResource(icon), null, tint = c.link, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Lifeline.type.bodyStrong, color = c.ink)
            Text(body, style = Lifeline.type.caption, color = c.inkMuted)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onFill, checkedTrackColor = c.link,
                uncheckedThumbColor = c.inkMuted, uncheckedTrackColor = c.surfaceRaised, uncheckedBorderColor = c.outline,
            ),
        )
    }
}

@Composable
private fun LinkRow(@DrawableRes icon: Int, title: String, body: String, onClick: () -> Unit) {
    val c = Lifeline.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.standard)
            .clip(Shapes.card)
            .background(c.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(painterResource(icon), null, tint = c.link, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Lifeline.type.bodyStrong, color = c.ink)
            Text(body, style = Lifeline.type.caption, color = c.inkMuted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.inkMuted)
    }
}
