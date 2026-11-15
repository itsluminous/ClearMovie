package com.itsluminous.clearmovie.render

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.totalDurationMs
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Spike risks 3 and 4, run by hand (`-e cm.long true`): they take minutes,
 * so CI skips them.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpikeLongTest {
    private val enabled = InstrumentationRegistry.getArguments().getString("cm.long") == "true"

    /** Risk 3: a 2-minute 1080p export with MAIN video, a PiP video and the keyed frame, watching memory. */
    @Test
    fun twoMinuteExportStaysWithinMemory() {
        assumeTrue("long spike tests are off", enabled)
        val ids = SequentialIds("m")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("memory"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000), ids)
                .addToMain(
                    (0 until 20).map { i ->
                        if (i % 2 == 0) {
                            VideoClip(ids.clipId(), fixture(if (i % 4 == 0) "clip_dark.mp4" else "clip_light.mp4"), sourceDurationMs = 3000, loops = 2)
                        } else {
                            PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 6000)
                        }
                    },
                    ids = ids,
                ).addTimedClip(
                    VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000, loops = 20, startMs = 2000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)),
                    ids = ids,
                )
        report("memory project: ${project.totalDurationMs()} ms")
        val peakPss = AtomicLong()
        val running = AtomicBoolean(true)
        val sampler =
            Thread {
                while (running.get()) {
                    val info = Debug.MemoryInfo()
                    Debug.getMemoryInfo(info)
                    peakPss.accumulateAndGet(info.totalPss.toLong()) { a, b -> maxOf(a, b) }
                    SystemClock.sleep(500)
                }
            }.apply { start() }
        val out =
            try {
                RenderHarness.export(RenderPlanBuilder(RenderHarness.preset).build(project), "memory-2min", timeoutMin = 40)
            } finally {
                running.set(false)
                sampler.join()
            }
        val am = RenderHarness.context.getSystemService(android.app.ActivityManager::class.java)
        report(
            "memory: exported ${RenderHarness.durationUs(out.file) / 1000} ms in ${out.wallMs} ms; peak PSS ${peakPss.get() / 1024} MB; " +
                "heap limit ${am.memoryClass} MB (large ${am.largeMemoryClass} MB); java heap max ${Runtime.getRuntime().maxMemory() / 1048576} MB",
        )
        assertThat(RenderHarness.durationUs(out.file)).isAtLeast(project.totalDurationMs() * 1000 - 50_000)
    }

    /**
     * Risk 4: seeking a frame video in a plain ExoPlayer (the layered
     * preview plays the frame this way). Uses the real frame when it is in
     * `realmedia/`, else the synthetic one.
     */
    @Test
    fun frameVideoSeekLatency() {
        assumeTrue("long spike tests are off", enabled)
        val real = File(checkNotNull(RenderHarness.context.getExternalFilesDir(null)), "realmedia/Frame-White-20s.mp4")
        val file = if (real.isFile) real else File(RenderHarness.fixtureDir, "frame.mp4")
        val player = arrayOfNulls<ExoPlayer>(1)
        val readyAt = AtomicLong(-1)
        val main = Handler(Looper.getMainLooper())
        val surface = android.media.ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.YUV_420_888, 2)
        val frames = AtomicLong()
        surface.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); frames.incrementAndGet() }, main)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player[0] =
                ExoPlayer.Builder(RenderHarness.context).build().apply {
                    setVideoSurface(surface.surface)
                    setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    addListener(
                        object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                readyAt.set(SystemClock.elapsedRealtime())
                            }
                        },
                    )
                    prepare()
                }
        }
        SystemClock.sleep(3000)
        val latencies = mutableListOf<Long>()
        for (t in listOf(1_300L, 7_900L, 3_100L, 15_400L, 600L, 11_250L)) {
            val before = frames.get()
            val start = SystemClock.elapsedRealtime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player[0]!!.seekTo(t) }
            while (frames.get() == before && SystemClock.elapsedRealtime() - start < 5000) SystemClock.sleep(5)
            latencies += SystemClock.elapsedRealtime() - start
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player[0]!!.release() }
        surface.close()
        report("frame seek latency (${file.name}, paused ExoPlayer): $latencies ms")
        assertThat(latencies.max()).isLessThan(5000)
    }
}
