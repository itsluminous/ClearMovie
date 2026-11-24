package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lower for talk on photos and videos (cm-voice-ducking): a video on MAIN
 * has it on its bar, after its other changes, and so does the MAIN row once
 * it holds a video; the popup names the videos' sound under voices, and
 * Done switches the row's setting in one undo step. A row of photos only
 * has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class VideoTalkScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val project: Project = Fixtures.blankWith(Fixtures.photo("p1"), Fixtures.video("v", 4000), ids = SequentialIds("w"))

    private fun show(p: Project = project): EditorHarness {
        val h = EditorHarness(p, null)
        compose.setContent { h.At(ScreenSize.EXPANDED, 1f) }
        compose.waitForIdle()
        return h
    }

    private fun barHas(action: EditAction): Boolean =
        compose
            .onAllNodes(hasTestTag(barActionTag(action)))
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun barButton(action: EditAction): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(action)))
        return compose.onNodeWithTag(barActionTag(action))
    }

    @Test
    fun `Lower for talk on a video names its sound under voices, and Done turns its row off in one step`() {
        val h = show()
        compose.runOnIdle { h.state!!.selected = ClipId("v") }
        compose.waitForIdle()
        barButton(EditAction.LOWER_MUSIC).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.TALK_LOWER)
        compose.onNodeWithText(context.getString(R.string.action_lower_video_sound)).assertExists()
        compose.onNodeWithText(context.getString(R.string.talk_level_video)).assertExists()
        compose.onNodeWithTag(TALK_VALUE_TAG, useUnmergedTree = true).assertTextEquals("10%")
        compose.onNodeWithTag(TALK_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.project.main.lowerWhenTalking).isFalse()
        assertThat(h.project.main.lowerToDb).isNull()
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project).isEqualTo(project)
    }

    @Test
    fun `the photos and videos row has Lower for talk while it holds a video`() {
        val h = show()
        compose.runOnIdle { h.state!!.selectedRow = project.main.id }
        compose.waitForIdle()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isTrue()
    }

    @Test
    fun `a row of photos only has no Lower for talk`() {
        val photos = Fixtures.blankWith(Fixtures.photo("p1"), Fixtures.photo("p2"), ids = SequentialIds("w2"))
        val h = show(photos)
        compose.runOnIdle { h.state!!.selectedRow = photos.main.id }
        compose.waitForIdle()
        assertThat(barHas(EditAction.ADD_MEDIA)).isTrue()
        assertThat(barHas(EditAction.LOWER_MUSIC)).isFalse()
    }
}
