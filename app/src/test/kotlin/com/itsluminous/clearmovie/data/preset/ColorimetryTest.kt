package com.itsluminous.clearmovie.data.preset

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** YUV to RGB with the stream's own matrix (the key-colour check's decoder). */
class ColorimetryTest {
    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun `limited-range black, white and grey`() {
        for (c in listOf(Colorimetry.BT709, Colorimetry.BT601)) {
            assertThat(rgb(c.toArgb(16, 128, 128))).isEqualTo(Triple(0, 0, 0))
            assertThat(rgb(c.toArgb(235, 128, 128))).isEqualTo(Triple(255, 255, 255))
        }
        assertThat(rgb(Colorimetry.BT709.copy(fullRange = true).toArgb(255, 128, 128))).isEqualTo(Triple(255, 255, 255))
    }

    @Test
    fun `the key green's BT 709 code values decode to the key green, and the BT 601 matrix gets it wrong`() {
        // ffmpeg's BT.709 limited-range code values for #00D922 (scale=out_color_matrix=bt709).
        val (r, g, b) = Triple(0.0, 217 / 255.0, 34 / 255.0)
        val yl = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val cb = Math.round(128 + 224 * (b - yl) / 1.8556).toInt()
        val cr = Math.round(128 + 224 * (r - yl) / 1.5748).toInt()
        val right = rgb(Colorimetry.BT709.toArgb(Math.round(16 + 219 * yl).toInt(), cb, cr))
        assertThat(right.second).isWithin(2).of(217)
        assertThat(right.first).isAtMost(2)
        val wrong = rgb(Colorimetry.BT601.toArgb(Math.round(16 + 219 * yl).toInt(), cb, cr))
        assertThat(wrong.second).isGreaterThan(240)
    }
}
