package com.lifeline.mesh.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Language
import com.lifeline.mesh.data.Role as UserRole
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews
import com.lifeline.mesh.ui.theme.Touch

private const val PAGES = 3

/**
 * Three short pages (DESIGN.md 8.1): what the app does, about you (name, language, role, password), permissions.
 * Step and typed fields survive rotation and the activity recreation that a language change causes.
 * Finishing creates a password-protected profile on this phone ([onDone] gets the password).
 */
@Composable
fun OnboardingScreen(
    settings: Settings,
    onName: (String) -> Unit,
    onLanguage: (Language) -> Unit,
    onRole: (UserRole) -> Unit,
    onPhone: (String) -> Unit,
    onDone: (password: String) -> Unit,
    /** Back to the sign-in screen; null when this phone has no profiles yet. */
    onSignIn: (() -> Unit)? = null,
) {
    val c = Lifeline.colors
    var page by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(settings.displayName) }
    var phone by rememberSaveable { mutableStateOf(settings.phone) }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var passwordError by rememberSaveable { mutableStateOf<Int?>(null) }
    var finishing by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            when (page) {
                0 -> WelcomePage()
                1 -> {
                    AboutYouPage(name, { name = it }, settings.language, onLanguage, settings.role, onRole, phone, { phone = it })
                    PasswordFields(password, { password = it; passwordError = null }, confirm, { confirm = it; passwordError = null }, passwordError)
                }
                else -> {
                    Title(stringResource(R.string.onb3_title))
                    Text(stringResource(R.string.onb3_body), style = Lifeline.type.body, color = c.inkMuted)
                    PermissionsChecklist()
                }
            }
        }
        // Actions live at the bottom for one-handed use.
        Column(Modifier.fillMaxWidth().padding(Space.screen), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            PageDots(page)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                if (page > 0) SecondaryButton(stringResource(R.string.back), { page-- }, Modifier.weight(1f).heightIn(min = Touch.emergency))
                PrimaryButton(
                    text = stringResource(if (page == PAGES - 1) R.string.finish else R.string.next),
                    onClick = {
                        if (page == 1) {
                            passwordError = passwordProblem(password, confirm)
                            if (passwordError != null) return@PrimaryButton
                            onName(name); onPhone(phone)
                        }
                        if (page < PAGES - 1) page++ else if (!finishing) { finishing = true; onDone(password) }
                    },
                    modifier = Modifier.weight(if (page > 0) 2f else 1f),
                )
            }
            if (page == 0 && onSignIn != null) {
                SecondaryButton(stringResource(R.string.signin_existing), onSignIn, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = Lifeline.type.headline, color = Lifeline.colors.ink, modifier = Modifier.semantics { heading() })

@Composable
private fun WelcomePage() {
    Spacer(Modifier.height(Space.xl))
    MeshIllustration(Modifier.fillMaxWidth().height(200.dp))
    Spacer(Modifier.height(Space.l))
    Title(stringResource(R.string.onb1_title))
    Text(stringResource(R.string.onb1_body), style = Lifeline.type.body, color = Lifeline.colors.inkMuted)
}

/** Three phones and their links, drawn in Compose; an amber packet travels across them. */
@Composable
private fun MeshIllustration(modifier: Modifier) {
    val c = Lifeline.colors
    val reduced = Lifeline.reducedMotion
    val t = if (reduced) null else {
        val tr = rememberInfiniteTransition(label = "packet")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart), label = "t")
    }
    Canvas(modifier) {
        val a = Offset(size.width * 0.18f, size.height * 0.68f)
        val b = Offset(size.width * 0.50f, size.height * 0.32f)
        val d = Offset(size.width * 0.82f, size.height * 0.68f)
        val linkW = 2.dp.toPx()
        drawLine(c.link, a, b, linkW)
        drawLine(c.link, b, d, linkW)
        drawLine(c.outline, a, d, linkW, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        listOf(a to 22.dp, b to 26.dp, d to 22.dp).forEach { (p, r) ->
            drawCircle(c.surfaceRaised, r.toPx(), p)
            drawCircle(c.link, r.toPx(), p, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        // Packet: A -> B for the first half, B -> C for the second.
        t?.value?.let { v ->
            val pos = if (v < 0.5f) lerp(a, b, v * 2f) else lerp(b, d, (v - 0.5f) * 2f)
            drawCircle(c.signal, 7.dp.toPx(), pos)
        }
    }
}

@Composable
private fun AboutYouPage(
    name: String,
    onNameChange: (String) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
    role: UserRole,
    onRole: (UserRole) -> Unit,
    phone: String,
    onPhone: (String) -> Unit,
) {
    Title(stringResource(R.string.onb2_title))
    OutlinedTextField(
        value = name,
        onValueChange = { onNameChange(it.take(16)) },
        label = { Text(stringResource(R.string.onb_name_label)) },
        supportingText = { Text(stringResource(R.string.onb_name_hint), style = Lifeline.type.caption) },
        singleLine = true,
        textStyle = Lifeline.type.body,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    SectionLabel(stringResource(R.string.onb_language))
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(Language.EN to R.string.lang_en, Language.HI to R.string.lang_hi, Language.KN to R.string.lang_kn).forEach { (lang, label) ->
            OptionCard(stringResource(label), selected = language == lang, onClick = { onLanguage(lang) }, modifier = Modifier.weight(1f), compact = true)
        }
    }

    SectionLabel(stringResource(R.string.onb_role))
    RolePicker(role, onRole)
    PhoneField(phone, onPhone)
    Spacer(Modifier.height(Space.s))
}

/**
 * Role (Phase 10): I need help, volunteer, or a rescue team, then which team. Shared by onboarding and Settings.
 * Team roles are self-declared and say so.
 */
@Composable
fun RolePicker(role: UserRole, onRole: (UserRole) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OptionCard(stringResource(R.string.role_needs_help), role == UserRole.NEEDS_HELP, { onRole(UserRole.NEEDS_HELP) }, icon = R.drawable.ic_person)
        OptionCard(stringResource(R.string.role_volunteer), role == UserRole.VOLUNTEER, { onRole(UserRole.VOLUNTEER) }, icon = R.drawable.ic_volunteer_activism)
        OptionCard(stringResource(R.string.role_rescuer), role.isTeam, { if (!role.isTeam) onRole(UserRole.RESCUER) }, icon = R.drawable.ic_health_and_safety)
    }
    if (role.isTeam) {
        SectionLabel(stringResource(R.string.onb_team))
        androidx.compose.foundation.layout.FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            maxItemsInEachRow = 2,
        ) {
            listOf(UserRole.FIREFIGHTER, UserRole.AMBULANCE, UserRole.POLICE, UserRole.NDRF, UserRole.RESCUER).forEach { t ->
                OptionCard(stringResource(teamLabel(t.code)), role == t, { onRole(t) }, Modifier.weight(1f), compact = true)
            }
        }
        Text(stringResource(R.string.onb_team_note), style = Lifeline.type.caption, color = Lifeline.colors.inkMuted)
    }
}

