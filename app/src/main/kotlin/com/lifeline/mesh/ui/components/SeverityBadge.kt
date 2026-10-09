package com.lifeline.mesh.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews
import com.lifeline.mesh.ui.theme.severity

@StringRes
fun severityLabelRes(level: Int): Int = when (level) {
    3 -> R.string.severity_3
    2 -> R.string.severity_2
    1 -> R.string.severity_1
    else -> R.string.severity_0
}

/** Pill with severity color and word; the word means it never relies on color alone. */
@Composable
fun SeverityBadge(level: Int, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    Text(
        stringResource(severityLabelRes(level)),
        style = Lifeline.type.label,
        color = c.onFill,
        modifier = modifier
            .background(c.severity(level), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@ThemePreviews
@Composable
private fun SeverityBadgePreview() = PreviewBox {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { (3 downTo 0).forEach { SeverityBadge(it) } }
}
