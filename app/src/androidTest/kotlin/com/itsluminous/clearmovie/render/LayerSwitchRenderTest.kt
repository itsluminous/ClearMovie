package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.applyTransitionToAll
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.render.RenderHarness.close
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.LayerRole
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.VisualSegment
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The frame overlay must be on every frame, also right after another row
 * changes item. On the emulator's SwiftShader GL, while a draw left texture
 * unit 1 active, after most item switches some of the next frames (often
 * every third frame for up to about 0.8 s) showed the MAIN clip's picture
 * where the frame layer should be (no frame, no strip). This exports an 11 s
 * framed video whose rows switch item at these boundaries: intro to clip, a
 * crossfade, a wipe, cuts from video to photo and back, a clip with a blurred
 * background, the last clip's held frame under the spoken ending, two
 * captions starting and ending, and a picture in picture appearing and going.
 * It checks the frame's blue strip on every frame the frame row covers.
 * Without the fix it found 28-32 bad frames of 269 in three runs: after the
 * captions, the picture in picture, the wipe's start, both transitions' ends
 * and the cut back to video (once, the intro cut). The crossfade's start, the
 * cut to the photo and the cut to the ending are checked too but did not
 * trigger it there.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LayerSwitchRenderTest {
    @Test
    fun frameOverlayIsOnEveryFrameAcrossItemSwitches() {
        val ids = SequentialIds("ls")
        val main =
            listOf(
                VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, trimEndMs = CLIP_MS),
                VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000, trimEndMs = CLIP_MS),
                VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, trimEndMs = CLIP_MS, fit = Fit.FIT_BLUR),
                PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 800),
                VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000, trimEndMs = CLIP_MS),
            )
        var project =
            ProjectFactory
                .fromPreset(
                    ProjectId("layer-switch"),
                    RenderHarness.preset,
                    PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000),
                    ids,
                ).addToMain(main, ids = ids)
                .applyTransitionToAll(null)
                // Clip 1 to 2 a crossfade, 2 to 3 a wipe; the rest are cuts.
                .setTransition(main[0].id, Transition(TransitionKind.CROSSFADE, TRANSITION_MS))
                .setTransition(main[1].id, Transition(TransitionKind.WIPE_LEFT, TRANSITION_MS))
        // MAIN: intro 0-2.0 s, clips 2.0-3.5, 3.1-4.6 (crossfade 3.1-3.5) and 4.2-5.7 s
        // (wipe 4.2-4.6), the photo 5.7-6.5 s, the last clip 6.5-8.0 s, then the spoken
        // ending to 11.0 s under that clip's held frame. Each caption and the picture in
        // picture starts 0.2 s after a MAIN switch and ends 0.4 s or more before the next.
        for (startMs in listOf(2_200L, 6_700L)) {
            project = project.addTimedClip(TextClip(ids.clipId(), startMs = startMs, durationMs = 500, text = "Hi"), ids = ids)
        }
        project =
            project.addTimedClip(
                PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 500, startMs = 4_800, pip = PipPlacement(Corner.TOP_LEFT, 0.3)),
                ids = ids,
            )
        val plan = RenderPlanBuilder(RenderHarness.preset).build(project, height = 360)
        val frameRow =
            plan.visual
                .single { it.role == LayerRole.OVERLAY }
                .segments
                .filterIsInstance<VisualSegment.Picture>()
        val frameStartUs = frameRow.first().startUs
        val frameEndUs = frameRow.last().endUs
        val out = RenderHarness.export(plan, "layer-switch")

        // Sample one point of the blue strip under the window, right of the logo and
        // clear of the centred caption text (it is inside the caption box, which the
        // shipped preset draws with no band or outline), on every frame the frame row
        // covers (one frame of slack at each end).
        val source = rgb(frameAt(File(RenderHarness.fixtureDir, "frame.mp4"), 1_500_000), STRIP_X, STRIP_Y)
        val edgeUs = 1_000_000L / plan.fps
        val samples = mutableListOf<Pair<Int, IntArray>>()
        val kept = mutableMapOf<Int, Bitmap>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(out.file.absolutePath)
            val count = checkNotNull(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)).toInt()
            var index = 0
            while (index < count) {
                val frames = r.getFramesAtIndex(index, minOf(BATCH, count - index))
                check(frames.isNotEmpty()) { "No frames decoded from frame $index of $count" }
                try {
                    frames.forEachIndexed { i, frame ->
                        val at = (index + i).toLong() * 1_000_000 / plan.fps
                        if (at in (frameStartUs + edgeUs) until (frameEndUs - edgeUs)) {
                            val got = rgb(frame, STRIP_X, STRIP_Y)
                            samples += (index + i) to got
                            if (kept.size < KEEP && !close(got, source, SOURCE_TOLERANCE)) {
                                kept[index + i] = frame.copy(Bitmap.Config.ARGB_8888, false)
                            }
                        }
                    }
                } finally {
                    frames.forEach { it.recycle() }
                }
                index += frames.size
            }
        } finally {
            r.release()
        }

        // The strip as this device's encoder and decoder show it: the median of all
        // samples (frames without the overlay are a minority even when the defect is
        // there). It must be the frame's blue, and no picture under the frame may look
        // like it at the sample point, or a frame without the overlay would pass.
        assertWithMessage("frames sampled under the frame row").that(samples).isNotEmpty()
        val strip = IntArray(3) { c -> samples.map { it.second[c] }.sorted()[samples.size / 2] }
        assertWithMessage(
            "strip ${strip.toList()} against the frame source ${source.toList()}",
        ).that(close(strip, source, SOURCE_TOLERANCE)).isTrue()
        for (name in listOf("clip_dark.mp4", "clip_light.mp4")) {
            val under = rgb(frameAt(File(RenderHarness.fixtureDir, name), 1_500_000), STRIP_X, STRIP_Y)
            assertWithMessage("$name at the strip point looks like the strip").that(close(under, strip, TOLERANCE)).isFalse()
        }
        val quadrants = android.graphics.BitmapFactory.decodeFile(File(RenderHarness.fixtureDir, "photo_quadrants.png").path)
        assertWithMessage("the photo at the strip point looks like the strip")
            .that(close(rgb(quadrants, STRIP_X, STRIP_Y), strip, TOLERANCE))
            .isFalse()

        val bad =
            samples
                .filter {
                    !close(it.second, strip, TOLERANCE)
                }.map { (n, got) -> "frame $n: strip ${got.toList()}, want ${strip.toList()}" }
        kept.forEach { (n, frame) ->
            savePng(frame, "layer-switch-bad-$n")
            frame.recycle()
        }
        val checked = samples.size
        val expected = ((frameEndUs - frameStartUs - 2 * edgeUs) * plan.fps / 1_000_000).toInt()
        val span = "${frameStartUs / 1000}-${frameEndUs / 1000} ms"
        val firstBad = bad.take(10)
        report("layer-switch: frame row $span, checked $checked of about $expected frames, ${bad.size} without the frame strip $firstBad")
        assertWithMessage("frames checked").that(checked).isAtLeast(expected - 1)
        assertWithMessage("frames whose frame overlay is missing").that(bad).isEmpty()
    }

    private companion object {
        /** Each video clip's trimmed length, short so the export is 11 s (17.8 s before) on slow CI emulators. */
        const val CLIP_MS = 1_500L

        /** At most half of [CLIP_MS] (Timeline.maxTransitionMs); the second clip has a transition at each end. */
        const val TRANSITION_MS = 400L
        const val STRIP_X = 0.92
        const val STRIP_Y = 0.9
        const val TOLERANCE = 40

        /** How far the export's strip may be from the frame source's (colour handling of a small export). */
        const val SOURCE_TOLERANCE = 60
        const val BATCH = 30
        const val KEEP = 3
    }
}
