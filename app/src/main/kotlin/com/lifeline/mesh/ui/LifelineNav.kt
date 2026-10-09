package com.lifeline.mesh.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Role
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.mesh.LinkState
import com.lifeline.mesh.mesh.MeshHealth
import com.lifeline.mesh.mesh.MeshService
import com.lifeline.mesh.ui.components.EmptyState
import com.lifeline.mesh.ui.mesh.LiveMeshScreen
import com.lifeline.mesh.ui.send.SendScreen
import com.lifeline.mesh.ui.rescuer.RescuerScreen
import com.lifeline.mesh.ui.map.OfflineMapScreen
import com.lifeline.mesh.ui.mesh.MeshDebugScreen
import com.lifeline.mesh.ui.components.StatusItem
import com.lifeline.mesh.ui.components.StatusStrip
import com.lifeline.mesh.ui.gallery.ComponentGallery
import com.lifeline.mesh.ui.onboarding.PermissionsChecklist
import com.lifeline.mesh.ui.settings.SettingsScreen
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Space
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object SosRoute
@Serializable object MeshRoute
@Serializable object RescueRoute
@Serializable object MapRoute
@Serializable object SettingsRoute
@Serializable object PermissionsRoute
@Serializable object GalleryRoute
@Serializable object DebugRoute
@Serializable object BenchmarksRoute
@Serializable object AskRoute
@Serializable object FirstAidRoute

private data class Tab(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val iconSelected: Int,
)

private val tabs = listOf(
    Tab(SosRoute, SosRoute::class, R.string.nav_sos, R.drawable.ic_sos, R.drawable.ic_sos_filled),
    Tab(MeshRoute, MeshRoute::class, R.string.nav_mesh, R.drawable.ic_hub, R.drawable.ic_hub_filled),
    Tab(RescueRoute, RescueRoute::class, R.string.nav_rescue, R.drawable.ic_health_and_safety, R.drawable.ic_health_and_safety_filled),
    Tab(MapRoute, MapRoute::class, R.string.nav_map, R.drawable.ic_map, R.drawable.ic_map_filled),
)

/** Bottom bar with SOS, Mesh, Rescue, Map; Settings from the top strip. Start tab follows the role. */
@Composable
fun MainScaffold(settings: Settings, repository: AppRepository) {
    val c = Lifeline.colors
    val nav = rememberNavController()
    val start: Any = remember { if (settings.role == Role.NEEDS_HELP) SosRoute else RescueRoute }
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onTab = tabs.any { t -> destination?.hasRoute(t.routeClass) == true }
    val running by repository.meshRunning.collectAsStateWithLifecycle()
    val neighbors by repository.neighbors.collectAsStateWithLifecycle()
    val connected = neighbors.count { it.state == LinkState.CONNECTED }

    // Start relaying as soon as the mesh permissions are there (also after granting them in Settings).
    val context = LocalContext.current
    LifecycleResumeEffect(settings.simulationMode) {
        val canRun = settings.simulationMode || MeshHealth.missingPermissions(context).isEmpty()
        if (!repository.userStoppedMesh && !running && canRun) {
            MeshService.start(context)
        }
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            Column(Modifier.background(c.bg).statusBarsPadding()) {
                StatusStrip(
                    mesh = meshStatus(running, connected),
                    ai = aiStatus(),
                    simulated = settings.simulationMode,
                    trailing = if (onTab) {
                        {
                            IconButton(onClick = { nav.navigate(SettingsRoute) { launchSingleTop = true } }) {
                                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.nav_settings), tint = c.ink)
                            }
                        }
                    } else null,
                )
                if (!onTab) SubScreenTitle(destination, onBack = { nav.popBackStack() })
            }
        },
        bottomBar = { if (onTab) BottomBar(nav, destination) },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable<SosRoute> { SendScreen(settings, onFirstAid = { nav.navigate(FirstAidRoute) }) }
            composable<MeshRoute> {
                LiveMeshScreen(settings, repository, onDebug = { nav.navigate(DebugRoute) }, onPermissions = { nav.navigate(PermissionsRoute) })
            }
            composable<DebugRoute> { MeshDebugScreen(settings, repository, onPermissions = { nav.navigate(PermissionsRoute) }) }
            composable<RescueRoute> {
                RescuerScreen(settings, onAsk = { nav.navigate(AskRoute) }, onOpenMesh = {
                    nav.navigate(MeshRoute) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable<MapRoute> { OfflineMapScreen(settings) }
            composable<SettingsRoute> {
                SettingsScreen(
                    settings = settings,
                    repository = repository,
                    onPermissions = { nav.navigate(PermissionsRoute) },
                    onGallery = { nav.navigate(GalleryRoute) },
                    onDebug = { nav.navigate(DebugRoute) },
                    onBenchmarks = { nav.navigate(BenchmarksRoute) },
                )
            }
            composable<PermissionsRoute> {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.screen)) { PermissionsChecklist() }
            }
            composable<GalleryRoute> { ComponentGallery() }
            composable<BenchmarksRoute> { com.lifeline.mesh.ui.settings.BenchmarksScreen() }
            composable<AskRoute> { com.lifeline.mesh.ui.rescuer.AskMeshScreen() }
            composable<FirstAidRoute> { com.lifeline.mesh.ui.send.FirstAidScreen() }
        }
    }
}

