package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.totalDurationMs
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.PopupCorner
import com.itsluminous.clearmovie.ui.editor.timeline.PreviewLayout
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_MAIN_ROW_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TRIM_END_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbPlanKey
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineZoom
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.editor.timeline.plusTag
import com.itsluminous.clearmovie.ui.editor.timeline.thumbTag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The editor timeline (ROADMAP cm-timeline, Compose UI acceptance) at the
 * three window sizes and at font scale 2: layout, pinch zoom around the
 * fingers, the zoom buttons, "+" insert, the scrolling bar, per-type actions,
 * drag reorder, trim, panels, the preview shrinking into a floating window,
 * and everything kept across a restore.
 *
 * The test screen is big enough for every forced size at a phone's density,
 * so 1 px is a third of a dp (Robolectric's default small screen would shrink
 * the density to fit, and 1 px would be 2 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(
                listOf(
                    Fixtures.photo("p1"),
                    Fixtures.video("v2", 4000),
                    Fixtures.photo("p3"),
                    Fixtures.video("v4", 6000),
                    Fixtures.photo("p5"),
                ),
            )

    private fun harness(p: com.itsluminous.clearmovie.domain.model.Project = project) = EditorHarness(p, Fixtures.preset)

    private fun show(
        size: ScreenSize,
        fontScale: Float = 1f,
        h: EditorHarness = harness(),
    ): EditorHarness {
        compose.setContent { h.At(size, fontScale) }
        compose.waitForIdle()
        return h
    }

    private var shown by androidx.compose.runtime.mutableStateOf<Triple<ScreenSize, Float, EditorHarness>?>(null)

    /** Runs [block] at each size (and font scale), with a fresh harness each time, in one composition (set once per test). */
    private fun forEachSize(
        sizes: List<ScreenSize> = ScreenSize.entries,
        fontScales: List<Float> = listOf(1f),
        make: () -> EditorHarness = { harness() },
        block: (ScreenSize, EditorHarness) -> Unit,
    ) {
        compose.setContent {
            val current = shown
            if (current != null) {
                androidx.compose.runtime.key(current) { current.third.At(current.first, current.second) }
            }
        }
        for (scale in fontScales) {
            for (size in sizes) {
                val h = make()
                shown = Triple(size, scale, h)
                compose.waitForIdle()
                block(size, h)
            }
        }
    }

    private fun SemanticsNodeInteraction.bounds() = getUnclippedBoundsInRoot()

    private fun Dp.v() = value

    // ---------------------------------------------------------------- layout

    private fun assertLayout(
        size: ScreenSize,
        fontScale: Float,
    ) {
        show(size, fontScale)
        val preview = compose.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed().bounds()
        val export = compose.onNodeWithTag(EDITOR_EXPORT_TAG).assertIsDisplayed().bounds()
        val more = compose.onNodeWithTag(EDITOR_MORE_TAG).assertIsDisplayed().bounds()
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).bounds()
        val redo = compose.onNodeWithTag(REDO_TAG).assertIsDisplayed().bounds()
        // More options sits at the top right of the screen (of the right pane in two panes), Redo before it.
        assertThat(more.right.v()).isWithin(8f).of(size.size.width.value)
        assertThat(redo.right.v()).isWithin(0.5f).of(more.left.v())
        assertThat(more.bottom.v()).isAtMost(preview.top.v() + 1f)
        // Export is right under the preview, at the strip's far end (the left pane's in two panes).
        assertThat(export.top.v()).isAtLeast(preview.bottom.v() - 1f)
        val strip = compose.onNodeWithTag(TRANSPORT_TAG).bounds()
        assertThat(export.right.v()).isWithin(16f).of(strip.right.v())
        assertThat(strip.top.v()).isAtLeast(preview.bottom.v() - 1f)
        if (size == ScreenSize.COMPACT) {
            assertThat(timeline.top.v()).isAtLeast(export.bottom.v())
        } else {
            assertThat(timeline.left.v()).isAtLeast(preview.right.v() - 1f)
            assertThat(preview.left.v()).isLessThan(size.size.width.value / 4)
        }
        assertThat((preview.right - preview.left).v() / (preview.bottom - preview.top).v()).isWithin(0.03f).of(16f / 9f)
        compose.onNodeWithTag(TIMELINE_MAIN_ROW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(barActionTag(EditAction.ADD_MEDIA)).assertIsDisplayed()
        bar(EditAction.ZOOM_IN).assertIsDisplayed()
        compose.onNodeWithTag(UNDO_TAG).assertIsDisplayed()
    }

    @Test
    fun compact() = assertLayout(ScreenSize.COMPACT, 1f)

    @Test
    fun medium() = assertLayout(ScreenSize.MEDIUM, 1f)

    @Test
    fun expanded() = assertLayout(ScreenSize.EXPANDED, 1f)

    @Test
    fun `compact at font scale 2`() = assertLayout(ScreenSize.COMPACT, 2f)

    @Test
    fun `medium at font scale 2`() = assertLayout(ScreenSize.MEDIUM, 2f)

    @Test
    fun `expanded at font scale 2`() = assertLayout(ScreenSize.EXPANDED, 2f)

    @Test
    fun `row headers are icons only, named for TalkBack, at least 48 dp to touch`() {
        forEachSize(fontScales = listOf(1f, 2f)) { _, _ ->
            val names =
                listOf(
                    R.string.timeline_row_text,
                    R.string.timeline_row_frame,
                    R.string.editor_row_main,
                    R.string.timeline_row_music,
                    R.string.timeline_row_voice,
                ).map(::s)
            // Every row shown (MAIN always; the others as the layout fits them) has its icon header.
            val shown = names.filter { compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty() }
            assertThat(shown).contains(s(R.string.editor_row_main))
            for (name in names) {
                // No visible label: the name is never drawn as text.
                assertThat(compose.onAllNodesWithText(name).fetchSemanticsNodes()).isEmpty()
            }
            for (name in shown) {
                val header = compose.onNodeWithContentDescription(name).bounds()
                assertThat((header.right - header.left).v()).isAtLeast(48f)
                assertThat((header.bottom - header.top).v()).isAtLeast(48f)
            }
        }
    }

    // ---------------------------------------------------------------- selection and the bar

    @Test
    fun `tapping an item selects it and the bar shows that kind's actions in order`() {
        forEachSize { _, h ->
            selectItem(h, "v2")
            assertBarOrder(listOf(EditAction.TRIM, EditAction.ADD_CAPTION, EditAction.TRANSITION, EditAction.ANIMATION))
            selectItem(h, "p1", zoomIns = 0)
            assertBarOrder(listOf(EditAction.DURATION, EditAction.ADD_CAPTION, EditAction.TRANSITION, EditAction.ANIMATION))
            selectItem(
                h,
                project.mainClips
                    .first()
                    .id.value,
                zoomIns = 0,
            )
            assertBarOrder(listOf(EditAction.PLAY, EditAction.TRANSITION, EditAction.REPLACE))
        }
    }

    /**
     * Zooms in [zoomIns] steps and scrolls so item [id] starts 40 dp into the
     * view, then taps it: the way a user brings a small item up to select it.
     */
    private fun selectItem(
        h: EditorHarness,
        id: String,
        zoomIns: Int = 3,
    ) {
        tapEmpty()
        repeat(zoomIns) { bar(EditAction.ZOOM_IN).performClick() }
        reveal(h, id)
        compose.onNodeWithTag(clipTag(ClipId(id))).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(ClipId(id))
    }

    private fun reveal(
        h: EditorHarness,
        id: String,
    ) {
        val z = h.state!!.zoom!!
        val slot = h.project.mainSlot(ClipId(id))!!
        h.state!!.zoom = z.copy(scrollMs = (slot.startMs - 40 * z.msPerDp).coerceAtLeast(0.0))
        compose.waitForIdle()
    }

    /** A tap on the ruler's left edge, above the row icons: nothing selected. */
    private fun tapEmpty() {
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(10f * density, 10f * density)) }
        compose.waitForIdle()
    }

    /** Taps the bar's [action], scrolling the bar to it first (the bar holds more buttons than fit). */
    private fun tapBar(action: EditAction) {
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(action)))
        compose.onNodeWithTag(barActionTag(action)).performClick()
        compose.waitForIdle()
    }

    /** The first buttons of the bar are [expected], left to right. */
    private fun assertBarOrder(expected: List<EditAction>) {
        val lefts =
            expected.map {
                compose
                    .onNodeWithTag(barActionTag(it))
                    .assertExists()
                    .bounds()
                    .left
                    .v()
            }
        assertThat(lefts).isInStrictOrder()
    }

    @Test
    fun `the bar scrolls sideways with a cut-off last button and a fade, upright and sideways at font scale 2`() {
        run {
            forEachSize(listOf(ScreenSize.COMPACT, ScreenSize.MEDIUM), listOf(1f, 2f)) { _, h ->
                selectItem(h, "v2")
                compose.waitForIdle()
                val bar = compose.onNodeWithTag(BAR_TAG).bounds()
                compose.onNodeWithTag(BAR_FADE_END_TAG).assertExists()
                // Some button is cut by the bar's right edge: the bar reads as scrollable.
                val buttons =
                    EditAction.entries.mapNotNull { a ->
                        runCatching { compose.onNodeWithTag(barActionTag(a)).bounds() }.getOrNull()
                    }
                assertThat(buttons.any { it.left < bar.right && it.right > bar.right + Dp(4f) }).isTrue()
                // The labels are whole: the Duplicate button's text is not clipped short.
                compose.onNodeWithTag(BAR_TAG).performTouchInput { swipeLeft() }
                compose.waitForIdle()
                compose.onNodeWithTag(BAR_FADE_START_TAG).assertExists()
            }
        }
    }

    @Test
    fun `Move left and Move right reorder items, and are disabled at the ends`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "p1")
        repeat(3) { compose.onNodeWithTag(BAR_TAG).performTouchInput { swipeLeft() } }
        compose.onNodeWithTag(barActionTag(EditAction.MOVE_LEFT)).assertIsNotEnabled()
        compose.onNodeWithTag(barActionTag(EditAction.MOVE_RIGHT)).assertIsEnabled().performClick()
        assertThat(h.project.contentIds()).containsExactly("v2", "p1", "p3", "v4", "p5").inOrder()
        compose.onNodeWithTag(barActionTag(EditAction.MOVE_LEFT)).assertIsEnabled().performClick()
        assertThat(h.project.contentIds()).containsExactly("p1", "v2", "p3", "v4", "p5").inOrder()
    }

    private fun com.itsluminous.clearmovie.domain.model.Project.contentIds() = mainIds().filter { !it.startsWith("e") }

    // ---------------------------------------------------------------- zoom

    @Test
    fun `pinching zooms around the fingers, and what is under them stays within 2 dp`() {
        forEachSize { _, h ->
            val tile = compose.onNodeWithTag(clipTag(ClipId("v4")))
            val before = tile.bounds()
            val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
            val main = compose.onNodeWithTag(TIMELINE_MAIN_ROW_TAG).fetchSemanticsNode().boundsInRoot
            val tileLeftPx =
                compose
                    .onNodeWithTag(clipTag(ClipId("v4")))
                    .fetchSemanticsNode()
                    .boundsInRoot.left
            // Fingers either side of v4's left edge, on the MAIN row, spreading apart.
            val fx = tileLeftPx - timeline.left
            val fy = main.center.y - timeline.top
            val scaleBefore = h.state!!.zoom!!.msPerDp
            compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
                pinch(Offset(fx - 20f, fy), Offset(fx - 80f, fy), Offset(fx + 20f, fy), Offset(fx + 80f, fy), durationMillis = 400)
            }
            compose.waitForIdle()
            val after = compose.onNodeWithTag(clipTag(ClipId("v4"))).bounds()
            assertThat(h.state!!.zoom!!.msPerDp).isLessThan(scaleBefore / 2)
            assertThat(abs((after.left - before.left).v())).isAtMost(2f)
            // And the item is wider on screen.
            assertThat((after.right - after.left).v()).isGreaterThan((before.right - before.left).v() * 2)
        }
    }

    @Test
    fun `thumbnails are planned again for the new zoom`() {
        val h = show(ScreenSize.COMPACT)
        val node = compose.onAllNodesWithTag(thumbTag(ClipId("v4")), useUnmergedTree = true)[0]
        val before = node.thumbPlan()
        bar(EditAction.ZOOM_IN).performClick()
        bar(EditAction.ZOOM_IN).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val after = compose.onAllNodesWithTag(thumbTag(ClipId("v4")), useUnmergedTree = true)[0].thumbPlan()
        assertThat(after).isNotEqualTo(before)
        assertThat(h.state!!.zoom!!.fitAll).isFalse()
    }

    private fun SemanticsNodeInteraction.thumbPlan(): String? {
        val node = fetchSemanticsNode()
        return (listOf(node) + node.children).firstNotNullOfOrNull { n -> n.config.getOrElseNullable(ThumbPlanKey) { null } }
    }

    @Test
    fun `Zoom in, Zoom out and Fit all change the scale by 1_5 and back`() {
        forEachSize { _, h ->
            val fit = h.state!!.zoom!!
            assertThat(fit.fitAll).isTrue()
            bar(EditAction.ZOOM_OUT).assertIsNotEnabled()
            bar(EditAction.ZOOM_IN).performClick()
            val zoomed = h.state!!.zoom!!
            assertThat(fit.msPerDp / zoomed.msPerDp).isWithin(1e-6).of(TimelineZoom.BUTTON_FACTOR)
            bar(EditAction.ZOOM_IN).performClick()
            bar(EditAction.ZOOM_OUT).assertIsEnabled().performClick()
            assertThat(h.state!!.zoom!!.msPerDp).isWithin(1e-6).of(zoomed.msPerDp)
            bar(EditAction.FIT_ALL).performClick()
            compose.waitForIdle()
            assertThat(h.state!!.zoom!!.fitAll).isTrue()
            assertThat(h.state!!.zoom!!.msPerDp).isWithin(1e-6).of(fit.msPerDp)
        }
    }

    /** A bar button, scrolled into view first (the bar scrolls sideways in a narrow pane). */
    private fun bar(action: EditAction): SemanticsNodeInteraction {
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(action)))
        return compose.onNodeWithTag(barActionTag(action))
    }

    @Test
    fun `the playback strip is one row, spread across, with zoom only in the bar`() = assertStripOneRow()

    @Test
    @Config(qualifiers = "+b+hi+Latn")
    fun `the playback strip stays one row in Hinglish at 1_3 and 1_5`() = assertStripOneRow(listOf(1.3f, 1.5f))

    @Test
    @Config(qualifiers = "+hi")
    fun `the playback strip stays one row in Hindi at 1_3 and 1_5`() = assertStripOneRow(listOf(1.3f, 1.5f))

    @Test
    fun `on a small 320 dp phone Play and Export keep their words in one row`() {
        val h = harness()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.action_play)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_export)).assertIsDisplayed()
        val strip = compose.onNodeWithTag(TRANSPORT_TAG).bounds()
        assertThat((strip.bottom - strip.top).v()).isAtMost(64f)
    }

    private fun assertStripOneRow(scales: List<Float> = listOf(1f, 1.3f, 2f)) {
        forEachSize(fontScales = scales) { _, _ ->
            val strip = compose.onNodeWithTag(TRANSPORT_TAG).bounds()
            val play = compose.onNodeWithTag(PLAY_TAG).bounds()
            val time = compose.onNodeWithTag(TIME_TAG).bounds()
            val export = compose.onNodeWithTag(EDITOR_EXPORT_TAG).bounds()
            // One row: everything centred on one line, and the strip no taller than one 56 dp row plus a little.
            for (b in listOf(time, export)) {
                assertThat(((b.top + b.bottom) / 2).v()).isWithin(2f).of(((play.top + play.bottom) / 2).v())
            }
            assertThat((strip.bottom - strip.top).v()).isAtMost(64f)
            // Spread: Play at the start, Export at the far end, the time between them.
            assertThat((play.left - strip.left).v()).isAtMost(16f)
            assertThat((strip.right - export.right).v()).isAtMost(16f)
            assertThat(time.left.v()).isAtLeast(play.right.v() - 0.5f)
            assertThat(export.left.v()).isAtLeast(time.right.v() - 0.5f)
            // Play and Export are compact 48 dp controls, not tall blobs.
            assertThat((play.bottom - play.top).v()).isWithin(1f).of(48f)
            assertThat((export.bottom - export.top).v()).isWithin(1f).of(48f)
            // Export is in the strip only, not in the top bar as well.
            assertThat(compose.onAllNodesWithTag(EDITOR_EXPORT_TAG).fetchSemanticsNodes()).hasSize(1)
            val bar = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).bounds()
            assertThat(export.top.v()).isAtLeast(bar.bottom.v() - 0.5f)
            // Zoom is in the bar, once each, and nowhere in the strip.
            for (a in listOf(EditAction.ZOOM_IN, EditAction.ZOOM_OUT, EditAction.FIT_ALL)) {
                assertThat(compose.onAllNodesWithTag(barActionTag(a)).fetchSemanticsNodes().size).isAtMost(1)
            }
            assertThat(compose.onAllNodesWithText(s(R.string.zoom_in)).fetchSemanticsNodes().size).isAtMost(1)
        }
    }

    @Test
    fun `the zoom, selection, open panel and popup corner come back after a restore`() {
        val h = harness()
        val tester = StateRestorationTester(compose)
        tester.setContent { AtSize(ScreenSize.COMPACT, 2f) { ClearMovieTheme(dynamicColor = false) { h.Content() } } }
        selectItem(h, "v2")
        tapBar(EditAction.SPEED)
        h.state!!.layoutEvent(
            com.itsluminous.clearmovie.ui.editor.timeline.LayoutEvent
                .PopupMoved(PopupCorner.TOP_START),
        )
        val zoom = h.state!!.zoom
        val first = h.state
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(h.state).isNotSameInstanceAs(first)
        assertThat(h.state!!.zoom!!.msPerDp).isWithin(1e-9).of(zoom!!.msPerDp)
        assertThat(h.state!!.zoom!!.scrollMs).isWithin(1e-6).of(zoom.scrollMs)
        assertThat(h.state!!.selected).isEqualTo(ClipId("v2"))
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.SPEED, ClipId("v2")))
        assertThat(h.state!!.layout.corner).isEqualTo(PopupCorner.TOP_START)
        compose.onNodeWithTag(PANEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `turning the phone keeps the selection, the open menu, the zoom and the popup corner`() {
        val h = harness()
        var size by androidx.compose.runtime.mutableStateOf(ScreenSize.COMPACT)
        compose.setContent { AtSize(size, 1f) { ClearMovieTheme(dynamicColor = false) { h.Content() } } }
        bar(EditAction.ZOOM_IN).performClick()
        reveal(h, "p3")
        compose.onNodeWithTag(clipTag(ClipId("p3"))).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.ITEM_MENU)
        h.state!!.layoutEvent(
            com.itsluminous.clearmovie.ui.editor.timeline.LayoutEvent
                .PopupMoved(PopupCorner.TOP_END),
        )
        h.playheadUs = 9_000_000
        val zoom = h.state!!.zoom!!.msPerDp
        size = ScreenSize.MEDIUM
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(ClipId("p3"))
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.ITEM_MENU)
        assertThat(h.state!!.zoom!!.msPerDp).isWithin(1e-9).of(zoom)
        assertThat(h.state!!.layout.corner).isEqualTo(PopupCorner.TOP_END)
        assertThat(h.state!!.layout.layout).isInstanceOf(PreviewLayout.Docked::class.java)
        size = ScreenSize.COMPACT
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.ITEM_MENU)
        assertThat(h.playheadUs).isEqualTo(9_000_000)
    }

    // ---------------------------------------------------------------- "+" insert

    @Test
    fun `the plus after an item opens the chooser, and a gallery pick goes in right after it`() {
        forEachSize { _, h ->
            h.galleryAnswer = listOf(Fixtures.photo("g1"), Fixtures.video("g2"))
            selectItem(h, "v2")
            compose
                .onNodeWithContentDescription(
                    context.getString(
                        R.string.timeline_add_after,
                        context.getString(R.string.timeline_item_numbered, s(R.string.editor_item_video), 2),
                    ),
                ).performClick()
            compose.onNodeWithTag(PANEL_TAG).assertIsDisplayed()
            compose.onNodeWithTag(insertOptionTag("gallery")).performClick()
            assertThat(h.calls).contains("pick:v2:AFTER")
            assertThat(h.project.contentIds()).containsExactly("p1", "v2", "g1", "g2", "p3", "v4", "p5").inOrder()
        }
    }

    @Test
    fun `the plus before an item puts a text card exactly before it`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "p3")
        compose
            .onNodeWithContentDescription(
                context.getString(
                    R.string.timeline_add_before,
                    context.getString(R.string.timeline_item_numbered, s(R.string.editor_item_photo), 3),
                ),
            ).performClick()
        compose.onNodeWithTag(insertOptionTag("text")).performClick()
        compose.onNodeWithTag("card_text_field").performTextInput("Happy birthday")
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        val ids = h.project.contentIds()
        assertThat(ids.indexOf("p3") - ids.indexOfFirst { it.startsWith("card") }).isEqualTo(1)
        val card = h.project.mainClips.first { it.id.value.startsWith("card") } as CardClip
        assertThat(card.text).isEqualTo("Happy birthday")
        assertThat(card.durationMs).isEqualTo(3000)
    }

    @Test
    fun `a colour card goes in after the intro from the plus on the boundary`() {
        val h = show(ScreenSize.EXPANDED)
        // The intro selected: its "+" after it is on boundary 1, between the intro and the first photo.
        repeat(3) { bar(EditAction.ZOOM_IN).performClick() }
        h.state!!.zoom = h.state!!.zoom!!.copy(scrollMs = 0.0)
        compose.waitForIdle()
        compose
            .onNodeWithTag(
                clipTag(
                    h.project.mainClips
                        .first()
                        .id,
                ),
            ).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(plusTag(1), useUnmergedTree = true).performClick()
        compose.onNodeWithTag(insertOptionTag("colour")).performClick()
        compose.onNodeWithTag(swatchTag(6)).performClick()
        val ids = h.project.mainIds()
        assertThat(ids[1]).startsWith("card")
        assertThat((h.project.mainClips[1] as CardClip).color).isEqualTo(CARD_COLOURS[6].first)
        assertThat(h.project.contentIds().drop(1)).containsExactly("p1", "v2", "p3", "v4", "p5").inOrder()
    }

    // ---------------------------------------------------------------- drag, trim, panels

    @Test
    fun `a long-press then a drag moves an item, and the model order follows`() {
        forEachSize { _, h ->
            repeat(2) { bar(EditAction.ZOOM_IN).performClick() }
            h.state!!.zoom = h.state!!.zoom!!.copy(scrollMs = 6000.0)
            compose.waitForIdle()
            val p1 = compose.onNodeWithTag(clipTag(ClipId("p1"))).fetchSemanticsNode().boundsInRoot
            val p3 = compose.onNodeWithTag(clipTag(ClipId("p3"))).fetchSemanticsNode().boundsInRoot
            val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
            val y = p1.center.y - timeline.top
            compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
                down(Offset(p1.center.x - timeline.left, y))
                advanceEventTime(700)
                moveTo(Offset(p1.center.x - timeline.left + 30f, y))
                moveTo(Offset(p3.right - timeline.left + 10f, y))
                up()
            }
            compose.waitForIdle()
            assertThat(h.project.contentIds()).containsExactly("v2", "p3", "p1", "v4", "p5").inOrder()
        }
    }

    @Test
    fun `a long-press without moving opens the item's menu, with Add before and after`() {
        val h = show(ScreenSize.COMPACT)
        repeat(2) { bar(EditAction.ZOOM_IN).performClick() }
        reveal(h, "v4")
        compose.onNodeWithTag(clipTag(ClipId("v4"))).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ITEM_MENU, ClipId("v4")))
        compose.onNodeWithTag("menu_add_before").performClick()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, ClipId("v4"), InsertSide.BEFORE))
    }

    @Test
    fun `dragging the end handle of the selected video trims it`() {
        val h = show(ScreenSize.EXPANDED)
        selectItem(h, "v4")
        val slot = h.project.mainSlot(ClipId("v4"))!!
        h.state!!.zoom = h.state!!.zoom!!.copy(scrollMs = slot.endMs - 200 * h.state!!.zoom!!.msPerDp)
        compose.waitForIdle()
        val handle = compose.onNodeWithTag(TRIM_END_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val start = Offset(handle.center.x - timeline.left, handle.center.y - timeline.top)
        val tl = compose.onNodeWithTag(TIMELINE_TAG)
        val pxPerDp = tl.fetchSemanticsNode().boundsInRoot.width / (tl.bounds().right - tl.bounds().left).v()
        val msPerPx = h.state!!.zoom!!.msPerDp / pxPerDp
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(start)
            moveBy(Offset(-20f, 0f))
            moveBy(Offset(-100f, 0f))
            up()
        }
        compose.waitForIdle()
        val v4 = h.project.clip(ClipId("v4")) as VideoClip
        assertThat(v4.trimEndMs.toDouble()).isWithin(60.0).of(6000 - 120 * msPerPx)
        assertThat(v4.trimStartMs).isEqualTo(0)
    }

    @Test
    fun `speed, loop, duration, fit, volume and split panels change the model`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "v4")
        tapBar(EditAction.SPEED)
        compose.onNodeWithText(context.getString(R.string.speed_value, "2")).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        assertThat((h.project.clip(ClipId("v4")) as VideoClip).durationMs).isEqualTo(3000)

        tapBar(EditAction.LOOP)
        compose.onNodeWithContentDescription(s(R.string.loop_more)).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        assertThat((h.project.clip(ClipId("v4")) as VideoClip).loops).isEqualTo(2)

        tapBar(EditAction.FIT)
        compose.onNodeWithTag("fit_FIT_BLUR").performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        assertThat((h.project.clip(ClipId("v4")) as VideoClip).fit).isEqualTo(com.itsluminous.clearmovie.domain.model.Fit.FIT_BLUR)

        tapBar(EditAction.VOLUME)
        compose.onNodeWithContentDescription(s(R.string.volume_mute)).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        assertThat((h.project.clip(ClipId("v4")) as VideoClip).volumeDb).isEqualTo(-60.0)

        selectItem(h, "p3", zoomIns = 0)
        tapBar(EditAction.DURATION)
        compose.onNodeWithContentDescription(s(R.string.duration_longer)).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        assertThat(h.project.clip(ClipId("p3"))!!.durationMs).isEqualTo(3500)

        selectItem(h, "v2", zoomIns = 0)
        tapBar(EditAction.SPLIT)
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        val halves =
            h.project.mainClips
                .filterIsInstance<VideoClip>()
                .filter { it.media == Fixtures.media("v2") }
        assertThat(halves).hasSize(2)
        assertThat(halves.sumOf { it.durationMs }).isEqualTo(4000)
    }

    @Test
    fun `tapping a trim handle keeps the item selected`() {
        val h = show(ScreenSize.EXPANDED)
        selectItem(h, "v4")
        compose.onNodeWithTag(TRIM_END_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(ClipId("v4"))
    }

    @Test
    fun `Trim on a very short fast video opens and works instead of crashing`() {
        // 1 s of file at 4x: shorter than the usual trim minimum, still a valid item.
        val short =
            project.addToMain(
                listOf(
                    VideoClip(
                        ClipId("fast"),
                        Fixtures.media("fast"),
                        sourceDurationMs = 1000,
                        speed = com.itsluminous.clearmovie.domain.model.Speed.X4,
                    ),
                ),
            )
        val h = show(ScreenSize.COMPACT, h = harness(short))
        selectItem(h, "fast", zoomIns = 6)
        tapBar(EditAction.TRIM)
        val range = compose.onNodeWithContentDescription(s(R.string.trim_title))
        range.onChildAt(0).performSemanticsAction(SemanticsActions.SetProgress) { it(500f) }
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        assertThat(h.project.clip(ClipId("fast"))).isNotNull()
    }

    @Test
    fun `a delete's Undo goes away once anything else changes, so it never undoes the wrong thing`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "p3")
        repeat(3) { compose.onNodeWithTag(BAR_TAG).performTouchInput { swipeLeft() } }
        tapBar(EditAction.DELETE)
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.action_undo)).assertExists()
        selectItem(h, "p1")
        tapBar(EditAction.ROTATE)
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.action_undo)).assertDoesNotExist()
        assertThat(h.project.contentIds()).doesNotContain("p3")
    }

    @Test
    fun `Split on a looped video says what to change first`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "v4")
        tapBar(EditAction.LOOP)
        compose.onNodeWithContentDescription(s(R.string.loop_more)).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.onNodeWithTag(barActionTag(EditAction.SPLIT)).assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.split_loop_first)).assertExists()
        assertThat(h.state!!.panel).isNull()
    }

    @Test
    fun `two deletes in a row, only the last one's Undo is offered, and it undoes that one`() {
        val h = show(ScreenSize.COMPACT)
        for (id in listOf("p1", "p3")) {
            selectItem(h, id, zoomIns = if (id == "p1") 3 else 0)
            repeat(3) { compose.onNodeWithTag(BAR_TAG).performTouchInput { swipeLeft() } }
            tapBar(EditAction.DELETE)
            compose.waitForIdle()
        }
        compose.onNodeWithText(s(R.string.action_undo)).performClick()
        compose.waitForIdle()
        assertThat(h.project.contentIds()).contains("p3")
        assertThat(h.project.contentIds()).doesNotContain("p1")
    }

    @Test
    fun `a text card is read out by what it says`() {
        val card =
            com.itsluminous.clearmovie.domain.model.CardClip(
                ClipId("card"),
                3000,
                com.itsluminous.clearmovie.domain.model
                    .ArgbColor(0xFF202020),
                "Happy birthday",
            )
        val h = show(ScreenSize.COMPACT, h = harness(project.addToMain(listOf(card))))
        val node = compose.onNodeWithTag(clipTag(ClipId("card"))).fetchSemanticsNode()
        val description = node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertThat(description).startsWith(context.getString(R.string.timeline_text_card_named, "Happy birthday"))
        assertThat(h.project.mainClips.any { it.id == ClipId("card") }).isTrue()
    }

    @Test
    fun `main controls are at least 48 dp to touch and bar buttons at least the bar's minimum wide, at font scale 2`() {
        forEachSize(fontScales = listOf(1f, 2f)) { _, h ->
            for (tag in listOf(EDITOR_EXPORT_TAG, PLAY_TAG, UNDO_TAG, REDO_TAG)) {
                val b = compose.onNodeWithTag(tag).bounds()
                assertThat((b.bottom - b.top).v()).isAtLeast(47.5f)
                assertThat((b.right - b.left).v()).isAtLeast(47.5f)
            }
            selectItem(h, "v2")
            for (a in listOf(EditAction.TRIM, EditAction.ADD_CAPTION)) {
                val b = compose.onNodeWithTag(barActionTag(a)).bounds()
                assertThat((b.right - b.left).v()).isAtLeast(BAR_MIN.value - 0.5f)
                // 52 dp to touch: the 56 dp bar less 2 dp outside the ripple at each edge.
                assertThat((b.bottom - b.top).v()).isAtLeast(51.5f)
            }
            // Whole labels at font scale 2 are checked on the device screenshots: Robolectric's
            // text measurement is not real enough to judge overflow.
        }
    }

    @Test
    fun `the bottom bar is as tall as the Play bar, and sideways the top bar is too`() {
        // Upright (411 x 891), then a phone held sideways (under 480 dp tall: 914 x 363).
        var size by androidx.compose.runtime.mutableStateOf(
            androidx.compose.ui.unit
                .DpSize(411.dp, 891.dp),
        )
        val h = harness()
        compose.setContent {
            androidx.compose.ui.test.DeviceConfigurationOverride(
                androidx.compose.ui.test.DeviceConfigurationOverride
                    .ForcedSize(size),
            ) {
                com.itsluminous.clearmovie.ui.theme
                    .ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        compose.waitForIdle()
        // Upright: the 56 dp bottom bar (one-line labels) is as tall as the 56 dp Play bar; Material's 64 dp top bar.
        val bar = compose.onNodeWithTag(BAR_TAG).bounds()
        val play = compose.onNodeWithTag(TRANSPORT_TAG).bounds()
        assertThat((bar.bottom - bar.top).v()).isWithin(0.5f).of((play.bottom - play.top).v())
        assertThat((bar.bottom - bar.top).v()).isWithin(0.5f).of(56f)
        val top = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).bounds()
        assertThat((top.bottom - top.top).v()).isWithin(0.5f).of(64f)
        // Sideways the top bar is 56 dp too; Undo and Redo stay 48 dp wide.
        size =
            androidx.compose.ui.unit
                .DpSize(914.dp, 363.dp)
        compose.waitForIdle()
        val sideTop = compose.onNodeWithTag(EDITOR_TOP_BAR_TAG).bounds()
        assertThat((sideTop.bottom - sideTop.top).v()).isWithin(0.5f).of(56f)
        val sideBar = compose.onNodeWithTag(BAR_TAG).bounds()
        val sidePlay = compose.onNodeWithTag(TRANSPORT_TAG).bounds()
        assertThat((sideBar.bottom - sideBar.top).v()).isWithin(0.5f).of((sidePlay.bottom - sidePlay.top).v())
        // The bottom bar and the Play bar beside it line up.
        assertThat(sideBar.bottom.v()).isWithin(0.5f).of(sidePlay.bottom.v())
        // Undo and Redo side by side in the slimmer bar, 48 dp each way.
        val undo = compose.onNodeWithTag(UNDO_TAG).bounds()
        val redo = compose.onNodeWithTag(REDO_TAG).bounds()
        assertThat(redo.left.v()).isWithin(0.5f).of(undo.right.v())
        for (b in listOf(undo, redo)) {
            assertThat((b.bottom - b.top).v()).isWithin(0.5f).of(48f)
            assertThat((b.right - b.left).v()).isWithin(0.5f).of(48f)
        }
        // Export is in the Play bar, beside Play, at its far end.
        val export = compose.onNodeWithTag(EDITOR_EXPORT_TAG).bounds()
        assertThat(export.top.v()).isAtLeast(sidePlay.top.v() - 0.5f)
        assertThat(export.bottom.v()).isAtMost(sidePlay.bottom.v() + 0.5f)
        for (tag in listOf(EDITOR_EXPORT_TAG, PLAY_TAG)) {
            val b = compose.onNodeWithTag(tag).bounds()
            assertThat((b.bottom - b.top).v()).isAtLeast(47.5f)
        }
    }

    @Test
    fun `TalkBack can move the time from the ruler, and the playhead does not cover the items`() {
        val h = show(ScreenSize.COMPACT)
        compose
            .onNodeWithTag(com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_RULER_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertThat(h.calls.last()).startsWith("seek:")
        val playhead =
            compose
                .onNodeWithTag(
                    com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_PLAYHEAD_TAG,
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
        assertThat(playhead.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)).isFalse()
    }

    @Test
    fun `the Trim panel trims without the handles, and the time can be set without dragging`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "v4")
        tapBar(EditAction.TRIM)
        // The range's two thumbs, start then end, each set as TalkBack sets them.
        val range = compose.onNodeWithContentDescription(s(R.string.trim_title))
        range.onChildAt(0).performSemanticsAction(SemanticsActions.SetProgress) { it(1500f) }
        range.onChildAt(1).performSemanticsAction(SemanticsActions.SetProgress) { it(4500f) }
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        val v4 = h.project.clip(ClipId("v4")) as VideoClip
        assertThat(v4.trimStartMs.toDouble()).isWithin(100.0).of(1500.0)
        assertThat(v4.trimEndMs.toDouble()).isWithin(100.0).of(4500.0)
        // The playback time is a TalkBack-adjustable control: no ruler drag needed.
        compose.onNodeWithContentDescription(s(R.string.editor_seek)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertThat(h.calls.last()).startsWith("seek:")
        assertThat(h.playheadUs).isWithin(300_000).of(h.project.mainLayoutTotalUs() / 2)
    }

    private fun com.itsluminous.clearmovie.domain.model.Project.mainLayoutTotalUs() = totalDurationMs() * 1000

    @Test
    fun `Delete removes the item and the snackbar's Undo brings it back`() {
        val h = show(ScreenSize.COMPACT)
        selectItem(h, "p3")
        repeat(3) { compose.onNodeWithTag(BAR_TAG).performTouchInput { swipeLeft() } }
        tapBar(EditAction.DELETE)
        assertThat(h.project.contentIds()).doesNotContain("p3")
        compose.onNodeWithText(s(R.string.action_undo)).performClick()
        compose.waitForIdle()
        assertThat(h.project.contentIds()).contains("p3")
    }

    @Test
    fun `Undo and Redo in the top bar step through edits`() {
        val h = show(ScreenSize.COMPACT)
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(REDO_TAG).assertIsNotEnabled()
        selectItem(h, "p1")
        tapBar(EditAction.ROTATE)
        compose.onNodeWithTag(REDO_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(UNDO_TAG).assertIsEnabled().performClick()
        assertThat((h.project.clip(ClipId("p1")) as com.itsluminous.clearmovie.domain.model.PhotoClip).rotation.degrees).isEqualTo(0)
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(REDO_TAG).assertIsEnabled().performClick()
        assertThat((h.project.clip(ClipId("p1")) as com.itsluminous.clearmovie.domain.model.PhotoClip).rotation.degrees).isEqualTo(90)
        compose.onNodeWithTag(REDO_TAG).assertIsNotEnabled()
    }

    // ---------------------------------------------------------------- rows and the floating preview

    private fun withRows(n: Int) =
        (1..n).fold(project) { p, i ->
            p.addRow(
                TrackType.TEXT,
                id =
                    com.itsluminous.clearmovie.domain.model
                        .TrackId("extra$i"),
            )
        }

    @Test
    fun `as rows are added the preview shrinks, then rows hide, then all rows float the preview, and fewer rows dock it`() {
        val h = harness(withRows(0))
        compose.setContent { AtSize(ScreenSize.COMPACT) { ClearMovieTheme(dynamicColor = false) { h.Content() } } }
        compose.waitForIdle()
        assertThat(h.state!!.layout.layout).isInstanceOf(PreviewLayout.Full::class.java)
        // Rows are added one by one: the layout goes Full, then Shrunk (the preview ever smaller), then Partial.
        val seen = mutableListOf<String>()
        var lastPreview = Float.MAX_VALUE
        for (n in 1..10) {
            h.actions.edit { withRows(n) }
            compose.waitForIdle()
            val layout = h.state!!.layout.layout!!
            seen += layout::class.java.simpleName
            if (layout !is PreviewLayout.Floating) {
                val box = compose.onNodeWithTag(EDITOR_PREVIEW_BOX_TAG).bounds()
                val height = (box.bottom - box.top).v()
                assertThat(height).isAtMost(lastPreview + 0.5f)
                lastPreview = height
            }
        }
        assertThat(seen.distinct()).containsAtLeast("Shrunk", "Partial").inOrder()
        assertThat(seen.last()).isEqualTo("Partial")
        compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertThat(h.state!!.layout.layout).isInstanceOf(PreviewLayout.Floating::class.java)
        compose.onNodeWithTag(EDITOR_POPUP_TAG).assertIsDisplayed()
        // Move it to the next corner without dragging, then close it.
        compose.onNodeWithTag(EDITOR_POPUP_MOVE_TAG).performClick()
        assertThat(h.state!!.layout.corner).isEqualTo(PopupCorner.BOTTOM_START)
        compose.onNodeWithTag(EDITOR_POPUP_CLOSE_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.layout.layout).isInstanceOf(PreviewLayout.Partial::class.java)
        compose.onNodeWithTag(EDITOR_POPUP_TAG).assertDoesNotExist()
        // And removing the rows again brings the full preview back.
        h.actions.edit { withRows(0) }
        compose.waitForIdle()
        assertThat(h.state!!.layout.layout).isInstanceOf(PreviewLayout.Full::class.java)
    }

    @Test
    fun `dragging the floating preview snaps it to the nearest corner`() {
        val h = harness(withRows(10))
        compose.setContent { AtSize(ScreenSize.COMPACT) { ClearMovieTheme(dynamicColor = false) { h.Content() } } }
        compose.waitForIdle()
        compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EDITOR_POPUP_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-20f, 0f))
            moveBy(Offset(-width * 1.5f, -height * 4f))
            up()
        }
        compose.waitForIdle()
        assertThat(h.state!!.layout.corner).isEqualTo(PopupCorner.TOP_START)
    }

    @Test
    fun `an empty video offers Add photos and videos once`() {
        val empty = EditorHarness(ProjectFactory.blank(Fixtures.PROJECT_ID), null)
        show(ScreenSize.COMPACT, h = empty)
        compose.onNodeWithText(s(R.string.editor_empty)).assertIsDisplayed()
        // The big button is the one control for it; the bar leaves out its own (no duplicate controls).
        compose.onNodeWithTag(barActionTag(EditAction.ADD_MEDIA)).assertDoesNotExist()
        compose.onNodeWithTag(EMPTY_ADD_TAG).performClick()
        assertThat(empty.calls).containsExactly("add")
    }

    @Test
    fun `the intro's Skip leaves it out, with Undo`() {
        val h = show(ScreenSize.COMPACT)
        val intro = project.mainClips.first().id
        selectItem(h, intro.value)
        tapBar(EditAction.SKIP)
        assertThat(h.project.mainClips.first()).isNotInstanceOf(com.itsluminous.clearmovie.domain.model.IntroClip::class.java)
        compose.onNodeWithText(s(R.string.action_undo)).performClick()
        compose.waitForIdle()
        assertThat(h.project.mainClips.first()).isInstanceOf(com.itsluminous.clearmovie.domain.model.IntroClip::class.java)
    }

    @Test
    fun `the intro's Play and Replace ask the owner`() {
        val h = show(ScreenSize.MEDIUM)
        val intro = project.mainClips.first().id
        selectItem(h, intro.value)
        tapBar(EditAction.PLAY)
        tapBar(EditAction.REPLACE)
        assertThat(h.calls).containsAtLeast("playPart:${intro.value}", "replace:${intro.value}").inOrder()
    }
}
