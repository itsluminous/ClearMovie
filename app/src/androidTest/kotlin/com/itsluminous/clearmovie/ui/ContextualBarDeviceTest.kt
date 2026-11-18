package com.itsluminous.clearmovie.ui

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.LocalLabelSizeProbe
import com.itsluminous.clearmovie.ui.components.MIN_LABEL_FONT_SIZE
import com.itsluminous.clearmovie.ui.editor.BAR_MAX
import com.itsluminous.clearmovie.ui.editor.BAR_MIN
import com.itsluminous.clearmovie.ui.editor.BAR_TARGET
import com.itsluminous.clearmovie.ui.editor.ContextualBar
import com.itsluminous.clearmovie.ui.editor.barActionTag
import com.itsluminous.clearmovie.ui.editor.barGrowth
import com.itsluminous.clearmovie.ui.editor.barItemWidth
import com.itsluminous.clearmovie.ui.editor.barLabel
import com.itsluminous.clearmovie.ui.editor.barLabelTag
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.ClipActions
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.RowFacts
import com.itsluminous.clearmovie.ui.editor.timeline.SelectionKind
import com.itsluminous.clearmovie.ui.editor.timeline.TimedFacts
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Labels with the device's real text shaping (cm-captions follow-up). A
 * label too long for its room is drawn smaller, down to 10 sp, to stay on
 * one line; only one too long even at 10 sp takes a second line, never a
 * third, and then every button beside it is as tall, icons level.
 *
 * The size each label settles on is read from AutoSizeLabel itself
 * (LocalLabelSizeProbe): a line's drawn width is not a reliable measure of
 * its size on every Android version (on CI's Android 10 an auto-sized line
 * drew up to 10% narrower than the same words measured at the same size).
 */
