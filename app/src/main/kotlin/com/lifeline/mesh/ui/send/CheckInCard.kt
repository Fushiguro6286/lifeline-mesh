package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/** Compact "I'm safe" card at the bottom of the SOS tab: sends a check-in with my name and position. */
@Composable
fun CheckInCard(sentAt: Long?, onSafe: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    Column(
        modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(painterResource(R.drawable.ic_verified_user), null, tint = c.safe, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.checkin_title), style = Lifeline.type.title, color = c.ink)
        }
        Text(stringResource(R.string.checkin_body), style = Lifeline.type.body, color = c.inkMuted)
        if (sentAt != null) {
            Text(stringResource(R.string.checkin_sent), style = Lifeline.type.bodyStrong, color = c.safe)
        } else {
            PrimaryButton(stringResource(R.string.checkin_send), onSafe, Modifier.fillMaxWidth(), color = c.safe)
        }
    }
}
