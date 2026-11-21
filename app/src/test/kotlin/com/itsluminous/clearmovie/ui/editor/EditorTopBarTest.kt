package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.components.LocalLabelSizeProbe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's top bar (kupraki's request): Undo stacked above Redo, and a
 * title that stays at full size when it fits, shrinks to stay on one line,
 * and only then takes a second line, without the bar ever growing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorTopBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(listOf(Fixtures.photo("p1"), Fixtures.video("v2", 4000)))

    /** The size each label and the title last settled on, by its words. */
    private val sizes = mutableMapOf<String, TextUnit>()

    private data class Shown(
        val title: String,
        val size: ScreenSize,
        val fontScale: Float,
    )

    private var shown by mutableStateOf<Shown?>(null)
    private var contentSet = false

    /** Shows the editor with [title]; content is set once per test, and each call swaps what it shows. */
    private fun show(
        title: String,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
    ) {
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                val current = shown ?: return@setContent
                key(current) {
                    val h = remember { EditorHarness(project, Fixtures.preset).also { it.title = current.title } }
                    CompositionLocalProvider(LocalLabelSizeProbe provides { text, s -> sizes[text] = s }) {
                        h.At(current.size, current.fontScale)
                    }
                }
            }
        }
        sizes.clear()
        shown = Shown(title, size, fontScale)
        compose.waitForIdle()
    }

    private fun titleLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithTag(EDITOR_TITLE_TAG).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** The size [title] settled on; fails readably when it never reported one. */
    private fun sizeOf(title: String): Float {
        val size = sizes[title]
        assertWithMessage("no size reported for \"$title\"").that(size).isNotNull()
        return size!!.value
    }

    /**
     * Whether [title] fits on one line of the title's room at [size] (sp), measured exactly as the title
     * was laid out (its own style, density, fonts, direction and width, from its layout), only the size
     * changed. The width is the one the words had: the room less the tap area's padding either side.
     */
    private fun fitsOnOneLine(
        title: String,
        size: Float,
    ): Boolean {
        val input = titleLayout().layoutInput
        val roomPx = input.constraints.maxWidth
        val room =
            compose
                .onNodeWithTag(EDITOR_TITLE_ROOM_TAG)
                .fetchSemanticsNode()
                .size.width
        // The words are measured in the room the bar gives the title, less the tap area's padding.
        assertThat(roomPx).isLessThan(room)
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        val layout =
            measurer.measure(
                title,
                input.style.copy(fontSize = size.sp),
                maxLines = input.maxLines,
                constraints = Constraints.fixedWidth(roomPx),
            )
        return layout.lineCount == 1 && !layout.hasVisualOverflow
    }

    /** How tall [title] is on two lines at [size] (sp), laid out as the title is, in the title's room. */
    private fun twoLineHeightPx(
        title: String,
        size: Float,
    ): Float {
        val roomPx =
            compose
                .onNodeWithTag(EDITOR_TITLE_ROOM_TAG)
                .fetchSemanticsNode()
                .size.width
        val input = titleLayout().layoutInput
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        val layout =
            measurer.measure(
                title,
                input.style.copy(fontSize = size.sp),
                maxLines = 2,
                constraints = Constraints.fixedWidth(roomPx),
            )
        return layout.size.height.toFloat()
    }

    /** How large [size] (sp) is on screen, in dp, at the text size shown (font scaling may be non-linear). */
    private fun onScreenDp(size: Float): Float = with(titleLayout().layoutInput.density) { size.sp.toDp().value }

    private fun barHeight(): Float {
        val b = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
        return (b.bottom - b.top).value
    }

    @Test
    fun `a short title stays at the bar's title size on one line`() {
        show(SHORT)
        assertThat(sizeOf(SHORT)).isEqualTo(22f)
        assertThat(titleLayout().lineCount).isEqualTo(1)
        assertThat(titleLayout().hasVisualOverflow).isFalse()
        assertThat(barHeight()).isWithin(0.5f).of(64f)
    }

    @Test
    fun `a long title shrinks only as far as it must to stay on one line`() {
        // Two lengths, so that a step coarser than a point would miss the right size for at least one.
        for (title in listOf(LONG, LONG_TOO)) {
            show(title)
            val size = sizeOf(title)
            assertThat(size).isLessThan(22f)
            assertThat(size).isGreaterThan(TITLE_MIN_FONT_SIZE.value)
            assertThat(titleLayout().lineCount).isEqualTo(1)
            assertThat(titleLayout().hasVisualOverflow).isFalse()
            // Step-wise and no further: one point larger would not fit on one line.
            assertThat(fitsOnOneLine(title, size)).isTrue()
            assertThat(fitsOnOneLine(title, size + 1)).isFalse()
            assertThat(barHeight()).isWithin(0.5f).of(64f)
        }
    }

    @Test
    fun `a very long title takes two lines at the smallest size, at most, and is cut`() {
        show(VERY_LONG)
        assertThat(sizeOf(VERY_LONG)).isEqualTo(TITLE_MIN_FONT_SIZE.value)
        assertThat(fitsOnOneLine(VERY_LONG, TITLE_MIN_FONT_SIZE.value)).isFalse()
        val layout = titleLayout()
        assertThat(layout.lineCount).isEqualTo(2)
        assertThat(layout.isLineEllipsized(1)).isTrue()
        assertThat(barHeight()).isWithin(0.5f).of(64f)
        // The title is centred in the bar's height, inside it.
        val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
        val text = compose.onNodeWithTag(EDITOR_TITLE_TAG).getUnclippedBoundsInRoot()
        assertThat(text.top.value).isAtLeast(bar.top.value - 0.5f)
        assertThat(text.bottom.value).isAtMost(bar.bottom.value + 0.5f)
        assertThat(((text.top + text.bottom) / 2).value).isWithin(1f).of(((bar.top + bar.bottom) / 2).value)
        // TalkBack reads the whole title, not the cut one.
        val node = compose.onNodeWithTag(EDITOR_TITLE_TAG).fetchSemanticsNode()
        assertThat(node.config[SemanticsProperties.Text].single().text).isEqualTo(VERY_LONG)
        assertThat(node.config.getOrNull(SemanticsProperties.Heading)).isNotNull()
    }

    @Test
    fun `the bar is one height for every title, at every window size and text size`() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            for (size in ScreenSize.entries) {
                for (title in listOf(SHORT, LONG, VERY_LONG, HINDI)) {
                    show(title, size, scale)
                    assertThat(barHeight()).isWithin(0.5f).of(64f)
                    val layout = titleLayout()
                    assertThat(layout.lineCount).isAtMost(2)
                    // Two lines only when two fit the bar; never a line drawn outside it.
                    val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
                    val text = compose.onNodeWithTag(EDITOR_TITLE_TAG).getUnclippedBoundsInRoot()
                    assertThat(text.bottom.value).isAtMost(bar.bottom.value + 0.5f)
                    assertThat(text.top.value).isAtLeast(bar.top.value - 0.5f)
                    // Never smaller on screen than 14 sp at usual text sizes.
                    assertThat(onScreenDp(sizeOf(title))).isAtLeast(TITLE_MIN_FONT_SIZE.value - 0.01f)
                }
            }
        }
    }

    @Test
    fun `at large text a very long title still takes two lines, just small enough for both to fit the bar`() {
        // From about 1.6x two lines of 14 sp no longer fit the 64 dp bar.
        for (scale in listOf(1.65f, 1.75f, 2f)) {
            for (size in listOf(ScreenSize.COMPACT, ScreenSize.MEDIUM)) {
                for (title in listOf(VERY_LONG, HINDI)) {
                    show(title, size, scale)
                    val where = "$title at $scale x on $size"
                    val layout = titleLayout()
                    assertWithMessage(where).that(layout.lineCount).isEqualTo(2)
                    assertThat(layout.isLineEllipsized(1)).isTrue()
                    assertThat(barHeight()).isWithin(0.5f).of(64f)
                    val chosen = sizeOf(title)
                    assertWithMessage(where).that(chosen).isLessThan(TITLE_MIN_FONT_SIZE.value)
                    // Two lines at the chosen size fit the bar; two lines half a point larger would not.
                    val barPx = with(layout.layoutInput.density) { 64.dp.toPx() }
                    assertWithMessage(where).that(twoLineHeightPx(title, chosen)).isAtMost(barPx)
                    assertWithMessage(where).that(twoLineHeightPx(title, chosen + 0.5f)).isGreaterThan(barPx - 2f)
                    // Drawn inside the bar, and never smaller on screen than 14 sp at usual text sizes.
                    val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
                    val text = compose.onNodeWithTag(EDITOR_TITLE_TAG).getUnclippedBoundsInRoot()
                    assertWithMessage(where).that(text.bottom.value).isAtMost(bar.bottom.value + 0.01f)
                    assertWithMessage(where).that(text.top.value).isAtLeast(bar.top.value - 0.01f)
                    assertThat(onScreenDp(chosen)).isAtLeast(TITLE_MIN_FONT_SIZE.value - 0.01f)
                }
            }
        }
    }

    @Test
    fun `with Android's non-linear text scaling at 2x the smallest size is 11 sp, 22 dp on screen`() {
        // Android 14's table for 2x text (sp to dp): 8 to 16, 10 to 20, 12 to 24, 14 to 26, 18 to 30, 20 to 34.
        val table = listOf(8f to 16f, 10f to 20f, 12f to 24f, 14f to 26f, 18f to 30f, 20f to 34f, 24f to 36f)
        val nonLinear =
            object : Density {
                override val density = 3f
                override val fontScale = 2f

                override fun TextUnit.toDp(): Dp {
                    val sp = value
                    val (a, b) = table.zipWithNext().first { (_, hi) -> sp <= hi.first }
                    return (a.second + (sp - a.first) / (b.first - a.first) * (b.second - a.second)).dp
                }
            }
        val min = titleMinSize(nonLinear, 64.dp).value
        assertThat(min).isEqualTo(11f)
        with(nonLinear) {
            assertThat(11.sp.toDp().value).isWithin(0.01f).of(22f)

            fun twoLinesPx(sp: Float) = 2 * kotlin.math.ceil(sp.sp.toPx() * 1.4f)
            assertThat(twoLinesPx(min)).isAtMost(64.dp.toPx())
            assertThat(twoLinesPx(min + 0.5f)).isGreaterThan(64.dp.toPx() - 2f)
        }
    }

    @Test
    fun `a long Hindi title takes two lines at the smallest size, cut on the second`() {
        show(HINDI)
        assertThat(sizeOf(HINDI)).isEqualTo(TITLE_MIN_FONT_SIZE.value)
        val layout = titleLayout()
        assertThat(layout.lineCount).isEqualTo(2)
        assertThat(layout.isLineEllipsized(1)).isTrue()
        assertThat(barHeight()).isWithin(0.5f).of(64f)
    }

    @Test
    fun `Undo sits above Redo, each 48 dp wide and half the bar tall, named, and off with nothing to undo or redo`() {
        for (scale in listOf(1f, 2f)) {
            for (size in ScreenSize.entries) {
                show(VERY_LONG, size, scale)
                val undo = compose.onNodeWithTag(UNDO_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
                val redo = compose.onNodeWithTag(REDO_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
                val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
                assertThat(undo.left.value).isWithin(0.5f).of(redo.left.value)
                assertThat(undo.bottom.value).isWithin(0.5f).of(redo.top.value)
                assertThat(undo.top.value).isWithin(0.5f).of(bar.top.value)
                assertThat(redo.bottom.value).isWithin(0.5f).of(bar.bottom.value)
                for (b in listOf(undo, redo)) {
                    assertThat((b.right - b.left).value).isWithin(0.5f).of(48f)
                    assertThat((b.bottom - b.top).value).isWithin(0.5f).of(32f)
                }
                // Between the title and Save video.
                val title = compose.onNodeWithTag(EDITOR_TITLE_TAG).getUnclippedBoundsInRoot()
                val export = compose.onNodeWithTag(EDITOR_EXPORT_TAG).getUnclippedBoundsInRoot()
                assertThat(undo.left.value).isAtLeast(title.right.value)
                assertThat(export.left.value).isAtLeast(undo.right.value)
                // A new video has nothing to undo or redo (stepping through edits: EditorScreenTest).
                compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()
                compose.onNodeWithTag(REDO_TAG).assertIsNotEnabled()
                // Named for TalkBack as before.
                compose.onNodeWithContentDescription(context.getString(R.string.action_undo)).assertIsDisplayed()
                compose.onNodeWithContentDescription(context.getString(R.string.action_redo)).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the bar reads Back, the title, Undo and Redo, Save video, More options, left to right, each 48 dp wide`() {
        for (scale in listOf(1f, 2f)) {
            for (size in ScreenSize.entries) {
                show(VERY_LONG, size, scale)
                val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
                val back = compose.onNodeWithContentDescription(context.getString(R.string.action_back)).getUnclippedBoundsInRoot()
                val room = compose.onNodeWithTag(EDITOR_TITLE_ROOM_TAG).getUnclippedBoundsInRoot()
                val undo = compose.onNodeWithTag(UNDO_TAG).getUnclippedBoundsInRoot()
                val save =
                    compose
                        .onNodeWithTag(EDITOR_EXPORT_TAG)
                        .assertIsDisplayed()
                        .assertContentDescriptionEquals(context.getString(R.string.editor_export))
                        .getUnclippedBoundsInRoot()
                val more = compose.onNodeWithTag(EDITOR_MORE_TAG).getUnclippedBoundsInRoot()
                assertThat(room.left.value).isAtLeast(back.right.value - 0.5f)
                assertThat(undo.left.value).isAtLeast(room.right.value - 0.5f)
                assertThat(save.left.value).isWithin(0.5f).of(undo.right.value)
                assertThat(more.left.value).isWithin(0.5f).of(save.right.value)
                // Material's 4 dp top app bar edge either side; no word on Save video, only the download arrow.
                assertThat(back.left.value).isWithin(0.5f).of(bar.left.value + 4f)
                assertThat(more.right.value).isWithin(0.5f).of(bar.right.value - 4f)
                for (b in listOf(back, save, more)) {
                    assertThat((b.right - b.left).value).isWithin(0.5f).of(48f)
                    assertThat((b.bottom - b.top).value).isWithin(0.5f).of(48f)
                }
                compose.onNodeWithText(context.getString(R.string.editor_export)).assertDoesNotExist()
                // The title has everything between Back and Undo but Material's 4 dp title inset either side.
                assertThat((undo.left - back.right - (room.right - room.left)).value).isWithin(0.5f).of(8f)
            }
        }
    }

    @Test
    fun `the title is a button named Rename video, its tap area inside the bar at every text size`() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            for (title in listOf(SHORT, VERY_LONG, HINDI)) {
                show(title, ScreenSize.COMPACT, scale)
                val node = compose.onNodeWithTag(EDITOR_TITLE_TAG).fetchSemanticsNode()
                assertThat(node.config[SemanticsProperties.Role]).isEqualTo(Role.Button)
                assertThat(node.config[SemanticsActions.OnClick].label).isEqualTo(context.getString(R.string.editor_menu_rename))
                assertThat(node.config.getOrNull(SemanticsProperties.Heading)).isNotNull()
                val tap = compose.onNodeWithTag(EDITOR_TITLE_TAG).getUnclippedBoundsInRoot()
                val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).getUnclippedBoundsInRoot()
                assertThat((tap.bottom - tap.top).value).isAtLeast(47.5f)
                assertThat(tap.top.value).isAtLeast(bar.top.value - 0.5f)
                assertThat(tap.bottom.value).isAtMost(bar.bottom.value + 0.5f)
                assertThat(barHeight()).isWithin(0.5f).of(64f)
            }
        }
    }

    private companion object {
        const val SHORT = "Party"
        const val LONG = "Asha's birthday party"
        const val LONG_TOO = "Ravi and Meera's party"
        const val VERY_LONG = "Riya and Arjun's wedding sangeet night with the whole family at the Jaipur farmhouse, December 2026"
        const val HINDI = "रिया और अर्जुन की शादी का संगीत, पूरे परिवार के साथ जयपुर फार्महाउस में, दिसंबर 2026"
    }
}
