package com.itsluminous.clearmovie.render

import android.media.MediaExtractor
import android.media.MediaFormat
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
    fun anInterruptedCalibrationAnswersNothingAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt()
        val result = ColourCalibration.measure(RenderHarness.context)
        // Clears the flag, so later tests on this thread are not interrupted.
        val stillInterrupted = Thread.interrupted()
        assertThat(result).isNull()
        assertWithMessage("the caller's interrupt was lost").that(stillInterrupted).isTrue()
    }

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
