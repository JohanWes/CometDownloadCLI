package com.johanwes.cometdownload.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.CometViewModel
import com.johanwes.cometdownload.JobItem
import com.johanwes.cometdownload.LibraryEpisode
import com.johanwes.cometdownload.LibraryTitle
import com.johanwes.cometdownload.episodesLabel
import com.johanwes.cometdownload.fileName
import com.johanwes.cometdownload.formatBytes
import com.johanwes.cometdownload.formatTimeLeft
import com.johanwes.cometdownload.player.openInOtherApp
import com.johanwes.cometdownload.player.ui.formatTime
import com.johanwes.cometdownload.releaseTags
import com.johanwes.cometdownload.resolutionTag

/**
 * One title of the library: artwork, rating and plot, a button that carries on where you
 * stopped, then its episodes (or versions) with what was watched, and its files and subtitles.
 */
@Composable
fun LibraryTitleScreen(viewModel: CometViewModel, title: LibraryTitle, paneColor: Color) {
    val context = LocalContext.current
    val flood = LocalPlayFlood.current
    val listState = rememberLazyListState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdropHeight = 230.dp + statusTop
    val bar = rememberTopBarState(listState, backdropHeight, statusTop)
    val seasons = title.seasons
    // Episodes are listed with a still and a line about them once those have been fetched.
    val illustrated = title.isSeries && title.episodes.any { it.still.isNotBlank() }
    // Opens on the season being watched; a season that was deleted falls back to the first.
    var pickedSeason by rememberSaveable { mutableIntStateOf(title.nextUp?.season ?: seasons.firstOrNull() ?: 0) }
    val season = if (pickedSeason in seasons) pickedSeason else seasons.firstOrNull() ?: 0
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    val next = title.nextUp

    Box(Modifier.fillMaxSize()) {
        ArtworkWash(url = title.poster, paneColor = paneColor, plainTop = statusTop + TopBarHeight)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                TitleHeader(
                    media = title.media,
                    details = title.details,
                    backdropHeight = backdropHeight,
                    poster = title.poster,
                    posterKey = libraryPosterKey(title),
                )
            }
            item(key = "play") {
                PlayBlock(
                    title = title,
                    onPlay = { from -> playFromLibrary(context, viewModel, title, flood = flood, from = from) },
                    modifier = Modifier.centered().padding(start = 16.dp, end = 16.dp, top = 20.dp),
                )
            }

            if (title.isSeries) {
                item(key = "season") {
                    val inSeason = title.episodesIn(season)
                    SeasonBar(
                        title = title,
                        season = season,
                        onPick = { pickedSeason = it },
                        onSetWatched = { watched -> viewModel.setWatched(inSeason, watched) },
                        onDelete = {
                            pending = PendingDelete(
                                heading = "Delete ${seasonName(season).lowercase()}?",
                                body = "${episodesLabel(inSeason.size)} of ${title.name} and ${subtitlesOf(inSeason.size)} removed " +
                                    "from the Download folder. This frees ${formatBytes(inSeason.sumOf { it.bytes })}.",
                                action = { viewModel.deleteEpisodes(inSeason) },
                            )
                        },
                        modifier = Modifier.centered().padding(top = 12.dp),
                    )
                }
                items(title.episodesIn(season), key = { it.path }) { episode ->
                    val name = episode.title.ifBlank {
                        if (episode.episode > 0) "Episode ${episode.episode}" else fileName(episode.path)
                    }
                    VideoRow(
                        video = episode,
                        number = episode.episode.takeIf { it > 0 },
                        name = name,
                        highlighted = episode.path == next?.path,
                        illustrated = illustrated,
                        deleteLabel = "Delete episode",
                        onPlay = { from -> playFromLibrary(context, viewModel, title, episode, flood, from) },
                        onSetWatched = { watched -> viewModel.setWatched(listOf(episode), watched) },
                        onOpenElsewhere = { openInOtherApp(context, episode.path)?.let(viewModel::showMessage) },
                        onDelete = {
                            pending = PendingDelete(
                                heading = "Delete this episode?",
                                body = "${listOf(episode.tag, name).filter { it.isNotBlank() }.joinToString(" · ")} and its " +
                                    "subtitles are removed from the Download folder. This frees ${formatBytes(episode.bytes)}.",
                                action = { viewModel.deleteEpisodes(listOf(episode)) },
                            )
                        },
                        modifier = Modifier.centered().padding(horizontal = 8.dp).animateItem(),
                    )
                }
                if (title.media.json.isNotBlank()) {
                    item(key = "more") {
                        Box(Modifier.centered().padding(horizontal = 8.dp)) {
                            TextButton(
                                onClick = { viewModel.findMore(title) },
                                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Get more episodes")
                            }
                        }
                    }
                }
            } else if (title.episodes.size > 1) {
                // The same movie downloaded more than once, e.g. in 4K and in 1080p.
                item(key = "versions") {
                    SectionLabel("Versions", Modifier.centered().padding(start = 16.dp, top = 8.dp))
                }
                items(title.episodes, key = { it.path }) { version ->
                    val file = fileName(version.path)
                    VideoRow(
                        video = version,
                        number = null,
                        name = (listOfNotNull(resolutionTag(file)) + releaseTags(file).take(3)).joinToString(" · ").ifBlank { file },
                        highlighted = version.path == next?.path,
                        illustrated = false,
                        deleteLabel = "Delete this version",
                        onPlay = { from -> playFromLibrary(context, viewModel, title, version, flood, from) },
                        onSetWatched = { watched -> viewModel.setWatched(listOf(version), watched) },
                        onOpenElsewhere = { openInOtherApp(context, version.path)?.let(viewModel::showMessage) },
                        onDelete = {
                            pending = PendingDelete(
                                heading = "Delete this version?",
                                body = "$file and its subtitles are removed from the Download folder. " +
                                    "This frees ${formatBytes(version.bytes)}.",
                                action = { viewModel.deleteEpisodes(listOf(version)) },
                            )
                        },
                        modifier = Modifier.centered().padding(horizontal = 8.dp).animateItem(),
                    )
                }
            }

            if (title.activeJobs.isNotEmpty()) {
                item(key = "active-label") {
                    SectionLabel("On the way", Modifier.centered().padding(start = 16.dp, top = 8.dp))
                }
                items(title.activeJobs, key = { "job-${it.id}" }) { job ->
                    DownloadRow(
                        job = job,
                        poster = viewModel.posterFor(job),
                        onCancel = { viewModel.cancel(job) },
                        onRetry = {},
                        onDismiss = {},
                        modifier = Modifier.centered().padding(horizontal = 16.dp, vertical = 4.dp).animateItem(),
                    )
                }
            }

            item(key = "files-label") {
                SectionLabel("Files and subtitles", Modifier.centered().padding(start = 16.dp, top = 8.dp))
            }
            items(title.jobs, key = { "files-${it.id}" }) { job ->
                FilesCard(
                    job = job,
                    onSubtitles = { tryAnother -> viewModel.refetchSubtitles(job, tryAnother) },
                    modifier = Modifier.centered().padding(horizontal = 16.dp, vertical = 4.dp).animateItem(),
                )
            }
            item(key = "footer") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }

        TitleTopBar(
            title = title.name,
            bar = bar,
            embedded = false,
            paneColor = paneColor,
            onBack = { viewModel.openTitle(null) },
        ) { scrim ->
            TitleMenu(
                title = title,
                scrim = scrim,
                onSetWatched = { watched -> viewModel.setWatched(title.episodes, watched) },
                onOpenElsewhere = title.episodes.singleOrNull()?.let { only ->
                    { openInOtherApp(context, only.path)?.let(viewModel::showMessage) }
                },
                onFindMore = if (title.media.json.isNotBlank()) ({ viewModel.findMore(title) }) else null,
                onRemove = {
                    pending = PendingDelete(
                        heading = "Remove ${title.name} from the library?",
                        body = "It is no longer listed here. The files stay in the Download folder.",
                        confirmLabel = "Remove",
                        action = { viewModel.removeTitle(title, deleteFiles = false) },
                    )
                },
                onDelete = { pending = deleteTitle(title) { viewModel.removeTitle(title, deleteFiles = true) } },
            )
        }
    }

    ConfirmDialog(pending) { pending = null }
}

