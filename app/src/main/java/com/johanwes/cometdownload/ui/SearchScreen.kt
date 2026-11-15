package com.johanwes.cometdownload.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.InputChip
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.CometViewModel
import com.johanwes.cometdownload.Load
import com.johanwes.cometdownload.MediaItem

@Composable
fun SearchScreen(
    viewModel: CometViewModel,
    twoPane: Boolean,
    showSettingsAction: Boolean,
    resultsListState: LazyListState,
) {
    if (twoPane) {
        // The list takes about 40% of the width, within sensible bounds.
        val listWidth = (LocalConfiguration.current.screenWidthDp * 0.4f).dp.coerceIn(340.dp, 460.dp)
        Row(Modifier.fillMaxSize()) {
            ResultsPane(
                viewModel = viewModel,
                showSettingsAction = showSettingsAction,
                listState = resultsListState,
                showChevrons = false,
                modifier = Modifier
                    .width(listWidth)
                    .fillMaxHeight(),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(top = 8.dp, end = 16.dp, bottom = 16.dp),
            ) {
                val media = viewModel.selected
                if (media == null) {
                    MessageState(
                        icon = Icons.Outlined.VideoLibrary,
                        title = "Pick a title",
                        body = "Select a search result to see details and available streams.",
                    )
                } else {
                    // Fresh scroll state per title.
                    key(media.imdbId) {
                        TitleScreen(
                            viewModel = viewModel,
                            media = media,
                            embedded = true,
                            paneColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                    }
                }
            }
        }
    } else {
        DetailSwitch(state = viewModel.selected, isDetail = { it != null }, label = "search-detail") { media ->
            if (media == null) {
                ResultsPane(
                    viewModel = viewModel,
                    showSettingsAction = showSettingsAction,
                    listState = resultsListState,
                    showChevrons = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TitleScreen(
                    viewModel = viewModel,
                    media = media,
                    embedded = false,
                    paneColor = MaterialTheme.colorScheme.background,
                )
            }
        }
    }
}

@Composable
private fun ResultsPane(
    viewModel: CometViewModel,
    showSettingsAction: Boolean,
    listState: LazyListState,
    showChevrons: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        SearchField(
            query = viewModel.query,
            onQueryChange = { viewModel.query = it },
            onClear = viewModel::clearSearch,
            onSearch = viewModel::search,
            showSettingsAction = showSettingsAction,
            onSettings = viewModel::openSettings,
            // Focus the field on the first open only, not every time the tab comes back.
            autoFocus = viewModel.results == Load.Idle && !viewModel.settingsOpen && viewModel.consumeAutoFocus(),
        )
        when (val results = viewModel.results) {
            Load.Idle -> if (viewModel.recentSearches.isEmpty()) {
                MessageState(
                    icon = Icons.Default.Search,
                    title = "Find something to download",
                    body = "Search for a movie or series by title.",
                )
            } else {
                RecentSearches(
                    searches = viewModel.recentSearches,
                    onPick = {
                        focusManager.clearFocus()
                        viewModel.searchFor(it)
                    },
                    onRemove = viewModel::forgetSearch,
                )
            }
            Load.Loading -> LoadingState("Searching…")
            is Load.Failed -> MessageState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Search failed",
                body = results.message,
                tint = MaterialTheme.colorScheme.error,
                actionLabel = "Try again",
                onAction = viewModel::search,
            )
            is Load.Ready -> if (results.value.isEmpty()) {
                MessageState(
                    icon = Icons.Default.SearchOff,
                    title = "No matches",
                    body = "Nothing found for “${viewModel.lastQuery}”. Check the spelling or try the original title.",
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") {
                        val count = results.value.size
                        Text(
                            "$count ${if (count == 1) "result" else "results"} for “${viewModel.lastQuery}”",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                    items(results.value, key = { it.imdbId + it.isSeries }) { media ->
                        MediaRow(
                            media = media,
                            inLibrary = media.imdbId in viewModel.libraryIds,
                            selected = !showChevrons && media == viewModel.selected,
                            showChevron = showChevrons,
                            onClick = { viewModel.select(media) },
                        )
                    }
                    item(key = "footer") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    showSettingsAction: Boolean,
    onSettings: () -> Unit,
    autoFocus: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 16.dp, end = if (showSettingsAction) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search movies & series", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        onClear()
                        runCatching { focusRequester.requestFocus() }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.clearFocus()
                onSearch()
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
        if (showSettingsAction) SettingsAction(onSettings)
    }
}

/** Names a result's poster, in the list and on its page, so it can travel between the two. */
internal fun searchPosterKey(media: MediaItem): String = "search-${media.imdbId}-${media.isSeries}"

@Composable
private fun MediaRow(media: MediaItem, inLibrary: Boolean, selected: Boolean, showChevron: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Poster(
                url = media.poster,
                isSeries = media.isSeries,
                modifier = Modifier.width(56.dp).sharedPoster(searchPosterKey(media)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    media.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    media.meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (media.rating.isNotBlank() || media.genres.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Rating(media.rating, style = MaterialTheme.typography.labelMedium, starSize = 14.dp)
                        if (media.genres.isNotEmpty()) {
                            Text(
                                media.genres.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (inLibrary) {
                    Tag(
                        "In library",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        Icons.Default.DownloadDone,
                    )
                }
            }
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(searches: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Recent searches",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            searches.forEach { text ->
                InputChip(
                    selected = false,
                    onClick = { onPick(text) },
                    label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $text",
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onRemove(text) },
                        )
                    },
                )
            }
        }
    }
}
