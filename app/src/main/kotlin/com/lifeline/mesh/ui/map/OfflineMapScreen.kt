package com.lifeline.mesh.ui.map

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.ui.rescuer.ACTIVE_MS
import com.lifeline.mesh.ui.rescuer.AlertDetailSheet
import com.lifeline.mesh.ui.rescuer.RescuerViewModel
import com.lifeline.mesh.ui.rescuer.rankAlerts
import com.lifeline.mesh.ui.rescuer.toReport
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineColors
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import com.lifeline.mesh.ui.theme.severity
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File

private enum class MapMode { MAP, RINGS }

/**
 * A help point, check-in or live position on the map. [kind]: 0 check-in, 1–4 community report type,
 * 10 + role code (11 needs help, 12 volunteer, 13–17 teams) for a phone's live position (Phase 10).
 */
data class Place(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val kind: Int,
    val label: String = "",
    val note: String = "",
    /** When it was heard here (ms), 0 if unknown. */
    val at: Long = 0,
    val hops: Int? = null,
    val accuracyM: Int? = null,
) {
    /** Unique across kinds (a live position's id is an origin, a report's a message id). */
    val key: String get() = "$kind:$id"
}

/** Live positions this phone may show (teams see everyone; others see only teams), as map places. */
fun positionPlaces(all: Collection<com.lifeline.mesh.mesh.PeerPosition>, viewer: com.lifeline.mesh.packet.HelloRole, now: Long): List<Place> =
    com.lifeline.mesh.mesh.PositionBeacon.visible(all, viewer, now).map { p ->
        Place(p.origin.toLong(), p.lat, p.lon, 10 + p.role.code, p.name, at = p.heardAt, hops = p.hops, accuracyM = p.accuracyM)
    }

/**
 * Map tab (DESIGN.md 8.5): the offline MapLibre map (venue PMTiles, local style) with alerts (size = people,
 * color = severity, rank inside), help points, check-ins, my position and OpenStreetMap attribution. Tap an alert for
 * the same detail sheet as the Rescue tab. The Canvas "Rings" view is always one tap away, and is used automatically
 * when the map file is missing or MapLibre fails.
 */
