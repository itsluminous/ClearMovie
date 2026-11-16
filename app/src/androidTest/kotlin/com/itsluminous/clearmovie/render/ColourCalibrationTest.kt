package com.itsluminous.clearmovie.render

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.media3.ColourCalibration
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.DeviceColour
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.RawYuv
import com.itsluminous.clearmovie.render.media3.RenderExporter
import com.itsluminous.clearmovie.render.media3.RetrieverProbe
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The device colour calibration: it must come to a clear answer on the
 * emulators (they misdecode BT.709, so a silent "trusted" would hide the
 * defect), and the BT.601 tag it can ask for must reach the file and decode
 * to the photo's colours.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ColourCalibrationTest {
    @Test
    fun calibrationMeasuresThisDevice() {
        val colour = checkNotNull(ColourCalibration.measure(RenderHarness.context)) { "The calibration export failed" }
        report("colour calibration: $colour")
        assertWithMessage("calibration could not tell the matrices apart").that(colour.measured).isTrue()
    }

    @Test
    fun aStreamTagWithoutAContainerTagDecidesTheMatrix() {
        val probe = RetrieverProbe(RenderHarness.context)
        // Both disagree with the size rule, so only the stream's own tag gets them right.
        val sd709 = checkNotNull(probe.probe(Uri.fromFile(File(RenderHarness.fixtureDir, "tag_sd709.mp4"))))
        val hd601 = checkNotNull(probe.probe(Uri.fromFile(File(RenderHarness.fixtureDir, "tag_hd601.mp4"))))
        report("stream tags: 640x480 BT.709 -> bt709=${sd709.bt709}, 1280x720 BT.601 -> bt709=${hd601.bt709}")
        assertWithMessage("640x480 tagged BT.709 in its stream").that(sd709.bt709).isTrue()
        assertWithMessage("1280x720 tagged BT.601 in its stream").that(hd601.bt709).isFalse()
    }

    @Test
    fun anInterruptedCalibrationAnswersNothingAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt()
        val result = ColourCalibration.measure(RenderHarness.context)
        // Clears the flag, so later tests on this thread are not interrupted.
        val stillInterrupted = Thread.interrupted()
        assertThat(result).isNull()
        assertWithMessage("the caller's interrupt was lost").that(stillInterrupted).isTrue()
    }

    @Test
    fun aCalibrationInterruptedWhileRunningAnswersNothingAndEndsItsThread() {
        val caller = Thread.currentThread()
        // Threads left by other tests end on their own; this one must be this test's.
        val earlier = calibrationThreads().toSet()
        val earlierPlayers = playbackThreads().toSet()
        // Interrupt once the calibration's own thread is up, so the wait is inside the export.
        val interrupter =
            Thread {
                val deadline = System.nanoTime() + 10_000_000_000L
                while ((calibrationThreads() - earlier).isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
                Thread.sleep(50)
                caller.interrupt()
            }.apply { start() }
        val result = ColourCalibration.measure(RenderHarness.context)
        val stillInterrupted = Thread.interrupted()
        interrupter.join()
        assertWithMessage("an interrupted calibration answered").that(result).isNull()
        assertWithMessage("the caller's interrupt was lost").that(stillInterrupted).isTrue()
        // Allow a slow emulator 30 s for the calibration's looper to end.
        val deadline = System.nanoTime() + 30_000_000_000L
        while ((calibrationThreads() - earlier).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertWithMessage("the calibration thread is still running").that(calibrationThreads() - earlier).isEmpty()
        // Media3 releases the player's decoders on its own playback thread, with its own timeout.
        val released = System.nanoTime() + 30_000_000_000L
        while ((playbackThreads() - earlierPlayers).isNotEmpty() && System.nanoTime() < released) Thread.sleep(50)
        report("interrupted calibration: playback threads still alive ${(playbackThreads() - earlierPlayers).size}")
        // Cancelling during decoder set-up can crash an emulator's codec service (the x86_64
        // c2.goldfish decoder does, in its own code), which then restarts; the first try after
        // that may time out. Leave the codecs working for the next test: a later calibration must
        // measure again, to a clear answer, as calibrationMeasuresThisDevice requires.
        var attempt = 0
        var recovered = false
        while (!recovered && attempt < RECOVERY_TRIES) {
            attempt++
            val started = System.nanoTime()
            recovered = ColourCalibration.measure(RenderHarness.context)?.measured == true
            report(
                "calibration after an interrupted one: try $attempt measured=$recovered in ${(System.nanoTime() - started) / 1_000_000} ms",
            )
        }
        assertWithMessage("no calibration measured after an interrupted one").that(recovered).isTrue()
    }

    private fun playbackThreads() = Thread.getAllStackTraces().keys.filter { it.name == "ExoPlayer:Playback" && it.isAlive }

    private companion object {
        const val RECOVERY_TRIES = 3
    }

    private fun calibrationThreads() = Thread.getAllStackTraces().keys.filter { it.name == "colour-calibration" && it.isAlive }

    @Test
    fun calibrationClipIsWhatTheMeasurementAssumes() {
        val clip = File(RenderHarness.context.cacheDir, "calibration-clip.mp4")
        RenderHarness.context.assets
            .open("render/colour-calibration.mp4")
            .use { input -> clip.outputStream().use { input.copyTo(it) } }
        try {
            val e = MediaExtractor()
            val format =
                try {
                    e.setDataSource(clip.path)
                    (0 until e.trackCount).map { e.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                } finally {
                    e.release()
                }
            assertThat(format.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(ColourCalibration.CLIP_WIDTH)
            assertThat(format.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(ColourCalibration.CLIP_HEIGHT)
            val yuv = checkNotNull(RawYuv.meanAt(clip, 500_000, ColourCalibration.CLIP_WIDTH / 2, ColourCalibration.CLIP_HEIGHT / 2))
            report("calibration clip YUV ${yuv.toList()}")
            yuv.indices.forEach { assertThat(yuv[it]).isWithin(1.0).of(ColourCalibration.CALIBRATION_YUV[it]) }
        } finally {
            clip.delete()
        }
    }

    private fun colourStandard(file: File): Int? {
        val e = MediaExtractor()
        try {
            e.setDataSource(file.path)
            val f = (0 until e.trackCount).map { e.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            return if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) f.getInteger(MediaFormat.KEY_COLOR_STANDARD) else null
        } finally {
            e.release()
        }
    }

    @Test
    fun bt601TagReachesTheFileAndKeepsPhotoColours() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(
                    ProjectId("tag"),
                    ids,
                ).addToMain(listOf(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 2000)), ids = ids)
        val plan = RenderPlanBuilder().build(project)
        // The tag is asked for only from the encoder the calibration measured.
        val measured = checkNotNull(ColourCalibration.of(RenderHarness.context).encoderName) { "The calibration named no encoder" }
        val colour = DeviceColour(gpuDecodesBt709AsBt601 = false, encoderWritesBt601 = true, encoderName = measured)
        val composition = CompositionFactory(RenderHarness.context, RenderHarness.resolver()) { colour }.build(plan)
        val out =
            RenderHarness.exportComposition(
                composition,
                "tag-bt601",
                RenderExporter(RenderHarness.context, plan.height, colour),
                EncodeSpec.forHeight(plan.height),
            )
        val standard = colourStandard(out.file)
        report("tag-bt601: KEY_COLOR_STANDARD $standard, encoder ${out.result.videoEncoderName}")
        assertThat(standard).isAnyOf(MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC)
        val f = frameAt(out.file, 1_000_000)
        // photo_quadrants fills the canvas (fill-crop): red, green, blue, white.
        val got = listOf(rgb(f, 0.25, 0.25), rgb(f, 0.75, 0.25), rgb(f, 0.25, 0.75), rgb(f, 0.75, 0.75))
        val want = listOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 255, 255))
        report("tag-bt601 quadrants ${got.map { it.toList() }}")
        got.zip(want).forEach { (g, w) ->
            assertWithMessage("${g.toList()} want ${w.toList()}").that(RenderHarness.close(g, w, 14)).isTrue()
        }
    }
}
