package com.itsluminous.clearmovie.render.media3

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.render.media3.ColourMath.Matrix.BT601
import com.itsluminous.clearmovie.render.media3.ColourMath.Matrix.BT709
import org.junit.Test
import kotlin.math.abs

class DeviceColourTest {
    private fun rgb(vararg v: Int) = v.map { it.toDouble() }.toDoubleArray()

    private fun maxDiff(
        a: DoubleArray,
        b: DoubleArray,
    ) = a.indices.maxOf { abs(a[it] - b[it]) }

    @Test
    fun `the correction undoes a BT601 decode of BT709 video`() {
        val m = ColourMath.bt601DecodedToBt709()
        // Frame border, strip, key green, skin, grey, a photo blue, the calibration colour.
        for (c in listOf(
            rgb(194, 17, 13),
            rgb(15, 61, 175),
            rgb(0, 217, 34),
            rgb(224, 172, 105),
            rgb(60, 60, 60),
            rgb(32, 64, 224),
            rgb(223, 41, 225),
        )) {
            val misdecoded = ColourMath.toRgb(ColourMath.toYuv(c, BT709), BT601)
            assertThat(maxDiff(ColourMath.apply(m, misdecoded), c)).isLessThan(0.5)
        }
        // Greys are unchanged, so the correction never tints neutral content.
        assertThat(maxDiff(ColourMath.apply(m, rgb(128, 128, 128)), rgb(128, 128, 128))).isLessThan(0.01)
    }

    @Test
    fun `the emulator's misdecode of the frame border matches the measured values`() {
        // The tagged frame fixture's border YUV (64,109,206): ffmpeg gives (194,17,13) with BT.709, MMR's misread (180,0,18).
        val yuv = doubleArrayOf(64.0, 109.0, 206.0)
        assertThat(maxDiff(ColourMath.toRgb(yuv, BT709).map { it.coerceIn(0.0, 255.0) }.toDoubleArray(), rgb(194, 17, 13))).isLessThan(3.0)
        assertThat(maxDiff(ColourMath.toRgb(yuv, BT601).map { it.coerceIn(0.0, 255.0) }.toDoubleArray(), rgb(180, 0, 18))).isLessThan(3.0)
    }

    @Test
    fun `calibration tells each matrix apart`() {
        val yuv = ColourCalibration.CALIBRATION_YUV
        val gl601 = ColourMath.toRgb(yuv, BT601)
        val gl709 = ColourMath.toRgb(yuv, BT709)
        // The Android 10 emulator: GPU and encoder both BT.601.
        assertThat(ColourCalibration.classify(gl601, ColourMath.toYuv(gl601, BT601))).isEqualTo(DeviceColour(true, true))
        // The Android 16 emulator: GPU BT.601, encoder BT.709.
        assertThat(ColourCalibration.classify(gl601, ColourMath.toYuv(gl601, BT709))).isEqualTo(DeviceColour(true, false))
        // A device that honours BT.709 throughout.
        assertThat(ColourCalibration.classify(gl709, ColourMath.toYuv(gl709, BT709))).isEqualTo(DeviceColour(false, false))
        // Within rounding of the measurement.
        val noisy = gl601.map { it + 2 }.toDoubleArray()
        assertThat(
            ColourCalibration.classify(
                noisy,
                ColourMath
                    .toYuv(gl601, BT601)
                    .map {
                        it - 1.5
                    }.toDoubleArray(),
            ),
        ).isEqualTo(DeviceColour(true, true))
    }

    @Test
    fun `a measurement between the two matrices changes nothing`() {
        val yuv = ColourCalibration.CALIBRATION_YUV
        val a = ColourMath.toRgb(yuv, BT601)
        val b = ColourMath.toRgb(yuv, BT709)
        val middle = DoubleArray(3) { (a[it] + b[it]) / 2 }
        assertThat(ColourCalibration.classify(middle, ColourMath.toYuv(middle, BT709))).isEqualTo(DeviceColour.TRUSTED)
        // Far from both, as a broken readback would be.
        assertThat(ColourCalibration.classify(rgb(0, 0, 0), ColourMath.toYuv(b, BT709))).isEqualTo(DeviceColour.TRUSTED)
    }

    @Test
    fun `the cache line is tied to its key and refuses anything else`() {
        val c = DeviceColour(gpuDecodesBt709AsBt601 = true, encoderWritesBt601 = false, encoderName = "c2.android.avc.encoder")
        val line = c.serialize("v1|fp")
        assertThat(DeviceColour.parse(line, "v1|fp")).isEqualTo(c)
        val unnamed = DeviceColour(true, false)
        assertThat(DeviceColour.parse(unnamed.serialize("v1|fp"), "v1|fp")).isEqualTo(unnamed)
        // A BT.601 encoder without a name, or a result that was not measured, is never stored as usable.
        assertThat(DeviceColour.parse(DeviceColour(true, true).serialize("v1|fp"), "v1|fp")).isNull()
        assertThat(DeviceColour.parse("v1|fp|0|0|0|", "v1|fp")).isNull()
        val rk = DeviceColour(false, true, encoderName = "OMX.rk.video_encoder.avc")
        assertThat(DeviceColour.parse(rk.serialize("v1|fp"), "v1|fp")).isEqualTo(rk)
        // Names with a plus or a colon, as some vendors use.
        for (name in listOf("c2.vendor.h264+secure.encoder", "OMX.vendor:avc.encoder")) {
            val named = DeviceColour(false, true, encoderName = name)
            assertThat(DeviceColour.parse(named.serialize("v1|fp"), "v1|fp")).isEqualTo(named)
        }
        // A name that is not a codec name is never written, so the line is not usable.
        assertThat(DeviceColour(false, true, encoderName = "bad|name").serialize("v1|fp")).isEqualTo("v1|fp|0|1|1|")
        assertThat(DeviceColour.parse(DeviceColour(false, true, encoderName = "bad|name").serialize("v1|fp"), "v1|fp")).isNull()
        // A name that is not a codec name is refused.
        assertThat(DeviceColour.parse("v1|fp|1|1|1|bad name/../x", "v1|fp")).isNull()
        assertThat(DeviceColour.parse("v1|fp|1|1|1|a|b", "v1|fp")).isNull()
        assertThat(DeviceColour.parse(line + "\n", "v1|fp")).isEqualTo(c)
        assertThat(DeviceColour.parse(line, "v1|other")).isNull()
        assertThat(DeviceColour.parse("v1|fp|1|0", "v1|fp")).isNull()
        assertThat(DeviceColour.parse("v1|fp|1|2|1", "v1|fp")).isNull()
        assertThat(DeviceColour.parse("", "v1|fp")).isNull()
    }

    @Test
    fun `untagged video is BT709 from 720 lines up, as Android assumes`() {
        assertThat(RetrieverProbe.videoIsBt709(null, 1080)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 480)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(android.media.MediaFormat.COLOR_STANDARD_BT709, 480)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, 1080)).isFalse()
        // Values outside the public constants (the emulator reports 71) fall back to size.
        assertThat(RetrieverProbe.videoIsBt709(71, 1080)).isTrue()
        // HDR is converted by Media3 itself, never corrected.
        assertThat(RetrieverProbe.videoIsBt709(null, 2160, android.media.MediaFormat.COLOR_TRANSFER_HLG)).isFalse()
        assertThat(
            RetrieverProbe.videoIsBt709(
                android.media.MediaFormat.COLOR_STANDARD_BT709,
                1080,
                android.media.MediaFormat.COLOR_TRANSFER_ST2084,
            ),
        ).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 1080, android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO)).isTrue()
    }
}
