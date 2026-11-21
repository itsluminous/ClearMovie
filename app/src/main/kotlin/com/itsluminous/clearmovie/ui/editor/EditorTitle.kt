package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.itsluminous.clearmovie.ui.components.LocalLabelSizeProbe
import com.itsluminous.clearmovie.ui.components.MIN_LABEL_FONT_SIZE
import kotlin.math.ceil

const val EDITOR_TITLE_TAG = "editor_title"

/** The room the top bar gives the title (the title itself is only as wide as its words). */
const val EDITOR_TITLE_ROOM_TAG = "editor_title_room"

/**
 * The smallest size the editor's title is drawn at, at usual text sizes.
 * 14 sp is Material's smallest title style (titleSmall), the size of the
 * Export label beside it, and still clear for Devanagari's vowel marks.
 * At the largest text sizes two lines of 14 sp do not fit the bar, so the
 * smallest size goes lower there, just far enough for two lines
 * ([titleMinSize]); on screen it is then still larger than 14 sp at usual
 * text sizes.
 */
val TITLE_MIN_FONT_SIZE: TextUnit = 14.sp

/** Below this the title is never drawn, at any text size: the app's smallest label size. */
private val TITLE_FLOOR_FONT_SIZE: TextUnit = MIN_LABEL_FONT_SIZE

/**
 * Pixels kept in hand when deciding whether two lines fit the bar: a line can
 * be drawn a pixel or two taller than its line height when a glyph reaches
 * past it (Devanagari's marks), and the bar does not clip.
 */
private const val TITLE_FIT_SLACK_PX = 2f

/**
 * The title's line height, as a share of its size so it follows the size
 * chosen. At 14 sp it is Material's titleSmall line (20 sp), which leaves
 * Devanagari's marks above and below room on a second line.
 */
private val TITLE_LINE_HEIGHT = 1.4.em

/** The title shrinks a point at a time. */
private const val TITLE_STEP = 1f

/** Below [TITLE_MIN_FONT_SIZE], at large text, the smallest size is found half a point at a time. */
private const val TITLE_MIN_STEP = 0.5f

/** The title takes at most this many lines. */
private const val TITLE_MAX_LINES = 2

/**
 * The editor's title in the room the top bar leaves it, never making the bar
 * ([barHeight] tall) taller:
 * - at the bar's title style (titleLarge) on one line when it fits;
 * - otherwise a point smaller at a time, down to its smallest size
 *   ([titleMinSize]: 14 sp, or less at the largest text sizes), to stay on
 *   one line;
 * - only when it still does not fit at that size, on two lines at that size,
 *   cut with an ellipsis if even two are not enough. The smallest size is
 *   chosen so that two lines always fit the bar's height.
 *
 * It is centred in the bar's height, and TalkBack reads the whole title.
 */
@Composable
internal fun EditorTitle(
    title: String,
    barHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.titleLarge
    val full = if (style.fontSize.isSpecified) style.fontSize else TITLE_FULL_FALLBACK
    val probe = LocalLabelSizeProbe.current
    val density = LocalDensity.current
    val min = remember(density, barHeight, full) { minOf(titleMinSize(density, barHeight).value, full.value).sp }
    // How many lines at the smallest size fit the bar's height: two, except past the floor at text sizes
    // Android does not offer.
    val lines =
        with(density) {
            ((barHeight.toPx() - TITLE_FIT_SLACK_PX) / titleLinePx(min)).toInt().coerceIn(1, TITLE_MAX_LINES)
        }
    Box(modifier.height(barHeight).testTag(EDITOR_TITLE_ROOM_TAG), contentAlignment = Alignment.CenterStart) {
        Text(
            title,
            style = style.copy(lineHeight = TITLE_LINE_HEIGHT),
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            autoSize = remember(full, min, probe) { ShrinkThenWrapTitle(full, min, probe) },
            modifier = Modifier.semantics { heading() }.testTag(EDITOR_TITLE_TAG),
        )
    }
}

/**
 * The title's smallest size for this [density]: [TITLE_MIN_FONT_SIZE], or,
 * when two lines of it do not fit [barHeight], the largest size half a
 * point at a time below it at which two lines do (never below
 * [TITLE_FLOOR_FONT_SIZE]), keeping [TITLE_FIT_SLACK_PX] in hand. Font
 * scaling is non-linear on Android 14 and later, so every size is converted
 * by the density itself (on a phone, LocalDensity carries the converter).
 */
internal fun titleMinSize(
    density: Density,
    barHeight: Dp,
): TextUnit =
    with(density) {
        val barPx = barHeight.toPx() - TITLE_FIT_SLACK_PX
        var size = TITLE_MIN_FONT_SIZE.value
        while (size > TITLE_FLOOR_FONT_SIZE.value && TITLE_MAX_LINES * titleLinePx(size.sp) > barPx) {
            size = maxOf(TITLE_FLOOR_FONT_SIZE.value, size - TITLE_MIN_STEP)
        }
        size.sp
    }

/** One line of the title at [size], rounded up to whole pixels as it is laid out. */
private fun Density.titleLinePx(size: TextUnit): Float = ceil(size.toPx() * TITLE_LINE_HEIGHT.value)

/**
 * Compose's auto size, told to try one line at each size from [full] down
 * to [min] before it settles for [min] (where the Text's own maxLines then
 * lets it take a second line).
 */
private class ShrinkThenWrapTitle(
    private val full: TextUnit,
    private val minSize: TextUnit,
    private val probe: ((String, TextUnit) -> Unit)?,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        val min = minSize.value
        var chosen = min
        if (constraints.hasBoundedWidth) {
            var size = full.value
            while (size > min) {
                val layout = performLayout(constraints, text, size.sp)
                if (layout.lineCount == 1 && !layout.hasVisualOverflow) {
                    chosen = size
                    break
                }
                size = maxOf(min, size - TITLE_STEP)
            }
            // The loop stops before trying the smallest size itself: it is the size used either way.
        } else {
            chosen = full.value
        }
        return chosen.sp.also { probe?.invoke(text.text, it) }
    }

    override fun equals(other: Any?): Boolean =
        other is ShrinkThenWrapTitle && other.full == full && other.minSize == minSize && other.probe === probe

    override fun hashCode(): Int = 31 * full.hashCode() + minSize.hashCode()
}

private val TITLE_FULL_FALLBACK = 22.sp