@RunWith(AndroidJUnit4::class)
class ContextualBarDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var content by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** The size each label's words settled on, as AutoSizeLabel reports it. */
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /** [content] with every label telling [sizes] its size. */
    private fun probed(body: @Composable () -> Unit): @Composable () -> Unit =
        {
            CompositionLocalProvider(LocalLabelSizeProbe provides { text, size -> sizes[text] = size.value }) { body() }
        }

    @Before
    fun show() = compose.setContent { content?.invoke() }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.lineWidth(line: Int = 0) = getLineRight(line) - getLineLeft(line)

    private companion object {
        const val MAX_LINES = 2
    }

    private class Tools(
        val context: Context,
    )

    private var measurer: TextMeasurer? = null
    private var style: TextStyle = TextStyle.Default

    /**
     * [text] at [sp] laid out the way a label is: wrapping on, in exactly
     * [widthPx] (the label's own width). Measuring it unbounded instead takes
     * another layout path on Android 10 that reads wider than Text draws.
     */
    private fun at(
        text: String,
        sp: Float,
        widthPx: Int,
        lines: Int = 1,
        spaced: Boolean = true,
    ): TextLayoutResult =
        measurer!!.measure(
            text,
            style.copy(fontSize = sp.sp, letterSpacing = if (spaced) style.letterSpacing else 0.sp),
            maxLines = lines,
            constraints = Constraints.fixedWidth(widthPx),
        )

    /** Whether [text] at [sp] fits on one line in [widthPx]. */
    private fun fits(
        text: String,
        sp: Float,
        widthPx: Int,
    ): Boolean = at(text, sp, widthPx, lines = MAX_LINES).let { it.lineCount == 1 && !it.hasVisualOverflow }

    /** How many lines [text] takes at 10 sp in [widthPx], with no limit. */
    private fun linesAtTen(
        text: String,
        widthPx: Int,
    ): Int = at(text, MIN_LABEL_FONT_SIZE.value, widthPx, lines = Int.MAX_VALUE).lineCount

    /** Every action any bar can show. */
    private val bars: List<List<BarAction>> =
        SelectionKind.entries.map { ClipActions.forSelection(it) } +
            SelectionKind.entries.map { ClipActions.forTimed(it, TimedFacts(true, true, fixed = false, isMusic = true)) } +
            listOf(ClipActions.forRow(RowFacts(false, true, true, true, true, true, true, true, true, true, false, true, true)))

    /**
     * Shows [actions] in a bar on a [phone] whose bar overflows, so each
     * button is as wide as there. Callers pass a few at a time: a lazy row
     * lays out only those on screen, and CI's screen is 320 dp wide.
     */
    private fun showBar(
        actions: List<BarAction>,
        phone: Dp,
        tag: String,
        scale: Float,
    ): Tools {
        val grow = barGrowth(scale)
        val item = barItemWidth(phone, 99, BAR_MIN * grow, BAR_TARGET * grow, BAR_MAX * grow)
        var tools: Tools? = null
        sizes.clear()
        content =
            probed {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.FontScale(scale) then DeviceConfigurationOverride.Locales(LocaleList(tag)),
                ) {
                    ClearMovieTheme(dynamicColor = false) {
                        val context = LocalContext.current
                        tools = Tools(context)
                        measurer = rememberTextMeasurer()
                        style = MaterialTheme.typography.labelMedium
                        // They all fit, so each gets [item].
                        ContextualBar(actions, onAction = {}, description = "bar", modifier = Modifier.width(item * actions.size + 1.dp))
                    }
                }
            }
        compose.waitForIdle()
        return tools!!
    }

    private fun label(action: EditAction) = compose.onNode(hasTestTag(barLabelTag(action)), useUnmergedTree = true)

    /** The buttons in the bar shown are all as tall, and their labels start at one height, so the icons are level. */
    private fun assertLevel(
        actions: List<BarAction>,
        where: String,
    ) {
        val buttons = actions.map { compose.onNodeWithTag(barActionTag(it.action)).getUnclippedBoundsInRoot() }
        val labels = actions.map { label(it.action).getUnclippedBoundsInRoot() }
        for ((b, l) in buttons.zip(labels)) {
            assertWithMessage(
                "$where: height",
            ).that(abs((b.bottom - b.top).value - (buttons[0].bottom - buttons[0].top).value)).isLessThan(0.6f)
            assertWithMessage("$where: label top").that(abs(l.top.value - labels[0].top.value)).isLessThan(0.6f)
            assertWithMessage(
                "$where: label height",
            ).that(abs((l.bottom - l.top).value - (labels[0].bottom - labels[0].top).value)).isLessThan(0.6f)
        }
    }

    @Test
    fun everyBarLabelShrinksToOneLineOrTakesExactlyTwoAtTenSp() {
        val all = bars.flatten().distinctBy { it.action }
        for (phone in listOf(360.dp, 411.dp)) {
            for (tag in listOf("en", "hi", "hi-Latn")) {
                for (scale in listOf(1.0f, 1.3f)) {
                    val shrunk = mutableListOf<EditAction>()
                    val two = mutableListOf<EditAction>()
                    val cut = mutableListOf<EditAction>()
                    for (chunk in all.chunked(2)) {
                        val t = showBar(chunk, phone, tag, scale)
                        for (a in chunk) {
                            val where = "${a.action} in $tag at $scale on ${phone.value.toInt()} dp"
                            val text = t.context.getString(barLabel(a.action))
                            val layout = label(a.action).layout()
                            val box = layout.size.width
                            if (layout.lineCount == 1) {
                                // One line, uncut, at 10 sp or more: as wide as the words at 10 sp, or wider.
                                assertWithMessage("$where: not cut").that(layout.isLineEllipsized(0)).isFalse()
                                val size = requireNotNull(sizes[text]) { "$where: no size reported" }
                                assertWithMessage("$where: one line at 10 sp or more").that(size).isAtLeast(MIN_LABEL_FONT_SIZE.value)
                                if (size < 12f) shrunk += a.action
                                // The drawn line against the same words measured at that size (with and without
                                // letter spacing), so CI shows how far Android 10's measuring is from its drawing.
                                if (a == chunk.first() && tag == "en" && scale == 1f) {
                                    Log.i(
                                        "BarLabels",
                                        "$where: drawn ${layout.lineWidth()} px; measured ${at(text, size, box).lineWidth()} px, " +
                                            "unspaced ${at(text, size, box, spaced = false).lineWidth()} px",
                                    )
                                }
                            } else {
                                // Two lines only when it does not fit on one even at 10 sp.
                                assertWithMessage(
                                    "$where: wraps only when needed",
                                ).that(fits(text, MIN_LABEL_FONT_SIZE.value, box)).isFalse()
                                two += a.action
                                val size = requireNotNull(sizes[text]) { "$where: no size reported" }
                                assertWithMessage("$where: two lines at 10 sp").that(size).isEqualTo(MIN_LABEL_FONT_SIZE.value)
                                // Cut only when two lines at 10 sp are not enough, the last resort.
                                if (layout.isLineEllipsized(1)) {
                                    cut += a.action
                                    assertWithMessage("$where: cut only past two lines").that(linesAtTen(text, box)).isGreaterThan(2)
                                }
                            }
                        }
                        assertLevel(chunk, "$tag at $scale on ${phone.value.toInt()} dp")
                        // No second line for nothing: a bar two lines tall has a label drawn on two.
                        val layouts = chunk.map { label(it.action).layout() }
                        // (Taller than its first line by over half a line: platforms pad a line differently.)
                        if (layouts.any { it.size.height > it.getLineBottom(0) * 1.5f }) {
                            val why =
                                chunk.zip(layouts).joinToString { (a, l) ->
                                    val w = t.context.getString(barLabel(a.action))
                                    "${a.action} '$w' box ${l.size.width} size ${sizes[w]} fits10 ${fits(w, 10f, l.size.width)} " +
                                        "fits10-2 ${fits(w, 10f, l.size.width - 2)}"
                                }
                            assertWithMessage("$tag at $scale on ${phone.value.toInt()} dp: second line used ($why)")
                                .that(layouts.any { it.lineCount == 2 })
                                .isTrue()
                        }
                    }
                    Log.i(
                        "BarLabels",
                        "${phone.value.toInt()} dp, $tag at $scale: smaller $shrunk; two lines $two; cut past two lines $cut",
                    )
                }
            }
        }
    }

    @Test
    fun aLongHindiLabelShrinksToOneLineAtTenSpOrMore() {
        val label = "दूसरी पंक्ति में ले जाएँ"
        sizes.clear()
        content =
            probed {
                ClearMovieTheme(dynamicColor = false) {
                    style = MaterialTheme.typography.labelMedium
                    val m = rememberTextMeasurer()
                    measurer = m
                    val full = m.measure(label, style, maxLines = 1, softWrap = false).size.width
                    // Nine tenths of the room it needs at 12 sp: it has to shrink, and fits at about 10.8 sp.
                    val box = with(LocalDensity.current) { (full * 0.9f).toDp() }
                    AutoSizeLabel(label, modifier = Modifier.width(box).testTag("hindi"), style = style)
                }
            }
        compose.waitForIdle()
        val layout = compose.onNodeWithTag("hindi", useUnmergedTree = true).layout()
        val box = layout.size.width
        val size = requireNotNull(sizes[label])
        Log.i("BarLabels", "Hindi label in $box px: drawn at $size sp, ${layout.lineWidth()} px wide")
        assertWithMessage("lines").that(layout.lineCount).isEqualTo(1)
        assertWithMessage("not cut").that(layout.isLineEllipsized(0)).isFalse()
        assertWithMessage("fits").that(layout.lineWidth()).isAtMost(box + 1.5f)
        assertWithMessage("10 sp or more").that(size).isAtLeast(MIN_LABEL_FONT_SIZE.value)
        assertWithMessage("smaller than 12 sp").that(size).isLessThan(12f)
        // At 12 sp it would not fit on one line, so the one line it is on is smaller than 12 sp.
        assertWithMessage("too wide at 12 sp").that(fits(label, 12f, box)).isFalse()
    }

    @Test
    fun aLabelTooLongEvenAtTenSpTakesExactlyTwoLinesAndItsNeighboursMatchIt() {
        // "Move to another row" needs about 95 dp at 10 sp; an 80 dp button holds about 70, two lines of it enough.
        val actions = listOf(EditAction.DELETE, EditAction.MOVE_TO_ROW, EditAction.SPLIT, EditAction.DUPLICATE).map { BarAction(it) }
        showBar(actions, 360.dp, "en", 1f)
        val long = label(EditAction.MOVE_TO_ROW).layout()
        val text = compose.activity.getString(barLabel(EditAction.MOVE_TO_ROW))
        assertWithMessage("too long at 10 sp").that(fits(text, MIN_LABEL_FONT_SIZE.value, long.size.width)).isFalse()
        assertWithMessage("lines").that(long.lineCount).isEqualTo(2)
        assertWithMessage("not cut").that(long.isLineEllipsized(1)).isFalse()
        // Its neighbours keep one line at full size, but are as tall, icons level.
        val short = label(EditAction.DELETE).layout()
        assertWithMessage("neighbour on one line").that(short.lineCount).isEqualTo(1)
        assertWithMessage(
            "neighbour at full size",
        ).that(sizes[compose.activity.getString(barLabel(EditAction.DELETE))]).isEqualTo(12f)
        assertLevel(actions, "en, 360 dp")
    }
}
