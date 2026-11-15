package com.johanwes.cometdownload.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import com.johanwes.cometdownload.update.AppUpdater
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.johanwes.cometdownload.CometViewModel
import com.johanwes.cometdownload.MainActivity
import com.johanwes.cometdownload.MessageAction
import com.johanwes.cometdownload.Tab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RAIL_MIN_WIDTH_DP = 600
private const val TWO_PANE_MIN_WIDTH_DP = 720
private const val FLIGHT_GRACE_MS = 200L

// Material's sizes for the selected tab's indicator.
private val BarPillSize = DpSize(64.dp, 32.dp)
private val RailPillSize = DpSize(56.dp, 32.dp)
private const val FLIGHT_RECENT_MS = 1000L

@Composable
fun CometApp(viewModel: CometViewModel, onExit: () -> Unit) {
    CometTheme {
        val widthDp = LocalConfiguration.current.screenWidthDp
        val useRail = widthDp >= RAIL_MIN_WIDTH_DP
        val twoPane = widthDp >= TWO_PANE_MIN_WIDTH_DP
        val snackbar = remember { SnackbarHostState() }
        val resultsListState = rememberLazyListState()
        val libraryGridState = rememberLazyGridState()
        // The list keeps its place while a title is open, but a new search starts at the top.
        LaunchedEffect(viewModel.searchCount) { resultsListState.requestScrollToItem(0) }
        val activeCount = viewModel.jobs.count { it.isActive }
        val anchors = remember { Anchors() }
        val flight = remember { DownloadFlight() }
        val flood = remember { PlayFlood() }

        LifecycleStartEffect(viewModel) {
            viewModel.setVisible(true)
            onStopOrDispose { viewModel.setVisible(false) }
        }

        val context = LocalContext.current
        // Looks for a new version when the app comes to the front (at most every few hours).
        LifecycleStartEffect(Unit) {
            AppUpdater.check(context)
            onStopOrDispose { }
        }
        LaunchedEffect(viewModel) {
            viewModel.messages.collect { message ->
                launch {
                    if (message.onlyQueued) {
                        // The dot that flies to the Library tab has said this already. It sets
                        // off a moment after the message, so that is waited for.
                        delay(FLIGHT_GRACE_MS)
                        if (flight.launchedWithin(FLIGHT_RECENT_MS)) return@launch
                    }
                    // A new message replaces the current one instead of queueing behind it.
                    snackbar.currentSnackbarData?.dismiss()
                    val action = when (message.action) {
                        MessageAction.ViewLibrary -> if (viewModel.tab != Tab.Library) "View" else null
                        MessageAction.StorageAccess -> "Allow"
                        MessageAction.None -> null
                    }
                    val duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short
                    val result = snackbar.showSnackbar(message.text, actionLabel = action, duration = duration)
                    if (result == SnackbarResult.ActionPerformed) {
                        when (message.action) {
                            MessageAction.ViewLibrary -> viewModel.showDownloads()
                            MessageAction.StorageAccess -> MainActivity.openStorageAccessSettings(context)
                            MessageAction.None -> Unit
                        }
                    }
                }
            }
        }

        BackHandler {
            when {
                viewModel.tab == Tab.Library && viewModel.openTitle != null -> viewModel.openTitle(null)
                viewModel.tab == Tab.Search && !twoPane && viewModel.selected != null -> viewModel.select(null)
                // Back leaves the other tabs for the one the app opens on, and the app from there.
                viewModel.tab != viewModel.homeTab -> viewModel.tab = viewModel.homeTab
                else -> onExit()
            }
        }

        CompositionLocalProvider(
            LocalAnchors provides anchors,
            LocalDownloadFlight provides flight,
            LocalPlayFlood provides flood,
        ) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    modifier = if (useRail) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier,
                )
            },
            bottomBar = {
                if (!useRail) {
                    // One pill that glides between the tabs stands in for each item's own.
                    NavigationBar(
                        containerColor = Color.Transparent,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .tabPill(viewModel.tab, BarPillSize, MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Destinations.forEach { destination ->
                            val selected = viewModel.tab == destination.tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.goTo(destination.tab) },
                                icon = { DestinationIcon(destination, selected, activeCount) },
                                label = { Text(destination.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = Color.Transparent,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .tabPill(viewModel.tab, RailPillSize, MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Destinations.forEach { destination ->
                            val selected = viewModel.tab == destination.tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.goTo(destination.tab) },
                                icon = { DestinationIcon(destination, selected, activeCount) },
                                label = { Text(destination.label) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = false,
                            onClick = viewModel::openSettings,
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Crossfade(targetState = viewModel.tab, label = "tab") { tab ->
                        when (tab) {
                            Tab.Search -> SearchScreen(
                                viewModel = viewModel,
                                twoPane = twoPane,
                                showSettingsAction = !useRail,
                                resultsListState = resultsListState,
                            )
                            Tab.Library -> LibraryScreen(
                                viewModel = viewModel,
                                showSettingsAction = !useRail,
                                gridState = libraryGridState,
                            )
                            Tab.Update -> UpdateScreen(
                                showSettingsAction = !useRail,
                                onSettings = viewModel::openSettings,
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = viewModel.settingsOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SettingsDialog(
                current = viewModel.settings,
                downloadsPath = viewModel.downloadsPath,
                firstRun = !viewModel.settings.isConfigured,
                onDismiss = viewModel::closeSettings,
                onSave = viewModel::saveSettings,
                onTestDebrid = viewModel::testDebrid,
            )
        }

        DownloadFlightOverlay(flight, target = anchors[tabAnchor(Tab.Library)])
        PlayFloodOverlay(flood)
        }
        }
    }
}

private data class Destination(
    val tab: Tab,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val Destinations = listOf(
    Destination(Tab.Search, "Search", Icons.Outlined.Search, Icons.Filled.Search),
    Destination(Tab.Library, "Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Destination(Tab.Update, "Update", Icons.Outlined.SystemUpdate, Icons.Filled.SystemUpdate),
)

@Composable
private fun DestinationIcon(destination: Destination, selected: Boolean, activeCount: Int) {
    val icon = if (selected) destination.selectedIcon else destination.icon
    // Where the icon is, for the selection pill and for downloads that fly to the Library tab.
    val place = Modifier.anchor(LocalAnchors.current, tabAnchor(destination.tab))
    if (destination.tab == Tab.Library) {
        LibraryIcon(icon, activeCount, place)
    } else if (destination.tab == Tab.Update && AppUpdater.hasUpdate) {
        // A plain dot: a newer version is waiting.
        BadgedBox(badge = { Badge(containerColor = UpdateYellow) }) {
            Icon(icon, contentDescription = "Update available", modifier = place)
        }
    } else {
        Icon(icon, contentDescription = null, modifier = place)
    }
}

/** The Library icon with the number of downloads still running; the number pops when one is added. */
@Composable
private fun LibraryIcon(icon: ImageVector, activeCount: Int, place: Modifier) {
    val flight = LocalDownloadFlight.current
    // A download that is still flying here is counted when it lands.
    var shown by remember { mutableIntStateOf(activeCount) }
    var seenLandings by remember { mutableIntStateOf(flight.landings) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(activeCount, flight.inFlight, flight.landings) {
        val landed = flight.landings != seenLandings
        seenLandings = flight.landings
        val waitForDot = activeCount > shown && flight.inFlight && !landed
        if (!waitForDot) {
            if (activeCount > shown) pop.snapTo(BADGE_POP)
            shown = activeCount
        }
        pop.animateTo(1f, Motion.bouncy())
    }
    if (shown > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    },
                ) { Text(shown.toString()) }
            },
        ) {
            Icon(icon, contentDescription = null, modifier = place)
        }
    } else {
        Icon(icon, contentDescription = null, modifier = place)
    }
}

private const val BADGE_POP = 1.5f

/** Settings shortcut for top bars on compact screens (the rail has its own entry). */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
}
