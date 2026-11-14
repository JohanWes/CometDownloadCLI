package com.johanwes.cometdownload.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.Load
import com.johanwes.cometdownload.OnlineSubtitle
import com.johanwes.cometdownload.player.PlayerViewModel
import com.johanwes.cometdownload.player.SubtitleSelection
import com.johanwes.cometdownload.ui.Stepper
import com.johanwes.cometdownload.ui.Tag
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Slides a panel in over the video: from the right in landscape (the picture stays visible
 * beside it), from the bottom in portrait. Tapping outside closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PanelHost(panel: Panel?, onDismiss: () -> Unit, content: @Composable (Panel) -> Unit) {
    // Keeps the content in place while the panel animates out.
    var last by remember { mutableStateOf(panel) }
    if (panel != null) last = panel

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (panel != null) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            )
        }
        val insets = WindowInsets.displayCutout.union(WindowInsets.systemBarsIgnoringVisibility)
        AnimatedVisibility(
            visible = panel != null,
            enter = fadeIn() + if (landscape) slideInHorizontally { it / 3 } else slideInVertically { it / 3 },
            exit = fadeOut() + if (landscape) slideOutHorizontally { it / 3 } else slideOutVertically { it / 3 },
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = if (landscape) {
                    RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                } else {
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                },
                modifier = if (landscape) {
                    Modifier.fillMaxHeight().width((maxWidth * 0.46f).coerceIn(300.dp, 420.dp))
                } else {
                    Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.62f)
                },
            ) {
                Box(
                    Modifier.windowInsetsPadding(
                        insets.only(if (landscape) WindowInsetsSides.End + WindowInsetsSides.Vertical else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
                ) {
                    last?.let { content(it) }
                }
            }
        }
    }
}

