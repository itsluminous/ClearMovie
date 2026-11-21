package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** cm-frame-area: the keyer the export runs, with a colour the user picked. */
class KeyMathTest {
    private val magenta = KeySpec(ArgbColor(0xFFFF00FF), FrameSpec.DEFAULT_TOLERANCE, FrameSpec.DEFAULT_SOFTNESS)
    private val green = KeySpec(ArgbColor(0xFF00D922), FrameSpec.DEFAULT_TOLERANCE, FrameSpec.DEFAULT_SOFTNESS)

    @Test
    fun `a picked magenta key removes magenta and keeps green, white and skin`() {
        val k = KeyMath.Keyer(magenta)
        assertThat(k.alpha(0xFF00FF)).isEqualTo(0.0)
        assertThat(k.alpha(0xF010F0)).isLessThan(0.05)
        assertThat(k.alpha(0x00D922)).isEqualTo(1.0)
        assertThat(k.alpha(0xFFFFFF)).isEqualTo(1.0)
        assertThat(k.alpha(0xE0AC90)).isEqualTo(1.0)
    }

    @Test
    fun `the default green key still removes the frame's green and keeps magenta`() {
        val k = KeyMath.Keyer(green)
        assertThat(k.alpha(0x00D922)).isEqualTo(0.0)
        assertThat(k.alpha(0xFF00FF)).isEqualTo(1.0)
    }

    @Test
    fun `more tolerance removes more`() {
        val near = 0xC040C0
        val tight = KeyMath.Keyer(magenta.copy(tolerance = KeyMath.MIN_TOLERANCE)).alpha(near)
        val loose = KeyMath.Keyer(magenta.copy(tolerance = KeyMath.MAX_TOLERANCE)).alpha(near)
        assertThat(loose).isLessThan(tight)
    }

    @Test
    fun `greys cannot be picked, colours can`() {
        assertThat(KeyMath.canKey(ArgbColor(0xFF808080))).isFalse()
        assertThat(KeyMath.canKey(ArgbColor(0xFFFFFFFF))).isFalse()
        assertThat(KeyMath.canKey(ArgbColor(0xFF000000))).isFalse()
        assertThat(KeyMath.canKey(ArgbColor(0xFFFF00FF))).isTrue()
        assertThat(KeyMath.canKey(ArgbColor(0xFF00D922))).isTrue()
        assertThat(KeyMath.canKey(ArgbColor(0xFF1565C0))).isTrue()
    }

    @Test
    fun `only a green key has its green spill pulled down`() {
        assertThat(KeyMath.suppressesGreenSpill(green.color)).isTrue()
        assertThat(KeyMath.suppressesGreenSpill(magenta.color)).isFalse()
        assertThat(KeyMath.suppressesGreenSpill(ArgbColor(0xFF1565C0))).isFalse()
    }

    @Test
    fun `the keyed part's bounds start the picture area`() {
        val w = 100
        val h = 60
        // A white frame with a magenta window from x 10 to 70 and y 5 to 45.
        val px = IntArray(w * h) { i -> if (i % w in 10 until 70 && i / w in 5 until 45) 0xFFFF00FF.toInt() else -1 }
        val area = KeyMath.keyedArea(px, w, h, magenta)!!
        assertThat(area.x).isWithin(1e-4).of(0.1)
        assertThat(area.y).isWithin(1e-4).of(5.0 / 60)
        assertThat(area.w).isWithin(1e-4).of(0.6)
        assertThat(area.h).isWithin(1e-4).of(40.0 / 60)
        // Keyed with green, the same picture has no window.
        assertThat(KeyMath.keyedArea(px, w, h, green)).isNull()
    }

    @Test
    fun `a sliver is not a picture area`() {
        assertThat(KeyMath.isUsableArea(NormRect(0.1, 0.1, 0.5, 0.5))).isTrue()
        assertThat(KeyMath.isUsableArea(NormRect(0.1, 0.1, 0.05, 0.5))).isFalse()
        assertThat(KeyMath.isUsableArea(NormRect(0.6, 0.1, 0.5, 0.5))).isFalse()
    }

    @Test
    fun `the CPU chroma matches the BT709 formula the shader uses`() {
        val (cb, cr) = KeyMath.chroma(0x00D922)
        val y = 0.2126 * 0 + 0.7152 * (0xD9 / 255.0) + 0.0722 * (0x22 / 255.0)
        assertThat(cb).isWithin(1e-9).of((0x22 / 255.0 - y) / 1.8556)
        assertThat(cr).isWithin(1e-9).of((0 - y) / 1.5748)
    }

    @Test
    fun `a frame look is stored on the frame, checked, and undoable data`() {
        val area = NormRect(0.1, 0.1, 0.6, 0.6)
        val p = Fixtures.blankWith(Fixtures.photo("p")).addTimedClip(Fixtures.overlay("o", 0, 3000))
        val set = p.setFrameLook(ClipId("o"), magenta, area)
        val o = set.clip(ClipId("o")) as OverlayClip
        assertThat(o.key).isEqualTo(magenta)
        assertThat(o.area).isEqualTo(area)
        assertThat(set.validate()).isEmpty()
        val back = ProjectCodec().decode(ProjectCodec().encode(set)) as ProjectDecodeResult.Ok
        assertThat((back.project.clip(ClipId("o")) as OverlayClip).area).isEqualTo(area)
        assertThat(runCatching { p.setFrameLook(ClipId("o"), magenta, NormRect(0.0, 0.0, 0.01, 0.5)) }.isFailure).isTrue()
        assertThat(runCatching { p.setFrameLook(ClipId("p"), magenta, area) }.isFailure).isTrue()
    }
}
