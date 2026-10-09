package com.lifeline.mesh.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews
import com.lifeline.mesh.ui.theme.Touch

@DrawableRes
fun Need.iconRes(): Int = when (this) {
    Need.TRAPPED -> R.drawable.ic_person_alert
    Need.MEDICAL -> R.drawable.ic_local_hospital
    Need.WATER_FOOD -> R.drawable.ic_water_drop
    Need.FIRE -> R.drawable.ic_local_fire_department
    Need.ROAD_BLOCKED -> R.drawable.ic_block
    Need.PANIC -> R.drawable.ic_sos
    Need.AMBULANCE -> R.drawable.ic_emergency
}

@StringRes
fun Need.labelRes(): Int = when (this) {
    Need.TRAPPED -> R.string.need_trapped
    Need.MEDICAL -> R.string.need_medical
    Need.WATER_FOOD -> R.string.need_water_food
    Need.FIRE -> R.string.need_fire
    Need.ROAD_BLOCKED -> R.string.need_road_blocked
    Need.PANIC -> R.string.sos_a11y_action
    Need.AMBULANCE -> R.string.need_ambulance
}

/** Icon + label toggle. Selected = surfaceRaised with a link outline (DESIGN.md section 6). */
@Composable
fun NeedChip(
    need: Need,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Lifeline.colors
    Row(
        modifier
            .heightIn(min = Touch.standard)
            .clip(Shapes.chip)
            .background(if (selected) c.surfaceRaised else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.link else c.outline, Shapes.chip)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(painterResource(need.iconRes()), contentDescription = null,
            tint = if (selected) c.link else c.inkMuted, modifier = Modifier.size(20.dp))
        Text(stringResource(need.labelRes()), style = Lifeline.type.label, color = c.ink)
    }
}

@ThemePreviews
@Composable
private fun NeedChipPreview() = PreviewBox {
    val picked = remember { mutableStateListOf(Need.TRAPPED, Need.MEDICAL) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Need.selectable.forEach { n ->
            NeedChip(n, n in picked, { if (it) picked += n else picked -= n })
        }
    }
}
