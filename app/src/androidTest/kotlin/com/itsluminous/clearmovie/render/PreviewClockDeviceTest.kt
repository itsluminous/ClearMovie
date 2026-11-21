package com.itsluminous.clearmovie.render

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.preview.ExoPreviewPlayers
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import com.itsluminous.clearmovie.render.preview.PlayerRole
import com.itsluminous.clearmovie.render.preview.PreviewPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview's clock over a photo (cm-preview-smooth). There MAIN plays
 * only silence; with Media3's dynamic scheduling its position moved about
 * every 250 ms, so the photo's zoom and the captions' fades moved in steps,
 * and the frame's music, the outgoing video and the frame looked out of step
 * and were moved back over and over. The real engine (ExoPlayer for every
 * role, with the preset's frame, music and spoken ending) plays a photo; the
 * position read about every 40 ms must move nearly every time, and never
 * jump far ahead of the time between two reads. How often the followers
 * were moved back is reported, not checked.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewClockDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Counts the seeks the engine asks of each follower. */
    private class Counted(
        private val real: PreviewPlayer,
        val role: PlayerRole,
    ) : PreviewPlayer by real {
        var seeks = 0

        override fun seekTo(
            index: Int,
            positionMs: Long,
        ) {
            seeks++
            real.seekTo(index, positionMs)
        }
    }

    @Test
    fun theClockMovesSmoothlyOverAPhoto() {
        val ids = SequentialIds("c")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("clock"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12_000, endingMs = 3000), ids)
                .addToMain(
                    listOf(
                        VideoClip(ClipId("v"), fixture("seconds.mp4"), sourceDurationMs = 10_000, trimEndMs = 3000),
                        PhotoClip(ClipId("p"), fixture("photo_blue.png"), 5000),
                    ),
                    ids = ids,
                )
        val schedule = PreviewSchedule.of(RenderPlanBuilder(RenderHarness.preset).build(project, shortSide = 720))
        val photo = project.mainSlot(ClipId("p"))!!
        val context = ApplicationProvider.getApplicationContext<Context>()
        val made = mutableListOf<Counted>()
        val real = ExoPreviewPlayers(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var engine: LayeredPreviewEngine
        instrumentation.runOnMainSync {
            engine = LayeredPreviewEngine(context, scope) { role, key -> Counted(real.create(role, key), role).also { made += it } }
            // Into the photo, past its transition in, so MAIN plays only silence from here.
            engine.load(schedule, RenderHarness.resolver(), startUs = (photo.startMs + 1000) * 1000, play = false)
        }
        Thread.sleep(SETTLE_MS)
        val followers = made.filter { it.role != PlayerRole.MAIN }
        assertWithMessage("the preset brings followers").that(followers).isNotEmpty()
        instrumentation.runOnMainSync { engine.play() }
        // Started: the first moments may still wait for the sound to begin.
        Thread.sleep(START_MS)
        val seeksBefore = followers.sumOf { it.seeks }
        // Each read with the time it was taken: a busy main thread delays a read, and the clock
        // then rightly moved further; what counts is how far it moved against how long it was.
        val samples = mutableListOf<Pair<Long, Long>>()
        val until = SystemClock.uptimeMillis() + SAMPLE_FOR_MS
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync { samples += SystemClock.uptimeMillis() to engine.positionUs / 1000 }
            Thread.sleep(SAMPLE_EVERY_MS)
        }
        val seeks = followers.sumOf { it.seeks } - seeksBefore
        instrumentation.runOnMainSync { engine.release() }
        scope.cancel()
        val pairs = samples.zipWithNext()
        // Reads at least a frame apart in which the clock did not move at all.
        val still = pairs.count { (a, b) -> b.first - a.first >= MIN_APART_MS && b.second == a.second }
        // How far the clock jumped ahead of the time between two reads: the stepping clock caught up a quarter second at once.
        val jump = pairs.maxOfOrNull { (a, b) -> (b.second - a.second) - (b.first - a.first) } ?: 0L
        val line =
            "clock over a photo: ${samples.size} reads about ${SAMPLE_EVERY_MS} ms apart, $still without a move, " +
                "largest jump ahead of the time between reads $jump ms; followers moved back $seeks times"
        report(line)
        assertWithMessage(line).that(still).isAtMost(samples.size / MAX_STILL_SHARE)
        assertWithMessage(line).that(jump).isAtMost(MAX_JUMP_MS)
    }

    private companion object {
        const val SETTLE_MS = 1500L
        const val START_MS = 500L
        const val SAMPLE_FOR_MS = 2500L
        const val SAMPLE_EVERY_MS = 40L

        /** At most one read in this many may find the clock where it was. */
        const val MAX_STILL_SHARE = 5
        const val MIN_APART_MS = 30L
        const val MAX_JUMP_MS = 120L
    }
}
