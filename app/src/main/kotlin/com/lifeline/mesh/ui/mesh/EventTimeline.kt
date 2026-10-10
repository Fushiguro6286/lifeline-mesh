package com.lifeline.mesh.ui.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.EventEntity
import com.lifeline.mesh.mesh.MeshEvent.Kind
import com.lifeline.mesh.ui.components.EmptyState
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineColors
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EventFilter(val kinds: Set<Kind>?) {
    ALL(null),
    ALERTS(setOf(Kind.SENT, Kind.QUEUED, Kind.RECEIVED, Kind.FORWARDED, Kind.DUPLICATE, Kind.REPLY, Kind.RECEIPT, Kind.TTL_END, Kind.RATE_LIMITED)),
    NETWORK(setOf(Kind.CONNECTED, Kind.DISCONNECTED, Kind.SYNC, Kind.ROUND_TRIP, Kind.MALFORMED)),
}

/** Filter chips: All, Alerts, Network (DESIGN.md 8.3). */
@Composable
fun EventFilterChips(filter: EventFilter, onFilter: (EventFilter) -> Unit, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(EventFilter.ALL to R.string.filter_all, EventFilter.ALERTS to R.string.filter_alerts, EventFilter.NETWORK to R.string.filter_network)
            .forEach { (f, label) ->
                val on = f == filter
                Box(
                    Modifier
                        .heightIn(min = Touch.standard)
                        .background(if (on) c.surfaceRaised else c.surface, Shapes.chip)
                        .border(if (on) 2.dp else 1.dp, if (on) c.link else c.outline, Shapes.chip)
                        .clickable(role = Role.Tab) { onFilter(f) }
                        .semantics { selected = on }
                        .padding(horizontal = Space.l),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), style = Lifeline.type.label, color = c.ink) }
            }
    }
}

/** Newest first, one plain sentence per line with an icon and the time. */
@Composable
fun EventTimeline(events: List<EventEntity>, filter: EventFilter, onEmptyAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    val shown = events.filter { e -> filter.kinds == null || runCatching { Kind.valueOf(e.kind) }.getOrNull() in filter.kinds }.take(200)
    if (shown.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.timeline_empty_title),
            body = stringResource(R.string.timeline_empty_body),
            actionLabel = stringResource(R.string.timeline_empty_action),
            onAction = onEmptyAction,
            icon = R.drawable.ic_hub,
            modifier = modifier,
        )
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        items(shown, key = { it.rowId }) { e -> EventLine(e, c) }
    }
}

@Composable
private fun EventLine(e: EventEntity, c: LifelineColors) {
    val kind = runCatching { Kind.valueOf(e.kind) }.getOrNull()
    val (icon, tone) = iconFor(kind, c)
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(painterResource(icon), null, tint = tone, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(e.text, style = Lifeline.type.body, color = if (tone == c.inkMuted) c.inkMuted else c.ink)
        }
        Text(TIME.get()!!.format(Date(e.time)), style = Lifeline.type.caption, color = c.inkMuted)
    }
}

private fun iconFor(kind: Kind?, c: LifelineColors): Pair<Int, Color> = when (kind) {
    Kind.SENT -> R.drawable.ic_navigation to c.signal
    Kind.QUEUED -> R.drawable.ic_schedule to c.warn
    Kind.RECEIVED -> R.drawable.ic_notifications to c.signal
    Kind.FORWARDED -> R.drawable.ic_hub to c.link
    Kind.DUPLICATE, Kind.RATE_LIMITED, Kind.TTL_END -> R.drawable.ic_block to c.inkMuted
    Kind.MALFORMED -> R.drawable.ic_warning to c.warn
    Kind.CONNECTED -> R.drawable.ic_bluetooth to c.link
    Kind.DISCONNECTED -> R.drawable.ic_close to c.inkMuted
    Kind.REPLY, Kind.RECEIPT -> R.drawable.ic_check_circle to c.safe
    Kind.ROUND_TRIP -> R.drawable.ic_speed to c.ink
    Kind.SYNC -> R.drawable.ic_refresh to c.link
    null -> R.drawable.ic_info to c.inkMuted
}

private val TIME = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
