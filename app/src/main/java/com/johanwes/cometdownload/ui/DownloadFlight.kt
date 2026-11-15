package com.johanwes.cometdownload.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.johanwes.cometdownload.Tab

/** Where the fixed things on screen are (in root coordinates), for what travels to them. */
@Stable
class Anchors {
    private val rects = mutableStateMapOf<String, Rect>()

    operator fun get(name: String): Rect? = rects[name]

    internal fun put(name: String, rect: Rect) {
        if (rects[name] != rect) rects[name] = rect
    }
}

val LocalAnchors = staticCompositionLocalOf { Anchors() }

fun Modifier.anchor(anchors: Anchors, name: String): Modifier =
    onGloballyPositioned { anchors.put(name, it.boundsInRoot()) }

/** The icon of [tab] in the bottom bar or the rail, whichever is showing. */
fun tabAnchor(tab: Tab): String = "tab-${tab.name}"

/**
 * A download that was just queued, shown as a dot that flies from its stream row to the
 * Library tab: that is where it went.
 */
@Stable
class DownloadFlight {
    internal val dots = mutableStateListOf<Dot>()
    private var nextId = 0
    private var lastLaunchAt = 0L

    /** Counts the dots that arrived; the Library badge answers each one. */
    var landings by mutableIntStateOf(0)
        private set

    val inFlight: Boolean get() = dots.isNotEmpty()

    /** [from] is where the dot sets off, in root coordinates. */
    fun launch(from: Offset) {
        lastLaunchAt = SystemClock.uptimeMillis()
        dots += Dot(nextId++, from)
    }

    fun launchedWithin(millis: Long): Boolean = SystemClock.uptimeMillis() - lastLaunchAt < millis

    internal fun landed(dot: Dot) {
        if (dots.remove(dot)) landings++
    }
}

internal class Dot(val id: Int, val from: Offset)

val LocalDownloadFlight = staticCompositionLocalOf { DownloadFlight() }

private const val FLIGHT_MS = 520

/** Sets off gently and is at full speed when it lands. */
private val FlightEasing = CubicBezierEasing(0.45f, 0f, 0.85f, 1f)

/** Draws the dots on their way; goes on top of everything else. */
@Composable
fun DownloadFlightOverlay(flight: DownloadFlight, target: Rect?, modifier: Modifier = Modifier) {
    if (flight.dots.isEmpty()) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        flight.dots.forEach { dot ->
            key(dot.id) {
                FlyingDot(dot = dot, to = target?.center, origin = origin, color = color, onLanded = { flight.landed(dot) })
            }
        }
    }
}

@Composable
private fun FlyingDot(dot: Dot, to: Offset?, origin: Offset, color: Color, onLanded: () -> Unit) {
    val progress = remember { Animatable(0f) }
    // Where the tab was when the dot set off. Without one there is nowhere to fly to.
    val target = remember { to }
    LaunchedEffect(Unit) {
        if (target != null) progress.animateTo(1f, tween(FLIGHT_MS, easing = FlightEasing))
        onLanded()
    }
    if (target == null) return
    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value
        val from = dot.from - origin
        val end = target - origin
        // A shallow arc: up and across first, then down onto the tab.
        val control = Offset((from.x + end.x) / 2, minOf(from.y, end.y) - 56.dp.toPx())
        val u = 1f - t
        val position = from * (u * u) + control * (2 * u * t) + end * (t * t)
        val radius = lerp(7.dp.toPx(), 3.5.dp.toPx(), t)
        drawCircle(color.copy(alpha = 0.22f), radius * 2.2f, position)
        drawCircle(color, radius, position)
    }
}
