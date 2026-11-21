package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import android.graphics.Color
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
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
 * The preset's frame shows in the paused preview after the playhead was put
 * in the intro and then on a video (cm-preview-smooth). The frame starts
 * after the intro, so the first seek sends its keyed player into its
 * silence; sent from there to the frame file while paused, the player stayed
 * buffering for good, and the frame never showed until the video was played:
 * only the exact draft (then behind "As exported") showed it. The frame's blue strip (scripts/make-fixtures.sh)
 * must be on the frame row's surface.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewFrameAfterSeekDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The frame's blue strip (0x1440B4), opaque, under the window near the right edge. */
    private fun stripShown(b: Bitmap): Boolean {
        val c = b.getPixel(b.width * 8 / 10, b.height * 885 / 1000)
        return Color.alpha(c) > 200 && Color.blue(c) > 120 && Color.red(c) < 90
    }

    @Test
    fun theFrameShowsAfterAPausedSeekFromTheIntroToAVideo() {
        val ids = SequentialIds("f")
        val project =
            ProjectFactory
                .fromPreset(
                    ProjectId("frame-seek"),
                    RenderHarness.preset,
                    PresetMedia(introMs = 2000, frameMs = 12_000, endingMs = 3000),
                    ids,
                ).addToMain(listOf(VideoClip(ClipId("v"), fixture("seconds.mp4"), sourceDurationMs = 10_000)), ids = ids)
        val schedule = PreviewSchedule.of(RenderPlanBuilder(RenderHarness.preset).build(project, shortSide = 720))
        val frameStart =
            schedule.overlays
                .single()
                .entries
                .first { it is PreviewSchedule.LaneEntry.Play }
                .startUs
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var shown = false
        val results = mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var main: TextureView
            lateinit var frame: TextureView
            lateinit var engine: LayeredPreviewEngine
            scenario.onActivity { activity ->
                main = TextureView(activity)
                frame = TextureView(activity).apply { isOpaque = false }
                activity.setContentView(
                    FrameLayout(activity).apply {
                        addView(main, ViewGroup.LayoutParams(640, 360))
                        addView(frame, ViewGroup.LayoutParams(640, 360))
                    },
                )
                engine = LayeredPreviewEngine(activity, scope)
                engine.setMainSurface(main)
                engine.load(schedule, RenderHarness.resolver(), startUs = 0, play = false)
                engine.setOverlaySurface(0, frame)
            }
            Thread.sleep(SETTLE_MS)

            fun frameShowsAfter(vararg seeks: Long): Boolean {
                seeks.forEach { us ->
                    instrumentation.runOnMainSync { engine.seekTo(us) }
                    Thread.sleep(SETTLE_MS)
                }
                var last: Bitmap? = null
                var ok = false
                val until = System.currentTimeMillis() + WAIT_MS
                while (!ok && System.currentTimeMillis() < until) {
                    instrumentation.runOnMainSync { last = frame.bitmap }
                    ok = last?.let(::stripShown) == true
                    if (!ok) Thread.sleep(250)
                }
                last?.let { savePng(it, "preview-frame-after-paused-seek-${seeks.size}") }
                return ok
            }
            // Into the intro, where the frame has not started (its player in its silence), then onto the video.
            val fromIntro = frameShowsAfter(frameStart / 3, frameStart * 2 / 3, frameStart + 3_000_000)
            // From the video back into the intro and onto the video again.
            val back = frameShowsAfter(frameStart / 2, frameStart + 5_000_000)
            results += "intro then video: ${if (fromIntro) "shown" else "not shown"}"
            results += "video, intro, video: ${if (back) "shown" else "not shown"}"
            shown = fromIntro && back
            instrumentation.runOnMainSync { engine.release() }
        }
        scope.cancel()
        val line = "frame after paused seeks: ${results.joinToString("; ")}"
        report(line)
        assertWithMessage(line).that(shown).isTrue()
    }

    private companion object {
        const val SETTLE_MS = 1500L

        /** The frame is up within a second or two when it works; it never came within ten when it did not. */
        const val WAIT_MS = 10_000L
    }
}
