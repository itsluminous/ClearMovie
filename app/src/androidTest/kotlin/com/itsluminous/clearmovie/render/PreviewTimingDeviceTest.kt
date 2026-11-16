package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import android.graphics.Color
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.setLoops
import com.itsluminous.clearmovie.domain.model.setSpeed
import com.itsluminous.clearmovie.domain.model.splitClip
import com.itsluminous.clearmovie.domain.model.trimVideo
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Trim, split, speed and loop land in the preview where the model says
 * (cm-timeline media-evidence rule). `seconds.mp4` shows one flat colour per
 * second of the file, so each preview frame says which second of the file is
 * on screen; the real preview engine (ExoPlayer on a TextureView) is sought
 * to known moments of the video and the colour is checked against the file
 * time the model gives for that moment.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewTimingDeviceTest {
    /** The colour of each second of seconds.mp4 (scripts/make-fixtures.sh). */
    private val secondColours =
        listOf(0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1, 0x1E88E5, 0x3949AB, 0x8E24AA, 0xD81B60, 0x6D4C41)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The second of the file a frame shows: the nearest of the ten colours, at the middle of the picture. */
    private fun secondShown(frame: Bitmap): Int {
        val c = frame.getPixel(frame.width / 2, frame.height / 2)
        return secondColours.indices.minBy { i ->
            val k = secondColours[i]
            val dr = Color.red(c) - (k shr 16 and 0xFF)
            val dg = Color.green(c) - (k shr 8 and 0xFF)
            val db = Color.blue(c) - (k and 0xFF)
            dr * dr + dg * dg + db * db
        }
    }

    @Test
    fun trimSplitSpeedAndLoopShowTheFileTimeTheModelGives() = check(withPreset = false)

    /**
     * The same with the preset: an intro first, and the frame, its music and the spoken ending
     * playing in players of their own beside MAIN, as in the editor (the engine's real
     * ExoPlayer set-up for every role, not only MAIN's).
     */
    @Test
    fun theSameHoldsWithThePresetsFrameMusicAndEndingPlaying() = check(withPreset = true)

    private fun check(withPreset: Boolean) {
        val ids = SequentialIds("t")
        val media = fixture("seconds.mp4")
        val start =
            if (withPreset) {
                ProjectFactory.fromPreset(
                    ProjectId("timing-preset"),
                    RenderHarness.preset,
                    com.itsluminous.clearmovie.domain.model
                        .PresetMedia(introMs = 2000, frameMs = 12_000, endingMs = 3000),
                    ids,
                )
            } else {
                ProjectFactory.blank(ProjectId("timing"), ids)
            }
        var project: Project =
            start
                .addToMain(
                    listOf(
                        VideoClip(ClipId("a"), media, sourceDurationMs = 10_000),
                        VideoClip(ClipId("b"), media, sourceDurationMs = 10_000),
                        VideoClip(ClipId("c"), media, sourceDurationMs = 10_000),
                    ),
                    ids = ids,
                )
        // a: trimmed to 2.0..5.0 s of the file, then split 1.5 s in (halves 2.0..3.5 and 3.5..5.0).
        project = project.trimVideo(ClipId("a"), 2000, 5000).splitClip(ClipId("a"), 1500, ClipId("a2"))
        // b: 4.0..8.0 s of the file at 2x: 2 s of video.
        project = project.trimVideo(ClipId("b"), 4000, 8000).setSpeed(ClipId("b"), Speed.X2)
        // c: 6.0..7.0 s of the file, looped twice: 2 s of video.
        project = project.trimVideo(ClipId("c"), 6000, 7000).setLoops(ClipId("c"), 2)

        // (clip, ms into the clip on the timeline, the file second the model says is there)
        data class Probe(
            val clip: String,
            val intoMs: Long,
        )

        fun fileMsAt(p: Probe): Long {
            val clip = project.mainClips.first { it.id == ClipId(p.clip) } as VideoClip
            val perLoop = clip.speed.timelineMs(clip.trimEndMs - clip.trimStartMs)
            return clip.trimStartMs + clip.speed.sourceMs(p.intoMs % perLoop)
        }
        val probes =
            listOf(
                Probe("a", 400),
                Probe("a", 1200),
                Probe("a2", 300),
                Probe("a2", 1200),
                Probe("b", 250),
                Probe("b", 1250),
                Probe("b", 1750),
                Probe("c", 500),
                Probe("c", 1500),
            )

        val schedule = PreviewSchedule.of(RenderPlanBuilder(if (withPreset) RenderHarness.preset else null).build(project, height = 720))
        if (withPreset) {
            assertWithMessage(
                "the preset brings a frame lane and sound lanes",
            ).that(schedule.overlays.size + schedule.sounds.size).isAtLeast(2)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val results = mutableListOf<String>()
        val failures = mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: TextureView
            lateinit var engine: LayeredPreviewEngine
            scenario.onActivity { activity ->
                view = TextureView(activity)
                activity.setContentView(view, ViewGroup.LayoutParams(640, 360))
                engine = LayeredPreviewEngine(activity, scope)
                engine.setMainSurface(view)
                engine.load(schedule, RenderHarness.resolver(), startUs = 0, play = false)
            }
            Thread.sleep(1500)
            for (p in probes) {
                val slot = project.mainSlot(ClipId(p.clip))!!
                val atMs = slot.startMs + p.intoMs
                instrumentation.runOnMainSync { engine.seekTo(atMs * 1000) }
                Thread.sleep(900)
                var frame: Bitmap? = null
                instrumentation.runOnMainSync { frame = view.bitmap }
                val shown = secondShown(checkNotNull(frame))
                val wantMs = fileMsAt(p)
                val want = (wantMs / 1000).toInt()
                val name = (if (withPreset) "preview-preset-" else "preview-") + "${p.clip}-plus${p.intoMs}ms-at${atMs}ms"
                savePng(frame!!, name)
                val line =
                    (if (withPreset) "preview (preset) " else "preview ") +
                        "${p.clip} +${p.intoMs} ms (video %.2f s): model says file %.2f s (second $want), frame shows second $shown"
                            .format(atMs / 1000.0, wantMs / 1000.0)
                results += line
                report(line)
                if (shown != want) failures += line
            }
            instrumentation.runOnMainSync { engine.release() }
        }
        scope.cancel()
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }
}
