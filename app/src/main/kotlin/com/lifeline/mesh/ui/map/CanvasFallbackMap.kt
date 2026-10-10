package com.lifeline.mesh.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.ui.rescuer.distanceText
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.severity
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val RING_STEPS_M = listOf(100.0, 250.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 25_000.0)

/**
 * Map without a map (DESIGN.md 8.5): four distance rings around me, north up, every alert and help point placed by its
 * real distance and bearing. Needs no file and no library, so it always works. If my position is unknown, the rings
 * are centred on the venue and say so.
 */
@Composable
fun CanvasFallbackMap(
    ranked: List<RankedAlert>,
    places: List<Place>,
    me: GeoFix?,
    onAlertTap: (Long) -> Unit,
    onPlaceTap: (String) -> Unit = {},
    routes: List<Route> = emptyList(),
    gapLines: List<List<Pair<Double, Double>>> = emptyList(),
    evac: List<Pair<Double, Double>> = emptyList(),
    meshPaths: List<DrawnPath> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val onPlace by rememberUpdatedState(onPlaceTap)
    val c = Lifeline.colors
    val type = Lifeline.type
    val measurer = rememberTextMeasurer()
    val onTap by rememberUpdatedState(onAlertTap)
    val refLat = me?.lat ?: MapAssets.CENTER_LAT
    val refLon = me?.lon ?: MapAssets.CENTER_LON
    val youLabel = stringResource(if (me != null) R.string.map_you else R.string.map_centre)

    data class Plot(val northM: Double, val eastM: Double)
    fun plot(lat: Double, lon: Double): Plot {
        val d = GeoMath.distanceM(refLat, refLon, lat, lon)
        val b = Math.toRadians(GeoMath.bearingDeg(refLat, refLon, lat, lon))
        return Plot(d * cos(b), d * sin(b))
    }
    val alertPlots = ranked.mapNotNull { r -> r.alert.position?.let { p -> r to plot(p.lat!!, p.lon!!) } }
    val placePlots = places.map { it to plot(it.lat, it.lon) }
    val farthest = (alertPlots.map { hypot(it.second.northM, it.second.eastM) } + placePlots.map { hypot(it.second.northM, it.second.eastM) })
        .maxOrNull() ?: 0.0
    val step = RING_STEPS_M.firstOrNull { it * 4 >= farthest * 1.1 } ?: RING_STEPS_M.last()

    // Screen positions of alerts, for taps; filled while drawing.
    val hits = HashMap<Long, Offset>()
    val placeHits = HashMap<String, Offset>()

    Canvas(
        modifier
            .semantics { contentDescription = "$youLabel. ${alertPlots.size} alerts within ${distanceText(step * 4)}." }
            .pointerInput(ranked, places) {
                detectTapGestures { p ->
                    // The nearest thing under the finger: an alert opens its sheet, anything else its details.
                    val a = hits.entries.minByOrNull { (_, o) -> hypot(o.x - p.x, o.y - p.y) }
                    val pl = placeHits.entries.minByOrNull { (_, o) -> hypot(o.x - p.x, o.y - p.y) }
                    val da = a?.let { hypot(it.value.x - p.x, it.value.y - p.y) } ?: Float.MAX_VALUE
                    val dp = pl?.let { hypot(it.value.x - p.x, it.value.y - p.y) } ?: Float.MAX_VALUE
                    val limit = 32.dp.toPx()
                    when {
                        da <= dp && da < limit -> onTap(a!!.key)
                        dp < limit -> onPlace(pl!!.key)
                    }
                }
            },
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) / 2 - 28.dp.toPx()
        val pxPerM = (radius / (step * 4)).toFloat()
        fun at(p: Plot) = Offset(center.x + (p.eastM * pxPerM).toFloat(), center.y - (p.northM * pxPerM).toFloat())

        // Rings with distance labels.
        val small = TextStyle(fontFamily = type.caption.fontFamily, fontSize = 12.sp, color = c.inkMuted)
        for (i in 1..4) {
            val r = radius * i / 4
            drawCircle(c.outline, r, center, style = Stroke(1.dp.toPx()))
            val t = measurer.measure(distanceText(step * i), small)
            // Along the south-east diagonal, so labels of neighbouring rings never touch (and stay clear of "N").
            val d = (r * 0.7071f)
            drawText(t, topLeft = Offset(center.x + d + 2.dp.toPx(), center.y + d + 2.dp.toPx()))
        }
        val n = measurer.measure("N", TextStyle(fontFamily = type.label.fontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink))
        drawText(n, topLeft = Offset(center.x - n.size.width / 2, center.y - radius - n.size.height - 4.dp.toPx()))

        // GPS accuracy circles, under everything (10 Oct 2026). Skipped when smaller than a few pixels.
        for ((centre, r, color) in accuracySpots(ranked, places, me, c)) {
            val px = r * pxPerM
            if (px < 4.dp.toPx()) continue
            val o = at(plot(centre.first, centre.second))
            drawCircle(color.copy(alpha = 0.12f), px, o)
            drawCircle(color.copy(alpha = 0.45f), px, o, style = Stroke(1.dp.toPx()))
        }

        // Responder → job lines (dashed), under every marker.
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
        for (r in routes) {
            val path = androidx.compose.ui.graphics.Path()
            r.points.forEachIndexed { i, (la, lo) -> val o = at(plot(la, lo)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, c.link, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), pathEffect = dash))
        }

        // My way to the open shelter: solid green.
        if (evac.size >= 2) {
            val path = androidx.compose.ui.graphics.Path()
            evac.forEachIndexed { i, (la, lo) -> val o = at(plot(la, lo)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, c.safe.copy(alpha = 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx()))
        }

        // Drop-off legs to the shelter: green dashes.
        for (r in routes) {
            val d = r.dropOff ?: continue
            val path = androidx.compose.ui.graphics.Path()
            d.points.forEachIndexed { i, (la, lo) -> val o = at(plot(la, lo)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, c.safe, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), pathEffect = dash))
        }
        // No minute labels here: on the rings' small scale they cover names and markers (seen on the iQOO 15). The
        // street map shows them.

        // Mesh paths: thin ink lines, numbered hops.
        for (mp in meshPaths) {
            if (mp.points.size < 2) continue
            val path = androidx.compose.ui.graphics.Path()
            mp.points.forEachIndexed { i, (la, lo) -> val o = at(plot(la, lo)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, c.ink.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f.dp.toPx()))
            for ((la, lo, n) in mp.badges) {
                val o = at(plot(la, lo))
                drawCircle(c.bg, 11.dp.toPx(), o)
                drawCircle(c.ink, 9.dp.toPx(), o)
                val t = measurer.measure(n.toString(), TextStyle(fontFamily = type.label.fontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.bg))
                drawText(t, topLeft = Offset(o.x - t.size.width / 2, o.y - t.size.height / 2))
            }
        }

        // Relay gaps: amber, short dashes.
        val gapDash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
        for (g in gapLines) {
            val path = androidx.compose.ui.graphics.Path()
            g.forEachIndexed { i, (la, lo) -> val o = at(plot(la, lo)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, c.signal, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), pathEffect = gapDash))
        }

        // Help points: squares; check-ins: small green dots; live positions: teams as teal squares, people as rings.
        for ((pl, p) in placePlots) {
            val o = at(p)
            placeHits[pl.key] = o
            when {
                pl.kind == STAND_KIND -> {
                    drawCircle(c.bg, 11.dp.toPx(), o)
                    drawCircle(c.signal, 9.dp.toPx(), o, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    drawCircle(c.signal, 3.dp.toPx(), o)
                }
                pl.kind >= 13 -> {
                    val s = 16.dp.toPx()
                    drawRoundRect(c.bg, Offset(o.x - s / 2 - 2.dp.toPx(), o.y - s / 2 - 2.dp.toPx()), Size(s + 4.dp.toPx(), s + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                    drawRoundRect(c.link, Offset(o.x - s / 2, o.y - s / 2), Size(s, s), CornerRadius(3.dp.toPx()))
                }
                pl.kind >= 11 -> drawCircle(if (pl.kind == 11) c.sos else c.warn, 7.dp.toPx(), o, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                pl.kind == 0 -> drawCircle(c.safe, 6.dp.toPx(), o)
                else -> {
                    val s = 14.dp.toPx()
                    val tone = if (pl.kind == 2 || pl.kind == 3) c.safe else c.warn
                    drawRoundRect(tone, Offset(o.x - s / 2, o.y - s / 2), Size(s, s), CornerRadius(3.dp.toPx()))
                }
            }
            // Live positions carry the phone's name.
            if (pl.kind >= 11 && pl.label.isNotBlank()) {
                val t = measurer.measure(pl.label, TextStyle(fontFamily = type.caption.fontFamily, fontSize = 12.sp, color = c.ink))
                drawText(t, topLeft = Offset(o.x - t.size.width / 2, o.y + 11.dp.toPx()))
            }
        }

        // Alerts: circles sized by people, colored by severity, rank inside. Rank 1 drawn last (on top).
        for ((r, p) in alertPlots.sortedByDescending { it.first.rank }) {
            val o = at(p)
            hits[r.alert.id] = o
            val rad = (13 + r.alert.people.coerceIn(0, 20) * 0.75f).dp.toPx()
            drawCircle(c.bg, rad + 2.dp.toPx(), o)
            drawCircle(if (r.alert.acked) c.inkMuted else c.severity(r.alert.severity), rad, o)
            val t = measurer.measure(r.rank.toString(), TextStyle(fontFamily = type.label.fontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.onFill))
            drawText(t, topLeft = Offset(o.x - t.size.width / 2, o.y - t.size.height / 2))
        }

        // Me (or the venue centre when my position is unknown).
        drawCircle(c.bg, 11.dp.toPx(), center)
        drawCircle(if (me != null) c.link else c.inkMuted, 8.dp.toPx(), center)
        val you = measurer.measure(youLabel, TextStyle(fontFamily = type.label.fontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink))
        drawText(you, topLeft = Offset(center.x - you.size.width / 2, center.y + 14.dp.toPx()))
    }
}
