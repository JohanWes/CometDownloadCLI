package com.johanwes.cometdownload.player.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.johanwes.cometdownload.player.PlayerViewModel
import com.johanwes.cometdownload.player.SEEK_STEP_MS
import com.johanwes.cometdownload.player.openInOtherApp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Panel { Subtitles, Audio, Chapters, Speed }

/** Brightness or volume while it is being dragged, or the temporary 2x speed. */
private data class Feedback(
    val icon: ImageVector,
    val label: String,
    val level: Float? = null,
    /** Stays up until cleared (the 2x speed lasts as long as the finger is down). */
    val held: Boolean = false,
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    // A video starts with the picture, not with the controls over it; see the title card below.
    var controlsVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    // The name of what is playing, shown alone for a moment when a video starts.
    var titleCard by remember { mutableStateOf(false) }
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped on every touch of the controls, which restarts the auto-hide countdown.
    var interactions by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    // Seconds skipped by double taps in a row (negative = backwards), shown on that side.
    var skipped by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(controlsVisible, viewModel.isPlaying, panel, interactions) {
        if (controlsVisible && viewModel.isPlaying && panel == null) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(viewModel.ended, viewModel.error) {
        if (viewModel.ended || viewModel.error != null) controlsVisible = true
    }
    // At the start, and again each time the next episode begins.
    LaunchedEffect(viewModel.index) {
        titleCard = true
        delay(TITLE_CARD_MS)
        titleCard = false
    }
    // Nothing left to play: back to the page the video was started from, which now offers
    // what comes next.
    LaunchedEffect(viewModel.finished) {
        if (viewModel.finished) onClose()
    }
    // Also when paused from outside (headset button, a call): show where playback stopped.
    LaunchedEffect(viewModel.isPlaying) {
        if (!viewModel.isPlaying) controlsVisible = true
    }
    LaunchedEffect(skipped) {
        if (skipped != 0) {
            delay(SKIP_FEEDBACK_MS)
            skipped = 0
        }
    }
    LaunchedEffect(feedback) {
        if (feedback?.held == false) {
            delay(FEEDBACK_MS)
            feedback = null
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(enabled = panel != null) { panel = null }

    fun skip(direction: Int) {
        viewModel.seekBy(direction * SEEK_STEP_MS)
        val step = (SEEK_STEP_MS / 1000).toInt() * direction
        skipped = if (skipped * direction > 0) skipped + step else step
    }

    // While subtitles are being adjusted they have to stay in view: beside the panel in
    // landscape (the text moves over), above it in portrait (the picture moves up with it).
    val adjusting = panel == Panel.Subtitles
    val landscape = screenSize.width > screenSize.height
    val (besidePanel, abovePanel) = with(LocalDensity.current) {
        when {
            !adjusting -> 0.dp to 0.dp
            landscape -> panelSize.width.toDp() to 0.dp
            else -> 0.dp to panelSize.height.toDp()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { screenSize = it }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.K, Key.MediaPlayPause -> viewModel.togglePlay()
                    Key.DirectionLeft, Key.J -> skip(-1)
                    Key.DirectionRight, Key.L -> skip(1)
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        Box(Modifier.fillMaxSize().padding(bottom = abovePanel)) {
            ContentFrame(
                player = viewModel.player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = if (viewModel.zoomed) ContentScale.Crop else ContentScale.Fit,
                keepContentOnReset = true,
            )

            SubtitleLayer(
                cues = viewModel.cues,
                videoAspect = viewModel.videoAspect,
                scale = viewModel.subtitleScale,
                raised = controlsVisible && panel == null,
                covered = besidePanel,
            )
        }

        GestureLayer(
            onTap = {
                if (panel != null) panel = null else controlsVisible = !controlsVisible
            },
            onDoubleTap = { zone -> if (zone == 0) viewModel.togglePlay() else skip(zone) },
            onBoost = { active ->
                viewModel.setBoost(active)
                feedback = if (active) Feedback(Icons.Outlined.FastForward, "2× speed", held = true) else null
            },
            onZoom = { viewModel.zoomed = it },
            onLevel = { feedback = it },
        )

        SkipFeedback(skipped, Modifier.fillMaxSize())

        feedback?.let { FeedbackPill(it, Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) }

        if (viewModel.isBuffering && viewModel.error == null) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(44.dp),
            )
        }

        AnimatedVisibility(
            visible = titleCard && !controlsVisible && panel == null && viewModel.error == null,
            enter = fadeIn(tween(TITLE_CARD_FADE_MS)),
            exit = fadeOut(tween(TITLE_CARD_FADE_MS)),
        ) {
            TitleCard(viewModel)
        }

        AnimatedVisibility(
            visible = controlsVisible && panel == null && viewModel.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                viewModel = viewModel,
                onClose = onClose,
                onPanel = { panel = it },
                onInteraction = { interactions++ },
            )
        }

        viewModel.error?.let { message ->
            ErrorOverlay(
                message = message,
                onRetry = viewModel::retry,
                onOpenElsewhere = {
                    val path = viewModel.current?.path ?: return@ErrorOverlay
                    if (openInOtherApp(context, path) == null) onClose()
                },
                onClose = onClose,
            )
        }

        PanelHost(panel = panel, onDismiss = { panel = null }, onSize = { panelSize = it }) { open ->
            when (open) {
                Panel.Subtitles -> SubtitlesPanel(viewModel)
                Panel.Audio -> AudioPanel(viewModel)
                Panel.Chapters -> ChaptersPanel(viewModel, onPicked = { panel = null })
                Panel.Speed -> SpeedPanel(viewModel)
            }
        }
    }
}

/**
 * Subtitles over the picture. Picture-based ones (PGS, styled ASS) are drawn for the exact video
 * area; plain text gets at least a 16:9 box, so it keeps a readable size on very wide films.
 *
 * [covered] is how much of the right side a panel hides. The subtitles move into what is left:
 * text keeps its size and wraps sooner, pictures get smaller as a whole.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SubtitleLayer(cues: List<Cue>, videoAspect: Float, scale: Float, raised: Boolean, covered: Dp) {
    val pictures = cues.any { it.bitmap != null }
    val aspect = (if (pictures) videoAspect else minOf(videoAspect, 16f / 9f)).coerceIn(0.2f, 5f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The box as it fits the screen, then narrowed to the part that is in view.
        val fitWidth = minOf(maxWidth, maxHeight * aspect)
        val inView = (maxWidth - covered).coerceAtLeast(0.dp)
        val width = minOf(fitWidth, inView)
        val height = if (pictures) width / aspect else fitWidth / aspect
        Box(Modifier.width(inView).fillMaxHeight(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    SubtitleView(context).apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                Typeface.create("sans-serif-medium", Typeface.NORMAL),
                            ),
                        )
                    }
                },
                update = { view ->
                    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
                    // Clear of the seek bar while the controls are up.
                    view.setBottomPaddingFraction(if (raised) RAISED_SUBTITLE_PADDING else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
                    view.setCues(cues)
                },
                modifier = Modifier.size(width, height),
            )
        }
    }
}

/**
 * Touch handling on the picture: tap, double tap (skip / play-pause), hold (2x), vertical
 * drags (brightness on the left half, volume on the right) and pinch (fit / fill).
 */
@Composable
private fun GestureLayer(
    onTap: () -> Unit,
    onDoubleTap: (zone: Int) -> Unit,
    onBoost: (Boolean) -> Unit,
    onZoom: (Boolean) -> Unit,
    onLevel: (Feedback) -> Unit,
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val boost by rememberUpdatedState(onBoost)
    val zoom by rememberUpdatedState(onZoom)
    val level by rememberUpdatedState(onLevel)
    var brightness by remember { mutableFloatStateOf(-1f) }
    var volume by remember { mutableFloatStateOf(-1f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var boosting = false
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { offset ->
                        val third = size.width / 3f
                        doubleTap(
                            when {
                                offset.x < third -> -1
                                offset.x > third * 2 -> 1
                                else -> 0
                            },
                        )
                    },
                    onLongPress = {
                        boosting = true
                        boost(true)
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (boosting) {
                            boosting = false
                            boost(false)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val edge = EDGE_GUARD_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Swipes from the screen edges belong to the system (status bar, home).
                    val fromEdge = down.position.y < edge || down.position.y > size.height - edge
                    val leftHalf = down.position.x < size.width / 2f
                    var dragging = false
                    var pinching = false
                    var ignored = fromEdge
                    var pinch = 1f
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && !dragging) {
                            pinching = true
                            pinch *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (pinching || ignored) continue
                        val change = pressed.first()
                        val delta = change.positionChange()
                        if (!dragging) {
                            dx += delta.x
                            dy += delta.y
                            if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) {
                                ignored = true
                            } else if (abs(dy) > viewConfiguration.touchSlop) {
                                dragging = true
                                if (leftHalf) {
                                    if (brightness < 0f) brightness = currentBrightness(context)
                                } else {
                                    volume = currentVolume(context)
                                }
                            }
                            continue
                        }
                        change.consume()
                        // A full-height drag covers a bit more than the whole range.
                        val step = -delta.y / size.height * 1.2f
                        if (leftHalf) {
                            brightness = (brightness + step).coerceIn(0.01f, 1f)
                            setBrightness(context, brightness)
                            level(Feedback(Icons.Outlined.BrightnessMedium, "${(brightness * 100).roundToInt()}%", brightness))
                        } else {
                            volume = (volume + step).coerceIn(0f, 1f)
                            setVolume(context, volume)
                            level(Feedback(Icons.AutoMirrored.Outlined.VolumeUp, "${(volume * 100).roundToInt()}%", volume))
                        }
                    }
                    if (pinching) {
                        if (pinch > 1.15f) zoom(true) else if (pinch < 0.87f) zoom(false)
                    }
                }
            },
    )
}

