package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.ClipActions
import com.itsluminous.clearmovie.ui.editor.timeline.SelectionKind
import com.itsluminous.clearmovie.ui.editor.timeline.TimedFacts
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The contextual bar's layout (cm-captions follow-up): compact buttons at
 * equal distances with half of one cut off when they overflow, 4.5 to 5.5 of
 * them on a 360 to 411 dp phone, and an even spread when they all fit. Text
 * shaping is checked on a device (ContextualBarDeviceTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class ContextualBarTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf<Triple<List<BarAction>, Dp, Float>?>(null)

    private val photo = ClipActions.forSelection(SelectionKind.PHOTO)
    private val text = ClipActions.forTimed(SelectionKind.TEXT, TimedFacts(canMoveEarlier = true, canMoveLater = true, fixed = false))
    private val nothing = ClipActions.forSelection(SelectionKind.NOTHING)

    private fun show(
        actions: List<BarAction>,
        width: Dp,
        fontScale: Float = 1f,
    ): List<DpRect> {
        if (shown == null) {
            compose.setContent {
                shown?.let { (a, w, f) ->
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(f)) {
                        ClearMovieTheme(dynamicColor = false) {
                            ContextualBar(a, onAction = {}, description = "bar", modifier = Modifier.width(w))
                        }
                    }
                }
            }
        }
        shown = Triple(actions, width, fontScale)
        compose.waitForIdle()
        // Only the buttons the row has laid out (those on screen, and one or two beyond).
        return actions.mapNotNull { a ->
            runCatching { compose.onNodeWithTag(barActionTag(a.action)).getUnclippedBoundsInRoot() }.getOrNull()
        }
    }

    private fun visible(
        buttons: List<DpRect>,
        width: Dp,
    ): Float =
        buttons
            .sumOf { b ->
                ((minOf(b.right, width) - maxOf(b.left, 0.dp)).value / (b.right - b.left).value).coerceIn(0f, 1f).toDouble()
            }.toFloat()

    @Test
    fun `an overflowing bar shows four and a half to five and a half compact buttons, the last cut in half`() {
        // More than fit at 64 dp: a photo's bar at both widths, a caption's at 360 dp.
        for ((width, actions) in listOf(360.dp to photo, 411.dp to photo, 360.dp to text)) {
            assertThat(BAR_MIN * actions.size).isGreaterThan(width)
            val buttons = show(actions, width)
            val count = visible(buttons, width)
            assertThat(count).isAtLeast(4.4f)
            assertThat(count).isAtMost(5.6f)
            // The last one on screen is partly shown.
            assertThat(buttons.any { it.left < width && it.right > width + 4.dp }).isTrue()
            // Compact: no button a third of the screen wide.
            assertThat(buttons.maxOf { (it.right - it.left).value }).isAtMost(BAR_MAX.value + 0.5f)
        }
    }

    @Test
    fun `neighbours are the same distance apart`() {
        for (width in listOf(360.dp, 411.dp)) {
            for (actions in listOf(photo, text, nothing)) {
                val lefts = show(actions, width).map { it.left.value }
                val steps = lefts.zipWithNext { a, b -> b - a }
                assertThat(steps).isNotEmpty()
                for (s in steps) assertThat(abs(s - steps.first())).isLessThan(0.6f)
            }
        }
    }

    @Test
    fun `buttons that all fit are spread evenly across the width`() {
        val buttons = show(nothing, 411.dp)
        assertThat(buttons).hasSize(nothing.size)
        // The same margin at both ends (the buttons' own padding), so nothing is left over.
        // (Each is floored to whole pixels, which leaves at most a pixel per button over at the end.)
        assertThat(buttons.first().left.value).isWithin(2.5f).of(411f - buttons.last().right.value)
        assertThat(buttons.first().left.value).isLessThan(8f)
        val widths = buttons.map { (it.right - it.left).value }
        for (w in widths) assertThat(w).isWithin(0.6f).of(widths.first())
    }

    @Test
    fun `larger text widens the buttons a little and still cuts the last one`() {
        val width = 411.dp
        val normal = show(text, width).first().let { it.right - it.left }
        val buttons = show(text, width, fontScale = 1.3f)
        val large = buttons.first().let { it.right - it.left }
        assertThat(large).isGreaterThan(normal)
        assertThat(buttons.any { it.left < width && it.right > width + 4.dp }).isTrue()
    }

    @Test
    fun `every button in a bar is as tall and its icon level, whatever its label needs`() {
        for (width in listOf(360.dp, 411.dp)) {
            for (scale in listOf(1f, 1.3f)) {
                for (actions in listOf(photo, text, nothing)) {
                    val buttons = show(actions, width, scale)
                    val labels =
                        actions.mapNotNull { a ->
                            runCatching {
                                compose
                                    .onNodeWithTag(
                                        barLabelSlotTag(a.action),
                                        useUnmergedTree = true,
                                    ).getUnclippedBoundsInRoot()
                            }.getOrNull()
                        }
                    for (b in buttons) assertThat((b.bottom - b.top).value).isWithin(0.6f).of((buttons[0].bottom - buttons[0].top).value)
                    for (l in labels) {
                        assertThat(l.top.value).isWithin(0.6f).of(labels[0].top.value)
                        assertThat((l.bottom - l.top).value).isWithin(0.6f).of((labels[0].bottom - labels[0].top).value)
                    }
                }
            }
        }
    }
}
