package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.CaptionWords
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.bringToTop
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TRIM_END_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.editor.timeline.plusTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowHeaderTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowTag
import com.itsluminous.clearmovie.ui.editor.timeline.timedPlusTag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rows of every kind (ROADMAP cm-tracks, Compose UI acceptance): reorder by
 * the icon, the long-press menu on the icon and on the content, what shows
 * on top, "+" before and after on each kind of row (with a new row where it
 * would overlap), every row on a timeline that scrolls up and down as the
 * window gets shorter (cm-timeline-scroll), and everything kept across a
 * restore and a turn of the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class TracksScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val ids = SequentialIds("k")

    /**
     * The preset project with five items on MAIN, two captions that touch
     * (10-12 s and 12-14 s), a picture-in-picture photo (10-13 s) and the
     * user's own music (10-13 s) on rows of their own.
     */
    private val project: Project =
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
            ).addText("hello", 10_000, 2000, ids)
            .addText("next", 12_000, 2000, ids)
            .addTimedClip(Fixtures.photo("pip1", 3000, pip = PipPlacement.DEFAULT).withStartMs(10_000), ids = ids)
            .addTimedClip(Fixtures.audio("m1", 10_000, 3000), AudioRole.MUSIC, ids)

    /** The row of the clip with id [clip], or of the caption that says [clip]. */
    private fun Project.rowOf(clip: String): TrackId = trackOf(idOf(clip))!!.id

    private fun Project.idOf(clip: String): ClipId =
        tracks.flatMap { it.clips }.firstOrNull { (it as? TextClip)?.text == clip }?.id ?: ClipId(clip)

    private fun Project.keys(): List<String> = tracks.map { it.id.value }

    private fun Project.frameRow(): TrackId = tracks.single { it.presetRole == PresetRole.FRAME }.id

    private fun show(
        p: Project = project,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
    ): EditorHarness {
        val h = EditorHarness(p, Fixtures.preset)
        compose.setContent { h.At(size, fontScale) }
        compose.waitForIdle()
        return h
    }

    /** 20 ms per dp from [fromMs]: items of two seconds are 100 dp, wide enough for their "+". */
    private fun zoomTo(
        h: EditorHarness,
        fromMs: Double,
    ) {
        h.state!!.zoom = ZoomState(msPerDp = 20.0, scrollMs = fromMs, fitAll = false)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun timeline(): Rect = bounds(TIMELINE_TAG)

    /** [rect]'s centre, relative to the timeline (where its touch input is given). */
    private fun centreIn(rect: Rect): Offset = rect.center - timeline().topLeft

    private val density get() = context.resources.displayMetrics.density

    /** A bar button, scrolled into view first (the bar scrolls sideways). */
    private fun barButton(action: EditAction): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(action)))
        return compose.onNodeWithTag(barActionTag(action))
    }

    /** Selects [anchor] (only the selected item has side "+" buttons), then taps its "+" on [side]. */
    private fun tapPlus(
        h: EditorHarness,
        anchor: ClipId,
        side: InsertSide,
    ) {
        compose.runOnIdle {
            h.state!!.selectedRow = null
            h.state!!.selected = anchor
        }
        compose.waitForIdle()
        check(timedPlusTag(anchor, side) in plusTags()) {
            "no ${timedPlusTag(anchor, side)} in ${plusTags()}, selected ${h.state!!.selected}, zoom ${h.state!!.zoom}"
        }
        compose.onNodeWithTag(timedPlusTag(anchor, side), useUnmergedTree = true).performClick()
    }

    /** The test tags of every "+" on screen (any row, the empty-row "+" included). */
    private fun plusTags(): List<String> {
        val tag = androidx.compose.ui.semantics.SemanticsProperties.TestTag
        val isPlus =
            androidx.compose.ui.test.SemanticsMatcher("a plus") { n ->
                n.config.getOrElseNullable(tag) { null }?.let { it.startsWith("timeline_plus_") || it.startsWith("timeline_row_add_") } ==
                    true
            }
        return compose.onAllNodes(isPlus, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[tag] }
    }

    // ---------------------------------------------------------------- reorder by the icon

    @Test
    fun `a long-press on a row's icon then a drag down two rows moves it on screen and not in the video`() {
        val h = show()
        val textRow = h.project.rowOf("hello").value
        val before = h.project.keys()
        val stacking = h.project.stacking
        assertThat(before.first()).isEqualTo(textRow)
        val start = centreIn(bounds(rowHeaderTag(textRow)))
        // Two rows of 48 dp with their 8 dp gaps, and a little more.
        val down = 2.5f * 56 * density
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(start)
            advanceEventTime(700)
            moveTo(start + Offset(0f, 20f * density))
            moveTo(start + Offset(0f, down / 2))
            moveTo(start + Offset(0f, down))
            up()
        }
        compose.waitForIdle()
        val expected = before.toMutableList().apply { add(2, removeAt(0)) }
        assertThat(h.project.keys()).isEqualTo(expected)
        assertThat(h.project.stacking).isEqualTo(stacking)
        // A drag is not a menu, and the moved row is selected so it stays shown.
        assertThat(h.state!!.panel).isNull()
        assertThat(h.state!!.selectedRow).isEqualTo(TrackId(textRow))
    }

    @Test
    fun `Move row down in the bar and the icon's TalkBack action do the same without dragging`() {
        val h = show()
        val textRow = h.project.rowOf("hello").value
        val before = h.project.keys()
        compose.onNodeWithTag(rowHeaderTag(textRow), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.selectedRow).isEqualTo(TrackId(textRow))
        barButton(EditAction.MOVE_ROW_UP).assertIsNotEnabled()
        barButton(EditAction.MOVE_ROW_DOWN).performClick()
        compose.waitForIdle()
        assertThat(h.project.keys()).isEqualTo(before.toMutableList().apply { add(1, removeAt(0)) })
        val moveUp =
            compose
                .onNodeWithTag(rowHeaderTag(textRow), useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == s(R.string.row_move_up) }
        compose.runOnIdle { moveUp.action() }
        compose.waitForIdle()
        assertThat(h.project.keys()).isEqualTo(before)
    }

    // ---------------------------------------------------------------- the long-press menu

    @Test
    fun `a long-press let go on a row's icon opens the row menu, and Show on top puts a text row on top`() {
        val pipRow = project.rowOf("pip1")
        val h = show(project.bringToTop(pipRow))
        val textRow = h.project.rowOf("hello")
        val screen = h.project.keys()
        assertThat(h.project.stacking.last()).isEqualTo(pipRow)
        compose.onNodeWithTag(rowHeaderTag(textRow.value), useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ROW_MENU, trackId = textRow))
        // The row the menu is about stays on screen above it.
        compose.onNodeWithTag(rowTag(textRow.value)).assertIsDisplayed()
        for (label in listOf(
            R.string.stack_show_on_top,
            R.string.stack_bring_forward,
            R.string.stack_send_backward,
            R.string.stack_show_at_bottom,
        )) {
            compose.onNodeWithText(s(label)).assertExists()
        }
        compose.onNodeWithTag(rowMenuTag(StackMove.TO_TOP.name)).performClick()
        compose.waitForIdle()
        assertThat(h.project.stacking.last()).isEqualTo(textRow)
        assertThat(h.project.keys()).isEqualTo(screen)
        assertThat(h.state!!.panel).isNull()
    }

    @Test
    fun `a long-press let go on the content opens the menu, on an item and on the row empty space`() {
        val h = show()
        zoomTo(h, 9_000.0)
        val hello = h.project.idOf("hello")
        compose.onNodeWithTag(clipTag(hello)).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ITEM_MENU, hello))
        // The item's menu has its row's stacking too.
        compose.onNodeWithText(s(R.string.stack_send_backward)).assertExists()
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        // The music row at 9.3 s, before its item: the row menu.
        val musicRow = h.project.rowOf("m1").value
        val row = bounds(rowTag(musicRow))
        val at = Offset(row.left + (48 + 28 + 15) * density, row.center.y) - timeline().topLeft
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(at)
            advanceEventTime(700)
            up()
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ROW_MENU, trackId = TrackId(musicRow)))
        compose.onNodeWithText(s(R.string.action_mute_row)).assertExists()
    }

    @Test
    fun `a long-press followed by movement opens no menu, on the icon or on an item`() {
        val h = show()
        zoomTo(h, 9_000.0)
        val textRow = h.project.rowOf("hello").value
        val before = h.project
        val header = centreIn(bounds(rowHeaderTag(textRow)))
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(header)
            advanceEventTime(700)
            // Past twice the touch slop (a held finger's), but not far enough to pass another row.
            moveTo(header + Offset(0f, 20f * density))
            moveTo(header + Offset(0f, 40f * density))
            up()
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        assertThat(h.project.keys()).isEqualTo(before.keys())
        // On an item: held, then moved sideways, it moves 1 s later in time instead of opening its menu.
        val pip = centreIn(bounds(clipTag(ClipId("pip1"))))
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(pip)
            advanceEventTime(700)
            moveTo(pip + Offset(25f * density, 0f))
            moveTo(pip + Offset(50f * density, 0f))
            up()
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        assertThat(h.project.clip(ClipId("pip1"))!!.startMs).isEqualTo(11_000)
    }

    @Test
    @Config(qualifiers = "+hi")
    fun `the menu's labels come from the Hindi resources`() =
        assertMenuLabels("सबसे ऊपर दिखाएँ", "एक ऊपर लाएँ", "एक नीचे भेजें", "सबसे नीचे दिखाएँ")

    @Test
    @Config(qualifiers = "+b+hi+Latn")
    fun `the menu's labels come from the Hinglish resources`() =
        assertMenuLabels("Sabse upar dikhayein", "Ek upar layein", "Ek neeche bhejein", "Sabse neeche dikhayein")

    private fun assertMenuLabels(vararg expected: String) {
        val h = show()
        val ids =
            listOf(R.string.stack_show_on_top, R.string.stack_bring_forward, R.string.stack_send_backward, R.string.stack_show_at_bottom)
        assertThat(ids.map(::s)).containsExactlyElementsIn(expected).inOrder()
        compose.onNodeWithTag(rowHeaderTag(h.project.rowOf("pip1").value), useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        for (label in expected) compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText("Show on top").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- "+" on every row

    @Test
    fun `plus after and before a caption add text there, and a new row where it would overlap`() {
        val h = show()
        zoomTo(h, 9_000.0)
        val hello =
            h.project.tracks
                .flatMap { it.clips }
                .first { (it as? TextClip)?.text == "hello" }
        val next =
            h.project.tracks
                .flatMap { it.clips }
                .first { (it as? TextClip)?.text == "next" }
        val textRow = h.project.rowOf(hello.id.value)
        val textRows = h.project.tracks.count { it.type == TrackType.TEXT }

        fun addText(
            anchor: ClipId,
            side: InsertSide,
            words: String,
        ): TextClip {
            tapPlus(h, anchor, side)
            compose.waitForIdle()
            assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, anchor, side))
            compose.onNodeWithTag(insertOptionTag("text")).performClick()
            // A new caption starts with the app's own words; the user's replace them.
            compose.onNodeWithTag(TEXT_FIELD_TAG).performTextReplacement(words)
            compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
            compose.waitForIdle()
            return h.project.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .single { it.text == words }
        }
        // After "next": nothing there, so it goes on the same row, starting where "next" ends.
        val after = addText(next.id, InsertSide.AFTER, "after")
        assertThat(after.startMs).isEqualTo(14_000)
        assertThat(h.project.rowOf(after.id.value)).isEqualTo(textRow)
        assertThat(h.state!!.selected).isEqualTo(after.id)
        // Before "hello": it ends where "hello" starts.
        zoomTo(h, 6_000.0)
        val before = addText(hello.id, InsertSide.BEFORE, "before")
        assertThat(before.endMs).isEqualTo(10_000)
        assertThat(h.project.rowOf(before.id.value)).isEqualTo(textRow)
        // Between "hello" and "next" (they touch): it would cover "next", so it gets a new text row.
        zoomTo(h, 9_000.0)
        val between = addText(hello.id, InsertSide.AFTER, "between")
        assertThat(between.startMs).isEqualTo(12_000)
        assertThat(h.project.rowOf(between.id.value)).isNotEqualTo(textRow)
        assertThat(h.project.tracks.count { it.type == TrackType.TEXT }).isEqualTo(textRows + 1)
    }

    @Test
    fun `plus on a picture-in-picture row adds from the gallery after and before, and a new row on overlap`() {
        val h = show()
        zoomTo(h, 9_000.0)
        val pipRow = h.project.rowOf("pip1")
        h.timedAnswer = listOf(Fixtures.photo("pa", 2000, pip = PipPlacement.DEFAULT))
        tapPlus(h, ClipId("pip1"), InsertSide.AFTER)
        compose.onNodeWithTag(insertOptionTag("gallery")).performClick()
        compose.waitForIdle()
        assertThat(h.calls).contains("pick:pip1:AFTER")
        assertThat(h.project.clip(ClipId("pa"))!!.startMs).isEqualTo(13_000)
        assertThat(h.project.rowOf("pa")).isEqualTo(pipRow)
        // Before: 2 s ending at 10 s.
        zoomTo(h, 6_000.0)
        h.timedAnswer = listOf(Fixtures.photo("pb", 2000, pip = PipPlacement.DEFAULT))
        tapPlus(h, ClipId("pip1"), InsertSide.BEFORE)
        compose.onNodeWithTag(insertOptionTag("gallery")).performClick()
        compose.waitForIdle()
        assertThat(h.project.clip(ClipId("pb"))!!.endMs).isEqualTo(10_000)
        // After pip1 again, where "pa" now is: a new picture-in-picture row.
        zoomTo(h, 9_000.0)
        h.timedAnswer = listOf(Fixtures.photo("pc", 2000, pip = PipPlacement.DEFAULT))
        tapPlus(h, ClipId("pip1"), InsertSide.AFTER)
        compose.onNodeWithTag(insertOptionTag("gallery")).performClick()
        compose.waitForIdle()
        assertThat(h.project.rowOf("pc")).isNotEqualTo(pipRow)
        assertThat(h.project.track(h.project.rowOf("pc"))!!.type).isEqualTo(TrackType.VISUAL)
    }

    @Test
    fun `plus on a music row adds a sound after and before it, and a new music row on overlap`() {
        val h = show()
        zoomTo(h, 9_000.0)
        val musicRow = h.project.rowOf("m1")
        h.timedAnswer = listOf(Fixtures.audio("ma", 0, 2000))
        tapPlus(h, ClipId("m1"), InsertSide.AFTER)
        compose.onNodeWithTag(insertOptionTag("sound")).performClick()
        compose.waitForIdle()
        assertThat(h.project.clip(ClipId("ma"))!!.startMs).isEqualTo(13_000)
        assertThat(h.project.rowOf("ma")).isEqualTo(musicRow)
        zoomTo(h, 6_000.0)
        h.timedAnswer = listOf(Fixtures.audio("mb", 0, 2000))
        tapPlus(h, ClipId("m1"), InsertSide.BEFORE)
        compose.onNodeWithTag(insertOptionTag("sound")).performClick()
        compose.waitForIdle()
        assertThat(h.project.clip(ClipId("mb"))!!.endMs).isEqualTo(10_000)
        zoomTo(h, 9_000.0)
        h.timedAnswer = listOf(Fixtures.audio("mc", 0, 2000))
        tapPlus(h, ClipId("m1"), InsertSide.AFTER)
        compose.onNodeWithTag(insertOptionTag("sound")).performClick()
        compose.waitForIdle()
        val mc = h.project.trackOf(ClipId("mc"))!!
        assertThat(mc.id).isNotEqualTo(musicRow)
        assertThat(mc.audioRole).isEqualTo(AudioRole.MUSIC)
        assertThat(h.project.clip(ClipId("mc"))).isInstanceOf(AudioClip::class.java)
    }

    @Test
    fun `plus on the frame row adds an overlay video, on a frame row of its own, before and after`() {
        val h = show()
        val frameRow = h.project.frameRow()
        val frame =
            h.project
                .track(frameRow)!!
                .clips
                .single()
        zoomTo(h, frame.startMs - 1500.0)
        h.timedAnswer = listOf(Fixtures.overlay("oa", 0, 3000))
        tapPlus(h, frame.id, InsertSide.BEFORE)
        compose.onNodeWithTag(insertOptionTag("overlay")).performClick()
        compose.waitForIdle()
        val oa = h.project.clip(ClipId("oa")) as OverlayClip
        assertThat(oa.endMs).isEqualTo(frame.startMs)
        // The preset's frame row holds only the frame: the overlay gets a new frame row.
        val overlayRow = h.project.rowOf("oa")
        assertThat(overlayRow).isNotEqualTo(frameRow)
        assertThat(h.project.track(overlayRow)!!.type).isEqualTo(TrackType.OVERLAY)
        // After the overlay, on its own row, starting where it ends.
        zoomTo(h, oa.startMs - 500.0)
        h.timedAnswer = listOf(Fixtures.overlay("ob", 0, 1000))
        tapPlus(h, ClipId("oa"), InsertSide.AFTER)
        compose.onNodeWithTag(insertOptionTag("overlay")).performClick()
        compose.waitForIdle()
        assertThat(h.project.clip(ClipId("ob"))!!.startMs).isEqualTo(oa.endMs)
        assertThat(h.project.rowOf("ob")).isEqualTo(overlayRow)
    }

    // ---------------------------------------------------------------- where the "+" buttons are

    @Test
    fun `one plus at the end of each row, one in an empty row, and side plus only on the selected item`() {
        val withEmpty = project.addRow(TrackType.TEXT, id = TrackId("emptyText"))
        val h = show(withEmpty, ScreenSize.EXPANDED)
        // Fit all (the start): every row's end is on screen.
        val p = h.project
        val hello = p.idOf("hello")
        val next = p.idOf("next")
        // Nothing selected: per non-empty row only its end "+"; none between "hello" and "next".
        var tags = plusTags()
        assertThat(tags).contains(timedPlusTag(next, InsertSide.AFTER))
        assertThat(
            tags,
        ).containsNoneOf(
            timedPlusTag(hello, InsertSide.BEFORE),
            timedPlusTag(hello, InsertSide.AFTER),
            timedPlusTag(next, InsertSide.BEFORE),
        )
        // One end "+" per shown non-empty row other than MAIN (the preset's own rows included): after its last item.
        val timedRows =
            h.project.tracks.filter { t ->
                t.type != TrackType.MAIN && t.clips.isNotEmpty() &&
                    compose.onAllNodesWithTag(rowTag(t.id.value)).fetchSemanticsNodes().isNotEmpty()
            }
        assertThat(timedRows).isNotEmpty()
        for (t in timedRows) assertThat(tags).contains(timedPlusTag(t.clips.maxBy { it.endMs }.id, InsertSide.AFTER))
        assertThat(tags.count { it.endsWith("_AFTER") || it.endsWith("_BEFORE") }).isEqualTo(timedRows.size)
        // MAIN: one "+", after its last photo or video (boundary 6: intro, five items).
        assertThat(tags.filter { it.matches(Regex("timeline_plus_\\d+")) }).containsExactly(plusTag(6))
        // The empty row: exactly one.
        assertThat(
            tags.filter {
                it.startsWith("timeline_row_add_")
            },
        ).containsExactly(
            com.itsluminous.clearmovie.ui.editor.timeline
                .rowAddTag("emptyText"),
        )
        // Select "hello": its two sides, and still no "+" between other items.
        compose.runOnIdle { h.state!!.selected = hello }
        compose.waitForIdle()
        tags = plusTags()
        assertThat(tags).containsAtLeast(timedPlusTag(hello, InsertSide.BEFORE), timedPlusTag(hello, InsertSide.AFTER))
        assertThat(tags).doesNotContain(timedPlusTag(next, InsertSide.BEFORE))
        // The selection moves: the side "+" go with it.
        compose.runOnIdle { h.state!!.selected = ClipId("v2") }
        compose.waitForIdle()
        tags = plusTags()
        assertThat(tags).containsNoneOf(timedPlusTag(hello, InsertSide.BEFORE), timedPlusTag(hello, InsertSide.AFTER))
        assertThat(tags).containsAtLeast(plusTag(2), plusTag(3))
        // Cleared: back to the end ones only.
        compose.runOnIdle { h.state!!.selected = null }
        compose.waitForIdle()
        assertThat(plusTags()).containsNoneOf(plusTag(2), plusTag(3))
    }

    /** Both side "+" of [clip] exist and lie wholly inside the timeline. */
    private fun assertBothSidesShown(
        h: EditorHarness,
        clip: String,
    ) {
        compose.runOnIdle { h.state!!.selected = ClipId(clip) }
        compose.waitForIdle()
        val i =
            h.project.main.clips
                .indexOfFirst { it.id == ClipId(clip) }
        val t = timeline()
        for (tag in listOf(plusTag(i), plusTag(i + 1))) {
            val b = bounds(tag)
            assertThat(b.left).isAtLeast(t.left + 48 * density - 1)
            assertThat(b.right).isAtMost(t.right + 1)
            assertThat((b.right - b.left) / density).isAtLeast(47.5f)
        }
    }

    @Test
    fun `at fit all the first and last items still get both side plus, wholly on screen`() {
        val h = show(project, ScreenSize.COMPACT)
        assertThat(h.state!!.zoom!!.fitAll).isTrue()
        assertBothSidesShown(h, "p5")
        assertBothSidesShown(h, "p1")
    }

    @Test
    fun `without a preset the first item at the very start still gets its plus before it`() {
        val blank = Fixtures.blankWith(Fixtures.photo("a", 3000), Fixtures.photo("b", 3000), Fixtures.photo("c", 3000))
        val h = show(blank, ScreenSize.COMPACT)
        assertBothSidesShown(h, "a")
        assertBothSidesShown(h, "c")
    }

    @Test
    fun `the plus buttons hide while a trim handle is dragged and come back after, and a tap on a handle opens no chooser`() {
        val h = show(project, ScreenSize.EXPANDED)
        // v2 well inside the view.
        zoomTo(h, h.project.mainSlot(ClipId("v2"))!!.startMs - 2000.0)
        compose.runOnIdle { h.state!!.selected = ClipId("v2") }
        compose.waitForIdle()
        assertThat(plusTags()).containsAtLeast(plusTag(2), plusTag(3))
        val handle = centreIn(bounds(TRIM_END_TAG))
        // A tap on the handle: no chooser.
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput { click(handle) }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        // Mid-drag: no "+" at all.
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(handle)
            moveTo(handle - Offset(20f * density, 0f))
            moveTo(handle - Offset(40f * density, 0f))
        }
        compose.mainClock.advanceTimeBy(1000)
        assertThat(plusTags()).isEmpty()
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput { up() }
        compose.waitForIdle()
        // Back, at the trimmed item's new edges.
        assertThat(plusTags()).containsAtLeast(plusTag(2), plusTag(3))
        assertThat(h.state!!.panel).isNull()
    }

    // ---------------------------------------------------------------- review fixes

    @Test
    fun `Move row up and down step among the rows shown, past rows folded away`() {
        val p = project
        val keys = p.keys()
        // Fold away the second row: "down" on the first then lands below the third, which is visible.
        val shown = keys - keys[1]
        val moved = p.moveRowBy(TrackId(keys[0]), 1, shown)
        val after = moved.keys()
        assertThat(after.indexOf(keys[0])).isGreaterThan(after.indexOf(keys[2]))
        assertThat(after.indexOf(keys[1])).isEqualTo(0)
        // And back up again past the folded row.
        assertThat(moved.moveRowBy(TrackId(keys[0]), -1, shown).keys().filter { it in shown }).isEqualTo(shown)
    }

    @Test
    fun `the preset frame is never dragged out of step with the video`() {
        val h = show()
        val frameRow = h.project.frameRow()
        val frame =
            h.project
                .track(frameRow)!!
                .clips
                .single()
        zoomTo(h, frame.startMs + 500.0)
        val at = centreIn(bounds(rowTag(frameRow.value)))
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(at)
            advanceEventTime(700)
            moveTo(at + Offset(40f * density, 0f))
            moveTo(at + Offset(80f * density, 0f))
            up()
        }
        compose.waitForIdle()
        assertThat(
            h.project
                .track(frameRow)!!
                .clips
                .single(),
        ).isEqualTo(frame)
    }

    @Test
    fun `an empty row has a plus that adds at the playhead, and Add row selects the new row`() {
        val h = show()
        compose.onNodeWithTag(barActionTag(EditAction.ADD_ROW)).performClick()
        compose.onNodeWithTag(addRowTag(com.itsluminous.clearmovie.ui.editor.timeline.RowType.TEXT)).performClick()
        compose.waitForIdle()
        val row = h.state!!.selectedRow!!
        assertThat(h.project.track(row)!!.clips).isEmpty()
        h.playheadUs = 4_000_000
        compose.waitForIdle()
        compose
            .onNodeWithTag(
                com.itsluminous.clearmovie.ui.editor.timeline
                    .rowAddTag(row.value),
            ).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = row))
        compose.onNodeWithTag(insertOptionTag("text")).performClick()
        compose.waitForIdle()
        // The preset sets no words of its own: the new caption starts with the app's.
        assertThat(
            h.project
                .track(row)!!
                .clips
                .map { (it as TextClip).text },
        ).containsExactly(CaptionWords.DEFAULT)
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextReplacement("here")
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val made =
            h.project.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .single { it.text == "here" }
        assertThat(made.startMs).isEqualTo(4000)
        assertThat(h.project.rowOf("here")).isEqualTo(row)
    }

    // ---------------------------------------------------------------- rows folding away

    @Test
    fun `every row stays on the timeline as the window gets shorter, the rows scroll, and Show all rows is offered only upright`() {
        val h = EditorHarness(project, Fixtures.preset)
        var size by mutableStateOf(DpSize(411.dp, 891.dp))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        compose.waitForIdle()
        val all = h.project.keys()
        val music = h.project.rowOf("m1").value

        fun inTree() = all.filter { compose.onNodeWithTag(rowTag(it)).run { runCatching { assertExists() }.isSuccess } }

        fun whole(key: String): Boolean {
            val view = compose.onNodeWithTag(TIMELINE_ROWS_TAG).getUnclippedBoundsInRoot()
            val row = compose.onNodeWithTag(rowTag(key)).getUnclippedBoundsInRoot()
            return row.top >= view.top - 1.dp && row.bottom <= view.bottom + 1.dp
        }
        assertThat(inTree()).isEqualTo(all)
        assertThat(all.all(::whole)).isTrue()
        compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).assertDoesNotExist()
        // The music row is selected: however short the window, it is scrolled into view.
        compose.onNodeWithTag(rowHeaderTag(music), useUnmergedTree = true).performClick()
        for (height in listOf(760, 680, 620, 560)) {
            size = DpSize(411.dp, height.dp)
            compose.waitForIdle()
            assertThat(inTree()).isEqualTo(all)
            assertWithMessage("music row in view at $height dp").that(whole(music)).isTrue()
        }
        assertThat(all.count(::whole)).isLessThan(all.size)
        compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).assertIsDisplayed()
        // Sideways (a phone held on its side, two panes): every row, scrolling, and no Show all rows.
        size = DpSize(800.dp, 363.dp)
        compose.waitForIdle()
        assertThat(inTree()).isEqualTo(all)
        assertThat(whole(music)).isTrue()
        compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).assertDoesNotExist()
        // Tall again: every row in view.
        size = DpSize(411.dp, 891.dp)
        compose.waitForIdle()
        assertThat(all.all(::whole)).isTrue()
    }

    @Test
    fun `an upright swipe scrolls the rows and leaves the time, and a sideways swipe scrolls the time and leaves the rows`() {
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 363.dp))) {
                ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        compose.waitForIdle()
        zoomTo(h, 0.0)
        val first = h.project.keys().first()

        fun top() = compose.onNodeWithTag(rowTag(first)).getUnclippedBoundsInRoot().top
        val top0 = top()
        val zoom0 = h.state!!.zoom
        val rows = compose.onNodeWithTag(TIMELINE_ROWS_TAG)
        rows.performTouchInput { swipe(Offset(width - 20f, height - 10f), Offset(width - 26f, 10f), 400) }
        compose.waitForIdle()
        assertThat(top()).isLessThan(top0)
        assertThat(h.state!!.zoom).isEqualTo(zoom0)
        val top1 = top()
        // Back down, then sideways a little upward: the time moves, the rows stay.
        rows.performTouchInput { swipe(Offset(width - 20f, 10f), Offset(width - 20f, height - 10f), 400) }
        compose.waitForIdle()
        assertThat(top()).isEqualTo(top0)
        rows.performTouchInput { swipe(Offset(width - 20f, centerY), Offset(width / 3f, centerY - 40f), 400) }
        compose.waitForIdle()
        assertThat(top()).isEqualTo(top0)
        assertThat(h.state!!.zoom!!.scrollMs).isGreaterThan(zoom0!!.scrollMs)
        assertThat(top1).isLessThan(top0)
    }

    @Test
    fun `a phone held sideways shows at least four whole rows, and a smaller one three`() {
        // The editor's room on a 411 dp tall phone held sideways, less its 24 dp status and gesture bars
        // (Robolectric has no insets), then on a 360 dp tall phone.
        val h = EditorHarness(project, Fixtures.preset)
        var size by mutableStateOf(DpSize(914.dp, 363.dp))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        for ((height, rows) in listOf(363 to 4, 312 to 3)) {
            size = DpSize(914.dp, height.dp)
            compose.waitForIdle()
            val view = compose.onNodeWithTag(TIMELINE_ROWS_TAG).getUnclippedBoundsInRoot()
            val whole =
                h.project.keys().count {
                    val r = compose.onNodeWithTag(rowTag(it)).getUnclippedBoundsInRoot()
                    r.top >= view.top - 1.dp && r.bottom <= view.bottom + 1.dp
                }
            assertWithMessage("whole rows at $height dp").that(whole).isAtLeast(rows)
            compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).assertDoesNotExist()
        }
    }

    // ---------------------------------------------------------------- state

    @Test
    fun `the selected row and its open menu come back after a restore and a turn of the phone`() {
        val h = EditorHarness(project, Fixtures.preset)
        var size by mutableStateOf(ScreenSize.COMPACT)
        val tester = StateRestorationTester(compose)
        tester.setContent { AtSize(size) { ClearMovieTheme(dynamicColor = false) { h.Content() } } }
        compose.waitForIdle()
        val pipRow = h.project.rowOf("pip1")
        compose.onNodeWithTag(rowHeaderTag(pipRow.value), useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        val first = h.state
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(h.state).isNotSameInstanceAs(first)
        assertThat(h.state!!.selectedRow).isEqualTo(pipRow)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ROW_MENU, trackId = pipRow))
        compose.onNodeWithText(s(R.string.stack_show_on_top)).assertExists()
        size = ScreenSize.MEDIUM
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ROW_MENU, trackId = pipRow))
        // Sideways the menu leaves room for the row it is about.
        compose.onNodeWithTag(rowTag(pipRow.value)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.stack_show_on_top)).assertExists()
        size = ScreenSize.COMPACT
        compose.waitForIdle()
        assertThat(h.state!!.selectedRow).isEqualTo(pipRow)
    }

    @Test
    fun `row icons are at least 48 dp and the row menu opens at every size and at font scale 2`() {
        var shown by mutableStateOf<Triple<ScreenSize, Float, EditorHarness>?>(null)
        compose.setContent {
            shown?.let { (size, scale, h) -> androidx.compose.runtime.key(shown) { h.At(size, scale) } }
        }
        for (scale in listOf(1f, 2f)) {
            for (size in ScreenSize.entries) {
                val h = EditorHarness(project, Fixtures.preset)
                shown = Triple(size, scale, h)
                compose.waitForIdle()
                val mainKey = h.project.main.id.value
                val main = bounds(rowHeaderTag(mainKey))
                assertThat((main.right - main.left) / density).isAtLeast(48f)
                assertThat((main.bottom - main.top) / density).isAtLeast(48f)
                compose.onNodeWithTag(rowHeaderTag(mainKey), useUnmergedTree = true).performTouchInput { longClick() }
                compose.waitForIdle()
                assertThat(h.state!!.panel?.panel).isEqualTo(Panel.ROW_MENU)
                compose.onNodeWithTag(rowMenuTag(StackMove.TO_TOP.name)).assertExists()
            }
        }
    }
}
