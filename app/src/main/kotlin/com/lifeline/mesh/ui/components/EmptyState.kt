package com.lifeline.mesh.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews

/** Empty or error state. Always has an action: never just "Nothing here". */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val c = Lifeline.colors
    Column(
        modifier.fillMaxWidth().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(48.dp))
        }
        Text(title, style = Lifeline.type.title, color = c.ink, textAlign = TextAlign.Center)
        Text(body, style = Lifeline.type.body, color = c.inkMuted, textAlign = TextAlign.Center)
        PrimaryButton(actionLabel, onAction, Modifier.padding(top = Space.s))
    }
}

@ThemePreviews
@Composable
private fun EmptyStatePreview() = PreviewBox {
    EmptyState(
        title = "No alerts yet",
        body = "Alerts from nearby phones appear here as soon as they arrive.",
        actionLabel = "Load sample alerts",
        onAction = {},
        icon = R.drawable.ic_hub,
    )
}