private fun seasonName(season: Int): String = if (season > 0) "Season $season" else "Other episodes"

/** The main button (resume, play the next episode, play again) and what is on the device. */
@Composable
private fun PlayBlock(title: LibraryTitle, onPlay: (from: Offset?) -> Unit, modifier: Modifier = Modifier) {
    val next = title.nextUp
    val buttonSpot = rememberSpot()
    val label = when {
        next == null -> "Play again"
        next.inProgress -> listOf("Resume", next.tag.takeIf { title.isSeries }.orEmpty(), "·", formatTime(next.positionMs))
            .filter { it.isNotBlank() }.joinToString(" ")
        title.isSeries && next.tag.isNotBlank() -> "Play ${next.tag}"
        else -> "Play"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onPlay(buttonSpot.center) },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(48.dp).spot(buttonSpot),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        next?.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(4.dp),
            )
        }
        val summary = if (title.isSeries) {
            listOfNotNull(
                episodesLabel(title.episodeCount),
                title.watchedCount.takeIf { it > 0 }?.let { "$it watched" },
                next?.remainingMs?.let(::formatTimeLeft),
                formatBytes(title.bytes),
            )
        } else {
            val file = title.episodes.singleOrNull()?.let { fileName(it.path) }
            listOfNotNull(
                next?.remainingMs?.let(::formatTimeLeft) ?: "Watched".takeIf { title.allWatched },
                formatBytes(title.bytes),
                file?.let(::resolutionTag),
            ) + file?.let(::releaseTags).orEmpty().take(3)
        }
        Text(
            summary.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Season chips (when there is more than one), the season's watched count and its menu. */
@Composable
private fun SeasonBar(
    title: LibraryTitle,
    season: Int,
    onPick: (Int) -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seasons = title.seasons
    val watched = title.watchedIn(season)
    val count = title.countIn(season)
    Column(modifier) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (seasons.size > 1) {
            PillChips(
                options = seasons,
                selected = season,
                onSelect = onPick,
                label = ::seasonName,
                scrollable = true,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(seasonName(season), style = MaterialTheme.typography.titleMedium)
            Text(
                if (watched > 0) "$watched of $count watched" else episodesLabel(count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "${seasonName(season)} options")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    val allWatched = watched >= count
                    DropdownMenuItem(
                        text = { Text(if (allWatched) "Mark season as unwatched" else "Mark season as watched") },
                        onClick = {
                            open = false
                            onSetWatched(!allWatched)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete ${seasonName(season).lowercase()}") },
                        onClick = {
                            open = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One downloaded video: an episode of a show, or one version of a movie. A tap plays it.
 * [illustrated] rows lead with a still from the episode and end with what it is about.
 */
@Composable
private fun VideoRow(
    video: LibraryEpisode,
    number: Int?,
    name: String,
    highlighted: Boolean,
    illustrated: Boolean,
    deleteLabel: String,
    onPlay: (from: Offset?) -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onOpenElsewhere: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val file = fileName(video.path)
    // What the row leads with (still or number), which the player opens out of.
    val leadSpot = rememberSpot()
    Surface(
        onClick = { onPlay(leadSpot.center) },
        // What plays next stands out, so the place in a season is found at a glance.
        color = if (highlighted) glassFill() else Color.Transparent,
        border = if (highlighted) glassRim() else null,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        val edge = if (illustrated) 8.dp else 6.dp
        Column(Modifier.padding(start = 12.dp, top = edge, bottom = edge)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (illustrated) {
                    EpisodeStill(
                        video = video,
                        number = number,
                        upNext = highlighted,
                        modifier = Modifier.width(StillWidth).spot(leadSpot),
                    )
                } else {
                    AnimatedContent(
                        targetState = video.watched,
                        transitionSpec = { IconSwap },
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.width(32.dp).spot(leadSpot),
                        label = "watched",
                    ) { watched ->
                        when {
                            watched -> Icon(Icons.Default.Check, contentDescription = "Watched", tint = colors.primary)
                            number != null -> Text("$number", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                            else -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = colors.onSurfaceVariant)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        // The number leads the name where no column of its own shows it: beside a
                        // still, and on a watched episode (whose number the check mark replaced).
                        if ((illustrated || video.watched) && number != null) "$number. $name" else name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (video.watched) colors.onSurfaceVariant else colors.onSurface,
                        fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            video.remainingMs?.let(::formatTimeLeft),
                            resolutionTag(file).takeIf { number != null },
                            formatBytes(video.bytes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (video.inProgress) colors.primary else colors.onSurfaceVariant,
                    )
                    // On an illustrated row the still carries the progress.
                    video.progress?.takeUnless { illustrated }?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp),
                        )
                    }
                }
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options for $name", tint = colors.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text(if (video.watched) "Mark as unwatched" else "Mark as watched") },
                            onClick = {
                                open = false
                                onSetWatched(!video.watched)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open with another app") },
                            onClick = {
                                open = false
                                onOpenElsewhere()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(deleteLabel) },
                            onClick = {
                                open = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            if (illustrated && video.overview.isNotBlank()) {
                Text(
                    video.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp),
                )
            }
        }
    }
}

private val StillWidth = 124.dp

/**
 * A still from the episode with, on top of it, how far it was watched. Without a still (none
 * exists, or it was not fetched yet) the episode's number stands in.
 */
@Composable
private fun EpisodeStill(video: LibraryEpisode, number: Int?, upNext: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (video.still.isNotBlank()) {
            RemoteImage(url = video.still, modifier = Modifier.fillMaxSize())
        } else if (number != null) {
            Text("$number", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
        }
        if (upNext) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.padding(5.dp).size(20.dp))
            }
        }
        AnimatedVisibility(
            visible = video.watched,
            enter = PopIn,
            exit = PopOut,
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
        ) {
            Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                Icon(Icons.Default.Check, contentDescription = "Watched", modifier = Modifier.padding(2.dp).size(13.dp))
            }
        }
        video.progress?.let { WatchBar(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** One finished download: where its files are and which subtitles it has. */
@Composable
private fun FilesCard(job: JobItem, onSubtitles: ((tryAnother: Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = glassFill(prominent = false),
        border = glassRim(prominent = false),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                job.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val size = listOfNotNull(
                job.files.size.takeIf { it > 1 }?.let { "$it files" },
                job.finalBytes.takeIf { it > 0 }?.let(::formatBytes),
            ).joinToString(" · ")
            if (job.outputDir.isNotBlank()) {
                DetailLine(Icons.Outlined.Folder, listOf("Download/${fileName(job.outputDir)}", size).filter { it.isNotBlank() }.joinToString(" · "))
            }
            SubtitleLine(job)
            if (job.embeddedSubtitles.isNotEmpty()) {
                DetailLine(Icons.Outlined.ClosedCaption, "In the file: ${job.embeddedSubtitles.joinToString(", ")}")
            }
            if (onSubtitles != null && job.subtitleStatus != "searching") {
                val haveSome = job.subtitleStatus == "found" || job.subtitleStatus == "partial"
                TextButton(
                    onClick = { onSubtitles(haveSome) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    // Its label lines up with the text above instead of sitting indented.
                    modifier = Modifier.offset(x = (-8).dp),
                ) {
                    Text(if (haveSome) "Try other subtitles" else "Get subtitles")
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Everything else that can be done with a title. */
@Composable
private fun TitleMenu(
    title: LibraryTitle,
    scrim: Boolean,
    onSetWatched: (Boolean) -> Unit,
    onOpenElsewhere: (() -> Unit)?,
    onFindMore: (() -> Unit)?,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ScrimIconButton(Icons.Default.MoreVert, "More options", scrim = scrim, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun entry(label: String, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
            val everything = if (title.isSeries) "all " else ""
            entry(if (title.allWatched) "Mark ${everything}as unwatched" else "Mark ${everything}as watched") {
                onSetWatched(!title.allWatched)
            }
            if (onOpenElsewhere != null) entry("Open with another app", onOpenElsewhere)
            if (onFindMore != null) entry(if (title.isSeries) "Get more episodes" else "Look for other versions", onFindMore)
            entry("Remove from library", onRemove)
            entry("Delete from device", onDelete)
        }
    }
}
