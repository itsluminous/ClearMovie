package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextBackground
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.luma
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.MediaUnavailableException
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Placement and bitmap details the review asked to see in real exports:
 * translucent captions, rotation direction and bars, the held last frame of
 * a video, and refusing a clip longer than its file.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlacementRenderTest {
    /** A 50% white caption box over a dark video blends to the midpoint, not darker (premultiplied alpha). */
    @Test
    fun translucentCaptionBoxBlendsLikeStraightAlpha() {
        val ids = SequentialIds("a")
        val project =
            ProjectFactory
                .blank(ProjectId("alpha"), ids)
                .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000)), ids = ids)
                .addTimedClip(
                    TextClip(
                        ids.clipId(),
                        startMs = 0,
                        durationMs = 3000,
                        text = "I",
                        style =
                            CaptionStyleOverride(
                                box = NormRect(0.1, 0.4, 0.5, 0.3),
                                sizePx = 20,
                                background = TextBackground(ArgbColor(0x80FFFFFFL), 0),
                            ),
                    ),
                    ids = ids,
                )
        val out = RenderHarness.export(RenderPlanBuilder().build(project), "translucent-caption")
        val f = frameAt(out.file, 1_500_000)
        savePng(f, "translucent-caption-t1.5")
        val dark = luma(rgb(frameAt(File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000), 0.8, 0.5))
        val box = luma(rgb(f, 0.2, 0.5))
        val want = 0.502 * 255 + 0.498 * dark
        report(
            "translucent caption: box luma %.1f, want %.1f (premultiplied twice would give %.1f)".format(
                box,
                want,
                0.502 * 0.502 * 255 + 0.498 * dark,
            ),
        )
        assertThat(box).isWithin(8.0).of(want)
    }

    /** A quarter turn clockwise, fit with bars: the portrait picture is centred between black bars. */
    @Test
    fun rotatedPhotoFitsBetweenBars() {
        val ids = SequentialIds("r")
        val project =
            ProjectFactory
                .blank(ProjectId("rotate"), ids)
                .addToMain(
                    listOf(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 2000, rotation = Rotation.R90, fit = Fit.FIT_BARS)),
                    ids = ids,
                )
        val out = RenderHarness.export(RenderPlanBuilder().build(project), "rotate-bars")
        val f = frameAt(out.file, 1_000_000)
        savePng(f, "rotate-bars-t1.0")
        // 640x360 turned to 360x640 at 1080 high: 607 px wide, x 0.342..0.658.
        // Clockwise: blue (was bottom-left) top-left, red top-right, white bottom-left, green bottom-right.
        val checks =
            mapOf(
                "left bar" to (0.1 to 0.5) to intArrayOf(0, 0, 0),
                "right bar" to (0.9 to 0.5) to intArrayOf(0, 0, 0),
                "top-left" to (0.42 to 0.25) to intArrayOf(0, 0, 255),
                "top-right" to (0.58 to 0.25) to intArrayOf(255, 0, 0),
                "bottom-left" to (0.42 to 0.75) to intArrayOf(255, 255, 255),
                "bottom-right" to (0.58 to 0.75) to intArrayOf(0, 255, 0),
            )
        val failures = mutableListOf<String>()
        for ((key, want) in checks) {
            val (name, p) = key
            val got = rgb(f, p.first, p.second)
            report("rotate-bars $name = ${got.toList()} want ${want.toList()}")
            if (!RenderHarness.close(got, want, 16)) failures += "$name ${got.toList()}"
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }

    /** A spoken ending after a video holds that video's last frame in the window. */
    @Test
    fun spokenEndingHoldsTheLastVideoFrame() {
        val ids = SequentialIds("h")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("hold"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000), ids)
                .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000)), ids = ids)
        val out = RenderHarness.export(RenderPlanBuilder(RenderHarness.preset).build(project), "hold-last-video")
        val f = frameAt(out.file, 6_500_000)
        savePng(f, "hold-last-video-t6.5")
        val window = RenderHarness.preset.frame!!.contentRect
        val got = rgb(f, window.x + window.w * 0.5, window.y + window.h * 0.6)
        val want = rgb(frameAt(File(RenderHarness.fixtureDir, "clip_light.mp4"), 2_900_000), 0.5, 0.6)
        report("hold-last-video t=6.5 window ${got.toList()} last frame ${want.toList()}")
        assertThat(RenderHarness.close(got, want, 14)).isTrue()
    }

    /** A clip that asks for more than its file holds fails the build instead of shifting later items. */
    @Test
    fun clipLongerThanItsFileIsRefused() {
        val ids = SequentialIds("l")
        val project =
            ProjectFactory
                .blank(ProjectId("long"), ids)
                .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 5000)), ids = ids)
        RenderHarness.fixtureDir
        val threw =
            try {
                CompositionFactory(RenderHarness.context, RenderHarness.resolver()).build(RenderPlanBuilder().build(project))
                false
            } catch (e: MediaUnavailableException) {
                true
            }
        assertThat(threw).isTrue()
    }
}