@Composable
fun SubtitlesPanel(viewModel: PlayerViewModel) {
    LaunchedEffect(viewModel.current) { viewModel.ensureOnlineSearch() }
    LaunchedEffect(viewModel.subtitleNotice) {
        if (viewModel.subtitleNotice != null) {
            delay(NOTICE_MS)
            viewModel.dismissSubtitleNotice()
        }
    }
    val selection = viewModel.selection
    val list = rememberLazyListState()
    val adjustable = selection != SubtitleSelection.Off
    // The delay and size row is added above the first row; a list resting at its top would
    // otherwise keep its place and leave the new row scrolled out of sight.
    LaunchedEffect(adjustable) {
        if (adjustable && list.firstVisibleItemIndex <= 2) list.scrollToItem(0)
    }

    PanelList(title = "Subtitles", state = list) {
        viewModel.subtitleNotice?.let { notice ->
            item(key = "notice") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (adjustable) {
            item(key = "adjust") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Stepper(
                        label = "Delay",
                        value = "%+.1f s".format(viewModel.subtitleDelayMs / 1000f),
                        onDecrement = { viewModel.changeSubtitleDelay(viewModel.subtitleDelayMs - DELAY_STEP_MS) },
                        onIncrement = { viewModel.changeSubtitleDelay(viewModel.subtitleDelayMs + DELAY_STEP_MS) },
                        canDecrement = true,
                    )
                    Stepper(
                        label = "Size",
                        value = "${(viewModel.subtitleScale * 100).roundToInt()}%",
                        onDecrement = { viewModel.changeSubtitleScale(viewModel.subtitleScale - SCALE_STEP) },
                        onIncrement = { viewModel.changeSubtitleScale(viewModel.subtitleScale + SCALE_STEP) },
                        canDecrement = viewModel.subtitleScale > PlayerViewModel.MIN_SUBTITLE_SCALE + 0.01f,
                    )
                }
            }
        }

        item(key = "off") {
            OptionRow(title = "Off", selected = selection == SubtitleSelection.Off) {
                viewModel.selectSubtitle(SubtitleSelection.Off)
            }
        }

        if (viewModel.textOptions.isNotEmpty()) {
            item(key = "embedded") { SectionLabel("In the file") }
            itemsIndexed(viewModel.textOptions, key = { index, _ -> "track-$index" }) { index, option ->
                OptionRow(
                    title = option.title,
                    detail = if (option.supported) option.detail else "Not supported",
                    selected = selection == SubtitleSelection.Embedded(index),
                    enabled = option.supported,
                ) { viewModel.selectSubtitle(SubtitleSelection.Embedded(index)) }
            }
        }

        if (viewModel.sidecars.isNotEmpty()) {
            item(key = "files") { SectionLabel("Downloaded") }
            items(viewModel.sidecars, key = { "file-${it.path}" }) { sidecar ->
                OptionRow(
                    title = sidecar.label,
                    selected = selection == SubtitleSelection.File(sidecar.path),
                ) { viewModel.selectSubtitle(SubtitleSelection.File(sidecar.path)) }
            }
        }

        item(key = "online") { SectionLabel("More from OpenSubtitles") }
        if (viewModel.languages.isNotEmpty()) {
            item(key = "languages") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "") {
                        FilterChip(
                            selected = viewModel.searchLanguage.isBlank(),
                            onClick = { viewModel.searchOnline("") },
                            label = { Text("Preferred") },
                        )
                    }
                    items(viewModel.languages, key = { it.code }) { language ->
                        FilterChip(
                            selected = viewModel.searchLanguage == language.code,
                            onClick = { viewModel.searchOnline(language.code) },
                            label = { Text(language.name) },
                        )
                    }
                }
            }
        }
        when (val online = viewModel.online) {
            Load.Idle -> Unit
            Load.Loading -> item(key = "searching") {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Searching…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is Load.Failed -> item(key = "failed") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(online.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.searchOnline(viewModel.searchLanguage) }) { Text("Try again") }
                }
            }
            is Load.Ready -> if (online.value.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing found for this video.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(online.value, key = { "online-${it.fileId}" }) { subtitle ->
                    OnlineRow(
                        subtitle = subtitle,
                        selected = subtitle.path.isNotBlank() && selection == SubtitleSelection.File(subtitle.path),
                        busy = viewModel.downloading == subtitle.fileId,
                        enabled = viewModel.downloading == null,
                        onClick = { viewModel.useOnline(subtitle) },
                    )
                }
            }
        }
        if (viewModel.quotaRemaining >= 0) {
            item(key = "quota") {
                Text(
                    "${viewModel.quotaRemaining} downloads left today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun OnlineRow(subtitle: OnlineSubtitle, selected: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                subtitle.release.ifBlank { subtitle.languageName },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(subtitle.languageName, colors.surfaceContainerHighest, colors.onSurfaceVariant)
                if (subtitle.hashMatch) Tag("Made for this file", colors.primaryContainer, colors.onPrimaryContainer)
                if (subtitle.hearingImpaired) Tag("SDH", colors.surfaceContainerHighest, colors.onSurfaceVariant)
                if (subtitle.machineTranslated) Tag("Machine translated", colors.surfaceContainerHighest, colors.onSurfaceVariant)
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            selected -> Icon(Icons.Filled.Check, contentDescription = "Showing", tint = colors.primary, modifier = Modifier.size(22.dp))
            subtitle.path.isNotBlank() -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Downloaded", tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            else -> Icon(Icons.Outlined.Download, contentDescription = "Download", tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun AudioPanel(viewModel: PlayerViewModel) {
    PanelList(title = "Audio") {
        itemsIndexed(viewModel.audioOptions, key = { index, _ -> index }) { _, option ->
            OptionRow(
                title = option.title,
                detail = if (option.supported) option.detail else listOf(option.detail, "Not supported").filter { it.isNotBlank() }.joinToString(" · "),
                selected = option.selected,
                enabled = option.supported,
            ) { viewModel.selectAudio(option) }
        }
    }
}

@Composable
fun ChaptersPanel(viewModel: PlayerViewModel, onPicked: () -> Unit) {
    val chapters = viewModel.chapters
    val position = remember { viewModel.player.currentPosition }
    val current = chapters.indexOfLast { it.startMs <= position }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    PanelList(title = "Chapters", state = state) {
        itemsIndexed(chapters, key = { index, _ -> index }) { index, chapter ->
            OptionRow(
                title = chapter.title,
                trailing = formatTime(chapter.startMs),
                selected = index == current,
            ) {
                viewModel.seekTo(chapter.startMs)
                onPicked()
            }
        }
    }
}

@Composable
fun SpeedPanel(viewModel: PlayerViewModel) {
    PanelList(title = "Speed") {
        items(SPEEDS, key = { it }) { speed ->
            OptionRow(
                title = if (speed == 1f) "Normal" else "${speed.toString().removeSuffix(".0")}×",
                selected = viewModel.speed == speed,
            ) { viewModel.changeSpeed(speed) }
        }
    }
}

@Composable
private fun PanelList(
    title: String,
    state: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 12.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** One selectable line: a check mark when chosen, a title, and optional detail. */
@Composable
private fun OptionRow(
    title: String,
    selected: Boolean,
    detail: String = "",
    trailing: String = "",
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.4f)
        selected -> colors.primary
        else -> colors.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.onSurfaceVariant else content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing.isNotBlank()) {
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val DELAY_STEP_MS = 100L
private const val SCALE_STEP = 0.1f
private const val NOTICE_MS = 4000L
