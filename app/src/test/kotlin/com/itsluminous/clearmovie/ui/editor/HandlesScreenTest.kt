package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.CaptionAttachment
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.attachSpan
import com.itsluminous.clearmovie.domain.model.trimVideo
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TRIM_END_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TRIM_START_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Handles on every row and Lower for talk (cm-timeline-handles): the
 * selected caption, frame, music and voice have a handle on each side;
 * dragging a caption's start handle detaches only its start; Match item
 * gives it back; the Lower for talk popup sets the music's level in one
 * undo step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class HandlesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val ids = SequentialIds("k")

    /** The preset project with three items on MAIN, a caption on v2 (3.5..7.5 s after the intro) and a voice clip. */
    private val project: Project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(listOf(Fixtures.photo("p1"), Fixtures.video("v2", 4000), Fixtures.photo("p3")))
            .addCaptionFor(ClipId("v2"), "on v2", ids, ClipId("cap"))
            .addTimedClip(Fixtures.audio("voice", 10_000, 3000), AudioRole.VOICE, ids)

    private fun show(): EditorHarness {
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent { h.At(ScreenSize.EXPANDED, 1f) }
        compose.waitForIdle()
        return h
    }

    private fun select(
        h: EditorHarness,
        id: ClipId,
    ) {
        val clip = h.project.requireClip(id)
        compose.runOnIdle {
            h.state!!.selectedRow = null
            h.state!!.selected = id
            // At least 20 ms per dp, and the whole item (300 dp at most) well inside the view.
            val msPerDp = maxOf(20.0, clip.durationMs / 300.0)
            h.state!!.zoom = ZoomState(msPerDp = msPerDp, scrollMs = clip.startMs - 75 * msPerDp, fitAll = false)
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun centreIn(rect: Rect): Offset = rect.center - bounds(TIMELINE_TAG).topLeft

    private val density get() = context.resources.displayMetrics.density

    private fun barButton(action: EditAction): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(action)))
        return compose.onNodeWithTag(barActionTag(action))
    }

    @Test
    fun `the selected caption, frame, music and voice each get a handle on both sides`() {
        val h = show()
        val frame =
            h.project.tracks
                .single { it.presetRole == PresetRole.FRAME }
                .clips
                .single()
                .id
        val music =
            h.project.tracks
                .single { it.presetRole == PresetRole.FRAME_MUSIC }
                .clips
                .single()
                .id
        for (id in listOf(ClipId("cap"), frame, music, ClipId("voice"))) {
            select(h, id)
            val start = bounds(TRIM_START_TAG)
            val end = bounds(TRIM_END_TAG)
            // 24 dp drawn, on the item's inner edges.
            assertThat(start.width / density).isWithin(0.5f).of(24f)
            assertWithMessage("$id: $start $end, ${h.project.requireClip(id)}").that(end.left).isGreaterThan(start.right)
        }
    }

    @Test
    fun `dragging a caption's start handle detaches only its start, and Match item gives it back`() {
        val h = show()
        select(h, ClipId("cap"))
        val before = h.project.requireClip(ClipId("cap")) as TextClip
        val handle = centreIn(bounds(TRIM_START_TAG))
        // 50 dp at 20 ms per dp: one second later.
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(handle)
            moveTo(handle + Offset(25f * density, 0f))
            moveTo(handle + Offset(50f * density, 0f))
            up()
        }
        compose.waitForIdle()
        val after = h.project.requireClip(ClipId("cap")) as TextClip
        assertThat(after.startMs).isWithin(30L).of(before.startMs + 1000)
        assertThat(after.endMs).isEqualTo(before.endMs)
        assertThat(after.attachedTo!!.detachedStartMs).isEqualTo(after.startMs)
        assertThat(after.attachedTo!!.detachedEndMs).isNull()
        // The end still follows v2: v2 a second shorter, the caption's end with it, its start where it was.
        compose.runOnIdle { h.actions.edit { trimVideo(ClipId("v2"), 0, 3000) } }
        compose.waitForIdle()
        val trimmed = h.project.requireClip(ClipId("cap")) as TextClip
        assertThat(trimmed.endMs).isEqualTo(h.project.attachSpan(ClipId("v2"))!!.second)
        assertThat(trimmed.endMs).isLessThan(after.endMs)
        assertThat(trimmed.startMs).isEqualTo(after.startMs)
        // Match item.
        barButton(EditAction.FOLLOW_ITEM).performClick()
        compose.waitForIdle()
        assertThat((h.project.requireClip(ClipId("cap")) as TextClip).attachedTo).isEqualTo(CaptionAttachment(ClipId("v2")))
        // Undo goes back one step at a time.
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.requireClip(ClipId("cap"))).isEqualTo(trimmed)
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.requireClip(ClipId("cap"))).isEqualTo(after)
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.requireClip(ClipId("cap"))).isEqualTo(before)
    }

    @Test
    fun `Lower for talk opens its popup, and Done sets the level in one step`() {
        val h = show()
        val music = h.project.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }
        select(h, music.clips.single().id)
        barButton(EditAction.LOWER_MUSIC).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.TALK_LOWER)
        // It shows today's level under a voice (-20 dB) until moved.
        compose.onNodeWithTag(TALK_VALUE_TAG, useUnmergedTree = true).assertTextEquals("10%")
        compose.onNodeWithTag(TALK_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.waitForIdle()
        compose.onNodeWithTag(TALK_VALUE_TAG, useUnmergedTree = true).assertTextEquals("50%")
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val row = h.project.track(music.id)!!
        assertThat(row.lowerWhenTalking).isTrue()
        assertThat(row.lowerToDb!!).isWithin(0.01).of(-6.02)
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.track(music.id)!!.lowerToDb).isNull()
    }

    @Test
    fun `switching Lower for talk off in the popup keeps its level, and Cancel changes nothing`() {
        val h = show()
        val music = h.project.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }
        select(h, music.clips.single().id)
        barButton(EditAction.LOWER_MUSIC).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TALK_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PANEL_CANCEL_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
        barButton(EditAction.LOWER_MUSIC).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TALK_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val row = h.project.track(music.id)!!
        assertThat(row.lowerWhenTalking).isFalse()
        assertThat(row.lowerToDb).isNull()
    }
}
