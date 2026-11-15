package com.johanwes.cometdownload.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.CometViewModel
import com.johanwes.cometdownload.JobItem
import com.johanwes.cometdownload.JobState
import com.johanwes.cometdownload.LibraryEpisode
import com.johanwes.cometdownload.LibraryFilter
import com.johanwes.cometdownload.LibrarySort
import com.johanwes.cometdownload.LibraryTitle
import com.johanwes.cometdownload.MainActivity
import com.johanwes.cometdownload.Tab
import com.johanwes.cometdownload.formatBytes
import com.johanwes.cometdownload.formatEta
import com.johanwes.cometdownload.formatSpeed
import com.johanwes.cometdownload.player.PlayerActivity
import kotlinx.coroutines.delay
import java.io.File

private val GridPadding = 16.dp
private const val LOADING_SPINNER_DELAY_MS = 700L

/** Keys of the section headings; whichever comes first is the top of the grid. */
private val SectionKeys = setOf("downloads-label", "continue-label", "titles-label")

/**
 * The library: finished downloads as a poster grid, with what is still downloading on top.
 * A title opens on its own page ([LibraryTitleScreen]).
 */
@Composable
fun LibraryScreen(viewModel: CometViewModel, showSettingsAction: Boolean, gridState: LazyGridState) {
    val open = viewModel.openTitle
    // The page that slides out still needs its title after it was closed (or deleted).
    var shown by remember { mutableStateOf(open) }
    if (open != null) shown = open
    DetailSwitch(state = open != null, isDetail = { it }, label = "library-detail") { detail ->
        val title = shown
        if (detail && title != null) {
            // Fresh scroll position and season choice per title.
            key(title.key) {
                LibraryTitleScreen(viewModel = viewModel, title = title, paneColor = MaterialTheme.colorScheme.background)
            }
        } else {
            LibraryFront(viewModel, showSettingsAction, gridState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFront(viewModel: CometViewModel, showSettingsAction: Boolean, gridState: LazyGridState) {
    val context = LocalContext.current
    val library = viewModel.library
    // Everything that isn't in the library (yet): running and queued first, then what stopped short.
    val downloads = viewModel.jobs
        .filter { it.state != JobState.Completed }
        .sortedWith(compareBy({ !it.isActive }, { if (it.isActive) it.id else -it.id }))
    val continueWatching = library.filter { it.canContinue }.sortedByDescending { it.lastPlayedAt }
    val bothKinds = library.any { it.isSeries } && library.any { !it.isSeries }
    val filter = if (bothKinds) viewModel.libraryFilter else LibraryFilter.All
    val visible = library
        .filter {
            when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Movies -> !it.isSeries
                LibraryFilter.Series -> it.isSeries
            }
        }
        .let { titles ->
            when (viewModel.librarySort) {
                LibrarySort.Recent -> titles // the library comes newest first
                LibrarySort.Title -> titles.sortedBy { sortName(it.name) }
                LibrarySort.Rating -> titles.sortedByDescending { it.rating.toFloatOrNull() ?: -1f }
            }
        }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }

    // A section that appears on top (a download starting, the first thing to continue) would
    // land above the screen: a lazy grid holds on to the item that was first before. So when
    // the grid sat at its top, it is put back there.
    LaunchedEffect(downloads.size, continueWatching.isEmpty()) {
        val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
        if (first != null && first.key in SectionKeys && gridState.firstVisibleItemScrollOffset == 0) {
            gridState.scrollToItem(0)
        }
    }
    // Asked for by "View" after queuing and by the download notification.
    LaunchedEffect(viewModel.libraryTopRequests) {
        if (viewModel.consumeLibraryTopRequest()) gridState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Library")
                    val summary = listOfNotNull(
                        library.size.takeIf { it > 0 }?.let { if (it == 1) "1 title" else "$it titles" },
                        library.sumOf { it.bytes }.takeIf { it > 0 }?.let(::formatBytes),
                        viewModel.freeBytes.takeIf { it >= 0 }?.let { "${formatBytes(it)} free" },
                    ).joinToString(" · ")
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                if (library.size > 1) SortMenu(viewModel.librarySort, viewModel::changeSort)
                if (showSettingsAction) SettingsAction(viewModel::openSettings)
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        if (!viewModel.storageGranted) {
            StorageBanner(onAllow = { MainActivity.openStorageAccessSettings(context) })
        }

        if (library.isEmpty() && downloads.isEmpty()) {
            if (!viewModel.jobsLoaded) {
                // The app can open on this tab before the download list has been read. Until
                // then it is not "nothing in your library": show nothing, and a spinner only
                // when it takes long.
                var slow by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(LOADING_SPINNER_DELAY_MS)
                    slow = true
                }
                if (slow) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                return@Column
            }
            MessageState(
                icon = Icons.Outlined.VideoLibrary,
                title = "Nothing in your library yet",
                body = "Movies and episodes you download show up here, ready to watch offline.",
                actionLabel = "Find something",
                onAction = { viewModel.tab = Tab.Search },
            )
            return@Column
        }

        LazyVerticalGrid(
            // Three posters across on a phone; larger ones where there is room for them.
            columns = GridCells.Adaptive(minSize = if (LocalConfiguration.current.screenWidthDp >= 600) 128.dp else 100.dp),
            state = gridState,
            contentPadding = PaddingValues(horizontal = GridPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val fullWidth: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = {
                GridItemSpan(maxLineSpan)
            }

            if (downloads.isNotEmpty()) {
                item(key = "downloads-label", span = fullWidth) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(if (downloads.any { it.isActive }) "Downloading" else "Not downloaded", Modifier.weight(1f))
                        // Finished downloads are the library; only what failed or was cancelled is cleared.
                        if (downloads.any { it.canRetry }) {
                            TextButton(onClick = viewModel::clearFailed) { Text("Clear failed") }
                        }
                    }
                }
                items(downloads, key = { "job-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { job ->
                    DownloadRow(
                        job = job,
                        poster = viewModel.posterFor(job),
                        onCancel = { viewModel.cancel(job) },
                        onRetry = { viewModel.retry(job) },
                        onDismiss = { viewModel.remove(job, deleteFiles = false) },
                        modifier = Modifier.centered().animateItem(),
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item(key = "continue-label", span = fullWidth) { SectionLabel("Continue watching") }
                item(key = "continue", span = fullWidth) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = GridPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        // The row runs edge to edge, past the padding the grid gives its cells.
                        modifier = Modifier.bleed(GridPadding),
                    ) {
                        items(continueWatching, key = { it.key }) { title ->
                            ContinueCard(
                                title = title,
                                onClick = { playFromLibrary(context, viewModel, title) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            if (library.isNotEmpty()) {
                item(key = "titles-label", span = fullWidth) {
                    if (bothKinds) {
                        PillChips(
                            options = LibraryFilter.entries,
                            selected = filter,
                            onSelect = { viewModel.libraryFilter = it },
                            label = { it.label },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        SectionLabel(if (library.first().isSeries) "Series" else "Movies")
                    }
                }
                items(visible, key = { it.key }) { title ->
                    PosterCard(
                        title = title,
                        onOpen = { viewModel.openTitle(title) },
                        onPlay = { playFromLibrary(context, viewModel, title) },
                        onSetWatched = { watched -> viewModel.setWatched(title.episodes, watched) },
                        onDelete = { pending = deleteTitle(title) { viewModel.removeTitle(title, deleteFiles = true) } },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "footer", span = fullWidth) { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
    }

    ConfirmDialog(pending) { pending = null }
}

/** Names a title's poster, in the grid and on its page, so it can travel between the two. */
internal fun libraryPosterKey(title: LibraryTitle): String = "library-${title.key}"

/** "The Bear" sorts under B. */
private fun sortName(name: String): String =
    name.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")

// ---------------------------------------------------------------------------
// Playing and deleting
// ---------------------------------------------------------------------------

/**
 * Plays [episode] of [title] (by default what is next up). A show plays on into its following
 * episodes; a movie that is there in two versions plays only the one picked.
 */
internal fun playFromLibrary(
    context: Context,
    viewModel: CometViewModel,
    title: LibraryTitle,
    episode: LibraryEpisode? = title.nextUp ?: title.episodes.firstOrNull(),
) {
    episode ?: return
    if (!File(episode.path).exists()) {
        viewModel.showMessage("That file is gone. It may have been moved or deleted.")
        return
    }
    val playlist = if (title.isSeries) title.episodes else listOf(episode)
    PlayerActivity.start(
        context = context,
        paths = playlist.map { it.path },
        labels = playlist.map { it.label },
        index = playlist.indexOfFirst { it.path == episode.path },
        title = title.name,
    )
}

/** Something that is only done after the user confirmed it. */
internal data class PendingDelete(
    val heading: String,
    val body: String,
    val confirmLabel: String = "Delete",
    val action: () -> Unit,
)

internal fun deleteTitle(title: LibraryTitle, action: () -> Unit) = PendingDelete(
    heading = "Delete ${title.name}?",
    body = buildString {
        append(if (title.isSeries) "${allEpisodesLabel(title)} and ${subtitlesOf(title.episodes.size)}" else "The video and its subtitles are")
        append(" removed from the Download folder. This frees ${formatBytes(title.bytes)}.")
    },
    action = action,
)

/** "its subtitles are" / "their subtitles are", to follow a number of videos. */
internal fun subtitlesOf(videos: Int): String = if (videos == 1) "its subtitles are" else "their subtitles are"

private fun allEpisodesLabel(title: LibraryTitle): String =
    if (title.episodes.size == 1) "1 episode" else "All ${title.episodes.size} episodes"

@Composable
internal fun ConfirmDialog(pending: PendingDelete?, onDone: () -> Unit) {
    pending ?: return
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(pending.heading) },
        text = { Text(pending.body) },
        confirmButton = {
            TextButton(
                onClick = {
                    pending.action()
                    onDone()
                },
            ) { Text(pending.confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------
// Front page pieces
// ---------------------------------------------------------------------------

@Composable
private fun SortMenu(current: LibrarySort, onPick: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label) },
                    trailingIcon = {
                        if (sort == current) Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        open = false
                        onPick(sort)
                    },
                )
            }
        }
    }
}

/** A title in the grid: its poster with progress on it, and where you are below it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    title: LibraryTitle,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val next = title.nextUp
    // A started video shows how far it is; a started show how much of it has been watched.
    val progress = next?.progress
        ?: title.takeIf { it.isSeries && it.watchedCount > 0 && !it.allWatched }
            ?.let { it.watchedCount.toFloat() / it.episodeCount }
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Clipped as a whole, so the progress bar follows the poster's rounded corners.
        Box(Modifier.clip(MaterialTheme.shapes.small)) {
            // No rating here: it helped pick the title in search; on your own shelf it is in the way.
            Poster(
                url = title.poster,
                isSeries = title.isSeries,
                modifier = Modifier.fillMaxWidth().sharedPoster(libraryPosterKey(title)),
                iconSize = 32.dp,
            )
            // Named in full: inside the card's column, the plain name means the column's own variant.
            androidx.compose.animation.AnimatedVisibility(
                visible = title.allWatched,
                enter = PopIn,
                exit = PopOut,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                    Icon(Icons.Default.Check, contentDescription = "Watched", modifier = Modifier.padding(2.dp).size(14.dp))
                }
            }
            if (title.activeJobs.isNotEmpty()) {
                PosterBadge(Modifier.align(Alignment.BottomEnd).padding(start = 6.dp, end = 6.dp, bottom = 10.dp)) {
                    Icon(Icons.Outlined.Downloading, contentDescription = "More is downloading", modifier = Modifier.size(14.dp))
                }
            }
            if (progress != null) {
                WatchBar(progress, Modifier.align(Alignment.BottomCenter))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (next?.inProgress == true) "Resume" else "Play") },
                    onClick = {
                        menuOpen = false
                        onPlay()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (title.allWatched) "Mark as unwatched" else "Mark as watched") },
                    onClick = {
                        menuOpen = false
                        onSetWatched(!title.allWatched)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete from device") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
        Text(
            title.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            title.statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = if (title.canContinue) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

/** Small dark label that stays readable on any artwork. */
@Composable
private fun PosterBadge(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) { content() }
    }
}

/** How much has been watched, along the bottom edge of a piece of artwork. */
@Composable
internal fun WatchBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.6f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** A started movie or show as a wide card; a tap carries on where it stopped. */
@Composable
private fun ContinueCard(title: LibraryTitle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val next = title.nextUp
    Column(
        modifier
            .width(220.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Play", onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceContainerHighest),
        ) {
            val image = title.background.ifBlank { title.poster }
            if (image.isNotBlank()) {
                RemoteImage(url = image, alignment = BiasAlignment(0f, -0.4f), modifier = Modifier.fillMaxSize())
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.padding(8.dp).size(28.dp))
            }
            next?.progress?.let { WatchBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(
            title.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            title.statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Downloads that are not finished
// ---------------------------------------------------------------------------

/** A download on its way (progress, cancel) or one that stopped short (reason, retry). */
@Composable
internal fun DownloadRow(
    job: JobItem,
    poster: String,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val progress = job.overallProgress
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Poster(
                    url = poster,
                    isSeries = job.isSeries,
                    shape = MaterialTheme.shapes.extraSmall,
                    iconSize = 18.dp,
                    modifier = Modifier.width(40.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        job.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        downloadStatus(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.state == JobState.Failed) colors.error else colors.onSurfaceVariant,
                    )
                }
                when {
                    job.isActive && !job.cancelRequested -> IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel download")
                    }
                    job.canRetry -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "Try again")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Remove from the list")
                        }
                    }
                }
            }

            if (job.isActive) {
                if (progress != null && job.state == JobState.Downloading) {
                    LinearProgressIndicator(
                        progress = glide(progress),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val files = if (job.totalFiles > 1) "File ${job.completedFiles + 1} of ${job.totalFiles} · " else ""
                        Text(
                            "$files${formatBytes(job.downloadedBytes)} of ${formatBytes(job.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            listOfNotNull(formatSpeed(job.speedBytes), formatEta(job.etaSeconds)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else if (job.state != JobState.Queued) {
                    LinearProgressIndicator(
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }

            if (job.state == JobState.Failed && job.errorText.isNotBlank()) {
                Surface(color = colors.errorContainer.copy(alpha = 0.35f), shape = MaterialTheme.shapes.small) {
                    Text(
                        job.errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun downloadStatus(job: JobItem): String {
    if (job.isActive && job.cancelRequested) return "Cancelling…"
    return when (job.state) {
        JobState.Queued -> "Waiting in queue"
        JobState.Resolving -> if (job.isWaiting) job.phase else "Preparing links"
        JobState.Downloading -> {
            val percent = job.overallProgress?.let { "${(it * 100).toInt()}%" }
            // "Downloading file 1/1" says nothing extra for single-file jobs.
            val phase = when {
                job.phase.isBlank() -> "Downloading"
                job.phase.startsWith("Downloading file") -> "Downloading"
                else -> job.phase
            }
            // The subtitle step has no byte progress, so a percentage would be stale.
            if (job.phase.startsWith("Downloading subtitles")) phase else listOfNotNull(phase, percent).joinToString(" · ")
        }
        JobState.Completed -> "Completed"
        JobState.Failed -> "Failed"
        JobState.Cancelled -> "Cancelled"
    }
}

// ---------------------------------------------------------------------------
// Shared small pieces
// ---------------------------------------------------------------------------

@Composable
private fun StorageBanner(onAllow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .centered(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.FolderOff, contentDescription = null, tint = colors.onErrorContainer)
            Text(
                "Allow file access so downloads can be saved to the Download folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun DetailLine(icon: ImageVector, text: String, tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What the automatic subtitle download came up with for a finished download. */
@Composable
internal fun SubtitleLine(job: JobItem) {
    when (job.subtitleStatus) {
        "searching" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(job.subtitleMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "found" -> DetailLine(Icons.Outlined.Subtitles, job.subtitleMessage, MaterialTheme.colorScheme.primary)
        "partial" -> DetailLine(Icons.Outlined.Subtitles, job.subtitleMessage)
        "missing", "failed" -> DetailLine(Icons.Outlined.SubtitlesOff, job.subtitleMessage)
        else -> Unit // "off" (turned off in Settings) or not reached
    }
}

/** Content that fills the width on a phone and stays a readable column on a tablet. */
internal fun Modifier.centered(max: Dp = 840.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** Lets a child run [by] past both sides of its parent's content padding. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}
