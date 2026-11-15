package com.johanwes.cometdownload.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.johanwes.cometdownload.CometViewModel
import com.johanwes.cometdownload.MainActivity
import com.johanwes.cometdownload.MessageAction
import com.johanwes.cometdownload.Tab
import kotlinx.coroutines.launch

private const val RAIL_MIN_WIDTH_DP = 600
private const val TWO_PANE_MIN_WIDTH_DP = 720

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

        LifecycleStartEffect(viewModel) {
            viewModel.setVisible(true)
            onStopOrDispose { viewModel.setVisible(false) }
        }

        val context = LocalContext.current
        LaunchedEffect(viewModel) {
            viewModel.messages.collect { message ->
                // A new message replaces the current one instead of queueing behind it.
                snackbar.currentSnackbarData?.dismiss()
                launch {
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
                viewModel.tab == Tab.Library -> viewModel.tab = Tab.Search
                !twoPane && viewModel.selected != null -> viewModel.select(null)
                else -> onExit()
            }
        }

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
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Destinations.forEach { destination ->
                            val selected = viewModel.tab == destination.tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.goTo(destination.tab) },
                                icon = { DestinationIcon(destination, selected, activeCount) },
                                label = { Text(destination.label) },
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
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Spacer(Modifier.height(12.dp))
                        Destinations.forEach { destination ->
                            val selected = viewModel.tab == destination.tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.goTo(destination.tab) },
                                icon = { DestinationIcon(destination, selected, activeCount) },
                                label = { Text(destination.label) },
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
)

@Composable
private fun DestinationIcon(destination: Destination, selected: Boolean, activeCount: Int) {
    val icon = if (selected) destination.selectedIcon else destination.icon
    // The number of downloads still running.
    if (destination.tab == Tab.Library && activeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(activeCount.toString()) }
            },
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

/** Settings shortcut for top bars on compact screens (the rail has its own entry). */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
}
