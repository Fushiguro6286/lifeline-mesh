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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * Phase 0 settings: You, Appearance, Mesh (permissions and battery), Privacy, Developer.
 * Mesh service, simulation, demo mode, AI, sync and export rows arrive with the phases that make them work.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    repository: AppRepository,
    onPermissions: () -> Unit,
    onGallery: () -> Unit,
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

        Section(stringResource(R.string.settings_privacy))
        Text(stringResource(R.string.privacy_body), style = Lifeline.type.body, color = c.ink)

        Section(stringResource(R.string.settings_developer))
        LinkRow(R.drawable.ic_widgets, stringResource(R.string.settings_gallery), stringResource(R.string.settings_gallery_body), onGallery)

        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, "%08x".format(settings.originId)),
            style = Lifeline.type.caption, color = c.inkMuted, modifier = Modifier.padding(vertical = Space.l),
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
