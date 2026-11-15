package com.johanwes.cometdownload.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max

/**
 * The way into the player: from the control that was tapped, its colour spreads over the
 * screen and darkens to black as it goes, and the player (which is black until its picture
 * is there) opens on that. Black meets black, also where the player turns the screen around.
 */
@Stable
class PlayFlood {
    internal var origin by mutableStateOf<Offset?>(null)
        private set
    private var whenCovered: (() -> Unit)? = null

    /**
     * Floods the screen from [from] (root coordinates) and then calls [open]. A second tap
     * while the first is still spreading is ignored.
     */
    fun launch(from: Offset, open: () -> Unit) {
        if (origin != null) return
        whenCovered = open
        origin = from
    }

    internal fun covered() {
        whenCovered?.invoke()
        whenCovered = null
    }

    /** Takes the flood away without opening anything (if it has not opened yet). */
    fun clear() {
        whenCovered = null
        origin = null
    }
}

val LocalPlayFlood = staticCompositionLocalOf { PlayFlood() }

private const val FLOOD_MS = 300
private const val FLOOD_GIVE_UP_MS = 3000L
private val FloodEasing = CubicBezierEasing(0.3f, 0f, 0.3f, 1f)

/** Draws the flood; goes on top of everything else. */
@Composable
fun PlayFloodOverlay(flood: PlayFlood, modifier: Modifier = Modifier) {
    // The player is in front now: the app is found as it was left when coming back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flood.clear() }
    val from = flood.origin ?: return

    val progress = remember(from) { Animatable(0f) }
    var corner by remember { mutableStateOf(Offset.Zero) }
    val start = MaterialTheme.colorScheme.primary
    LaunchedEffect(from) {
        progress.animateTo(1f, tween(FLOOD_MS, easing = FloodEasing))
        flood.covered()
        // Should the player not take over after all, the app must not stay black.
        delay(FLOOD_GIVE_UP_MS)
        flood.clear()
    }
    BackHandler { flood.clear() }

    Canvas(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { corner = it.positionInRoot() }
            // Nothing underneath is to be tapped meanwhile.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val center = from - corner
        val reach = hypot(max(center.x, size.width - center.x), max(center.y, size.height - center.y))
        val spread = progress.value
        drawCircle(
            // Black well before it fills the screen: the colour is where it comes from, not a flash.
            color = lerp(start, Color.Black, (spread / 0.55f).coerceAtMost(1f)),
            radius = lerp(24.dp.toPx(), reach, spread),
            center = center,
        )
    }
}
