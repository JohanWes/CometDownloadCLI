package com.johanwes.cometdownload.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation

/**
 * A title page's artwork as atmosphere: its poster, blurred until only the colours are left
 * and dimmed towards [paneColor], fixed behind the page. The top stays plain down to
 * [plainTop], where the page's bar sits once it has turned solid.
 */
@Composable
fun ArtworkWash(url: String, paneColor: Color, plainTop: Dp, modifier: Modifier = Modifier) {
    if (url.isBlank()) return
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            // Tiny on purpose: it is blurred once, here, and then only stretched. That costs
            // nothing while the page scrolls and looks the same on every Android version.
            .size(WASH_WIDTH * 2, WASH_HEIGHT * 2)
            .transformations(WashBlur)
            .crossfade(400)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.High,
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val plain = (plainTop.toPx() / size.height).coerceIn(0f, 0.5f)
                val settled = ((plainTop + WashFadeIn).toPx() / size.height).coerceIn(plain, 0.9f)
                drawRect(
                    Brush.verticalGradient(
                        0f to paneColor,
                        plain to paneColor,
                        settled to paneColor.copy(alpha = WASH_DIM),
                        1f to paneColor.copy(alpha = WASH_DIM_LOW),
                    ),
                )
            },
    )
}

private const val WASH_WIDTH = 40
private const val WASH_HEIGHT = 60
/** How much of the page colour lies over the artwork: it tints the page, it is not a picture. */
private const val WASH_DIM = 0.74f
private const val WASH_DIM_LOW = 0.84f
private val WashFadeIn = 180.dp

/** Scales a poster down to a few dozen pixels and blurs those into soft patches of colour. */
private object WashBlur : Transformation {
    override val cacheKey = "wash-blur-${WASH_WIDTH}x$WASH_HEIGHT"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val small = Bitmap.createScaledBitmap(input, WASH_WIDTH, WASH_HEIGHT, true)
        val pixels = IntArray(WASH_WIDTH * WASH_HEIGHT)
        small.getPixels(pixels, 0, WASH_WIDTH, 0, 0, WASH_WIDTH, WASH_HEIGHT)
        val scratch = IntArray(pixels.size)
        // Three box blurs come close to a Gaussian one.
        repeat(3) {
            boxBlur(pixels, scratch, WASH_WIDTH, WASH_HEIGHT, horizontal = true)
            boxBlur(scratch, pixels, WASH_WIDTH, WASH_HEIGHT, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, WASH_WIDTH, WASH_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(from: IntArray, to: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val radius = 4
        val length = if (horizontal) width else height
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                val along = if (horizontal) x else y
                for (step in -radius..radius) {
                    // The edge pixel repeats beyond the edge, so borders don't darken.
                    val at = (along + step).coerceIn(0, length - 1)
                    val pixel = if (horizontal) from[y * width + at] else from[at * width + x]
                    red += pixel shr 16 and 0xFF
                    green += pixel shr 8 and 0xFF
                    blue += pixel and 0xFF
                }
                val count = radius * 2 + 1
                to[y * width + x] = (0xFF shl 24) or (red / count shl 16) or (green / count shl 8) or (blue / count)
            }
        }
    }
}

/** IMDb's brand yellow, used only for the rating star. */
val RatingStar = Color(0xFFF5C518)

/** 2:3 poster with a type icon underneath, which shows while loading or when art is missing. */
@Composable
fun Poster(
    url: String,
    isSeries: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isSeries) Icons.Outlined.Tv else Icons.Outlined.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(iconSize),
        )
        if (url.isNotBlank()) {
            RemoteImage(url = url, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
    )
}

/** "★ 8.4" — nothing at all when the rating is unknown. */
@Composable
fun Rating(
    rating: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    starSize: Dp = 16.dp,
    suffix: String? = null,
) {
    if (rating.isBlank()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Rounded.Star, contentDescription = "IMDb rating", tint = RatingStar, modifier = Modifier.size(starSize))
        Text(rating, style = style, fontWeight = FontWeight.SemiBold)
        if (suffix != null) {
            Text(suffix, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
