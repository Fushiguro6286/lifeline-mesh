package com.lifeline.mesh.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lifeline.mesh.R
import com.lifeline.mesh.data.ProfileInfo
import com.lifeline.mesh.data.Role as UserRole
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Shown after Sign out: pick a profile on this phone and enter its password, or create a new profile.
 * Everything is checked on the phone; no internet needed.
 */
@Composable
fun SignInScreen(
    profiles: List<ProfileInfo>,
    onSignIn: suspend (id: String, password: String) -> Boolean,
    onCreate: () -> Unit,
) {
    val c = Lifeline.colors
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(profiles.singleOrNull()?.id) }
    var password by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val canSubmit = selected != null && password.isNotEmpty() && !checking
    fun submit() {
        val id = selected ?: return
        if (!canSubmit) return
        checking = true
        scope.launch {
            wrong = !onSignIn(id, password)
            checking = false
            if (wrong) password = ""
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(stringResource(R.string.signin_title), style = Lifeline.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.signin_body), style = Lifeline.type.body, color = c.inkMuted)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                profiles.forEach { p ->
                    OptionCard(
                        text = profileLabel(p),
                        selected = p.id == selected,
                        onClick = { if (selected != p.id) { selected = p.id; password = ""; wrong = false } },
                        icon = R.drawable.ic_person,
                    )
                }
            }
            if (selected != null) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(64); wrong = false },
                    label = { Text(stringResource(R.string.onb_password)) },
                    supportingText = if (wrong) {
                        { Text(stringResource(R.string.signin_wrong), style = Lifeline.type.caption, color = c.warn) }
                    } else null,
                    isError = wrong,
                    singleLine = true,
                    textStyle = Lifeline.type.body,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(Space.screen), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            PrimaryButton(
                stringResource(if (checking) R.string.signin_checking else R.string.signin_button),
                { submit() },
                Modifier.fillMaxWidth(),
                enabled = canSubmit,
            )
            SecondaryButton(stringResource(R.string.signin_new), onCreate, Modifier.fillMaxWidth())
        }
    }
}

/** "Asha · Ambulance"; a profile with no name still shows its role. */
@Composable
fun profileLabel(p: ProfileInfo): String {
    val name = p.name.ifBlank { stringResource(R.string.profile_no_name) }
    return "$name · ${stringResource(roleLabel(p.role))}"
}

/** Translated role words, the same ones onboarding uses. */
fun roleLabel(role: UserRole): Int = when (role) {
    UserRole.NEEDS_HELP -> R.string.role_needs_help
    UserRole.VOLUNTEER -> R.string.role_volunteer
    else -> teamLabel(role.code)
}
