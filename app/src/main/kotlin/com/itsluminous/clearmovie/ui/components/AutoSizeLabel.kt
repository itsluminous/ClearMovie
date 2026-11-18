package com.itsluminous.clearmovie.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** The smallest size a label is shrunk to; below it, it takes a second line instead. */
val MIN_LABEL_FONT_SIZE: TextUnit = 10.sp

/** A label never takes more than this many lines. */
const val MAX_LABEL_LINES = 2

/**
 * A label for a button, bar item, tab or chip that fits the room it has, in
 * every language and at any text size:
 * - When it is too wide at its style's size, it is drawn smaller, in steps
 *   of half a point, down to [MIN_LABEL_FONT_SIZE] (10 sp), to stay on one
 *   line.
 * - Only when it still does not fit at 10 sp does it wrap, at 10 sp, onto a
 *   second line, never a third. It is cut with an ellipsis only when even
 *   two lines are not enough (a single word wider than its button).
 *
 * Labels side by side (a bar, a tab row, a row of chips) pass the same
 * [lines], worked out with [labelLines], so that when one of them needs two
 * lines every one of them is as tall and their icons stay level.
 *
 * Give it the width it may use (for example `fillMaxWidth()` inside a
 * fixed-width button); without a bound (Constraints.Infinity) it simply
 * draws at full size on one line.
 */
@Composable
fun AutoSizeLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    lines: Int = 1,
) {
    val full = fullSize(style)
    Text(
        text,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = textAlign,
        minLines = lines.coerceIn(1, MAX_LABEL_LINES),
        maxLines = MAX_LABEL_LINES,
        overflow = TextOverflow.Ellipsis,
        autoSize = remember(full) { ShrinkThenWrap(full) },
    )
}

/**
 * How many lines a row of labels needs, each in [widthPx]: 2 when any one
 * of them does not fit on one line even at [MIN_LABEL_FONT_SIZE], else 1.
 */
fun labelLines(
    measurer: TextMeasurer,
    labels: List<String>,
    style: TextStyle,
    widthPx: Int,
): Int = if (labels.any { !fitsOnOneLine(measurer, it, style.copy(fontSize = minSize(style)), widthPx) }) MAX_LABEL_LINES else 1

/** [labelLines] in a composable, for labels each [width] wide; 1 when the width is unbounded. */
@Composable
fun rememberLabelLines(
    labels: List<String>,
    style: TextStyle,
    width: Dp,
    density: Density,
): Int {
    val measurer = rememberTextMeasurer()
    // Two pixels short of the room: each padding around a label rounds to whole pixels on its own,
    // so its real width can come out up to two pixels under [width]; a row never then says one line
    // for a label that takes two.
    val px = if (width.isFinite) with(density) { width.roundToPx() } - 2 else Constraints.Infinity
    return remember(labels, style, px, density.fontScale, density.density) { labelLines(measurer, labels, style, px.coerceAtLeast(0)) }
}

private fun fitsOnOneLine(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
): Boolean {
    if (widthPx >= MAX_MEASURE_PX) return true
    // Laid out as the label is: wrapping on, in exactly its width. An unbounded single line is laid out
    // another way on Android 10 (BoringLayout), which reads wider than what Text draws.
    val layout = measurer.measure(text, style, maxLines = MAX_LABEL_LINES, constraints = Constraints.fixedWidth(widthPx))
    return layout.lineCount == 1 && !layout.hasVisualOverflow
}

private fun pickSize(
    full: TextUnit,
    maxWidthPx: Int,
    fits: (TextUnit) -> Boolean,
): TextUnit {
    val min = minOf(MIN_LABEL_FONT_SIZE.value, full.value)
    if (maxWidthPx == Constraints.Infinity) return full
    var size = full.value
    while (size > min) {
        if (fits(size.sp)) return size.sp
        size = maxOf(min, size - STEP)
    }
    return min.sp
}

private fun fullSize(style: TextStyle): TextUnit = if (style.fontSize.isSpecified) style.fontSize else DEFAULT_SIZE

private fun minSize(style: TextStyle): TextUnit = minOf(MIN_LABEL_FONT_SIZE.value, fullSize(style).value).sp

/** Compose's auto size, told to try one line at each size before it settles for two lines at 10 sp. */
private class ShrinkThenWrap(
    private val full: TextUnit,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit =
        pickSize(full, constraints.maxWidth) { size ->
            val layout = performLayout(constraints, text, size)
            layout.lineCount == 1 && !layout.hasVisualOverflow
        }

    override fun equals(other: Any?): Boolean = other is ShrinkThenWrap && other.full == full

    override fun hashCode(): Int = full.hashCode()
}

private val DEFAULT_SIZE = 14.sp

/** Wider than any screen; at or past it a label is taken as unbounded (and Constraints stay valid). */
private const val MAX_MEASURE_PX = 100_000
private const val STEP = 0.5f
