package com.lifeline.mesh.ui.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeline.mesh.R
import com.lifeline.mesh.mesh.LinkEvent
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineColors
import com.lifeline.mesh.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val MAX_FLIGHTS = 20
private const val LABEL_MS = 1_500L
private const val FLASH_MS = 300L

/** A packet dot travelling one link. */
private class Flight(val from: String, val to: String, val color: Color, val start: Long, val label: String?)
private class Pulse(val node: String, val color: Color, val start: Long)
private class FloatLabel(val node: String, val text: String, val color: Color, val start: Long)

/** Canvas texts, from string resources (built by the screen). */
class MeshLabels(
    val alert: (Int) -> String,
    val reply: (Int) -> String,
    val helpComing: String,
    val checkIn: (Int) -> String,
    val report: (Int) -> String,
    val ping: (Int) -> String,
    val echo: (Int) -> String,
    val waiting: (Int) -> String,
    val you: String,
)

/**
 * The live mesh drawing (DESIGN.md 8.3). Positions come from [MeshLayout] once per topology change; each frame only
 * eases nodes toward them, adds a 2–3 dp idle drift, and advances packet dots, rings and labels.
 * With reduced motion: no drift, no moving dots; a packet shows as a brief color flash on the receiving node.
 */
@Composable
fun MeshCanvas(
    graph: MeshGraph,
    activity: Flow<LinkEvent>,
    waiting: Int,
    searching: Boolean,
    demo: Boolean,
    selected: String?,
    isMine: (Long) -> Boolean,
    labels: MeshLabels,
    onNodeTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Lifeline.colors
    val reduced = Lifeline.reducedMotion
    val density = LocalDensity.current
    val scale = if (demo) 1.3f else 1f
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val type = Lifeline.type

    var size by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(density) { 44.dp.toPx() } * scale
    val targets = remember(graph, size) {
        if (size.width == 0) emptyMap() else MeshLayout.compute(
            graph, size.width.toFloat(), size.height.toFloat(), margin, minRing = with(density) { 104.dp.toPx() } * scale,
        )
    }
    // Eased on-screen positions; plain map (not state): the frame clock below drives redraws.
    val shown = remember { HashMap<String, Offset>() }
    val flights = remember { ArrayList<Flight>() }
    val pulses = remember { ArrayList<Pulse>() }
    val floating = remember { ArrayList<FloatLabel>() }
    val labelsNow by rememberUpdatedState(labels)
    var frame by remember { mutableLongStateOf(0L) }

    val graphNow by rememberUpdatedState(graph)
    val isMineNow by rememberUpdatedState(isMine)
    val colorsNow by rememberUpdatedState(c)

    // Frame clock. Full rate while anything moves; slow ticks when reduced motion and idle.
    LaunchedEffect(reduced) {
        while (true) {
            withFrameMillis { frame = it }
            if (reduced && flights.isEmpty() && pulses.isEmpty() && floating.isEmpty()) delay(250)
        }
    }

    // Packets → flights along this phone's link (and, for multi-hop arrivals, the path it most likely came by).
    LaunchedEffect(activity) {
        activity.collect { ev ->
            val g = graphNow
            val meKey = g.me.key
            val peerKey = ev.peerOrigin?.let(MeshGraph::key) ?: "e:${ev.peer}"
            if (g.node(peerKey) == null) return@collect
            val path: List<String> = if (ev.outgoing) listOf(meKey, peerKey) else {
                val originKey = MeshGraph.key(ev.packet.header.origin)
                val upstream = if (ev.linkHops > 1 && g.node(originKey) != null) {
                    g.path(originKey, peerKey)?.takeIf { it.size - 1 == ev.linkHops - 1 && meKey !in it }
                } else null
                (upstream ?: listOf(peerKey)) + meKey
            }
            val color = packetColor(ev.packet, colorsNow)
            val links = path.size - 1
            for (i in 0 until links) {
                val hop = ev.linkHops - (links - 1) + i
                val text = packetLabel(ev.packet, hop, isMineNow, labelsNow)
                flights.add(Flight(path[i], path[i + 1], color, frame + i * Motion.PACKET_HOP.toLong(), text))
            }
            while (flights.size > MAX_FLIGHTS) flights.removeAt(0)
        }
    }

    val roleIcons = mapOf(
        1 to painterResource(R.drawable.ic_person),
        2 to painterResource(R.drawable.ic_volunteer_activism),
        3 to painterResource(R.drawable.ic_health_and_safety),
    )
    val summary = buildString {
        append("Mesh. You")
        val direct = graph.nodes.count { it.ring == Ring.DIRECT }
        val outer = graph.nodes.count { it.ring == Ring.OUTER }
        append(", $direct phone${if (direct == 1) "" else "s"} linked to you")
        if (outer > 0) append(", $outer more further away")
        if (waiting > 0) append(", $waiting waiting to send")
        append(".")
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .semantics { contentDescription = summary }
            .pointerInput(targets) {
                detectTapGestures { p ->
                    val hit = shown.entries.minByOrNull { (_, o) -> hypot(o.x - p.x, o.y - p.y) }
                    if (hit != null && hypot(hit.value.x - p.x, hit.value.y - p.y) < 40.dp.toPx() * scale) onNodeTap(hit.key)
                }
            },
    ) {
        val now = frame
        // Ease toward layout targets (snap with reduced motion or first appearance).
        targets.forEach { (k, t) ->
            val cur = shown[k]
            shown[k] = if (cur == null || reduced) Offset(t.x, t.y) else Offset(cur.x + (t.x - cur.x) * 0.12f, cur.y + (t.y - cur.y) * 0.12f)
        }
        shown.keys.retainAll(targets.keys)

        fun pos(k: String): Offset? {
            val base = shown[k] ?: return null
            if (reduced) return base
            val seed = (k.hashCode() and 0xFFFF) / 65535f * 2 * PI.toFloat()
            val amp = 2.5.dp.toPx()
            return Offset(
                base.x + amp * sin((now % 3_000L) / 3_000f * 2 * PI.toFloat() + seed),
                base.y + amp * cos((now % 3_700L) / 3_700f * 2 * PI.toFloat() + seed * 1.3f),
            )
        }
        fun radius(n: GraphNode) = when (n.ring) { Ring.ME -> 28.dp; Ring.DIRECT -> 22.dp; Ring.OUTER -> 18.dp }.toPx() * scale

        val me = graph.me
        val mePos = pos(me.key)

        // Radar sweep while nobody is linked.
        if (searching && mePos != null) drawRadar(mePos, now, reduced, c, 0.30f * minOf(this.size.width, this.size.height) * 1.5f)

        // Links.
        for (e in graph.edges) {
            val a = pos(e.a) ?: continue
            val b = pos(e.b) ?: continue
            val na = graph.node(e.a) ?: continue
            val nb = graph.node(e.b) ?: continue
            val d = hypot(b.x - a.x, b.y - a.y).takeIf { it > 1f } ?: continue
            val ux = (b.x - a.x) / d; val uy = (b.y - a.y) / d
            val start = Offset(a.x + ux * (radius(na) + 6.dp.toPx()), a.y + uy * (radius(na) + 6.dp.toPx()))
            val end = Offset(b.x - ux * (radius(nb) + 6.dp.toPx()), b.y - uy * (radius(nb) + 6.dp.toPx()))
            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            when (e.style) {
                EdgeStyle.CONNECTED -> drawLine(c.link, start, end, 2.dp.toPx() * scale, StrokeCap.Round)
                EdgeStyle.CONNECTING -> drawLine(c.warn, start, end, 2.dp.toPx() * scale, StrokeCap.Round, dash)
                EdgeStyle.LOST -> drawLine(c.outline, start, end, 2.dp.toPx() * scale, StrokeCap.Round, dash)
                EdgeStyle.HEARD -> drawLine(c.link.copy(alpha = 0.45f), start, end, 1.5.dp.toPx() * scale, StrokeCap.Round)
            }
        }

        // Packet dots. On arrival: rings and a label at the receiving node.
        val iter = flights.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val t = (now - f.start).toFloat() / Motion.PACKET_HOP
            if (t < 0f) continue
            if (t >= 1f) {
                pulses.add(Pulse(f.to, f.color, now))
                f.label?.let { l -> floating.add(FloatLabel(f.to, l, f.color, now)) }
                iter.remove()
                continue
            }
            if (reduced) continue
            val a = pos(f.from) ?: continue
            val b = pos(f.to) ?: continue
            val e = Motion.EmphasizedDecelerate.transform(t)
            drawCircle(f.color, radius = 5.dp.toPx() * scale, center = Offset(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e))
        }
        pulses.removeAll { now - it.start > Motion.RING_PERIOD + Motion.RING_STAGGER * (Motion.RING_COUNT - 1) }
        floating.removeAll { now - it.start > LABEL_MS }

        // Nodes.
        for (n in graph.nodes) {
            val p = pos(n.key) ?: continue
            val r = radius(n)
            val flash = pulses.lastOrNull { it.node == n.key && now - it.start < FLASH_MS }
            // Rings (one set per arrival).
            if (!reduced) pulses.filter { it.node == n.key }.forEach { pu -> drawRings(p, r, pu, now) }
            drawCircle(flash?.color?.copy(alpha = 0.35f)?.compositeOver(c.surface) ?: if (n.ring == Ring.ME) c.surfaceRaised else c.surface, r, p)
            val ringColor = when {
                n.key == selected -> c.ink
                n.ring == Ring.ME -> c.link
                n.ring == Ring.DIRECT && n.linkState == com.lifeline.mesh.mesh.LinkState.CONNECTED -> c.link
                else -> c.outline
            }
            drawCircle(ringColor, r, p, style = Stroke((if (n.ring == Ring.ME) 3.dp else 2.dp).toPx() * scale))
            n.battery?.let { b -> drawBattery(p, r, b, c) }

            val label = if (n.ring == Ring.ME && n.name.isBlank()) labels.you else n.name
            val initial = (label.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString()
            val initialStyle = type.title.copy(color = if (n.ring == Ring.OUTER) c.inkMuted else c.ink,
                fontSize = (if (n.ring == Ring.ME) 22.sp else 18.sp) * scale)
            val m = measurer.measure(initial, initialStyle)
            drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - m.size.height / 2f))

            n.role?.let { role -> roleIcons[role]?.let { icon -> drawRoleBadge(p, r, icon, c) } }

            val name = if (n.ring == Ring.ME) n.name.ifBlank { labels.you } else n.name
            val nameStyle = TextStyle(fontFamily = type.caption.fontFamily, fontSize = 13.sp * scale,
                color = if (n.ring == Ring.OUTER) c.inkMuted else c.ink)
            val nm = measurer.measure(name, nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (120.dp.toPx() * scale).toInt()))
            val nameAt = Offset(p.x - nm.size.width / 2f, p.y + r + 8.dp.toPx())
            // Links are drawn first; a background pill keeps a line crossing the name from striking through it.
            val pad = 4.dp.toPx()
            drawRoundRect(
                c.bg.copy(alpha = 0.9f),
                topLeft = Offset(nameAt.x - pad, nameAt.y),
                size = androidx.compose.ui.geometry.Size(nm.size.width + 2 * pad, nm.size.height.toFloat()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            drawText(nm, topLeft = nameAt)
        }

        // "N waiting" badge on my node.
        if (waiting > 0 && mePos != null) {
            val text = labels.waiting(waiting)
            val st = TextStyle(fontFamily = type.label.fontFamily, fontWeight = type.label.fontWeight, fontSize = 12.sp * scale, color = c.onFill)
            val tm = measurer.measure(text, st)
            val r = radius(me)
            val pad = 6.dp.toPx()
            val topLeft = Offset(mePos.x + r * 0.5f, mePos.y - r - tm.size.height - pad)
            drawRoundRect(c.warn, topLeft = topLeft, size = Size(tm.size.width + pad * 2, tm.size.height + pad / 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawText(tm, topLeft = Offset(topLeft.x + pad, topLeft.y + pad / 4))
        }

        // Floating labels: rise 16 dp and fade over 1.5 s.
        for (l in floating) {
            val p = pos(l.node) ?: continue
            val k = ((now - l.start).toFloat() / LABEL_MS).coerceIn(0f, 1f)
            val alpha = if (k < 0.7f) 1f else 1f - (k - 0.7f) / 0.3f
            val st = TextStyle(fontFamily = type.label.fontFamily, fontWeight = type.label.fontWeight, fontSize = 13.sp * scale, color = l.color.copy(alpha = alpha))
            val tm = measurer.measure(l.text, st)
            val rise = if (reduced) 0f else 16.dp.toPx() * k
            val node = graph.node(l.node)
            val r = node?.let { radius(it) } ?: 20.dp.toPx()
            val bg = c.bg.copy(alpha = 0.8f * alpha)
            // Above the node, except on my node, where it goes under my name (neighbors usually sit above me).
            val tl = if (node?.ring == Ring.ME) Offset(p.x - tm.size.width / 2f, p.y + r + 30.dp.toPx() * scale - rise)
            else Offset(p.x - tm.size.width / 2f, p.y - r - tm.size.height - 10.dp.toPx() - rise)
            drawRoundRect(bg, topLeft = Offset(tl.x - 4.dp.toPx(), tl.y - 2.dp.toPx()),
                size = Size(tm.size.width + 8.dp.toPx(), tm.size.height + 4.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawText(tm, topLeft = tl)
        }
    }
}

private fun Color.compositeOver(bg: Color): Color = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
    alpha = 1f,
)

private fun packetColor(p: Packet, c: LifelineColors): Color = when (p) {
    is Packet.Alert -> if (p.severity >= 3) c.sos else c.signal
    is Packet.Ack -> c.safe
    is Packet.CheckIn -> c.link
    is Packet.Ping -> c.ink
    is Packet.Report -> c.warn
    else -> c.inkMuted
}

private fun packetLabel(p: Packet, hop: Int, isMine: (Long) -> Boolean, l: MeshLabels): String? = when (p) {
    is Packet.Alert -> l.alert(hop)
    is Packet.Ack -> if (isMine(p.refId)) l.helpComing else l.reply(hop)
    is Packet.CheckIn -> l.checkIn(hop)
    is Packet.Report -> l.report(hop)
    is Packet.Ping -> if (p.echo) l.echo(hop) else l.ping(hop)
    else -> null
}

/** SignalRings once: 3 rings 1.0 → 2.2 × radius, alpha 0.5 → 0 over 1.2 s, staggered 400 ms. */
private fun DrawScope.drawRings(center: Offset, r: Float, p: Pulse, now: Long) {
    for (i in 0 until Motion.RING_COUNT) {
        val t = (now - p.start - i * Motion.RING_STAGGER).toFloat() / Motion.RING_PERIOD
        if (t !in 0f..1f) continue
        val scale = 1f + (Motion.RING_SCALE_TO - 1f) * t
        drawCircle(p.color.copy(alpha = Motion.RING_ALPHA_FROM * (1f - t)), r * scale, center, style = Stroke(2.dp.toPx()))
    }
}

/** Battery arc just outside the node: green above 50%, amber 20–50%, red below 20%. */
private fun DrawScope.drawBattery(center: Offset, r: Float, battery: Int, c: LifelineColors) {
    val rr = r + 5.dp.toPx()
    val color = when { battery > 50 -> c.safe; battery >= 20 -> c.signal; else -> c.sos }
    val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
    val tl = Offset(center.x - rr, center.y - rr)
    drawArc(c.outline.copy(alpha = 0.5f), -90f, 360f, false, tl, Size(rr * 2, rr * 2), style = Stroke(2.5.dp.toPx()))
    drawArc(color, -90f, 360f * battery.coerceIn(0, 100) / 100f, false, tl, Size(rr * 2, rr * 2), style = stroke)
}

/** Small role badge at the node's lower right. */
private fun DrawScope.drawRoleBadge(center: Offset, r: Float, icon: Painter, c: LifelineColors) {
    val s = 16.dp.toPx()
    val bc = Offset(center.x + r * 0.72f, center.y + r * 0.72f)
    drawCircle(c.bg, s / 2 + 2.dp.toPx(), bc)
    drawCircle(c.surfaceRaised, s / 2, bc)
    val iconSize = 12.dp.toPx()
    translate(bc.x - iconSize / 2, bc.y - iconSize / 2) {
        with(icon) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(c.ink)) }
    }
}

/** Slow sweep from my node, one turn per 4 s, with two faint range circles. */
private fun DrawScope.drawRadar(center: Offset, now: Long, reduced: Boolean, c: LifelineColors, reach: Float) {
    drawCircle(c.outline.copy(alpha = 0.6f), reach * 0.5f, center, style = Stroke(1.dp.toPx()))
    drawCircle(c.outline.copy(alpha = 0.4f), reach, center, style = Stroke(1.dp.toPx()))
    if (reduced) return
    val a = (now % 4_000L) / 4_000f * 2 * PI.toFloat() - PI.toFloat() / 2
    for (i in 0 until 12) {
        val back = a - i * 0.04f
        drawLine(
            c.link.copy(alpha = 0.35f * (1f - i / 12f)), center,
            Offset(center.x + reach * cos(back), center.y + reach * sin(back)), 2.dp.toPx(), StrokeCap.Round,
        )
    }
}