@Composable
private fun aiStatus(): StatusItem {
    val c = Lifeline.colors
    val ai = (LocalContext.current.applicationContext as com.lifeline.mesh.LifelineApp).ai
    val status by ai.status.collectAsStateWithLifecycle()
    val gov by ai.governor.state.collectAsStateWithLifecycle()
    return when (val s = status) {
        is com.lifeline.mesh.ai.AiStatus.Ready ->
            if (gov.pauseNonEssential) StatusItem(stringResource(R.string.status_ai_paused), c.warn)
            else StatusItem(stringResource(R.string.status_ai_ready, s.model, s.backend.name), c.safe)
        is com.lifeline.mesh.ai.AiStatus.Loading -> StatusItem(stringResource(R.string.status_ai_loading), c.warn, searching = true)
        is com.lifeline.mesh.ai.AiStatus.Testing -> StatusItem(stringResource(R.string.status_ai_testing), c.warn, searching = true)
        com.lifeline.mesh.ai.AiStatus.Idle -> StatusItem(stringResource(R.string.status_ai_loading), c.inkMuted)
        is com.lifeline.mesh.ai.AiStatus.RulesOnly -> StatusItem(stringResource(R.string.status_ai_rules), c.inkMuted)
    }
}

@Composable
private fun meshStatus(running: Boolean, connected: Int): StatusItem {
    val c = Lifeline.colors
    return when {
        !running -> StatusItem(stringResource(R.string.status_mesh_off), c.inkMuted)
        connected == 0 -> StatusItem(stringResource(R.string.status_mesh_searching), c.warn, searching = true)
        else -> StatusItem(pluralStringResource(R.plurals.status_mesh_nearby, connected, connected), c.link)
    }
}

@Composable
private fun SubScreenTitle(destination: androidx.navigation.NavDestination?, onBack: () -> Unit) {
    val c = Lifeline.colors
    val title = when {
        destination?.hasRoute(SettingsRoute::class) == true -> stringResource(R.string.nav_settings)
        destination?.hasRoute(PermissionsRoute::class) == true -> stringResource(R.string.settings_permissions)
        destination?.hasRoute(GalleryRoute::class) == true -> stringResource(R.string.settings_gallery)
        destination?.hasRoute(DebugRoute::class) == true -> stringResource(R.string.settings_debug)
        destination?.hasRoute(BenchmarksRoute::class) == true -> "Benchmarks"
        destination?.hasRoute(AskRoute::class) == true -> "Ask the mesh"
        destination?.hasRoute(FirstAidRoute::class) == true -> stringResource(R.string.firstaid_title)
        else -> ""
    }
    Row(Modifier.heightIn(min = 56.dp).padding(end = Space.screen), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = c.ink)
        }
        Text(title, style = Lifeline.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun BottomBar(nav: NavHostController, destination: androidx.navigation.NavDestination?) {
    val c = Lifeline.colors
    NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            val selected = destination?.hasRoute(tab.routeClass) == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(painterResource(if (selected) tab.iconSelected else tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label), style = Lifeline.type.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.ink,
                    selectedTextColor = c.ink,
                    indicatorColor = c.surfaceRaised,
                    unselectedIconColor = c.inkMuted,
                    unselectedTextColor = c.inkMuted,
                ),
            )
        }
    }
}

/** Tabs whose real screens are built in later phases say so plainly, with a useful action. */
@Composable
private fun PhasePlaceholder(@StringRes title: Int, @StringRes body: Int, @DrawableRes icon: Int, onGallery: () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(title),
            body = stringResource(body),
            actionLabel = stringResource(R.string.ph_action),
            onAction = onGallery,
            icon = icon,
        )
    }
}
