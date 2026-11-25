package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineZoom
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long video on the timeline (cm-crash-after-media-pick). Compose can not
 * lay out a box much wider than 262,143 px, so the timeline must never draw
 * an item at a scale that makes it that wide:
 *
 * - while the editor opens it shows no project for a frame or two, and the
 *   zoom it keeps from then (fit all of nothing, the short-video cap) must not
 *   be used for the long video that arrives next: 0.0.5 crashed there on every
 *   open of a video longer than about 13 minutes;
 * - zoomed in all the way, a long video must still fit that limit.
 */
@RunWith(RobolectricTestRunner::class)
// A phone held upright (xxhdpi, 3 px per dp), tall enough that the frame and music rows show under the preview.
@Config(qualifiers = "w384dp-h1200dp-xxhdpi")
class LongVideoTimelineTest {
    @get:Rule
    val compose = createComposeRule()

    private fun project(minutes: Long): Project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(listOf(Fixtures.photo("p1"), Fixtures.video("long", minutes * 60_000), Fixtures.photo("p2")))

    private fun show(
        project: Project,
        screen: EditorScreenState,
    ) {
        val ui = EditorUi(project = project, preset = Fixtures.preset)
        compose.setContent {
            ClearMovieTheme(dynamicColor = false) {
                EditorScreen(
                    ui = ui,
                    title = "Party",
                    playhead = PlayheadUi(0L, (ui.slots.lastOrNull()?.endMs ?: 0) * 1000, false),
                    preview = { m -> Box(m.background(Color.Black).testTag("preview")) },
                    thumbnail = { _, _ -> null },
                    actions = EditorHarness(project(1), Fixtures.preset).actions,
                    state = screen,
                )
            }
        }
        compose.waitForIdle()
    }

    /** The laid-out width of [tag]'s node in dp, not clipped by the screen. */
    private fun widthOf(tag: String): Double =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .size.width / compose.density.density.toDouble()

    @Test
    fun `a long video is drawn fitted, not at the scale kept while the editor was still empty`() {
        val screen = EditorScreenState()
        val long = project(20)
        show(long, screen)
        val span = screen.spanWidth!!
        // The zoom the editor keeps from its first frames, before the project loads: fit all of an empty video,
        // the short-video cap. On a phone the frame the project arrives in is laid out with it, before the
        // timeline's effects can fit the new length; here it is handed to the timeline the same way.
        compose.runOnIdle { screen.zoom = TimelineZoom.fitAll(0L, span) }
        compose.waitForIdle()
        // The 20-minute video is drawn at fit all of its own length, so it fits across the timeline.
        val total = long.mainLayout().last().endMs
        assertThat(widthOf(clipTag(ClipId("long")))).isWithin(1.0).of(20 * 60_000 / TimelineZoom.fitAllMsPerDp(total, span))
        assertThat(widthOf(clipTag(ClipId("long")))).isAtMost(widthOf(TIMELINE_TAG))
    }

    @Test
    fun `a long video zoomed in all the way stays within what can be laid out`() {
        val screen = EditorScreenState(zoom = ZoomState(TimelineZoom.closeUpMsPerDp, 0.0, fitAll = false))
        val long = project(10)
        show(long, screen)
        // Zoom in as far as it goes: the 10-minute video must still be under Compose's limit.
        compose.runOnIdle { screen.zoom = ZoomState(TimelineZoom.closeUpMsPerDp / 4, 0.0, fitAll = false) }
        compose.waitForIdle()
        // The frame row's item runs from the intro's end to the video's end, so it starts on screen.
        val frame =
            long.tracks
                .first { it.presetRole == PresetRole.FRAME }
                .clips
                .first()
                .id
        val px =
            compose
                .onNodeWithTag(clipTag(frame), useUnmergedTree = true)
                .fetchSemanticsNode()
                .size.width
        assertThat(px).isAtMost(COMPOSE_MAX_PX)
    }

    private companion object {
        /** The widest box Compose can measure when its height is small (Constraints keeps 18 bits for it). */
        const val COMPOSE_MAX_PX = 262_143
    }
}
