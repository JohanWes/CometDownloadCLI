package com.johanwes.cometdownload.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import com.johanwes.cometdownload.releaseTags
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.CometViewModel
import com.johanwes.cometdownload.Episode
import com.johanwes.cometdownload.EpisodeScope
import com.johanwes.cometdownload.Load
import com.johanwes.cometdownload.MediaDetails
import com.johanwes.cometdownload.MediaItem
import com.johanwes.cometdownload.JobItem
import com.johanwes.cometdownload.JobState
import com.johanwes.cometdownload.LibraryTitle
import com.johanwes.cometdownload.episodesLabel
import com.johanwes.cometdownload.StreamItem
import com.johanwes.cometdownload.StreamState
import com.johanwes.cometdownload.formatBytes
import com.johanwes.cometdownload.formatRuntime

internal val TopBarHeight = 64.dp

/** How far the poster reaches up into the backdrop. */
private val PosterRise = 104.dp

/** What a title page's top bar shows at the current scroll position. */
internal data class TopBarState(
    /** Filled with the page colour instead of floating on the artwork. */
    val solid: Boolean,
    /** Showing the title, the header's own having scrolled away. */
    val titled: Boolean,
)

/**
 * The bar turns solid once the poster reaches it (it would otherwise slide under the buttons
 * and the status bar), and shows the title once the backdrop has scrolled away beneath it.
 */
@Composable
internal fun rememberTopBarState(listState: LazyListState, backdropHeight: Dp, statusTop: Dp): TopBarState {
    val titledAt = with(LocalDensity.current) { (backdropHeight - TopBarHeight - statusTop).toPx() }
    val solidAt = titledAt - with(LocalDensity.current) { PosterRise.toPx() }
    val state by remember(listState, titledAt, solidAt) {
        derivedStateOf {
            val past = listState.firstVisibleItemIndex > 0
            val offset = listState.firstVisibleItemScrollOffset
            TopBarState(solid = past || offset > solidAt, titled = past || offset > titledAt)
        }
    }
    return state
}

/**
 * One scrolling page per title: backdrop header with poster, rating and plot, then the
 * season/episode picker for series, then the stream list.
 */