private fun currentBrightness(context: Context): Float {
    val window = (context as? Activity)?.window?.attributes?.screenBrightness ?: -1f
    if (window >= 0f) return window
    // Following the system setting so far: start from (roughly) where that is.
    val system = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (system / 255f).coerceIn(0.05f, 1f)
}

private fun setBrightness(context: Context, value: Float) {
    val window = (context as? Activity)?.window ?: return
    window.attributes = window.attributes.apply { screenBrightness = value }
}

private fun currentVolume(context: Context): Float {
    val audio = context.getSystemService(AudioManager::class.java)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun setVolume(context: Context, value: Float) {
    val audio = context.getSystemService(AudioManager::class.java)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val target = (value * max).roundToInt()
    if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }
}

@Composable
private fun FeedbackPill(feedback: Feedback, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, shape = CircleShape, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(feedback.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            if (feedback.level != null) {
                LinearProgressIndicator(
                    progress = { feedback.level },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.width(120.dp),
                )
            }
            Text(feedback.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "−20 s" / "+10 s" on the side that was double-tapped. */
@Composable
private fun SkipFeedback(seconds: Int, modifier: Modifier = Modifier) {
    if (seconds == 0) return
    Box(modifier) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(if (seconds < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 56.dp),
        ) {
            Text(
                if (seconds < 0) "−${-seconds} s" else "+$seconds s",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onOpenElsewhere: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
            Text(message, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                TextButton(onClick = onOpenElsewhere) { Text("Open in another app") }
                FilledTonalButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

private const val CONTROLS_TIMEOUT_MS = 3500L
private const val TITLE_CARD_MS = 4000L
private const val TITLE_CARD_FADE_MS = 500
private const val SKIP_FEEDBACK_MS = 700L
private const val FEEDBACK_MS = 900L
private const val RAISED_SUBTITLE_PADDING = 0.3f
private const val EDGE_GUARD_DP = 40
