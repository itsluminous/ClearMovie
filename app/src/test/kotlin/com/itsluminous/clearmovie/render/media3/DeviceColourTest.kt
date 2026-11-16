package com.itsluminous.clearmovie.render.media3

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.render.media3.ColourMath.Matrix.BT601
import com.itsluminous.clearmovie.render.media3.ColourMath.Matrix.BT709
import com.itsluminous.clearmovie.render.media3.RetrieverProbe.Companion.Vui
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

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun `the colour an H264 stream states in its SPS is read`() {
        // SPS NAL units from libx264 (64x64 main), with BT.709, SMPTE 170M, BT.2020 HLG and no colour description.
        val bt709 = "00000001674d400aeca213602d4040405000000300100000030020f1225960"
        val bt601 = "00000001674d400aeca213602d4181819000000300100000030020f1225960"
        val hlg = "00000001674d400aeca213602d4244825000000300100000030020f1225960"
        val none = "00000001674d400aeca2136022000003000200000300041e244b2c"
        val sdr = android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        assertThat(
            RetrieverProbe.avcVui(hex(bt709)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT709, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        assertThat(
            RetrieverProbe.avcVui(hex(bt601)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, sdr, android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC))
        assertThat(
            RetrieverProbe.avcVui(hex(hlg)),
        ).isEqualTo(
            Vui(
                android.media.MediaFormat.COLOR_STANDARD_BT2020,
                android.media.MediaFormat.COLOR_TRANSFER_HLG,
                android.media.MediaFormat.COLOR_STANDARD_BT2020,
            ),
        )
        assertThat(RetrieverProbe.avcVui(hex(none))).isEqualTo(Vui.NONE)
        // A PPS after the SPS, as in csd-0, changes nothing.
        assertThat(RetrieverProbe.avcVui(hex(bt601 + "0000000168ee3c80"))).isEqualTo(RetrieverProbe.avcVui(hex(bt601)))
        assertThat(RetrieverProbe.avcVui(null)).isEqualTo(Vui.NONE)
        assertThat(RetrieverProbe.avcVui(hex("00000001"))).isEqualTo(Vui.NONE)
        assertThat(RetrieverProbe.avcVui(hex("0000000167"))).isEqualTo(Vui.NONE)
        // Cut short anywhere, an SPS reads as untagged rather than throwing.
        for (n in 6 until bt709.length / 2) {
            assertThat(
                RetrieverProbe.avcVui(hex(bt709).copyOf(n)),
            ).isAnyOf(Vui.NONE, RetrieverProbe.avcVui(hex(bt709)))
        }
    }

    @Test
    fun `the stream's matrix decides its standard, whatever its primaries`() {
        val sdr = android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        // BT.709 primaries and transfer with the BT.601 matrix, as many SD files are: the decoder uses BT.601.
        val bt709Primaries601Matrix = "00000001674d400aeca213602d4040419000000300100000030020f1225960"
        assertThat(
            RetrieverProbe.avcVui(hex(bt709Primaries601Matrix)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        // High profile (chroma format and bit depths in the SPS): BT.709, and BT.2020 PQ.
        val high709 = "000000016764000aacd94426c05a808080a000000300200000030041e244b2c0"
        val highPq = "000000016764000aacd94426c05a848804a000000300200000030041e244b2c0"
        assertThat(
            RetrieverProbe.avcVui(hex(high709)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT709, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        assertThat(
            RetrieverProbe.avcVui(hex(highPq)),
        ).isEqualTo(
            Vui(
                android.media.MediaFormat.COLOR_STANDARD_BT2020,
                android.media.MediaFormat.COLOR_TRANSFER_ST2084,
                android.media.MediaFormat.COLOR_STANDARD_BT2020,
            ),
        )
    }

    @Test
    fun `a container standard leads to what Media3 configures, its primaries`() {
        val bt709 = android.media.MediaFormat.COLOR_STANDARD_BT709
        val bt601 = android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC
        val bt2020 = android.media.MediaFormat.COLOR_STANDARD_BT2020

        fun of(code: Int) = RetrieverProbe.standardOf(code)
        // Extended: 64 + primaries + 7 * matrix, in Android's enum order.
        // BT.709 primaries with the matrix unstated (65), BT.601 (86), BT.2020 (100): BT.709 all.
        for (code in listOf(65, 86, 100)) assertThat(of(code)).isEqualTo(bt709)
        // Primaries unstated with the BT.709 matrix (71, as the Android 16 emulator reports): none.
        assertThat(of(71)).isNull()
        assertThat(of(64)).isNull()
        // Primaries unstated with the BT.2020 matrix (99): none.
        assertThat(of(99)).isNull()
        // BT.470M, BT.601 625 and 525 primaries (66, 67, 68), with any matrix (89 = 68 + 7 * 3).
        for (code in listOf(66, 67, 68, 89)) assertThat(of(code)).isEqualTo(bt601)
        // Film primaries (69): none. BT.2020 primaries (70, and 105 with the BT.2020 matrix).
        assertThat(of(69)).isNull()
        assertThat(of(70)).isEqualTo(bt2020)
        assertThat(of(105)).isEqualTo(bt2020)
        // Outside the extended range, and vendor codes (P3 primaries, BT.709 matrix, 0x10108): none.
        assertThat(of(113)).isNull()
        assertThat(of(0x10108)).isNull()
        assertThat(of(-1)).isNull()
        // Android's non-public plain codes: 3 and 5 (BT.601 primaries, other matrices), 7 (BT.2020
        // constant), 8 (BT.470M), 9 (film), 10 (Display P3).
        assertThat(of(3)).isEqualTo(bt601)
        assertThat(of(5)).isEqualTo(bt601)
        assertThat(of(7)).isEqualTo(bt2020)
        assertThat(of(8)).isEqualTo(bt601)
        assertThat(of(9)).isNull()
        assertThat(of(10)).isNull()
        // The public ones.
        assertThat(of(android.media.MediaFormat.COLOR_STANDARD_BT601_PAL)).isEqualTo(bt601)
        assertThat(of(bt601)).isEqualTo(bt601)
        assertThat(of(bt709)).isEqualTo(bt709)
        assertThat(of(bt2020)).isEqualTo(bt2020)
    }

    @Test
    fun `every SPS field before the colour is skipped right`() {
        val sdr = android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        // libx264 1920x1080 High: frame cropping (1088 coded rows); BT.709 primaries, BT.601 matrix.
        val cropped = "0000000167640028acd940780227e5c05a80808320000003002000000641e30632c0"
        assertThat(
            RetrieverProbe.avcVui(hex(cropped)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        // libx264 interlaced (frame_mbs_only 0), cropped, with an extended SAR (13:11, aspect_ratio_idc 255); BT.709.
        val interlaced = "0000000167640028acd94078044fdffe001a0016d4040405000003000100000300321f162d96"
        assertThat(
            RetrieverProbe.avcVui(hex(interlaced)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT709, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        // Written bit by bit and checked with ffmpeg's trace_headers: High with two scaling lists
        // (4x4 and 8x8), pic_order_cnt_type 1 with a 3-frame cycle, cropping, extended SAR, matrix 5.
        val scaled =
            "0000000167640028ad9999999999999999826666666666666666666666666666666666666666666666666666666666666666a15108c4201e0089f97ff000d000bb5010105020"
        assertThat(
            RetrieverProbe.avcVui(hex(scaled)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT601_PAL, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        // libx264 64x64 Main with matrix 4 (FCC, close to BT.601) and 7 (SMPTE 240M, close to BT.709).
        // Primaries BT.470BG (5) and SMPTE 240M (7) with the matrix unstated: no standard, configured as
        // Media3 does (BT.601 for both).
        for (primaries in listOf(
            "00000001674d400aeca213602d4140409000000300100000030020f1225960",
            "00000001674d400aeca213602d41c0409000000300100000030020f1225960",
        )) {
            assertThat(RetrieverProbe.avcVui(hex(primaries))).isEqualTo(Vui(null, sdr, android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC))
        }
        val fcc = "00000001674d400aeca213602d4040411000000300100000030020f1225960"
        val smpte240 = "00000001674d400aeca213602d404041d000000300100000030020f1225960"
        assertThat(
            RetrieverProbe.avcVui(hex(fcc)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
        assertThat(
            RetrieverProbe.avcVui(hex(smpte240)),
        ).isEqualTo(Vui(android.media.MediaFormat.COLOR_STANDARD_BT709, sdr, android.media.MediaFormat.COLOR_STANDARD_BT709))
    }

    @Test
    fun `the stream's tag wins over the container's, which wins over size`() {
        val bt709 = Vui(android.media.MediaFormat.COLOR_STANDARD_BT709, null)
        val bt601 = Vui(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, null)
        val pq = Vui(null, android.media.MediaFormat.COLOR_TRANSFER_ST2084)
        // classify(container, stream, ...): the decoder keeps the coded aspects.
        assertThat(RetrieverProbe.classify(bt601, bt709, 640, 480)).isTrue()
        assertThat(RetrieverProbe.classify(bt709, bt601, 1920, 1080)).isFalse()
        assertThat(RetrieverProbe.classify(bt601, Vui.NONE, 1920, 1080)).isFalse()
        assertThat(RetrieverProbe.classify(bt709, Vui.NONE, 640, 480)).isTrue()

        // A stream that states primaries but no matrix: Media3 configures its primaries, over the container.
        val streamPrimaries709 = Vui(null, null, configured = android.media.MediaFormat.COLOR_STANDARD_BT709)
        assertThat(RetrieverProbe.classify(bt601, streamPrimaries709, 640, 480)).isTrue()
        // A stated matrix wins over the stream's own primaries.
        val matrix601 = bt601.copy(configured = android.media.MediaFormat.COLOR_STANDARD_BT709)
        assertThat(RetrieverProbe.classify(Vui.NONE, matrix601, 640, 480)).isFalse()

        // Android's extended standard codes, through standardOf as the probe does.
        fun container(code: Int) = Vui(RetrieverProbe.standardOf(code), null)
        assertThat(RetrieverProbe.classify(container(86), Vui.NONE, 640, 480)).isTrue()
        assertThat(RetrieverProbe.classify(container(71), Vui.NONE, 640, 480)).isFalse()
        assertThat(RetrieverProbe.classify(container(68), Vui.NONE, 1920, 1080)).isFalse()
        assertThat(RetrieverProbe.classify(Vui.NONE, bt709, 640, 480)).isTrue()
        assertThat(RetrieverProbe.classify(Vui.NONE, bt601, 1920, 1080)).isFalse()
        assertThat(RetrieverProbe.classify(Vui.NONE, Vui.NONE, 640, 480)).isFalse()
        assertThat(RetrieverProbe.classify(Vui.NONE, Vui.NONE, 1920, 1080)).isTrue()
        // HDR in either place is never corrected, even under a BT.709 standard.
        assertThat(RetrieverProbe.classify(bt709, pq, 1920, 1080)).isFalse()
        assertThat(RetrieverProbe.classify(pq, bt709, 1920, 1080)).isFalse()
        // No size: 1280x720, as Codec2 assumes.
        assertThat(RetrieverProbe.classify(Vui.NONE, Vui.NONE, null, null)).isTrue()
    }

    @Test
    fun `untagged video is BT709 by size as Android assumes`() {
        assertThat(RetrieverProbe.videoIsBt709(null, 1920, 1080)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 1080, 1920)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 1280, 720)).isTrue()
        // A Small export's size is HD to Android, unlike 640x480 or PAL.
        assertThat(RetrieverProbe.videoIsBt709(null, 854, 480)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 640, 480)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 720, 576)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 480, 854)).isTrue()
        // 4K and up defaults to BT.2020.
        assertThat(RetrieverProbe.videoIsBt709(null, 3840, 2160)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 2160, 3840)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 0, 0)).isFalse()
        // The edges of Android's rule, each side of every bound.
        assertThat(RetrieverProbe.videoIsBt709(null, 720, 480)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 721, 480)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 720, 481)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 720, 576)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 720, 577)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 480, 720)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 480, 721)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 576, 720)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 577, 720)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 3839, 1080)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(null, 3000, 2092)).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 3000, 2091)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(android.media.MediaFormat.COLOR_STANDARD_BT709, 640, 480)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC, 1920, 1080)).isFalse()
        // A standard that is not a public one falls back to size.
        assertThat(RetrieverProbe.videoIsBt709(71, 1920, 1080)).isTrue()
        assertThat(RetrieverProbe.videoIsBt709(71, 640, 480)).isFalse()
        // HDR is converted by Media3 itself, never corrected.
        assertThat(RetrieverProbe.videoIsBt709(null, 1920, 1080, android.media.MediaFormat.COLOR_TRANSFER_HLG)).isFalse()
        assertThat(
            RetrieverProbe.videoIsBt709(
                android.media.MediaFormat.COLOR_STANDARD_BT709,
                1920,
                1080,
                android.media.MediaFormat.COLOR_TRANSFER_ST2084,
            ),
        ).isFalse()
        assertThat(RetrieverProbe.videoIsBt709(null, 1920, 1080, android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO)).isTrue()
    }
}