@Composable
fun TitleScreen(viewModel: CometViewModel, media: MediaItem, embedded: Boolean, paneColor: Color) {
    val listState = rememberLazyListState()
    // Resolution groups the user expanded with "Show more"; reset for every new stream list.
    var expandedGroups by remember(viewModel.streams) { mutableStateOf(emptySet<String>()) }
    val details = (viewModel.details as? Load.Ready)?.value
    val statusTop = if (embedded) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdropHeight = (if (embedded) 200.dp else 230.dp) + statusTop
    val bar = rememberTopBarState(listState, backdropHeight, statusTop)
    val inLibrary = viewModel.libraryTitle(media)

    Box(Modifier.fillMaxSize()) {
        ArtworkWash(
            url = media.poster.ifBlank { details?.poster.orEmpty() },
            paneColor = paneColor,
            plainTop = statusTop + TopBarHeight,
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                TitleHeader(
                    media = media,
                    details = details,
                    backdropHeight = backdropHeight,
                    posterKey = searchPosterKey(media),
                )
            }
            if (inLibrary != null) {
                item(key = "in-library") {
                    InLibraryRow(
                        title = inLibrary,
                        onOpen = { viewModel.showInLibrary(inLibrary) },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
            if (media.isSeries) {
                item(key = "episodes") {
                    EpisodeSection(
                        details = viewModel.details,
                        scope = viewModel.scope,
                        onChange = viewModel::changeScope,
                        episodeJob = viewModel::episodeJob,
                    )
                }
            }
            streamItems(viewModel, media, expandedGroups, onExpand = { expandedGroups = expandedGroups + it })
            item(key = "footer") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }

        TitleTopBar(
            title = media.title,
            bar = bar,
            embedded = embedded,
            paneColor = paneColor,
            onBack = if (embedded) null else ({ viewModel.select(null) }),
        ) { scrim ->
            ScrimIconButton(
                Icons.Default.Refresh,
                "Reload streams",
                scrim = scrim,
                enabled = viewModel.streams != Load.Loading,
                onClick = viewModel::refresh,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/** Backdrop with the poster, title, year, genres and rating over it, and the plot below. */
@Composable
internal fun TitleHeader(
    media: MediaItem,
    details: MediaDetails?,
    backdropHeight: Dp,
    poster: String = media.poster.ifBlank { details?.poster.orEmpty() },
    /** Names the poster where it travels here from the list the page was opened in. */
    posterKey: Any? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth()) {
            val background = details?.background.orEmpty()
            // Darken the top for the bar icons, and let the bottom dissolve into the page: it is
            // faded out rather than painted over, because the page behind it is not one colour.
            val shade = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.3f to Color.Transparent)
            val dissolve = Brush.verticalGradient(
                0.3f to Color.Black,
                0.55f to Color.Black.copy(alpha = 0.5f),
                1f to Color.Transparent,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(backdropHeight)
                    // Crop scaling overflows the box on wide panes; keep it inside.
                    .clipToBounds()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(shade)
                        drawRect(dissolve, blendMode = BlendMode.DstIn)
                    },
            ) {
                if (background.isNotBlank()) {
                    // Bias the crop upwards: faces sit in the upper half of most backdrops.
                    RemoteImage(url = background, alignment = BiasAlignment(0f, -0.4f), modifier = Modifier.fillMaxSize())
                }
            }
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = backdropHeight - PosterRise),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Poster(
                    url = poster,
                    isSeries = media.isSeries,
                    iconSize = 36.dp,
                    modifier = Modifier
                        .width(112.dp)
                        .sharedPoster(posterKey)
                        .shadow(12.dp, MaterialTheme.shapes.small),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        media.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        headerMeta(media, details),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    val genres = details?.genres?.takeIf { it.isNotEmpty() } ?: media.genres
                    if (genres.isNotEmpty()) {
                        Text(
                            genres.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Rating(
                        rating = details?.rating?.ifBlank { null } ?: media.rating,
                        suffix = "IMDb",
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        val description = details?.description.orEmpty()
        if (description.isNotBlank()) {
            Plot(description, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        }
    }
}

private fun headerMeta(media: MediaItem, details: MediaDetails?): String {
    val year = details?.yearLabel?.ifBlank { null } ?: media.year.ifBlank { null }
    val extent = if (media.isSeries) {
        val seasons = details?.seasons?.size?.takeIf { it > 0 } ?: media.seasonCount
        seasons.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
    } else {
        formatRuntime(details?.runtimeMinutes?.takeIf { it > 0 } ?: media.runtimeMinutes)
    }
    return listOfNotNull(year, media.typeLabel, extent).joinToString(" · ")
}

/** Plot clamped to three lines; tapping toggles the full text. */
@Composable
private fun Plot(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(
        modifier
            .animateContentSize()
            .clickable(enabled = overflows || expanded) { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The bar over the backdrop; [actions] get told whether they sit on artwork (and need a scrim). */
@Composable
internal fun TitleTopBar(
    title: String,
    bar: TopBarState,
    embedded: Boolean,
    paneColor: Color,
    onBack: (() -> Unit)?,
    actions: @Composable (scrim: Boolean) -> Unit,
) {
    val barColor by animateColorAsState(if (bar.solid) paneColor else paneColor.copy(alpha = 0f), label = "bar")
    val titleAlpha by animateFloatAsState(if (bar.titled) 1f else 0f, label = "title")
    Surface(color = barColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(if (embedded) Modifier else Modifier.statusBarsPadding())
                .height(TopBarHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                ScrimIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", scrim = !bar.solid, onClick = onBack)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .graphicsLayer { alpha = titleAlpha },
            )
            actions(!bar.solid)
        }
    }
}

/** Icon button with a translucent dark disc, so it stays legible on top of artwork. */
@Composable
internal fun ScrimIconButton(
    icon: ImageVector,
    description: String,
    scrim: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val container by animateColorAsState(
        if (scrim) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0f),
        label = "scrim",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = container,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** Says that (some of) this title is already downloaded, and leads to it in the library. */
@Composable
private fun InLibraryRow(title: LibraryTitle, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        color = glassFill(),
        border = glassRim(),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.DownloadDone, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Text(
                if (title.isSeries) "${episodesLabel(title.episodeCount)} in your library" else "In your library",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

// ---------------------------------------------------------------------------
// Season / episode
// ---------------------------------------------------------------------------

@Composable
private fun EpisodeSection(
    details: Load<MediaDetails>,
    scope: EpisodeScope,
    onChange: (EpisodeScope, Long) -> Unit,
    episodeJob: (Int, Int) -> JobItem?,
) {
    val ready = (details as? Load.Ready)?.value
    when {
        ready != null && ready.episodes.isNotEmpty() -> SeasonEpisodePicker(ready, scope, onChange, episodeJob)
        // Details are usually cached from the search, so this only flashes briefly.
        details == Load.Loading -> Spacer(Modifier.height(8.dp))
        // No episode list available: fall back to plain number steppers.
        else -> EpisodeStepper(
            scope = scope,
            onChange = { onChange(it, 600) },
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
        )
    }
}

@Composable
private fun SeasonEpisodePicker(
    details: MediaDetails,
    scope: EpisodeScope,
    onChange: (EpisodeScope, Long) -> Unit,
    episodeJob: (Int, Int) -> JobItem?,
) {
    val seasons = details.seasons
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val episodes = details.episodesIn(scope.season)
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (seasons.size > 1) {
            PillChips(
                options = seasons,
                selected = scope.season,
                onSelect = { onChange(EpisodeScope(season = it, episode = 0), 0) },
                label = { "Season $it" },
                scrollable = true,
                contentPadding = PaddingValues(horizontal = 16.dp),
            )
        }
        EpisodeField(
            label = if (seasons.size > 1) "Episode" else "Season ${scope.season} · Episode",
            value = episodeLabel(episodes, scope.episode),
            onClick = { sheetOpen = true },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        )
    }
    if (sheetOpen) {
        EpisodeSheet(
            season = scope.season,
            episodes = episodes,
            selected = scope.episode,
            episodeJob = episodeJob,
            onSelect = {
                sheetOpen = false
                onChange(scope.copy(episode = it), 0)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

private fun episodeLabel(episodes: List<Episode>, selected: Int): String {
    val current = episodes.firstOrNull { it.episode == selected }
    return when {
        selected == 0 -> "All episodes · season pack"
        current != null -> "${current.episode}. ${current.title.ifBlank { "Episode ${current.episode}" }}"
        else -> "Episode $selected"
    }
}

/** Looks like an outlined text field; opens the episode sheet. */
@Composable
private fun EpisodeField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, colors.outline),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose episode", tint = colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSheet(
    season: Int,
    episodes: List<Episode>,
    selected: Int,
    episodeJob: (Int, Int) -> JobItem?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Row 0 is the season pack, so episode N sits at index N (or close to it).
    val selectedIndex = if (selected == 0) 0 else episodes.indexOfFirst { it.episode == selected } + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = colors.surfaceContainerLow,
    ) {
        Text(
            "Season $season",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "pack") {
                EpisodeRow(
                    number = null,
                    title = "All episodes",
                    subtitle = "Season $season pack · ${episodes.size} episodes",
                    selected = selected == 0,
                    enabled = true,
                    job = episodeJob(season, 0),
                    onClick = { onSelect(0) },
                )
            }
            items(episodes, key = { it.episode }) { episode ->
                EpisodeRow(
                    number = episode.episode,
                    title = episode.title.ifBlank { "Episode ${episode.episode}" },
                    subtitle = when {
                        !episode.aired -> "Airs ${episode.released}"
                        episode.released.isNotBlank() -> episode.released
                        else -> null
                    },
                    selected = selected == episode.episode,
                    enabled = episode.aired,
                    job = episodeJob(season, episode.episode),
                    onClick = { onSelect(episode.episode) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    number: Int?,
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    job: JobItem?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (number != null) {
                    Text("$number", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                } else {
                    Icon(Icons.Outlined.VideoLibrary, contentDescription = null, tint = colors.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            when {
                job == null -> Unit
                job.state == JobState.Completed -> Icon(Icons.Default.DownloadDone, contentDescription = "Downloaded", tint = colors.primary)
                job.isActive -> Icon(Icons.Outlined.Downloading, contentDescription = "Downloading", tint = colors.primary)
            }
        }
    }
}

@Composable
private fun EpisodeStepper(scope: EpisodeScope, onChange: (EpisodeScope) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = glassFill(), border = glassRim(), shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stepper(
                    label = "Season",
                    value = scope.season.toString(),
                    canDecrement = scope.season > 1,
                    onDecrement = { onChange(scope.copy(season = scope.season - 1)) },
                    onIncrement = { onChange(scope.copy(season = scope.season + 1)) },
                )
                Stepper(
                    label = "Episode",
                    value = if (scope.episode == 0) "All" else scope.episode.toString(),
                    canDecrement = scope.episode > 0,
                    onDecrement = { onChange(scope.copy(episode = scope.episode - 1)) },
                    onIncrement = { onChange(scope.copy(episode = scope.episode + 1)) },
                )
            }
            Text(
                if (scope.episode == 0) {
                    "Season %d · full-season packs".format(scope.season)
                } else {
                    "S%02dE%02d · single episode".format(scope.season, scope.episode)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Streams
// ---------------------------------------------------------------------------

private const val STREAMS_SHOWN = 3

// [media] is the page's own title rather than viewModel.selected, which is already null
// while the page slides out on back; the stream list should stay put until it's gone.
private fun LazyListScope.streamItems(
    viewModel: CometViewModel,
    media: MediaItem,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
) {
    // For a season pack, an approximate per-episode size helps compare against single episodes.
    val room = viewModel.downloadRoom
    val packEpisodes = if (media.isSeries && viewModel.scope.episode == 0) {
        (viewModel.details as? Load.Ready)?.value?.episodesIn(viewModel.scope.season)?.count { it.aired } ?: 0
    } else {
        0
    }
    when (val streams = viewModel.streams) {
        Load.Idle -> Unit
        Load.Loading -> item(key = "streams-loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(
                        "Finding streams…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is Load.Failed -> item(key = "streams-error") {
            MessageState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Couldn't load streams",
                body = streams.message,
                tint = MaterialTheme.colorScheme.error,
                actionLabel = "Try again",
                onAction = { viewModel.loadStreams() },
            )
        }
        is Load.Ready -> if (streams.value.isEmpty()) {
            item(key = "streams-empty") {
                val scope = viewModel.scope
                when {
                    !media.isSeries -> MessageState(
                        icon = Icons.Default.SearchOff,
                        title = "No streams found",
                        body = "Nothing is available for this title right now. New releases often show up within a few days.",
                        actionLabel = "Search again",
                        onAction = { viewModel.loadStreams() },
                    )
                    scope.episode > 0 -> MessageState(
                        icon = Icons.Default.SearchOff,
                        title = "No streams for episode ${scope.episode}",
                        body = "Single episodes are often only available inside a season pack.",
                        actionLabel = "Look for a season ${scope.season} pack",
                        onAction = { viewModel.changeScope(scope.copy(episode = 0)) },
                    )
                    else -> MessageState(
                        icon = Icons.Default.SearchOff,
                        title = "No season ${scope.season} packs",
                        body = "Pick a single episode above. Seasons that are still airing rarely have packs.",
                        actionLabel = "Try episode 1",
                        onAction = { viewModel.changeScope(scope.copy(episode = 1)) },
                    )
                }
            }
        } else {
            streams.value.groupBy { it.resolutionLabel }.forEach { (resolution, group) ->
                item(key = "header-$resolution") {
                    Row(
                        modifier = Modifier.animateItem().padding(start = 20.dp, end = 16.dp, top = 12.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(resolution, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${group.size} ${if (group.size == 1) "option" else "options"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                val shown = if (resolution in expanded) group else group.take(STREAMS_SHOWN)
                items(shown, key = { it.key }) { stream ->
                    StreamRow(
                        stream = stream,
                        state = viewModel.streamState(stream),
                        fits = room == null || stream.sizeBytes <= room,
                        packEpisodes = packEpisodes,
                        providerLabel = viewModel.settings.provider.label,
                        onQueue = { viewModel.enqueue(stream) },
                        onShowDownloads = viewModel::showDownloads,
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp),
                    )
                }
                if (group.size > shown.size) {
                    item(key = "more-$resolution") {
                        TextButton(
                            onClick = { onExpand(resolution) },
                            modifier = Modifier.animateItem().padding(start = 8.dp),
                        ) { Text("Show ${group.size - shown.size} more $resolution") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamRow(
    stream: StreamItem,
    state: StreamState,
    fits: Boolean,
    packEpisodes: Int,
    providerLabel: String,
    onQueue: () -> Unit,
    onShowDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val job = (state as? StreamState.Linked)?.job
    val tags = remember(stream.description) { releaseTags(stream.description) }
    // Said before the tap rather than after it; a download that is already running has its space.
    val tooBig = !fits && state == StreamState.Ready
    // Uncached streams are a gamble (the debrid service has to fetch them first), so they
    // sit visually behind the cached ones. So does what there is no room for.
    val prominent = stream.cached && !tooBig
    val sizeColor = if (prominent) colors.onSurface else colors.onSurfaceVariant
    // Once the download is really in the list, a dot flies from the button to the Library tab.
    val flight = LocalDownloadFlight.current
    val actionSpot = rememberSpot()
    var wasQueueing by remember { mutableStateOf(false) }
    val queueing = state == StreamState.Queueing
    val active = job?.isActive == true
    LaunchedEffect(queueing, active) {
        if (queueing) {
            wasQueueing = true
        } else {
            if (wasQueueing && active) actionSpot.center?.let(flight::launch)
            wasQueueing = false
        }
    }
    Surface(
        color = glassFill(prominent),
        border = glassRim(prominent),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatBytes(stream.sizeBytes), style = MaterialTheme.typography.titleMedium, color = sizeColor)
                        if (packEpisodes > 1) {
                            Text(
                                "≈ ${formatBytes(stream.sizeBytes / packEpisodes)}/ep",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (stream.cached) {
                            Tag("Cached", colors.primaryContainer, colors.onPrimaryContainer, Icons.Default.Bolt)
                        } else {
                            Tag(
                                "Not cached",
                                Color.Transparent,
                                colors.onSurfaceVariant,
                                Icons.Outlined.CloudOff,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                            )
                        }
                        if (stream.subtitleHint) {
                            // From the release name: the file itself carries English subtitles.
                            Tag("EN subs", colors.secondaryContainer, colors.onSecondaryContainer, Icons.Default.Subtitles)
                        }
                    }
                    if (tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            tags.forEach { Tag(it, colors.surfaceContainerHighest, colors.onSurfaceVariant) }
                        }
                    }
                    if (stream.description.isNotBlank()) {
                        Text(
                            stream.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = if (tags.isEmpty()) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (job != null) JobStatusText(job)
                    if (tooBig) {
                        Text("Not enough free space", style = MaterialTheme.typography.labelMedium, color = colors.error)
                    }
                }
                Box(Modifier.size(48.dp).spot(actionSpot), contentAlignment = Alignment.Center) {
                    StreamAction(stream, state, tooBig, providerLabel, onQueue, onShowDownloads)
                }
            }
            val progress = job?.progress
            if (job != null && job.isActive) {
                if (progress != null && job.state == JobState.Downloading) {
                    LinearProgressIndicator(
                        progress = glide(progress),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                } else {
                    LinearProgressIndicator(gapSize = 0.dp, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
    }
}

@Composable
private fun JobStatusText(job: JobItem) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (job.state) {
        JobState.Queued -> "Queued" to colors.primary
        JobState.Resolving -> (if (job.isWaiting) job.phase else "Preparing…") to colors.primary
        JobState.Downloading -> {
            val percent = job.progress?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
            "Downloading$percent" to colors.primary
        }
        JobState.Completed -> "Downloaded" to colors.primary
        JobState.Failed -> "Failed: ${job.errorText.ifBlank { "unknown error" }}" to colors.error
        JobState.Cancelled -> "Cancelled" to colors.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** What the button at the end of a stream row is, whatever the job behind it says in detail. */
private enum class StreamActionKind { Download, TooBig, Request, Queueing, Retry, Waiting, Done, Downloading }

@Composable
private fun StreamAction(
    stream: StreamItem,
    state: StreamState,
    tooBig: Boolean,
    providerLabel: String,
    onQueue: () -> Unit,
    onShowDownloads: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val kind = when (state) {
        StreamState.Ready -> when {
            tooBig -> StreamActionKind.TooBig
            stream.cached -> StreamActionKind.Download
            else -> StreamActionKind.Request
        }
        StreamState.Queueing -> StreamActionKind.Queueing
        is StreamState.Linked -> when {
            state.job.canRetry -> StreamActionKind.Retry
            state.job.isWaiting -> StreamActionKind.Waiting
            state.job.state == JobState.Completed -> StreamActionKind.Done
            else -> StreamActionKind.Downloading
        }
    }
    AnimatedContent(
        targetState = kind,
        transitionSpec = { IconSwap },
        contentAlignment = Alignment.Center,
        label = "stream-action",
    ) { shown ->
        when (shown) {
            // Still a button: tapping it says how much is needed and how much is free.
            StreamActionKind.TooBig -> IconButton(onClick = onQueue) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download (not enough free space)",
                    tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            StreamActionKind.Download -> FilledTonalIconButton(onClick = onQueue) {
                Icon(Icons.Default.Download, contentDescription = "Download")
            }
            StreamActionKind.Request -> OutlinedIconButton(onClick = onQueue) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = "Request from $providerLabel")
            }
            StreamActionKind.Queueing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            StreamActionKind.Retry -> FilledTonalIconButton(onClick = onQueue) {
                Icon(Icons.Default.Refresh, contentDescription = "Try again")
            }
            StreamActionKind.Waiting -> IconButton(onClick = onShowDownloads) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = "Waiting for $providerLabel, show downloads", tint = colors.primary)
            }
            StreamActionKind.Done -> IconButton(onClick = onShowDownloads) {
                Icon(Icons.Default.DownloadDone, contentDescription = "Downloaded, show downloads", tint = colors.primary)
            }
            StreamActionKind.Downloading -> IconButton(onClick = onShowDownloads) {
                Icon(Icons.Outlined.Downloading, contentDescription = "Downloading, show downloads", tint = colors.primary)
            }
        }
    }
}
