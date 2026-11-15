package com.johanwes.cometdownload.player.ui

import android.graphics.Typeface
import android.text.Layout as TextLayout
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.media3.common.text.Cue
import com.johanwes.cometdownload.player.SubtitleStyle
import kotlin.math.roundToInt

/**
 * Subtitle text, drawn here instead of by the stock subtitle view because that one has a
 * single fixed outline. Lines that ask for a place on screen get it; the rest stack up from
 * the bottom, [bottomPadding] (a share of the height) above the edge.
 */
@Composable
fun TextCues(
    cues: List<Cue>,
    style: SubtitleStyle,
    textSize: Dp,
    bottomPadding: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val (placed, stacked) = cues.partition { it.line != Cue.DIMEN_UNSET }
        val boxWidth = maxWidth
        val sizePx = with(LocalDensity.current) { textSize.toPx() }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            stacked.forEach { CueText(it, style, textSize, boxWidth) }
        }

        placed.forEach { cue ->
            Layout(content = { CueText(cue, style, textSize, boxWidth) }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                val text = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val x = if (cue.position == Cue.DIMEN_UNSET) {
                    (width - text.width) / 2f
                } else {
                    val anchor = width * cue.position
                    when (cue.positionAnchor) {
                        Cue.ANCHOR_TYPE_END -> anchor - text.width
                        Cue.ANCHOR_TYPE_MIDDLE -> anchor - text.width / 2f
                        else -> anchor
                    }
                }
                val y = if (cue.lineType == Cue.LINE_TYPE_FRACTION) {
                    val anchor = height * cue.line
                    when (cue.lineAnchor) {
                        Cue.ANCHOR_TYPE_END -> anchor - text.height
                        Cue.ANCHOR_TYPE_MIDDLE -> anchor - text.height / 2f
                        else -> anchor
                    }
                } else {
                    // Counted in lines of text: from the top, or from the bottom when negative.
                    val lineHeight = sizePx * LINE_HEIGHT
                    if (cue.line >= 0) cue.line * lineHeight else height + (cue.line + 1) * lineHeight - text.height
                }
                layout(width, height) {
                    text.place(
                        x.roundToInt().coerceIn(0, maxOf(0, width - text.width)),
                        y.roundToInt().coerceIn(0, maxOf(0, height - text.height)),
                    )
                }
            }
        }
    }
}

/** One line of dialogue: the border underneath, the text on top of it. */
@Composable
private fun CueText(cue: Cue, style: SubtitleStyle, textSize: Dp, boxWidth: Dp) {
    val source = cue.text ?: return
    val fill = remember(source) { source.toAnnotatedString(colors = true) }
    val outline = remember(source) { source.toAnnotatedString(colors = false) }
    val density = LocalDensity.current
    val base = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = with(density) { textSize.toSp() },
        textAlign = when (cue.textAlignment) {
            TextLayout.Alignment.ALIGN_NORMAL -> TextAlign.Start
            TextLayout.Alignment.ALIGN_OPPOSITE -> TextAlign.End
            else -> TextAlign.Center
        },
    )
    // Half of a stroke falls inside the letters, where the text covers it.
    val stroke = with(density) { textSize.toPx() } * BORDER_STEP * style.border
    val share = if (cue.size == Cue.DIMEN_UNSET) 1f else cue.size.coerceIn(0.1f, 1f)
    Box(Modifier.widthIn(max = boxWidth * share).padding(horizontal = textSize * SIDE_PADDING)) {
        if (stroke > 0f) {
            Text(
                outline,
                color = Color(style.borderColor),
                style = base.copy(drawStyle = Stroke(width = stroke, join = StrokeJoin.Round)),
                overflow = TextOverflow.Visible,
            )
        }
        Text(fill, color = Color(style.textColor), style = base, overflow = TextOverflow.Visible)
    }
}

/** Keeps the styling a subtitle carries (italics, bold, underline, its own colours). */
private fun CharSequence.toAnnotatedString(colors: Boolean): AnnotatedString = buildAnnotatedString {
    val source = this@toAnnotatedString
    append(source.toString())
    if (source !is Spanned) return@buildAnnotatedString
    for (span in source.getSpans(0, source.length, Any::class.java)) {
        val spanStyle = when (span) {
            is StyleSpan -> when (span.style) {
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                else -> null
            }
            is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
            is ForegroundColorSpan -> if (colors) SpanStyle(color = Color(span.foregroundColor)) else null
            else -> null
        } ?: continue
        addStyle(spanStyle, source.getSpanStart(span), source.getSpanEnd(span))
    }
}

/** Stroke width per border step, as a share of the text size. */
private const val BORDER_STEP = 0.045f
private const val SIDE_PADDING = 0.25f
private const val LINE_HEIGHT = 1.17f
