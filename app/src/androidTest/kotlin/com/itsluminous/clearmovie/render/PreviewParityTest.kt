package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.ClipId
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
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.GainEnvelopeAudioProcessor
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.media3.PreviewPlayer
import com.itsluminous.clearmovie.render.plan.GainEnvelope
import com.itsluminous.clearmovie.render.plan.RenderPlan
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Risk 2 (preview equals export) and risk 7's preview half (seeking across
 * the A/B lanes), plus risk 4 (seek latency). The same Composition is
 * exported and played in `CompositionPlayer` into an ImageReader; after
 * seeking to each timestamp the shown frame is compared with the exported
 * one on a grid of patches.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewParityTest {
    /** Records every flush position Media3 gives the gain processors. */
    private val flushes: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    private inner class RecordingGain(
        envelope: GainEnvelope,
    ) : GainEnvelopeAudioProcessor(envelope) {
        override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
            flushes += streamMetadata.positionOffsetUs
            super.onFlush(streamMetadata)
        }
    }

    private fun richProject(): Project {
        val ids = SequentialIds("p")
        val a = ClipId("pa")
        return ProjectFactory
            .fromPreset(ProjectId("parity"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000), ids)
            .addToMain(
                listOf(
                    VideoClip(a, fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                    VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000),
                    PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                ),
                ids = ids,
            ).setTransition(a, Transition(TransitionKind.CROSSFADE, 1000))
            .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 4000, startMs = 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)), ids = ids)
            .addTimedClip(
                TextClip(
                    ids.clipId(),
                    startMs = 2500,
                    durationMs = 5000,
                    text = "Preview",
                    style = CaptionStyleOverride(background = TextBackground(ArgbColor(0xFFFF0000L), 12), box = NormRect(0.2, 0.62, 0.4, 0.12)),
                ),
                ids = ids,
            )
    }

    @Test
    fun previewFramesMatchTheExportAfterEverySeek() {
        val project = richProject()
        // Intro 0-2, dark 2-5, crossfade 4-5, light 4-7, photo 7-10, held frame 10-13.
        val plan = RenderPlanBuilder(RenderHarness.preset).build(project)
        val exported = RenderHarness.export(plan, "parity-export")
        val times = listOf(1.0, 3.2, 4.5, 6.0, 8.5, 11.5)
        val preview = previewFrames(plan, RenderHarness.resolver(), times, 960, 540)

        val failures = mutableListOf<String>()
        for ((i, t) in times.withIndex()) {
            val ex = frameAt(exported.file, (t * 1_000_000).toLong())
            val pv = preview.frames[i]
            savePng(ex, "parity-t%04.1f-export".format(Locale.ROOT, t))
            savePng(pv, "parity-t%04.1f-preview".format(Locale.ROOT, t))
            val diffs = mutableListOf<Double>()
            for (gy in 0 until 9) {
                for (gx in 0 until 16) {
                    val nx = (gx + 0.5) / 16
                    val ny = (gy + 0.5) / 9
                    val e = rgb(ex, nx, ny, 4)
                    val p = rgb(pv, nx, ny, 2)
                    diffs += e.indices.map { abs(e[it] - p[it]).toDouble() }.average()
                }
            }
            diffs.sort()
            val median = diffs[diffs.size / 2]
            val p90 = diffs[(diffs.size * 9) / 10]
            report(
                "parity t=$t median patch diff=%.1f p90=%.1f max=%.1f seek=%d ms".format(Locale.ROOT, median, p90, diffs.last(), preview.seekMs[i]),
            )
            if (median > 6 || p90 > 20) failures += "t=$t median $median p90 $p90"
        }
        report("parity gain processor flush offsets (us): ${flushes.toList()}")
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
        assertThat(preview.error).isNull()
    }

    class Preview(
        val frames: List<Bitmap>,
        val seekMs: List<Long>,
        val error: PlaybackException?,
    )

    /** Plays [plan] paused and grabs the frame shown after seeking to each of [timesS]. */
    fun previewFrames(
        plan: RenderPlan,
        resolver: MediaResolver,
        timesS: List<Double>,
        width: Int,
        height: Int,
    ): Preview {
        val context = RenderHarness.context
        val imageThread = HandlerThread("preview-images").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val latest = AtomicReference<Bitmap?>()
        val frameCount =
            java.util.concurrent.atomic
                .AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                latest.set(Bitmap.createBitmap(bmp, 0, 0, image.width, image.height))
                frameCount.incrementAndGet()
            }
        }, Handler(imageThread.looper))
        val main = Handler(Looper.getMainLooper())
        val player = AtomicReference<CompositionPlayer>()
        val error = AtomicReference<PlaybackException?>()
        val ready = CountDownLatch(1)
        main.post {
            val composition: Composition = CompositionFactory(context, resolver) { RecordingGain(it) }.build(plan)
            val p = PreviewPlayer.create(context)
            p.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        error.set(e)
                        ready.countDown()
                    }
                },
            )
            p.setVideoSurface(reader.surface, Size(width, height))
            p.setComposition(composition)
            p.prepare()
            player.set(p)
        }
        val frames = mutableListOf<Bitmap>()
        val seekMs = mutableListOf<Long>()
        try {
            check(ready.await(60, TimeUnit.SECONDS)) { "Preview never became ready" }
            error.get()?.let { throw AssertionError("Preview failed: ${it.errorCodeName}", it) }
            for (t in timesS) {
                val before = frameCount.get()
                val started = SystemClock.elapsedRealtime()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { player.get().seekTo((t * 1000).toLong()) }
                var firstFrameMs = -1L
                val deadline = started + 20_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (firstFrameMs < 0 && frameCount.get() > before) firstFrameMs = SystemClock.elapsedRealtime() - started
                    var state = 0
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { state = player.get().playbackState }
                    if (firstFrameMs >= 0 && state == Player.STATE_READY) break
                    SystemClock.sleep(20)
                }
                // Let the paused player settle on the seeked frame.
                SystemClock.sleep(500)
                seekMs += firstFrameMs
                frames += checkNotNull(latest.get()) { "No preview frame at $t" }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.get()?.release() }
            reader.close()
            imageThread.quitSafely()
        }
        return Preview(frames, seekMs, error.get())
    }
}
