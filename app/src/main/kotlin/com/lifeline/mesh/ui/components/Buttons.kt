package com.lifeline.mesh.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews
import com.lifeline.mesh.ui.theme.Touch

/** Filled action button. Defaults to `link`; pass `Lifeline.colors.sos` for emergency actions. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Lifeline.colors.link,
    enabled: Boolean = true,
) {
    val c = Lifeline.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Touch.emergency),
        shape = Shapes.card,
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = c.onFill,
            disabledContainerColor = c.surfaceRaised, disabledContentColor = c.inkMuted,
        ),
        elevation = null,
    ) { Text(text, style = Lifeline.type.bodyStrong, textAlign = TextAlign.Center) }
}

/** Quiet outlined button for secondary actions. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Touch.standard),
        shape = Shapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
    ) { Text(text, style = Lifeline.type.label, textAlign = TextAlign.Center) }
}

/** 64 dp icon + label, for one-handed actions like Speak and Type. */
@Composable
fun BigIconButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Lifeline.colors
    Column(
        modifier
            .heightIn(min = Touch.emergency)
            .clip(Shapes.card)
            .background(c.surface)
            .border(1.dp, c.outline, Shapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(Modifier.size(64.dp).background(c.surfaceRaised, Shapes.circle), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = c.link, modifier = Modifier.size(32.dp))
        }
        Text(label, style = Lifeline.type.bodyStrong, color = c.ink, textAlign = TextAlign.Center)
    }
}

@ThemePreviews
@Composable
private fun ButtonsPreview() = PreviewBox {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            BigIconButton(R.drawable.ic_mic, "Speak", {}, Modifier.weight(1f))
            BigIconButton(R.drawable.ic_keyboard, "Type", {}, Modifier.weight(1f))
        }
        PrimaryButton("Send alert", {}, color = Lifeline.colors.sos)
        SecondaryButton("Open app settings", {})
    }
}
