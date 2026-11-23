package com.itsluminous.clearmovie.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.ui.editor.ContextualBar
import com.itsluminous.clearmovie.ui.editor.barActionTag
import com.itsluminous.clearmovie.ui.editor.rowBar
import com.itsluminous.clearmovie.ui.editor.timedBar
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the device (cm-frame-audio): a frame whose video has sound shows
 * Volume and Lower for talk on its bar, and its row Lower for talk; a frame
 * without sound shows neither.
 */
@RunWith(AndroidJUnit4::class)
class FrameSoundBarDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var actions by mutableStateOf(emptyList<BarAction>())

    private val loud = MediaRef.Store("content://media/external/video/media/1")
    private val quiet = MediaRef.Store("content://media/external/video/media/2")

    private val project =
        SequentialIds("d").let { ids ->
            ProjectFactory
                .blank(ProjectId("d"), ids)
                .addToMain(listOf(PhotoClip(ids.clipId(), MediaRef.Store("content://media/external/images/media/3"), 6000)), ids = ids)
                .addTimedClip(OverlayClip(ClipId("loud"), loud, 0, 2000, 2000, repeat = false), ids = ids)
                .addTimedClip(OverlayClip(ClipId("quiet"), quiet, 0, 2000, 2000, repeat = false), ids = ids)
        }

    @Before
    fun show() = compose.setContent { ClearMovieTheme(dynamicColor = false) { ContextualBar(actions, onAction = {}, description = "bar") } }

    private fun shown(action: EditAction) = compose.onAllNodes(hasTestTag(barActionTag(action))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aFrameWithSoundShowsVolumeAndLowerForTalkAndOneWithoutShowsNeither() {
        val withSound = setOf(loud)
        compose.runOnIdle { actions = timedBar(project, project.requireClip(ClipId("loud")), withSound) }
        compose.onNodeWithTag(barActionTag(EditAction.VOLUME)).assertIsDisplayed()
        compose.onNodeWithTag(barActionTag(EditAction.LOWER_MUSIC)).assertIsDisplayed()
        compose.runOnIdle { actions = timedBar(project, project.requireClip(ClipId("quiet")), withSound) }
        compose.waitForIdle()
        assertThat(shown(EditAction.VOLUME)).isFalse()
        assertThat(shown(EditAction.LOWER_MUSIC)).isFalse()
        val loudRow = project.tracks.single { t -> t.type == TrackType.OVERLAY && t.clips.any { it.id == ClipId("loud") } }
        compose.runOnIdle { actions = rowBar(project, loudRow, framesWithSound = withSound) }
        compose.onNodeWithTag(barActionTag(EditAction.LOWER_MUSIC)).assertIsDisplayed()
        compose.runOnIdle { actions = rowBar(project, loudRow, framesWithSound = emptySet()) }
        compose.waitForIdle()
        assertThat(shown(EditAction.LOWER_MUSIC)).isFalse()
    }
}
