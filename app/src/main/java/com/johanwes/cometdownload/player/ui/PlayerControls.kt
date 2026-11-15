package com.johanwes.cometdownload.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.player.ChapterMark
import com.johanwes.cometdownload.player.PlayerViewModel
import com.johanwes.cometdownload.player.SEEK_STEP_MS
import kotlinx.coroutines.delay

/** The overlay with transport buttons, the seek bar and the track buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerControls(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
    onPanel: (Panel) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = viewModel.player
    val duration = viewModel.durationMs
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var buffered by remember { mutableLongStateOf(player.bufferedPosition) }
    // Position under the finger while the seek bar is dragged; null otherwise.
    var scrubTo by remember { mutableStateOf<Long?>(null) }

    // Only ticks while the controls are on screen, so normal playback redraws nothing here.
    LaunchedEffect(player) {
        while (true) {
            if (scrubTo == null) position = player.currentPosition
            buffered = player.bufferedPosition
            delay(POSITION_TICK_MS)
        }
    }

    val shown = scrubTo ?: position
    val chapter = viewModel.chapters.lastOrNull { it.startMs <= shown }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.7f),
                        0.3f to Color.Black.copy(alpha = 0.25f),
                        0.6f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.8f),
                    ),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Clear of camera cut-outs and of where the system bars sit when swiped in.
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBarsIgnoringVisibility))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                TitleBar(
                    title = viewModel.title.ifBlank { viewModel.current?.fileName.orEmpty() },
                    subtitle = listOfNotNull(viewModel.current?.label?.ifBlank { null }, chapter?.title).joinToString(" · "),
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.TopStart),
                )

                // Hidden while loading: the spinner sits in the same spot.
                if (!viewModel.isBuffering || viewModel.ended) {
                    TransportRow(
                        viewModel = viewModel,
                        onInteraction = onInteraction,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeText(shown)
                        SeekBar(
                            positionMs = shown,
                            bufferedMs = buffered,
                            durationMs = duration,
                            chapters = viewModel.chapters,
                            onScrubStart = { viewModel.setScrubbing(true) },
                            onScrub = { target ->
                                scrubTo = target
                                viewModel.seekTo(target)
                                onInteraction()
                            },
                            onScrubEnd = {
                                viewModel.setScrubbing(false)
                                scrubTo?.let { position = it }
                                scrubTo = null
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        TimeText(duration)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        ControlButton(Icons.Outlined.Subtitles, "Subtitles") { onPanel(Panel.Subtitles) }
                        if (viewModel.audioOptions.size > 1) {
                            ControlButton(Icons.Outlined.Audiotrack, "Audio track") { onPanel(Panel.Audio) }
                        }
                        if (viewModel.chapters.isNotEmpty()) {
                            ControlButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "Chapters") { onPanel(Panel.Chapters) }
                        }
                        ControlButton(
                            Icons.Outlined.Speed,
                            "Playback speed",
                            tint = if (viewModel.speed != 1f) MaterialTheme.colorScheme.primary else Color.White,
                        ) { onPanel(Panel.Speed) }
                        ControlButton(
                            if (viewModel.zoomed) Icons.Outlined.ZoomInMap else Icons.Outlined.ZoomOutMap,
                            if (viewModel.zoomed) "Fit to screen" else "Fill the screen",
                        ) {
                            viewModel.zoomed = !viewModel.zoomed
                            onInteraction()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Only the name of what is playing, over a light shade at the top: shown for a moment when a
 * video starts, so the picture isn't covered by the controls and a new episode says which it is.
 * The text sits where the controls show it, so bringing those up doesn't move it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleCard(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBarsIgnoringVisibility))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .padding(bottom = 56.dp),
        ) {
            TitleBar(
                title = viewModel.title.ifBlank { viewModel.current?.fileName.orEmpty() },
                subtitle = viewModel.current?.label.orEmpty(),
                onClose = null,
            )
        }
    }
}

/** The title and, below it, which episode (and chapter) this is. Without [onClose] no back arrow. */
@Composable
private fun TitleBar(title: String, subtitle: String, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        Column(Modifier.padding(start = 4.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TransportRow(viewModel: PlayerViewModel, onInteraction: () -> Unit, modifier: Modifier = Modifier) {
    val season = viewModel.items.size > 1
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (season) {
            ControlButton(Icons.Filled.SkipPrevious, "Previous episode", enabled = viewModel.hasPrevious) {
                viewModel.playItem(viewModel.index - 1)
                onInteraction()
            }
        }
        ControlButton(Icons.Filled.Replay10, "Back 10 seconds", size = 36) {
            viewModel.seekBy(-SEEK_STEP_MS)
            onInteraction()
        }
        Surface(
            onClick = {
                viewModel.togglePlay()
                onInteraction()
            },
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
        ) {
            Icon(
                when {
                    viewModel.ended -> Icons.Filled.Replay
                    viewModel.isPlaying -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                modifier = Modifier.padding(14.dp).size(44.dp),
            )
        }
        ControlButton(Icons.Filled.Forward10, "Forward 10 seconds", size = 36) {
            viewModel.seekBy(SEEK_STEP_MS)
            onInteraction()
        }
        if (season) {
            ControlButton(Icons.Filled.SkipNext, "Next episode", enabled = viewModel.hasNext) {
                viewModel.playItem(viewModel.index + 1)
                onInteraction()
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    size: Int = 24,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size((size + 24).dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(size.dp),
        )
    }
}

@Composable
private fun TimeText(ms: Long) {
    Text(
        formatTime(ms),
        // Fixed-width digits, so the seek bar doesn't jiggle as the time changes.
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}

/**
 * The seek bar: played and buffered parts, small gaps where chapters start, and a thumb that can
 * be dragged or tapped anywhere along the bar.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    chapters: List<ChapterMark>,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val start by rememberUpdatedState(onScrubStart)
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    var dragging by remember { mutableStateOf(false) }

    Canvas(
        modifier
            .height(40.dp)
            .semantics { contentDescription = "Seek bar, ${formatTime(positionMs)} of ${formatTime(durationMs)}" }
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                fun target(x: Float) = ((x / size.width).coerceIn(0f, 1f) * durationMs).toLong()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    start()
                    scrub(target(down.position.x))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        scrub(target(change.position.x))
                    }
                    dragging = false
                    end()
                }
            },
    ) {
        val thickness = (if (dragging) 6.dp else 4.dp).toPx()
        val top = (size.height - thickness) / 2f
        val radius = CornerRadius(thickness / 2f)
        fun fraction(ms: Long) = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, top), Size(size.width, thickness), radius)
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, top), Size(size.width * fraction(bufferedMs), thickness), radius)
        val played = size.width * fraction(positionMs)
        drawRoundRect(accent, Offset(0f, top), Size(played, thickness), radius)

        // Chapter starts: a notch cut out of the bar.
        val notch = 2.dp.toPx()
        chapters.forEach { chapter ->
            if (chapter.startMs <= 0 || chapter.startMs >= durationMs) return@forEach
            val x = size.width * fraction(chapter.startMs)
            drawRect(Color.Black.copy(alpha = 0.75f), Offset(x - notch / 2f, top), Size(notch, thickness))
        }

        drawCircle(accent, radius = (if (dragging) 9.dp else 6.dp).toPx(), center = Offset(played, size.height / 2f))
    }
}

/** "1:02:33" or "4:07". */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private const val POSITION_TICK_MS = 250L
