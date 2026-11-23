package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A frame with sound (cm-frame-audio): selected, it has Volume and Lower
 * for talk on its bar, as a music item does, and its row has Lower for talk;
 * a frame without sound, and the preset's frame (its sound is the music
 * row), have neither. The popups set the frame's volume and its row's
 * level in one undo step each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class FrameSoundScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val ids = SequentialIds("k")

    /** The preset project with two photos, and a frame row of the user's with a frame with sound and one without. */
    private val project: Project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(listOf(Fixtures.photo("p1", 6000), Fixtures.photo("p2", 6000)))
            .addTimedClip(Fixtures.overlay("loud", 7000, 2000), ids = ids)
            .let { p ->
                val row = p.tracks.single { it.type == TrackType.OVERLAY && it.presetRole == null }
                p.copy(
                    tracks =
                        p.tracks.map {
                            if (it.id == row.id) it.copy(clips = it.clips + Fixtures.overlay("quiet", 10_000, 2000)) else it
                        },
                )
            }

    private val userRow get() = project.tracks.single { it.type == TrackType.OVERLAY && it.presetRole == null }

    private fun show(): EditorHarness {
        val h = EditorHarness(project, Fixtures.preset)
        h.framesWithSound = setOf(Fixtures.media("loud"), MediaRef.Preset(PresetSlot.FRAME))
        compose.setContent { h.At(ScreenSize.EXPANDED, 1f) }
        compose.waitForIdle()
        return h
    }

    private fun select(
        h: EditorHarness,
        id: ClipId,
    ) {
        compose.runOnIdle {
            h.state!!.selectedRow = null
            h.state!!.selected = id
        }
        compose.waitForIdle()
    }

    private fun barHas(action: EditAction): Boolean =
        compose
            .onAllNodes(hasTestTag(barActionTag(action)))
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun hasProgressBarRangeInfo() =
        androidx.compose.ui.test.SemanticsMatcher
            .keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)

    private fun barButton(action: EditAction): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(action)))
        return compose.onNodeWithTag(barActionTag(action))
    }

    @Test
    fun `a frame with sound has Volume and Lower for talk, and one without has neither`() {
        val h = show()
        select(h, ClipId("loud"))
        assertThat(barHas(EditAction.VOLUME)).isTrue()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isTrue()
        select(h, ClipId("quiet"))
        assertThat(barHas(EditAction.VOLUME)).isFalse()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isFalse()
        // The preset's frame: its sound is the music row's, which has its own Lower for talk.
        val presetFrame = project.tracks.single { it.presetRole == PresetRole.FRAME }
        select(h, presetFrame.clips.single().id)
        assertThat(barHas(EditAction.VOLUME)).isFalse()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isFalse()
    }

    @Test
    fun `a frame row with a frame with sound has Lower for talk, and loses it without one`() {
        val h = show()
        compose.runOnIdle {
            h.state!!.selected = null
            h.state!!.selectedRow = userRow.id
        }
        compose.waitForIdle()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isTrue()
        assertThat(barHas(EditAction.MUTE_ROW)).isTrue()
        compose.runOnIdle { h.framesWithSound = emptySet() }
        compose.waitForIdle()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isFalse()
        assertThat(barHas(EditAction.MUTE_ROW)).isFalse()
        // The preset's frame row: its sound is the music row's.
        compose.runOnIdle {
            h.framesWithSound = setOf(MediaRef.Preset(PresetSlot.FRAME))
            h.state!!.selectedRow = project.tracks.single { it.presetRole == PresetRole.FRAME }.id
        }
        compose.waitForIdle()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isFalse()
        assertThat(barHas(EditAction.MUTE_ROW)).isFalse()
    }

    @Test
    fun `Lower for talk on a frame names the frame's sound, and Done sets its row's level in one step`() {
        val h = show()
        select(h, ClipId("loud"))
        barButton(EditAction.LOWER_MUSIC).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.TALK_LOWER)
        compose.onNodeWithText(context.getString(R.string.action_lower_frame_sound)).assertExists()
        compose.onNodeWithText(context.getString(R.string.talk_level_frame)).assertExists()
        compose.onNodeWithTag(TALK_VALUE_TAG, useUnmergedTree = true).assertTextEquals("10%")
        compose.onNodeWithTag(TALK_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val row = h.project.track(userRow.id)!!
        assertThat(row.lowerWhenTalking).isTrue()
        assertThat(row.lowerToDb!!).isWithin(0.01).of(-6.02)
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
    }

    @Test
    fun `Volume on a frame sets its own level in one step`() {
        val h = show()
        select(h, ClipId("loud"))
        barButton(EditAction.VOLUME).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.VOLUME)
        compose
            .onNode(hasContentDescription(context.getString(R.string.volume_title)) and hasProgressBarRangeInfo())
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        assertThat((h.project.requireClip(ClipId("loud")) as OverlayClip).volumeDb).isWithin(0.01).of(0.0)
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
    }
}
