package com.itsluminous.clearmovie.data.preset

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.NormRect
import org.junit.Test
import javax.imageio.ImageIO

/**
 * The key-colour check at bind (ROADMAP cm-storage: "a re-encoded frame
 * fixture, green shifted by Delta E 9, gets its key colour adjusted"). The
 * frames are the PNGs `scripts/make-storage-fixtures.sh` extracts from the
 * synthetic frame videos; the instrumented test decodes the videos themselves.
 */
class KeyColorCheckTest {
    private val key = ArgbColor(0xFF00D922)
    private val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)

    @Test
    fun `the re-encoded frame is about Delta E 9 away and gets its own key colour`() {
        val check = KeyColorCheck.measure(frames("reencoded"), window, key)!!
        assertThat(check.deltaE).isWithin(1.0).of(9.0)
        assertThat(check.needsAdjusting).isTrue()
        val g = (check.measured.argb shr 8) and 0xFF
        assertThat(g).isLessThan(0xD9)
    }

    @Test
    fun `the original frame keeps the preset's colour`() {
        val check = KeyColorCheck.measure(frames("original"), window, key)!!
        assertThat(check.deltaE).isLessThan(KeyColorCheck.MAX_DELTA_E)
        assertThat(check.needsAdjusting).isFalse()
    }

    @Test
    fun `a file with no flat green window is not adjusted`() {
        val grey = FrameSample(100, 50, IntArray(5000) { 0xFF808080.toInt() })
        assertThat(KeyColorCheck.measure(listOf(grey), window, key)).isNull()
        val noise = FrameSample(100, 50, IntArray(5000) { i -> if (i % 2 == 0) 0xFF00FF00.toInt() else 0xFF00A000.toInt() })
        assertThat(KeyColorCheck.measure(listOf(noise), window, key)).isNull()
        assertThat(KeyColorCheck.measure(emptyList(), window, key)).isNull()
    }

    @Test
    fun `a logo over the window's corner does not move the measurement`() {
        val base = frames("reencoded").first()
        val clean = KeyColorCheck.measure(listOf(base), window, key)!!.measured
        val withLogo = base.argb.copyOf()
        // A white block over the bottom-left of the window, as the channel logo sits in the real frame.
        for (y in (base.height * 0.6).toInt() until (base.height * 0.79).toInt()) {
            for (x in (base.width * 0.02).toInt() until (base.width * 0.3).toInt()) withLogo[y * base.width + x] = -1
        }
        assertThat(KeyColorCheck.measure(listOf(FrameSample(base.width, base.height, withLogo)), window, key)!!.measured).isEqualTo(clean)
    }

    @Test
    fun `Lab and Delta E match published values`() {
        val white = Lab.of(0xFFFFFF)
        assertThat(white.l).isWithin(0.01).of(100.0)
        assertThat(white.a).isWithin(0.01).of(0.0)
        val red = Lab.of(0xFF0000)
        assertThat(red.l).isWithin(0.05).of(53.24)
        assertThat(red.a).isWithin(0.05).of(80.09)
        assertThat(red.b).isWithin(0.05).of(67.20)
        assertThat(Lab.of(0x000000).deltaE(white)).isWithin(0.01).of(100.0)
    }

    private fun frames(name: String): List<FrameSample> =
        listOf(10, 50, 90).map { pct ->
            val image = javaClass.classLoader!!.getResourceAsStream("fixtures/media/frame-$name-$pct.png").use { ImageIO.read(it) }
            FrameSample(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }
}
