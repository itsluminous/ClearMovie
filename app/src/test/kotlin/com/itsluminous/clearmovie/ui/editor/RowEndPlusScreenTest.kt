package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import com.itsluminous.clearmovie.ui.editor.timeline.plusTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowEndPlusTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "+" at every row's right end (cm-plus-position): a tap adds at the
 * cursor, or right after the item under it, on every kind of row; TalkBack
 * hears where; one Undo takes it away again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class RowEndPlusScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val ids = SequentialIds("k")

    /** Five items on MAIN; captions "hello" 10-12 s and "next" 12-14 s, touching; a picture in picture and music at 10-13 s. */
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

    private fun Project.captionId(text: String): ClipId = tracks.flatMap { it.clips }.single { (it as? TextClip)?.text == text }.id

    private fun Project.rowOf(id: ClipId): TrackId = trackOf(id)!!.id

    private fun show(): EditorHarness {
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent { h.At(ScreenSize.EXPANDED, 1f) }
        compose.waitForIdle()
        return h
    }

    private fun at(
        h: EditorHarness,
        ms: Long,
    ) {
        h.playheadUs = ms * 1000
        compose.waitForIdle()
    }

    /** A real tap (through the timeline's own touch handler) on the "+" at the end of [row]. */
    private fun tapEnd(row: TrackId) {
        compose.onNodeWithTag(rowEndPlusTag(row.value), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun description(row: TrackId): String =
        compose
            .onNodeWithTag(rowEndPlusTag(row.value), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    private fun newCaption(h: EditorHarness): TextClip {
        val before =
            h.project.tracks
                .flatMap { it.clips }
                .map { it.id }
                .toSet()
        compose.onNodeWithTag(insertOptionTag("text")).performClick()
        compose.waitForIdle()
        return h.project.tracks
            .flatMap { it.clips }
            .filterIsInstance<TextClip>()
            .single { it.id !in before }
    }

    @Test
    fun `on a text row with the cursor in a gap the new caption starts at the cursor, and one Undo removes it`() {
        val h = show()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        at(h, 5000)
        assertThat(
            description(textRow),
        ).isEqualTo(context.getString(R.string.timeline_add_at, context.getString(R.string.timeline_row_text), "0:05"))
        tapEnd(textRow)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = textRow, atMs = 5000))
        val made = newCaption(h)
        assertThat(made.startMs).isEqualTo(5000)
        assertThat(h.project.rowOf(made.id)).isEqualTo(textRow)
        compose.runOnIdle { h.actions.undo() }
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
    }

    @Test
    fun `with the cursor on an item it goes right after it, and past the items that touch it`() {
        val h = show()
        val hello = h.project.captionId("hello")
        val next = h.project.captionId("next")
        val textRow = h.project.rowOf(hello)
        // On "hello", whose end is where "next" starts: after "next", the first free moment on the row.
        at(h, 10_500)
        val named = context.getString(R.string.timeline_named_item, context.getString(R.string.timeline_row_text), "next")
        assertThat(description(textRow)).isEqualTo(context.getString(R.string.timeline_add_after, named))
        tapEnd(textRow)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, next, InsertSide.AFTER))
        val made = newCaption(h)
        assertThat(made.startMs).isEqualTo(14_000)
        assertThat(h.project.rowOf(made.id)).isEqualTo(textRow)
        compose.runOnIdle { h.actions.undo() }
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
    }

    @Test
    fun `picture in picture and music rows follow the cursor the same way`() {
        val h = show()
        for (id in listOf(ClipId("pip1"), ClipId("m1"))) {
            val row = h.project.rowOf(id)
            at(h, 11_000)
            tapEnd(row)
            assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, id, InsertSide.AFTER))
            compose.runOnIdle { h.state!!.panel = null }
            at(h, 2000)
            tapEnd(row)
            assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = row, atMs = 2000))
            compose.runOnIdle { h.state!!.panel = null }
        }
    }

    @Test
    fun `on MAIN it goes right after the item under the cursor, and later items move later`() {
        val h = show()
        val main = h.project.main.id
        val p3 = h.project.mainSlot(ClipId("p3"))!!
        at(h, p3.startMs + 500)
        tapEnd(main)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, ClipId("p3"), InsertSide.AFTER))
        h.galleryAnswer = listOf(Fixtures.photo("new"))
        compose.onNodeWithTag(insertOptionTag("gallery")).performClick()
        compose.waitForIdle()
        val order =
            h.project.main.clips
                .map { it.id.value }
        assertThat(order.indexOf("new")).isEqualTo(order.indexOf("p3") + 1)
        compose.runOnIdle { h.actions.undo() }
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
    }

    @Test
    fun `the time is fixed when the plus is tapped, so the cursor moving on before a choice does not move the caption`() {
        val h = show()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        at(h, 5000)
        tapEnd(textRow)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = textRow, atMs = 5000))
        // The video plays on (or the cursor is moved) while the chooser is open.
        at(h, 9500)
        val made = newCaption(h)
        assertThat(made.startMs).isEqualTo(5000)
        assertThat(h.project.rowOf(made.id)).isEqualTo(textRow)
    }

    @Test
    fun `where a new caption does not fit at the cursor it goes on the same row after the next caption`() {
        val h = show()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        // 8.5 s: a 1.5 s gap before "hello" (10 s), too short for a 3 s caption.
        at(h, 8500)
        tapEnd(textRow)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, h.project.captionId("next"), InsertSide.AFTER))
        val made = newCaption(h)
        assertThat(made.startMs).isEqualTo(14_000)
        assertThat(h.project.rowOf(made.id)).isEqualTo(textRow)
    }

    @Test
    fun `rows the preset manages and rows switched off have no plus`() {
        val off = project.tracks.first { it.id == project.trackOf(ClipId("m1"))!!.id }.id
        val h = EditorHarness(project.setTrackEnabled(off, false), Fixtures.preset)
        compose.setContent { h.At(ScreenSize.EXPANDED, 1f) }
        compose.waitForIdle()
        for (t in h.project.tracks.filter { it.presetRole != null || !it.enabled }) {
            compose.onNodeWithTag(rowEndPlusTag(t.id.value), useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag(rowEndPlusTag(h.project.rowOf(ClipId("pip1")).value), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `in the column only the plus itself adds, and the last photo's plus after it stays beside it`() {
        // Font scale 2: a text row is 96 dp tall, the "+" 48 dp in its middle.
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent { h.At(ScreenSize.EXPANDED, 2f) }
        compose.waitForIdle()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        at(h, 5000)
        val cell = compose.onNodeWithTag(rowEndPlusTag(textRow.value), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertThat((cell.bottom - cell.top) / density).isAtLeast(90f)
        // Near the cell's top edge, off the "+": nothing opens.
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(cell.center.x - timeline.left, cell.top + 6 * density - timeline.top),
            )
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        // On the "+": its chooser.
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            click(
                androidx.compose.ui.geometry.Offset(
                    cell.center.x - timeline.left,
                    cell.center.y - timeline.top,
                ),
            )
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = textRow, atMs = 5000))
        compose.runOnIdle { h.state!!.panel = null }
        // The last photo or video selected at fit all (the ending after it): its "+" after it is beside it, left of the column.
        val last =
            h.project.main.clips
                .last { it !is com.itsluminous.clearmovie.domain.model.EndingClip }
                .id
        compose.runOnIdle { h.state!!.selected = last }
        compose.waitForIdle()
        val i =
            h.project.main.clips
                .indexOfFirst { it.id == last }
        val after = compose.onNodeWithTag(plusTag(i + 1), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(after.right).isAtMost(cell.left + 1)
    }

    @Test
    fun `after playing to the end a caption still goes where it is seen, and the chooser says when`() {
        val h = show()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        val total =
            h.project
                .mainLayout()
                .last()
                .endMs
        at(h, total)
        val expected = total - 3000
        tapEnd(textRow)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = textRow, atMs = expected))
        val seconds = (expected + 500) / 1000
        val time = context.getString(R.string.duration_minutes_seconds, seconds / 60, seconds % 60)
        val title = context.getString(R.string.timeline_add_at, context.getString(R.string.timeline_row_text), time)
        compose.onNodeWithText(title).assertExists()
        val made = newCaption(h)
        assertThat(made.startMs).isEqualTo(expected)
        assertThat(made.endMs).isAtMost(total)
    }

    @Test
    fun `near the end with the row busy, the caption goes right after the captions there, on the same row`() {
        val total = project.mainLayout().last().endMs
        // Captions running to 2 s before the end, touching: the row is busy 3 s before the end.
        val busy =
            project
                .addText("last", total - 6000, 4000, ids)
        val h = EditorHarness(busy, Fixtures.preset)
        compose.setContent { h.At(ScreenSize.EXPANDED, 1f) }
        compose.waitForIdle()
        val last = h.project.captionId("last")
        val row = h.project.rowOf(last)
        at(h, total - 5000)
        tapEnd(row)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, last, InsertSide.AFTER))
        val made = newCaption(h)
        assertThat(made.startMs).isEqualTo(total - 2000)
        assertThat(h.project.rowOf(made.id)).isEqualTo(row)
    }

    @Test
    fun `a selected ending just off the left edge is brought in so its plus before it shows`() {
        val h = show()
        val clips = h.project.main.clips
        val ending = clips.last()
        assertThat(ending).isInstanceOf(com.itsluminous.clearmovie.domain.model.EndingClip::class.java)
        val start = h.project.mainSlot(ending.id)!!.startMs
        // Zoomed in, the ending's start 10 dp left of the view.
        compose.runOnIdle { h.state!!.zoom = ZoomState(msPerDp = 10.0, scrollMs = start + 100.0, fitAll = false) }
        compose.waitForIdle()
        compose.runOnIdle { h.state!!.selected = ending.id }
        compose.waitForIdle()
        compose.onNodeWithTag(plusTag(clips.lastIndex), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a selected caption flush with the column keeps the whole of its end handle`() {
        val h = show()
        val hello = h.project.captionId("hello")
        val clip = h.project.clip(hello)!!
        compose.runOnIdle { h.state!!.selected = hello }
        compose.waitForIdle()
        compose.runOnIdle {
            // Its end exactly at the column's left edge.
            val span = h.state!!.spanWidth!!
            h.state!!.zoom = ZoomState(msPerDp = 10.0, scrollMs = clip.endMs - span * 10.0, fitAll = false)
        }
        compose.waitForIdle()
        val cell =
            compose
                .onNodeWithTag(
                    com.itsluminous.clearmovie.ui.editor.timeline
                        .rowTag(h.project.rowOf(hello).value),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        // 6 dp into the column, on the handle's outer part: a drag left trims the caption's end.
        val at =
            androidx.compose.ui.geometry
                .Offset(timeline.width - 48 * density + 6 * density, cell.center.y - timeline.top)
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(at)
            moveTo(
                at -
                    androidx.compose.ui.geometry
                        .Offset(30f * density, 0f),
            )
            moveTo(
                at -
                    androidx.compose.ui.geometry
                        .Offset(60f * density, 0f),
            )
            up()
        }
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(hello)
        assertThat(h.project.clip(hello)!!.endMs).isLessThan(clip.endMs)
    }

    @Test
    fun `on slim rows sideways the plus takes its whole 48 dp, past the row into the gap`() {
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent {
            androidx.compose.ui.test.DeviceConfigurationOverride(
                androidx.compose.ui.test.DeviceConfigurationOverride
                    .ForcedSize(
                        androidx.compose.ui.unit
                            .DpSize(800.dp, 363.dp),
                    ),
            ) {
                com.itsluminous.clearmovie.ui.theme
                    .ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        compose.waitForIdle()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        at(h, 5000)
        val cell = compose.onNodeWithTag(rowEndPlusTag(textRow.value), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertThat((cell.bottom - cell.top) / density).isWithin(0.5f).of(40f)
        // 1 dp below the slim row, in the 4 dp gap, but inside the "+"'s 48 dp.
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(cell.center.x - timeline.left, cell.bottom + 1 * density - timeline.top),
            )
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.INSERT, trackId = textRow, atMs = 5000))
    }

    @Test
    fun `held in the column off the plus, a row opens its menu`() {
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent { h.At(ScreenSize.EXPANDED, 2f) }
        compose.waitForIdle()
        val textRow = h.project.rowOf(h.project.captionId("hello"))
        val cell = compose.onNodeWithTag(rowEndPlusTag(textRow.value), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            val p =
                androidx.compose.ui.geometry
                    .Offset(cell.center.x - timeline.left, cell.top + 6 * density - timeline.top)
            down(p)
            advanceEventTime(800)
            up()
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ROW_MENU, trackId = textRow))
    }

    @Test
    fun `on the selected item's row the column is empty, and a tap there only lets go of the selection`() {
        val h = show()
        val hello = h.project.captionId("hello")
        val textRow = h.project.rowOf(hello)
        compose.runOnIdle { h.state!!.selected = hello }
        compose.waitForIdle()
        compose.onNodeWithTag(rowEndPlusTag(textRow.value), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(rowEndPlusTag(h.project.main.id.value), useUnmergedTree = true).assertExists()
        // The column's place on that row: the right end of the timeline, on the row's middle.
        val row =
            compose
                .onNodeWithTag(
                    com.itsluminous.clearmovie.ui.editor.timeline
                        .rowTag(textRow.value),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(timeline.width - 24 * density, row.center.y - timeline.top),
            )
        }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        assertThat(h.state!!.selected).isNull()
        assertThat(h.project).isEqualTo(project)
    }
}
