package com.itsluminous.clearmovie.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TRIM_END_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.editor.timeline.markerTag
import com.itsluminous.clearmovie.ui.editor.timeline.plusTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on a timeline item and the cursor (cm-tap-seek), through the
 * timeline's own touch handler: while playing a tap on any item moves the
 * cursor to the time tapped and play goes on; while paused a tap on a photo,
 * video, caption, frame or picture in picture moves it there too, so the
 * preview shows that item, and a tap on a sound only selects it. A "+", a
 * transition marker, a trim handle, a long-press and TalkBack keep their
 * meaning, and none of it is an undo step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class TapSeekScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val ids = SequentialIds("k")

    /** Five items on MAIN (a crossfade after v2); captions 10-12 s and 12-14 s; a picture in picture and music at 10-13 s. */
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
            ).setTransition(ClipId("v2"), Transition(TransitionKind.CROSSFADE, 1000))
            .addText("hello", 10_000, 2000, ids)
            .addText("next", 12_000, 2000, ids)
            .addTimedClip(Fixtures.photo("pip1", 3000, pip = PipPlacement.DEFAULT).withStartMs(10_000), ids = ids)
            .addTimedClip(Fixtures.audio("m1", 10_000, 3000), AudioRole.MUSIC, ids)

    private fun Project.captionId(text: String): ClipId = tracks.flatMap { it.clips }.single { (it as? TextClip)?.text == text }.id

    private fun Project.frameId(): ClipId = tracks.flatMap { it.clips }.first { it is OverlayClip }.id

    /**
     * Shown at 50 ms a dp from 6 s, the cursor at 12 s (so while playing the view, following the cursor, stays
     * there): every item tested is whole in view.
     */
    private fun show(
        p: Project = project,
        playing: Boolean = false,
        size: ScreenSize = ScreenSize.EXPANDED,
    ): EditorHarness {
        val h = EditorHarness(p, Fixtures.preset)
        h.playing = playing
        h.playheadUs = 12_000_000
        compose.setContent { h.At(size, 1f) }
        compose.waitForIdle()
        compose.runOnIdle { h.state!!.zoom = ZoomState(msPerDp = 50.0, scrollMs = 6000.0, fitAll = false) }
        compose.waitForIdle()
        return h
    }

    private fun seeks(h: EditorHarness) = h.calls.filter { it.startsWith("seek:") }.map { it.removePrefix("seek:").toLong() }

    /** A real tap on the middle of item [id]: the cursor, if it moves, goes to the middle of [start, end). */
    private fun tapMiddle(
        h: EditorHarness,
        id: ClipId,
        start: Long,
        end: Long,
    ): Long? {
        h.calls.clear()
        compose.onNodeWithTag(clipTag(id), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(id)
        val s = seeks(h)
        if (s.isEmpty()) return null
        val t = s.single()
        // The middle of the item, give or take two dp (100 ms) of the touch's rounding.
        assertWithMessage("seek for $id").that(t).isIn(Range.closed((start + end) / 2 - 100, (start + end) / 2 + 100))
        return t
    }

    @Test
    fun `paused, a tap on a caption, a picture in picture or a photo moves the cursor into it`() {
        val h = show()
        val hello = h.project.clip(h.project.captionId("hello"))!!
        assertThat(tapMiddle(h, hello.id, hello.startMs, hello.endMs)).isNotNull()
        assertThat(h.playheadUs / 1000).isIn(Range.closedOpen(hello.startMs, hello.endMs))
        val pip = h.project.clip(ClipId("pip1"))!!
        assertThat(tapMiddle(h, pip.id, pip.startMs, pip.endMs)).isNotNull()
        // A photo away from the transition marker (whose 48 dp win over what is under them).
        val p1 = h.project.mainSlot(ClipId("p1"))!!
        assertThat(tapMiddle(h, p1.clip.id, p1.startMs, p1.endMs)).isNotNull()
        // Never played: the tap only moves the cursor.
        assertThat(h.calls).doesNotContain("play")
    }

    @Test
    fun `paused, a tap on the frame moves the cursor to the time tapped on it`() {
        val h = show()
        val frame = h.project.clip(h.project.frameId())!!
        h.calls.clear()
        compose.onNodeWithTag(clipTag(frame.id), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(frame.id)
        // The frame runs past the view: the time tapped is the one in the middle of what shows, inside the frame.
        assertThat(seeks(h).single()).isIn(Range.closedOpen(frame.startMs, frame.endMs))
    }

    @Test
    fun `paused, a tap on music only selects it, as before`() {
        val h = show()
        val m1 = h.project.clip(ClipId("m1"))!!
        assertThat(tapMiddle(h, m1.id, m1.startMs, m1.endMs)).isNull()
        assertThat(h.playheadUs).isEqualTo(12_000_000)
    }

    @Test
    fun `while playing, a tap on music or a video moves the cursor there and play goes on`() {
        val h = show(playing = true)
        val m1 = h.project.clip(ClipId("m1"))!!
        assertThat(tapMiddle(h, m1.id, m1.startMs, m1.endMs)).isNotNull()
        val v4 = h.project.mainSlot(ClipId("v4"))!!
        assertThat(tapMiddle(h, v4.clip.id, v4.startMs, v4.endMs)).isNotNull()
        // Nothing paused it, nor pressed Play.
        assertThat(h.pauses).isEqualTo(0)
        assertThat(h.calls).doesNotContain("play")
        assertThat(h.state!!.panel).isNull()
    }

    @Test
    fun `while playing, the view goes on following the cursor after a tap, but not after a swipe along the time`() {
        val h = show(playing = true)
        val m1 = h.project.clip(ClipId("m1"))!!
        tapMiddle(h, m1.id, m1.startMs, m1.endMs)
        // The cursor runs out of view (here: to 1 s, left of the view): the view follows it.
        compose.runOnIdle { h.playheadUs = 1_000_000 }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isLessThan(1000.0)
        // Moved along the time by hand, the view stays where it was put.
        compose.onNodeWithTag(clipTag(ClipId("v4")), useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val put = h.state!!.zoom!!.scrollMs
        assertThat(put).isGreaterThan(1000.0)
        compose.runOnIdle { h.playheadUs = 500_000 }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isEqualTo(put)
    }

    @Test
    fun `while playing, the view stops following once an item is dragged, as before`() {
        val h = show(playing = true)
        val pip = h.project.clip(ClipId("pip1"))!!
        val timeline = compose.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        val at =
            compose
                .onNodeWithTag(clipTag(pip.id), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.center - timeline.topLeft
        val density =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .layoutInfo.density.density
        // Held, then moved sideways past twice the touch slop (as TracksScreenTest drags it).
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(at)
            advanceEventTime(700)
            moveTo(at + Offset(25f * density, 0f))
            moveTo(at + Offset(50f * density, 0f))
            up()
        }
        compose.waitForIdle()
        assertThat(h.project.clip(pip.id)!!.startMs).isGreaterThan(pip.startMs)
        val put = h.state!!.zoom!!.scrollMs
        // The cursor runs out of view: the view stays on the item just moved.
        compose.runOnIdle { h.playheadUs = 1_000_000 }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isEqualTo(put)
    }

    @Test
    fun `a tap on a selected item narrow enough to be all handles still moves the cursor, and keeps it selected`() {
        val h = show()
        // p1 is 3 s, 60 dp at 50 ms a dp: its two 36 dp handle areas cover all of it.
        val p1 = h.project.mainSlot(ClipId("p1"))!!
        compose.runOnIdle { h.state!!.selected = p1.clip.id }
        compose.waitForIdle()
        assertThat(tapMiddle(h, p1.clip.id, p1.startMs, p1.endMs)).isNotNull()
        // The same on another row: the 3 s picture in picture.
        val pip = h.project.clip(ClipId("pip1"))!!
        compose.runOnIdle { h.state!!.selected = pip.id }
        compose.waitForIdle()
        assertThat(tapMiddle(h, pip.id, pip.startMs, pip.endMs)).isNotNull()
        assertThat(h.project).isEqualTo(project)
    }

    @Test
    fun `while playing, a tap on an item after a swipe starts the following again from there`() {
        val h = show(playing = true)
        // A short swipe along the time towards the start (60 dp, 3 s at 50 ms a dp; the view at 6 s is already at the
        // video's end at this width): m1 (10-13 s) stays in view.
        compose.onNodeWithTag(clipTag(ClipId("v4")), useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(60.dp.toPx(), 0f))
        }
        compose.waitForIdle()
        val put = h.state!!.zoom!!.scrollMs
        assertThat(put).isLessThan(6000.0)
        assertThat(put).isGreaterThan(1000.0)
        // The swipe stopped the following: the cursor out of view moves nothing.
        compose.runOnIdle { h.playheadUs = 1_000_000 }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isEqualTo(put)
        val m1 = h.project.clip(ClipId("m1"))!!
        tapMiddle(h, m1.id, m1.startMs, m1.endMs)
        compose.runOnIdle { h.playheadUs = 1_000_000 }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isLessThan(1000.0)
    }

    @Test
    fun `the selected item's plus cut off while playing is brought into view once paused`() {
        val h = show(playing = true)
        val p5 = h.project.mainSlot(ClipId("p5"))!!
        val width = h.state!!.spanWidth!!
        // p5's end 5 dp in from the right edge, the cursor at its start (well in view, so nothing turns the page).
        val scroll = p5.endMs - (width - 5) * 50.0
        compose.runOnIdle {
            h.playheadUs = p5.startMs * 1000
            h.state!!.zoom = ZoomState(msPerDp = 50.0, scrollMs = scroll, fitAll = false)
            h.state!!.selected = p5.clip.id
        }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isEqualTo(scroll)
        compose.runOnIdle { h.playing = false }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isGreaterThan(scroll)
    }

    @Test
    fun `Play and Pause with an item long selected move nothing, even with its plus cut off`() {
        val h = show()
        val p5 = h.project.mainSlot(ClipId("p5"))!!
        compose.runOnIdle { h.state!!.selected = p5.clip.id }
        compose.waitForIdle()
        val width = h.state!!.spanWidth!!
        val scroll = p5.endMs - (width - 5) * 50.0
        compose.runOnIdle {
            h.playheadUs = p5.startMs * 1000
            h.state!!.zoom = ZoomState(msPerDp = 50.0, scrollMs = scroll, fitAll = false)
        }
        compose.waitForIdle()
        compose.runOnIdle { h.playing = true }
        compose.waitForIdle()
        compose.runOnIdle { h.playing = false }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isEqualTo(scroll)
    }

    @Test
    fun `while playing, a swipe up and down the rows leaves the view following`() {
        // Sideways on a phone, where the rows scroll (else an upright swipe moves along the time, and stops it).
        val h = show(playing = true, size = ScreenSize.MEDIUM)
        compose.onNodeWithTag(TIMELINE_ROWS_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { h.playheadUs = 1_000_000 }
        compose.waitForIdle()
        assertThat(h.state!!.zoom!!.scrollMs).isLessThan(1000.0)
    }

    @Test
    fun `paused, a tap on a caption on a row switched off only selects it, as the preview shows nothing of it`() {
        val hello = project.captionId("hello")
        val off = project.setTrackEnabled(project.trackOf(hello)!!.id, false)
        val h = show(off)
        tapMiddle(h, hello, 10_000, 12_000).let { assertThat(it).isNull() }
        assertThat(h.playheadUs).isEqualTo(12_000_000)
    }

    @Test
    fun `a tap on a transition marker or a plus moves no cursor, and one on a trim handle keeps the item, inside it`() {
        val h = show()
        val v2 = h.project.mainSlot(ClipId("v2"))!!
        val boundary = v2.index + 1
        h.calls.clear()
        compose.onNodeWithTag(markerTag(boundary)).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.TRANSITION, ClipId("v2")))
        assertThat(seeks(h)).isEmpty()
        // v4 selected: its "+" after it and its end handle.
        compose.runOnIdle {
            h.state!!.panel = null
            h.state!!.selected = ClipId("v4")
        }
        compose.waitForIdle()
        h.calls.clear()
        compose.onNodeWithTag(TRIM_END_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        // Nothing trimmed; still selected; a tap on the item's handle is a tap on the item, kept inside it.
        val v4 = h.project.mainSlot(ClipId("v4"))!!
        assertThat(h.project).isEqualTo(project)
        assertThat(h.state!!.selected).isEqualTo(ClipId("v4"))
        assertThat(seeks(h).single()).isIn(Range.closed(v4.startMs, v4.endMs - 1))
        h.calls.clear()
        compose.onNodeWithTag(plusTag(v4.index + 1), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.INSERT)
        assertThat(seeks(h)).isEmpty()
    }

    @Test
    fun `a long-press opens the item's menu and moves no cursor`() {
        val h = show()
        val hello = h.project.captionId("hello")
        h.calls.clear()
        compose.onNodeWithTag(clipTag(hello), useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ITEM_MENU, hello))
        assertThat(seeks(h)).isEmpty()
    }

    @Test
    fun `TalkBack's click on an item selects it, as before, with no tap point to move the cursor to`() {
        val h = show()
        val hello = h.project.captionId("hello")
        h.calls.clear()
        compose.onNodeWithTag(clipTag(hello), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertThat(h.state!!.selected).isEqualTo(hello)
        assertThat(seeks(h)).isEmpty()
    }

    @Test
    fun `taps that move the cursor are no undo step`() {
        val h = show()
        val hello = h.project.clip(h.project.captionId("hello"))!!
        tapMiddle(h, hello.id, hello.startMs, hello.endMs)
        val p1 = h.project.mainSlot(ClipId("p1"))!!
        tapMiddle(h, p1.clip.id, p1.startMs, p1.endMs)
        assertThat(h.project).isEqualTo(project)
        assertThat(h.canUndo).isFalse()
    }
}
