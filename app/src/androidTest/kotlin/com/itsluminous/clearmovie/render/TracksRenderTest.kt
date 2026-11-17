package com.itsluminous.clearmovie.render

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import com.itsluminous.clearmovie.render.preview.StillLoader
import com.itsluminous.clearmovie.ui.editor.LayeredPreview
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.StackMove
import com.itsluminous.clearmovie.ui.editor.stack
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cm-tracks media evidence: two picture-in-picture rows overlapping in one
 * corner are exported in the order the row menu's Show on top / Show at
 * bottom set (the editor's own [stack]), with frames taken where they
 * overlap; and a picture-in-picture row shows in the layered editor
 * preview, grabbed from the screen.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TracksRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val blue = intArrayOf(32, 64, 224)

    /** MAIN light, a big dark video PiP and a small blue photo PiP in the top-right corner, both 0..3 s. */
    private fun twoPips(): Project {
        val ids = SequentialIds("r")
        return ProjectFactory
            .blank(ProjectId("tracks"), ids)
            .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000)), ids = ids)
            .addTimedClip(
                VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.5)),
                ids = ids,
            ).addTimedClip(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.25)), ids = ids)
    }

    @Test
    fun showOnTopAndShowAtBottomDecideWhichPictureInPictureCoversTheOther() {
        val base = twoPips()
        val rows = base.tracks.filter { it.type == TrackType.VISUAL }
        val small = rows.single { it.clips.single() is PhotoClip }.id
        val big = rows.single { it.clips.single() is VideoClip }.id
        // The small one is inside the big one's box: sample the small one's middle.
        val rect =
            RenderPlanBuilder.pipRect(
                PipPlacement(Corner.TOP_RIGHT, 0.25),
                com.itsluminous.clearmovie.domain.model.NormRect.FULL,
                16.0 / 9,
            )
        val x = rect.x + rect.w / 2
        val y = rect.y + rect.h / 2
        val dark = rgb(frameAt(java.io.File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000), 0.5, 0.5)

        fun sample(
            project: Project,
            name: String,
        ): IntArray {
            val out = RenderHarness.export(RenderPlanBuilder().build(project), name)
            val f = frameAt(out.file, 1_500_000)
            savePng(f, "$name-t01.5")
            return rgb(f, x, y).also { report("$name stacking=${project.stacking.map { it.value }} overlap at 1.5 s=${it.toList()}") }
        }
        val smallOnTop = base.stack(small, StackMove.TO_TOP)
        assertThat(smallOnTop.stackingTopFirst.first()).isEqualTo(small)
        val bigOnTop = smallOnTop.stack(small, StackMove.TO_BOTTOM).stack(big, StackMove.TO_TOP)
        assertThat(bigOnTop.stackingTopFirst.first()).isEqualTo(big)
        // Screen order is never touched by these.
        assertThat(bigOnTop.tracks.map { it.id }).isEqualTo(base.tracks.map { it.id })
        val a = sample(smallOnTop, "tracks-small-pip-on-top")
        val b = sample(bigOnTop, "tracks-big-pip-on-top")
        assertWithMessage("small one on top: its blue shows").that(RenderHarness.close(a, blue, 24)).isTrue()
        assertWithMessage("big one on top: its dark video covers the blue").that(RenderHarness.close(b, dark, 24)).isTrue()
    }

    @Test
    fun aPictureInPictureVideoShowsInTheLayeredPreview() {
        val ids = SequentialIds("w")
        val project =
            ProjectFactory
                .blank(ProjectId("pip-preview"), ids)
                .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000)), ids = ids)
                .addTimedClip(
                    VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.4)),
                    ids = ids,
                )
        val schedule = PreviewSchedule.of(RenderPlanBuilder().build(project, height = 720))
        assertThat(schedule.overlays).hasSize(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val activity = compose.activity
        val engine = LayeredPreviewEngine(activity, scope)
        val stills = StillLoader(activity)
        val resolver = RenderHarness.resolver()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.load(schedule, resolver, startUs = 1_500_000, play = false) }
        compose.setContent {
            val state by engine.state.collectAsState()
            ClearMovieTheme(dynamicColor = false) {
                LayeredPreview(engine, schedule, state, stills, resolver, Modifier.size(640.dp, 360.dp))
            }
        }
        val rect =
            RenderPlanBuilder.pipRect(
                PipPlacement(Corner.TOP_RIGHT, 0.4),
                com.itsluminous.clearmovie.domain.model.NormRect.FULL,
                16.0 / 9,
            )
        val dark = rgb(frameAt(java.io.File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000), 0.5, 0.5)
        val light = rgb(frameAt(java.io.File(RenderHarness.fixtureDir, "clip_light.mp4"), 1_500_000), 0.5, 0.5)
        var inside = IntArray(3)
        var outside = IntArray(3)
        val until = System.currentTimeMillis() + 10_000
        do {
            Thread.sleep(300)
            compose.waitForIdle()
            val grab = compose.onNodeWithTag(PREVIEW_TAG).captureToImage().asAndroidBitmap()
            inside = rgb(grab, rect.x + rect.w / 2, rect.y + rect.h / 2)
            outside = rgb(grab, 0.25, 0.7)
            if (RenderHarness.close(inside, dark, 30) && RenderHarness.close(outside, light, 30)) {
                savePng(grab, "tracks-preview-pip-t01.5")
                break
            }
        } while (System.currentTimeMillis() < until)
        report("preview pip: inside=${inside.toList()} (dark ${dark.toList()}), outside=${outside.toList()} (light ${light.toList()})")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.release() }
        scope.cancel()
        assertWithMessage("the picture in picture shows its video in its corner").that(RenderHarness.close(inside, dark, 30)).isTrue()
        assertWithMessage("MAIN shows around it").that(RenderHarness.close(outside, light, 30)).isTrue()
    }
}