/** Short team name for a role code (3–7); 1 and 2 have their own words. */
fun teamLabel(code: Int): Int = when (code) {
    4 -> R.string.team_fire; 5 -> R.string.team_ambulance; 6 -> R.string.team_police; 7 -> R.string.team_ndrf
    else -> R.string.team_other
}

/** Password and repeat, for a new profile. [error] is a string resource, shown under the second field. */
@Composable
fun PasswordFields(password: String, onPassword: (String) -> Unit, confirm: String, onConfirm: (String) -> Unit, error: Int?) {
    val keyboard = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Next)
    OutlinedTextField(
        value = password,
        onValueChange = { onPassword(it.take(64)) },
        label = { Text(stringResource(R.string.onb_password)) },
        supportingText = { Text(stringResource(R.string.onb_password_hint), style = Lifeline.type.caption) },
        singleLine = true,
        textStyle = Lifeline.type.body,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = keyboard,
        isError = error == R.string.password_too_short,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = { onConfirm(it.take(64)) },
        label = { Text(stringResource(R.string.onb_password_confirm)) },
        supportingText = error?.let { { Text(stringResource(it), style = Lifeline.type.caption, color = Lifeline.colors.warn) } },
        singleLine = true,
        textStyle = Lifeline.type.body,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
        isError = error != null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Null if the password can be used, else the string resource saying why not. */
fun passwordProblem(password: String, confirm: String): Int? = when {
    password.length < com.lifeline.mesh.data.Passwords.MIN_LENGTH -> R.string.password_too_short
    password != confirm -> R.string.password_mismatch
    else -> null
}

/** Optional phone number; says exactly where it goes. */
@Composable
fun PhoneField(phone: String, onPhone: (String) -> Unit) {
    OutlinedTextField(
        value = phone,
        onValueChange = { v -> onPhone(v.filter { it.isDigit() || it == '+' }.take(16)) },
        label = { Text(stringResource(R.string.onb_phone)) },
        supportingText = { Text(stringResource(R.string.onb_phone_hint), style = Lifeline.type.caption) },
        singleLine = true,
        textStyle = Lifeline.type.body,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = Lifeline.type.label, color = Lifeline.colors.inkMuted, modifier = Modifier.padding(top = Space.s))

/** Single-choice card: selected = surfaceRaised with a link outline, like NeedChip. */
@Composable
fun OptionCard(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    /** Narrow cards (three in a row): no check mark, tighter padding; the outline still marks selection. */
    compact: Boolean = false,
) {
    val c = Lifeline.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.emergency)
            .clip(Shapes.card)
            .background(if (selected) c.surfaceRaised else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.link else c.outline, Shapes.card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = if (compact) Space.m else Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = if (selected) c.link else c.inkMuted, modifier = Modifier.size(24.dp))
        Text(text, style = Lifeline.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
        if (selected && !compact) Icon(painterResource(R.drawable.ic_check), null, tint = c.link, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PageDots(page: Int) {
    val c = Lifeline.colors
    val label = stringResource(R.string.onb_step, page + 1, PAGES)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
    ) {
        repeat(PAGES) { i ->
            Box(Modifier.height(6.dp).width(if (i == page) 24.dp else 6.dp).background(if (i == page) c.link else c.outline, Shapes.circle))
        }
    }
}

@ThemePreviews
@Composable
private fun OptionCardPreview() = PreviewBox {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OptionCard("I need help", true, {}, icon = R.drawable.ic_person)
        OptionCard("I'm a rescuer", false, {}, icon = R.drawable.ic_health_and_safety)
    }
}