@Composable
fun OfflineMapScreen(settings: Settings, vm: RescuerViewModel = viewModel()) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val repo = (context.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val slowMe by vm.me.collectAsStateWithLifecycle()
    val liveMe by repo.myFix.collectAsStateWithLifecycle()
    // The service's live GPS fix when the mesh runs; otherwise the screen's own fix (refreshed every minute).
    val me = liveMe ?: slowMe
    val positions by repo.positions.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val ranked = remember(packets, me) { rankAlerts(packets, me, now) }
    val places = remember(packets, positions, settings.role, now) {
        places(packets, now) + positionPlaces(positions.values, settings.role.hello, System.currentTimeMillis())
    }
    val archive by produceState<Result<File?>?>(null) { value = Result.success(MapAssets.prepare(context)) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(MapMode.MAP) }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var openPlace by rememberSaveable { mutableStateOf<String?>(null) }

    val file = archive?.getOrNull()
    val mapUsable = archive != null && file != null && !failed
    val note = when {
        archive == null -> stringResource(R.string.map_preparing)
        file == null -> stringResource(R.string.map_missing)
        failed -> stringResource(R.string.map_failed)
        else -> null
    }

    Column(Modifier.fillMaxSize()) {
        ModeToggle(mode) { mode = it }
        Box(Modifier.fillMaxSize()) {
            if (mode == MapMode.MAP && mapUsable) {
                key(c.isNight) {
                    LibreMap(file!!, c, ranked, places, me, onAlertTap = { open = it }, onPlaceTap = { openPlace = it }, onFail = { failed = true })
                }
                Text(
                    stringResource(R.string.map_attribution), style = Lifeline.type.caption, color = c.ink,
                    modifier = Modifier.align(Alignment.BottomStart).padding(Space.s)
                        .background(c.bg.copy(alpha = 0.75f), Shapes.chip).padding(horizontal = Space.s, vertical = 2.dp),
                )
                if (me != null && !MapAssets.contains(me!!.lat, me!!.lon)) Banner(stringResource(R.string.map_outside), Modifier.align(Alignment.TopCenter))
            } else {
                CanvasFallbackMap(ranked, places, me, onAlertTap = { open = it }, onPlaceTap = { openPlace = it }, modifier = Modifier.fillMaxSize())
                if (mode == MapMode.MAP && note != null) Banner(note, Modifier.align(Alignment.TopCenter))
            }
            val unplaced = ranked.count { it.alert.position == null }
            if (unplaced > 0) {
                Banner(pluralStringResource(R.plurals.map_no_location, unplaced, unplaced), Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
            }
        }
    }

    openPlace?.let { key ->
        val p = places.firstOrNull { it.key == key }
        if (p == null) openPlace = null
        else PlaceDetailSheet(p, me, settings, onDismiss = { openPlace = null })
    }

    open?.let { id ->
        val r = ranked.firstOrNull { it.alert.id == id }
        if (r == null) open = null
        else AlertDetailSheet(r, now, onSend = { eta, n -> repo.sendAcks(r.alert.reports.map { it.id }, eta, n); open = null }, onDismiss = { open = null })
    }
}

/** Community reports (kind 1–4) and check-ins (kind 0) with a position, newest per phone for check-ins. */
private fun places(packets: List<PacketEntity>, now: Long): List<Place> {
    val live = packets.filter { now - it.receivedAt < ACTIVE_MS }
    val reports = live.filter { it.type == PacketType.REPORT.code }.mapNotNull { p ->
        val r = p.toReport(); if (r.lat == null || r.lon == null) null
        else Place(p.id, r.lat, r.lon, p.reportType ?: 1, note = p.note ?: "", at = p.receivedAt, hops = p.hops.takeIf { !p.isMine }, accuracyM = r.accuracyM)
    }
    val checkIns = live.filter { it.type == PacketType.CHECK_IN.code && !it.isMine }.groupBy { it.origin }.values
        .map { l -> l.maxBy { it.receivedAt } }
        .mapNotNull { p ->
            val r = p.toReport()
            if (r.lat == null || r.lon == null) null
            else Place(p.id, r.lat, r.lon, 0, label = p.name ?: "", at = p.receivedAt, hops = p.hops, accuracyM = r.accuracyM)
        }
    return reports + checkIns
}

@Composable
private fun ModeToggle(mode: MapMode, onChange: (MapMode) -> Unit) {
    val c = Lifeline.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(MapMode.MAP to R.string.map_mode_map, MapMode.RINGS to R.string.map_mode_rings).forEach { (m, label) ->
            val on = m == mode
            Box(
                Modifier.weight(1f).heightIn(min = Touch.standard)
                    .background(if (on) c.surfaceRaised else c.surface, Shapes.chip)
                    .border(if (on) 2.dp else 1.dp, if (on) c.link else c.outline, Shapes.chip)
                    .clickable(role = Role.Tab) { onChange(m) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(label), style = Lifeline.type.label, color = c.ink) }
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    Text(
        text, style = Lifeline.type.caption, color = c.ink,
        modifier = modifier.padding(Space.m).background(c.surface, Shapes.card).border(1.dp, c.warn, Shapes.card).padding(Space.m),
    )
}

/** MapLibre in a Compose AndroidView, with its lifecycle forwarded. */
@Composable
private fun LibreMap(
    archive: File,
    c: LifelineColors,
    ranked: List<RankedAlert>,
    places: List<Place>,
    me: GeoFix?,
    onAlertTap: (Long) -> Unit,
    onPlaceTap: (String) -> Unit,
    onFail: () -> Unit,
) {
    val onPlace by rememberUpdatedState(onPlaceTap)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onTap by rememberUpdatedState(onAlertTap)
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            mapView.addOnDidFailLoadingMapListener { msg -> Log.w("OfflineMap", "map failed: $msg"); onFail() }
            mapView.getMapAsync { m ->
                map = m
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = false // shown as text in Compose, always visible
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.setMinZoomPreference(11.0)
                m.setMaxZoomPreference(18.0)
                m.setLatLngBoundsForCameraTarget(LatLngBounds.from(MapAssets.NORTH, MapAssets.EAST, MapAssets.SOUTH, MapAssets.WEST))
                val start = me?.takeIf { MapAssets.contains(it.lat, it.lon) }?.let { LatLng(it.lat, it.lon) }
                    ?: LatLng(MapAssets.CENTER_LAT, MapAssets.CENTER_LON)
                m.cameraPosition = CameraPosition.Builder().target(start).zoom(14.0).build()
                try {
                    m.setStyle(Style.Builder().fromJson(MapStyle.json(context, archive, c.isNight))) { st ->
                        st.addImage("me", MarkerBitmaps.me(c.link.toArgb(), c.bg.toArgb(), density))
                        listOf(
                            0 to (R.drawable.ic_verified_user to c.safe), 1 to (R.drawable.ic_block to c.warn),
                            2 to (R.drawable.ic_water_drop to c.link), 3 to (R.drawable.ic_groups to c.safe), 4 to (R.drawable.ic_groups to c.warn),
                            // Live positions (Phase 10): people in need, volunteers, and each team type.
                            11 to (R.drawable.ic_person to c.sos), 12 to (R.drawable.ic_volunteer_activism to c.warn),
                            13 to (R.drawable.ic_health_and_safety to c.link), 14 to (R.drawable.ic_local_fire_department to c.link),
                            15 to (R.drawable.ic_emergency to c.link), 16 to (R.drawable.ic_local_police to c.link), 17 to (R.drawable.ic_boat to c.link),
                        ).forEach { (kind, v) ->
                            st.addImage("place-$kind", MarkerBitmaps.square(context, v.first, c.surface.toArgb(), v.second.toArgb(), v.second.toArgb(), density))
                        }
                        st.addSource(GeoJsonSource("places"))
                        st.addSource(GeoJsonSource("alerts"))
                        st.addSource(GeoJsonSource("me"))
                        val icon = listOf(
                            PropertyFactory.iconImage(Expression.get("icon")),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                        )
                        st.addLayer(SymbolLayer("places", "places").withProperties(*icon.toTypedArray()))
                        st.addLayer(SymbolLayer("alerts", "alerts").withProperties(*(icon + PropertyFactory.symbolSortKey(Expression.get("order"))).toTypedArray()))
                        st.addLayer(SymbolLayer("me", "me").withProperties(*icon.toTypedArray()))
                        style = st
                    }
                } catch (e: Exception) {
                    Log.w("OfflineMap", "style failed", e)
                    onFail()
                }
                m.addOnMapClickListener { ll ->
                    val pt = m.projection.toScreenLocation(ll)
                    val f = m.queryRenderedFeatures(pt, "alerts").firstOrNull()
                    val id = f?.getStringProperty("id")?.toLongOrNull()
                    val place = if (id == null) m.queryRenderedFeatures(pt, "places").firstOrNull()?.getStringProperty("key") else null
                    when {
                        id != null -> { onTap(id); true }
                        place != null -> { onPlace(place); true }
                        else -> false
                    }
                }
            }
            mapView
        },
    )

    // Once, when my fix (or the first alerts) arrive: frame me and the five highest-ranked alerts, so a rescuer opening
    // the map sees who needs help first, not just their own dot.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(map, me, ranked, places) {
        val m = map ?: return@LaunchedEffect
        if (framed) return@LaunchedEffect
        val points = buildList {
            me?.let { add(LatLng(it.lat, it.lon)) }
            ranked.take(5).forEach { r -> r.alert.position?.let { p -> add(LatLng(p.lat!!, p.lon!!)) } }
            // The nearest teams too (Phase 10): someone waiting for help wants to see who is coming.
            places.filter { it.kind >= 13 }.sortedBy { pl -> me?.let { com.lifeline.mesh.triage.GeoMath.distanceM(it.lat, it.lon, pl.lat, pl.lon) } ?: 0.0 }
                .take(2).forEach { add(LatLng(it.lat, it.lon)) }
        }.filter { MapAssets.contains(it.latitude, it.longitude) }
        if (points.isEmpty()) return@LaunchedEffect
        framed = true
        val update = if (points.size == 1) org.maplibre.android.camera.CameraUpdateFactory.newLatLng(points[0])
        else org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().includes(points).build(), (64 * density).toInt())
        m.animateCamera(update)
        if (m.cameraPosition.zoom > 16.0) m.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomTo(16.0))
    }

    // Push data into the sources whenever it changes. Marker images are created on demand, once each.
    LaunchedEffect(style, ranked, places, me) {
        val st = style ?: return@LaunchedEffect
        val alerts = ranked.mapNotNull { r ->
            val p = r.alert.position ?: return@mapNotNull null
            val name = "alert-${r.rank}-${r.alert.severity}-${r.alert.people.coerceIn(0, 20)}-${if (r.alert.acked) 1 else 0}"
            if (st.getImage(name) == null) {
                val fill = if (r.alert.acked) c.inkMuted else c.severity(r.alert.severity)
                st.addImage(name, MarkerBitmaps.alert(r.rank, fill.toArgb(), c.onFill.toArgb(), c.bg.toArgb(), r.alert.people, density))
            }
            Feature.fromGeometry(Point.fromLngLat(p.lon!!, p.lat!!)).apply {
                addStringProperty("icon", name)
                addStringProperty("id", r.alert.id.toString())
                addNumberProperty("order", -r.rank) // rank 1 drawn on top
            }
        }
        st.getSourceAs<GeoJsonSource>("alerts")?.setGeoJson(FeatureCollection.fromFeatures(alerts))
        st.getSourceAs<GeoJsonSource>("places")?.setGeoJson(FeatureCollection.fromFeatures(places.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("icon", "place-${p.kind}")
                addStringProperty("key", p.key)
            }
        }))
        st.getSourceAs<GeoJsonSource>("me")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(me?.let {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("icon", "me") }
        })))
        map?.triggerRepaint()
    }
}
