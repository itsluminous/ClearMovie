package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextBackground
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.bringForward
import com.itsluminous.clearmovie.domain.model.sendBackward
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Risk 6 (layer order follows stacking) and risk 5 (several video rows at
 * once). Pixels where two rows overlap must show the row stacked higher, and
 * swap when the stacking changes.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class StackingRenderTest {
    private val red = intArrayOf(255, 0, 0)
    private val blue = intArrayOf(32, 64, 224)

    /** A red caption box over a blue picture in picture; with text on top the overlap is red, after "send backward" blue. */
    @Test
    fun textAbovePipThenSentBackward() {
        val ids = SequentialIds("s")
        val base =
            ProjectFactory
                .blank(ProjectId("stack"), ids)
                .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000)), ids = ids)
                .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.35)), ids = ids)
                .addTimedClip(
                    TextClip(
                        ids.clipId(),
                        startMs = 0,
                        durationMs = 3000,
                        text = "I",
                        style =
                            CaptionStyleOverride(
                                box = NormRect(0.55, 0.15, 0.25, 0.15),
                                sizePx = 24,
                                background = TextBackground(ArgbColor(0xFFFF0000L), 12),
                            ),
                    ),
                    ids = ids,
                )
        val text = base.tracks.single { it.type == TrackType.TEXT && it.clips.isNotEmpty() }
        val pip = base.tracks.single { it.type == TrackType.VISUAL }
        assertThat(base.stackIndex(text.id)).isGreaterThan(base.stackIndex(pip.id))
        val above = exportAndSample(base, "stacking-text-above")
        val behind = base.sendBackward(text.id)
        assertThat(behind.stackIndex(text.id)).isLessThan(behind.stackIndex(pip.id))
        val below = exportAndSample(behind, "stacking-text-sent-backward")

        report("stacking overlap text-above=${above.overlap.toList()} after-sendBackward=${below.overlap.toList()}")
        assertWithMessage("overlap with text above").that(RenderHarness.close(above.overlap, red, 20)).isTrue()
        assertWithMessage("overlap after send backward").that(RenderHarness.close(below.overlap, blue, 20)).isTrue()
        // Outside the overlap each row shows either way.
        for (s in listOf(above, below)) {
            assertWithMessage("caption only").that(RenderHarness.close(s.captionOnly, red, 20)).isTrue()
            assertWithMessage("picture only").that(RenderHarness.close(s.pipOnly, blue, 20)).isTrue()
        }
    }

    private class Samples(
        val overlap: IntArray,
        val captionOnly: IntArray,
        val pipOnly: IntArray,
    )

    private fun exportAndSample(
        project: Project,
        name: String,
    ): Samples {
        val out = RenderHarness.export(RenderPlanBuilder().build(project), name)
        val f = frameAt(out.file, 1_500_000)
        savePng(f, "$name-t01.5")
        // PiP rect: x 0.62..0.97, y 0.053..0.403. Caption box: x 0.55..0.80, y 0.15..0.30.
        val s = Samples(rgb(f, 0.77, 0.27), rgb(f, 0.57, 0.27), rgb(f, 0.92, 0.36))
        report("$name overlap=${s.overlap.toList()} captionOnly=${s.captionOnly.toList()} pipOnly=${s.pipOnly.toList()}")
        return s
    }

    /**
     * Four video decoders at once: MAIN video, two picture-in-picture video
     * rows in the same corner (the smaller inside the larger) and the keyed
     * frame. The smaller box shows whichever PiP row is stacked higher, and
     * swaps when the order is swapped.
     */
    @Test
    fun fourVideoRowsFollowStacking() {
        val ids = SequentialIds("v")
        val base =
            ProjectFactory
                .fromPreset(ProjectId("rows"), RenderHarness.preset, PresetMedia(frameMs = 12000), ids)
                .addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000)), ids = ids)
                .addTimedClip(
                    VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.5)),
                    ids = ids,
                ).addTimedClip(
                    VideoClip(ids.clipId(), fixture("intro.mp4"), sourceDurationMs = 2000, pip = PipPlacement(Corner.TOP_RIGHT, 0.25)),
                    ids = ids,
                )
        val big = base.tracks.single { t -> t.type == TrackType.VISUAL && t.clips.any { (it as VideoClip).media == fixture("clip_dark.mp4") } }
        val small = base.tracks.single { t -> t.type == TrackType.VISUAL && t.clips.any { (it as VideoClip).media == fixture("intro.mp4") } }
        val plan = RenderPlanBuilder(RenderHarness.preset).build(base)
        report("rows: video decoders at t=1s: ${plan.videoDecodersAt(1_000_000)}; stacking=${base.stacking.map { it.value }}")
        assertThat(plan.videoDecodersAt(1_000_000)).isEqualTo(4)

        val darkSrc = rgb(frameAt(File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_000_000), 0.5, 0.5)
        val orangeSrc = rgb(frameAt(File(RenderHarness.fixtureDir, "intro.mp4"), 1_000_000), 0.5, 0.8)
        // Small PiP (scale 0.25 of the window) in the top-right corner: sample its middle.
        val window = RenderHarness.preset.frame!!.contentRect
        val rect = RenderPlanBuilder.pipRect(PipPlacement(Corner.TOP_RIGHT, 0.25), window, 16.0 / 9)
        val px = rect.x + rect.w / 2
        val py = rect.y + rect.h * 0.8

        fun sample(
            project: Project,
            name: String,
        ): IntArray {
            val out = RenderHarness.export(RenderPlanBuilder(RenderHarness.preset).build(project), name)
            val f = frameAt(out.file, 1_000_000)
            savePng(f, "$name-t01.0")
            return rgb(f, px, py).also { report("$name small-pip-area=${it.toList()}") }
        }
        val smallOnTop = if (base.stackIndex(small.id) > base.stackIndex(big.id)) base else base.bringForward(small.id)
        assertThat(smallOnTop.stackIndex(small.id)).isGreaterThan(smallOnTop.stackIndex(big.id))
        val first = sample(smallOnTop, "rows-small-pip-on-top")
        val swapped = smallOnTop.sendBackward(small.id)
        assertThat(swapped.stackIndex(small.id)).isLessThan(swapped.stackIndex(big.id))
        val second = sample(swapped, "rows-small-pip-sent-backward")
        assertWithMessage("small PiP on top shows the orange video").that(RenderHarness.close(first, orangeSrc, 20)).isTrue()
        assertWithMessage("after send backward the big dark PiP covers it").that(RenderHarness.close(second, darkSrc, 20)).isTrue()
    }
}
