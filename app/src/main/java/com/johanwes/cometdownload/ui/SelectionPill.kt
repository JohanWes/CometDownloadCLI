package com.johanwes.cometdownload.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.Tab
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Draws the highlight of what is selected behind this element's content, at [target] (in the
 * element's own coordinates), and lets it glide there when [selected] changes. The edge that
 * leads the way moves faster than the one that follows, so the pill stretches a little as it goes.
 */
@Composable
fun Modifier.selectionPill(selected: Any?, target: Rect?, color: Color): Modifier {
    val pill = remember { PillState() }
    LaunchedEffect(selected, target) {
        if (target != null) pill.moveTo(selected, target)
    }
    return drawBehind {
        val rect = pill.rect ?: return@drawBehind
        drawRoundRect(color, rect.topLeft, rect.size, CornerRadius(minOf(rect.width, rect.height) / 2))
    }
}

private class PillState {
    private val left = Animatable(0f)
    private val top = Animatable(0f)
    private val right = Animatable(0f)
    private val bottom = Animatable(0f)
    private var selected: Any? = null
    private var placed by mutableStateOf(false)

    val rect: Rect? get() = if (placed) Rect(left.value, top.value, right.value, bottom.value) else null

    suspend fun moveTo(selected: Any?, target: Rect) {
        // The first time, and when the same thing is merely somewhere else (a rotation, a
        // resize), the pill is simply there.
        val jump = !placed || selected == this.selected
        this.selected = selected
        if (jump) {
            left.snapTo(target.left)
            top.snapTo(target.top)
            right.snapTo(target.right)
            bottom.snapTo(target.bottom)
            placed = true
            return
        }
        coroutineScope {
            launch { left.animateTo(target.left, edge(leads = target.left < left.value)) }
            launch { right.animateTo(target.right, edge(leads = target.right > right.value)) }
            launch { top.animateTo(target.top, edge(leads = target.top < top.value)) }
            launch { bottom.animateTo(target.bottom, edge(leads = target.bottom > bottom.value)) }
        }
    }
}

private fun edge(leads: Boolean): SpringSpec<Float> =
    spring(dampingRatio = 0.82f, stiffness = if (leads) 620f else 330f)

/**
 * The pill behind the selected tab of the bottom bar or the rail: put on the bar itself, it
 * follows the tab icons (see [tabAnchor]) around.
 */
@Composable
fun Modifier.tabPill(tab: Tab, size: DpSize, color: Color): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val icon = LocalAnchors.current[tabAnchor(tab)]
    val target = icon?.let {
        val extent = with(LocalDensity.current) { Size(size.width.toPx(), size.height.toPx()) }
        Rect(it.center - origin - Offset(extent.width / 2, extent.height / 2), extent)
    }
    return onGloballyPositioned { origin = it.positionInRoot() }.selectionPill(tab, target, color)
}

/**
 * A row of chips of which one is selected, its highlight gliding to the chip that is picked.
 * A [scrollable] row may be wider than the screen: it opens with the selected chip in view
 * and fades out towards what is scrolled out of sight.
 */
@Composable
fun <T> PillChips(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Where each chip is, within the row.
    val bounds = remember { mutableStateMapOf<T, Rect>() }
    val scroll = rememberScrollState()
    // A chip is laid out taller than it looks, to be easy to hit; the pill is as tall as it looks.
    val chipHeight = with(LocalDensity.current) { FilterChipDefaults.Height.toPx() }
    val target = bounds[selected]?.let {
        Rect(it.left, it.center.y - chipHeight / 2, it.right, it.center.y + chipHeight / 2)
    }

    if (scrollable) {
        // Opens on the selected chip, with the one before it still in view.
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(target != null) {
            if (target == null || opened) return@LaunchedEffect
            opened = true
            val before = options.getOrNull(options.indexOf(selected) - 1)
            scroll.scrollTo(((bounds[before] ?: target).left).roundToInt())
        }
    }

    Row(
        modifier
            .then(if (scrollable) Modifier.fadingEdges(scroll).horizontalScroll(scroll) else Modifier)
            .padding(contentPadding)
            .selectionPill(selected, target, MaterialTheme.colorScheme.secondaryContainer),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            key(option) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    // The pill is the selected chip's fill.
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.Transparent),
                    modifier = Modifier.onPlaced { coordinates ->
                        val placed = coordinates.boundsInParent()
                        if (bounds[option] != placed) bounds[option] = placed
                    },
                )
            }
        }
    }
}
