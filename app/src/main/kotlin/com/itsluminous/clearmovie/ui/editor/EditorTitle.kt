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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.itsluminous.clearmovie.ui.components.LocalLabelSizeProbe
import kotlin.math.ceil

const val EDITOR_TITLE_TAG = "editor_title"

/** The room the top bar gives the title (the title itself is only as wide as its words). */
const val EDITOR_TITLE_ROOM_TAG = "editor_title_room"

/**
 * The smallest size the editor's title is drawn at. 14 sp is Material's
 * smallest title style (titleSmall), the size of the Export label beside it,
 * and still clear for Devanagari's vowel marks; below it a title stops
 * reading as a title.
 */
val TITLE_MIN_FONT_SIZE: TextUnit = 14.sp

/**
 * The title's line height, as a share of its size so it follows the size
 * chosen. At 14 sp it is Material's titleSmall line (20 sp), which leaves
 * Devanagari's marks above and below room on a second line.
 */
private val TITLE_LINE_HEIGHT = 1.4.em

/** The title shrinks a point at a time. */
private const val TITLE_STEP = 1f

/** The title takes at most this many lines. */
private const val TITLE_MAX_LINES = 2

/**
 * The editor's title in the room the top bar leaves it, never making the bar
 * ([barHeight] tall) taller:
 * - at the bar's title style (titleLarge) on one line when it fits;
 * - otherwise a point smaller at a time, down to [TITLE_MIN_FONT_SIZE], to
 *   stay on one line;
 * - only when it still does not fit at that size, on two lines at that size,
 *   cut with an ellipsis if even two are not enough. Two lines are used only
 *   when two fit the bar's height; at the largest text sizes they do not, and
 *   the title stays on one line with an ellipsis.
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
    // How many lines at the smallest size fit the bar's height. Font scaling may be non-linear, so the size
    // is converted by the density itself, and each line is rounded up to whole pixels as it is laid out.
    val lines =
        with(density) {
            val linePx = ceil(TITLE_MIN_FONT_SIZE.toPx() * TITLE_LINE_HEIGHT.value)
            (barHeight.toPx() / linePx).toInt().coerceIn(1, TITLE_MAX_LINES)
        }
    Box(modifier.height(barHeight).testTag(EDITOR_TITLE_ROOM_TAG), contentAlignment = Alignment.CenterStart) {
        Text(
            title,
            style = style.copy(lineHeight = TITLE_LINE_HEIGHT),
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            autoSize = remember(full, probe) { ShrinkThenWrapTitle(full, probe) },
            modifier = Modifier.semantics { heading() }.testTag(EDITOR_TITLE_TAG),
        )
    }
}

/**
 * Compose's auto size, told to try one line at each size from [full] down
 * to [TITLE_MIN_FONT_SIZE] before it settles for the smallest size (where the
 * Text's own maxLines then lets it take a second line).
 */
private class ShrinkThenWrapTitle(
    private val full: TextUnit,
    private val probe: ((String, TextUnit) -> Unit)?,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        val min = minOf(TITLE_MIN_FONT_SIZE.value, full.value)
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

    override fun equals(other: Any?): Boolean = other is ShrinkThenWrapTitle && other.full == full && other.probe === probe

    override fun hashCode(): Int = full.hashCode()
}

private val TITLE_FULL_FALLBACK = 22.sp
