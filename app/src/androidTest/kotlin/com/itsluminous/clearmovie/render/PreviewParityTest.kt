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
        // Intro 0-2, dark 2-5 (crossfade 4-5), light 4-7, photo 7-10, held frame 10-13.
        val plan = RenderPlanBuilder(RenderHarness.preset).build(project)
        val exported = RenderHarness.export(plan, "parity-export")
        val times = listOf(1.0, 3.2, 4.5, 6.0, 8.5, 11.5).map { (it * 1_000_000).toLong() }
        val failures = mutableListOf<String>()
        PreviewProbe(plan, RenderHarness.resolver(), gain = { RecordingGain(it) }).use { probe ->
            // Scrubbing: a paused seek must show a new frame (risks 2, 4 and 7).
            for (t in times) {
                val ms = probe.pausedSeek(t)
                val shown = probe.lastFrame()
                report("parity paused seek to ${t / 1000} ms: first new frame after $ms ms, shown frame time ${shown?.timeUs}")
                if (ms < 0) failures += "paused seek to ${t / 1000} ms showed no new frame"
            }
            // Playback: frames at the same composition time match the export.
            val got = probe.playAndCapture(500_000, times)
            for ((i, t) in times.withIndex()) {
                val pv = got[i]
                if (pv == null) {
                    failures += "no preview frame near ${t / 1000} ms"
                    continue
                }
                val ex = frameAt(exported.file, pv.timeUs)
                val label = "%04.1f".format(Locale.ROOT, t / 1e6)
                savePng(ex, "parity-t$label-export")
                savePng(pv.bitmap, "parity-t$label-preview")
                val diffs = mutableListOf<Double>()
                for (gy in 0 until 9) {
                    for (gx in 0 until 16) {
                        val nx = (gx + 0.5) / 16
                        val ny = (gy + 0.5) / 9
                        val e = rgb(ex, nx, ny, 4)
                        val p = rgb(pv.bitmap, nx, ny, 2)
                        diffs += e.indices.map { abs(e[it] - p[it]).toDouble() }.average()
                    }
                }
                diffs.sort()
                val median = diffs[diffs.size / 2]
                val p90 = diffs[(diffs.size * 9) / 10]
                report(
                    "parity t=$label preview frame at %d us: median patch diff=%.1f p90=%.1f max=%.1f"
                        .format(Locale.ROOT, pv.timeUs, median, p90, diffs.last()),
                )
                if (abs(pv.timeUs - t) > 100_000) failures += "nearest preview frame to $t is at ${pv.timeUs}"
                if (median > 6 || p90 > 20) failures += "t=$label median $median p90 $p90"
            }
            assertThat(probe.error.get()).isNull()
        }
        report("parity gain processor flush offsets (us): ${flushes.toList()}")
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }
}
