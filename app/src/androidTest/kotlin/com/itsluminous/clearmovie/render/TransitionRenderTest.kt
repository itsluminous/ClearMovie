package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.domain.model.totalDurationMs
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.luma
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.LayerRole
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Risk 7 (A/B MAIN lanes): a three-clip crossfade. At each midpoint both
 * clips contribute 40-60% of the luminance difference; the video is shorter
 * by the two overlaps; the clip after the second junction is back in lane A.
 * Also timing: speed and loops give the planned length.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TransitionRenderTest {
    @Test
    fun crossfadeMidpointsBlendBothClips() {
        val ids = SequentialIds("x")
        val a = ClipId("dark1")
        val b = ClipId("light")
        val c = ClipId("dark2")
        val project =
            ProjectFactory
                .blank(ProjectId("xfade"), ids)
                .addToMain(
                    listOf(
                        VideoClip(a, fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                        VideoClip(b, fixture("clip_light.mp4"), sourceDurationMs = 3000),
                        VideoClip(c, fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                    ),
                    ids = ids,
                ).setTransition(a, Transition(TransitionKind.CROSSFADE, 1000))
                .setTransition(b, Transition(TransitionKind.CROSSFADE, 1000))
        assertThat(project.totalDurationMs()).isEqualTo(7000)
        val plan = RenderPlanBuilder().build(project)
        assertThat(plan.visual.map { it.role }).containsExactly(LayerRole.MAIN, LayerRole.MAIN_B).inOrder()
        val out = RenderHarness.export(plan, "crossfade")

        val dark = luma(rgb(frameAt(File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000), 0.5, 0.6))
        val light = luma(rgb(frameAt(File(RenderHarness.fixtureDir, "clip_light.mp4"), 1_500_000), 0.5, 0.6))
        val failures = mutableListOf<String>()
        for ((t, want) in listOf(1.0 to 0.0, 2.5 to 0.5, 3.5 to 1.0, 4.5 to 0.5, 6.0 to 0.0)) {
            val f = frameAt(out.file, (t * 1_000_000).toLong())
            savePng(f, "crossfade-t%03.1f".format(java.util.Locale.ROOT, t))
            val y = luma(rgb(f, 0.5, 0.6))
            val share = (y - dark) / (light - dark)
            report("crossfade t=$t luma=%.1f dark=%.1f light=%.1f light share=%.3f (want %.1f)".format(java.util.Locale.ROOT, y, dark, light, share, want))
            val ok = if (want == 0.5) share in 0.4..0.6 else kotlin.math.abs(share - want) < 0.08
            if (!ok) failures += "t=$t light share $share, want $want"
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
        assertThat(RenderHarness.durationUs(out.file)).isIn(Range.closed(6_950_000L, 7_050_000L))
    }

    /** Speed 2x of a 3 s clip lasts 1.5 s; two loops of it 3 s; a photo its duration: 6.5 s in all. */
    @Test
    fun speedAndLoopsGiveThePlannedLength() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("timing"), ids)
                .addToMain(
                    listOf(
                        VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, speed = Speed.X2),
                        VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000, trimStartMs = 500, trimEndMs = 2000, loops = 2),
                        PhotoClip(ids.clipId(), fixture("photo_blue.png"), 2000),
                    ),
                    ids = ids,
                )
        assertThat(project.totalDurationMs()).isEqualTo(6500)
        val out = RenderHarness.export(RenderPlanBuilder().build(project), "timing")
        val d = RenderHarness.durationUs(out.file)
        report("timing planned=6500000us exported=${d}us")
        assertThat(d).isIn(Range.closed(6_450_000L, 6_550_000L))
        val light = rgb(frameAt(out.file, 2_500_000), 0.5, 0.6)
        val blue = rgb(frameAt(out.file, 5_500_000), 0.5, 0.5)
        val dark = rgb(frameAt(out.file, 700_000), 0.5, 0.6)
        report("timing dark@0.7=${dark.toList()} light@2.5=${light.toList()} blue@5.5=${blue.toList()}")
        assertThat(luma(dark)).isLessThan(90.0)
        assertThat(luma(light)).isGreaterThan(170.0)
        assertThat(RenderHarness.close(blue, intArrayOf(32, 64, 224), 20)).isTrue()
    }
}
