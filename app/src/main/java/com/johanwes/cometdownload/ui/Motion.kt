package com.johanwes.cometdownload.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's motion in one place. Nothing here checks Android's "remove animations" setting:
 * Compose scales every animation by it already, so with animations off these all jump to
 * their end.
 */
object Motion {
    /** For what moves or resizes: quick, settles without overshoot. */
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 380f)

    /** For small things that arrive: one slight overshoot. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
}

/** A detail page sliding in over its list ([forward]), or back out to it. */
fun detailTransition(forward: Boolean): ContentTransform =
    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())

/** A small icon or mark arriving. */
val PopIn: EnterTransition = scaleIn(Motion.bouncy(), initialScale = 0.5f) + fadeIn(tween(120))

/** ... and leaving. */
val PopOut: ExitTransition = scaleOut(tween(90), targetScale = 0.5f) + fadeOut(tween(90))

/** One status icon taking the place of another. */
val IconSwap: ContentTransform = ContentTransform(PopIn, PopOut, sizeTransform = SizeTransform(clip = false))

/** A progress value that glides to where it was last reported instead of jumping there. */
@Composable
fun glide(progress: Float): () -> Float {
    val shown by animateFloatAsState(progress, Motion.spatial(), label = "progress")
    return { shown }
}

// ---------------------------------------------------------------------------
// Poster: list -> title page
// ---------------------------------------------------------------------------

/** Set where a list and its detail page swap inside a shared-transition layout. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedContentScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val PosterBounds = BoundsTransform { _, _ -> Motion.spatial() }

/**
 * A list and the detail page that opens from it, swapping in place. Posters marked with
 * [sharedPoster] travel between the two.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T> DetailSwitch(
    state: T,
    isDetail: (T) -> Boolean,
    label: String,
    content: @Composable (T) -> Unit,
) {
    SharedTransitionLayout {
        AnimatedContent(
            targetState = state,
            contentKey = isDetail,
            transitionSpec = { detailTransition(forward = isDetail(targetState)) },
            label = label,
        ) { shown ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalAnimatedContentScope provides this@AnimatedContent,
            ) { content(shown) }
        }
    }
}

/**
 * Makes a poster travel between the list it was tapped in and the title page: the two ends
 * carry the same [key]. Does nothing where there is no such transition (two panes side by
 * side) or no key.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPoster(key: Any?): Modifier {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalAnimatedContentScope.current
    if (key == null || shared == null || animated == null) return this
    return with(shared) {
        this@sharedPoster.sharedElement(
            rememberSharedContentState(key),
            animatedVisibilityScope = animated,
            boundsTransform = PosterBounds,
        )
    }
}

// ---------------------------------------------------------------------------
// Where things are
// ---------------------------------------------------------------------------

/** The middle of something on screen (in root coordinates), for an effect that starts there. */
class Spot {
    var center: Offset? = null
        internal set
}

@Composable
fun rememberSpot(): Spot = remember { Spot() }

fun Modifier.spot(spot: Spot): Modifier = onGloballyPositioned { spot.center = it.boundsInRoot().center }

// ---------------------------------------------------------------------------
// Scrolling rows
// ---------------------------------------------------------------------------

/** Fades a horizontally scrolling row out at the side(s) it can still scroll towards. */
fun Modifier.fadingEdges(scroll: ScrollState, width: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = width.toPx()
        // Grows in over the first stretch scrolled, so it never pops up.
        val start = (scroll.value / edge).coerceIn(0f, 1f)
        val end = ((scroll.maxValue - scroll.value) / edge).coerceIn(0f, 1f)
        if (start > 0f) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - start), Color.Black), 0f, edge),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (end > 0f) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - end)), size.width - edge, size.width),
                topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
