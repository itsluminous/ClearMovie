package com.itsluminous.clearmovie.data.preset

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** BS.1770 integrated loudness on generated tones. */
class LoudnessMeterTest {
    private fun sine(
        rate: Int,
        channels: Int,
        seconds: Double,
        peak: Double,
        hz: Double = 997.0,
    ): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i -> (peak * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }
    }

    private fun measure(
        samples: FloatArray,
        rate: Int,
        channels: Int,
        chunk: Int = 4096,
    ): Double? {
        val meter = LoudnessMeter(rate, channels)
        var i = 0
        while (i < samples.size) {
            val n = minOf(chunk, samples.size - i)
            meter.add(samples.copyOfRange(i, i + n))
            i += n
        }
        return meter.integratedLufs()
    }

    @Test
    fun `a 997 Hz sine at -20 dBFS in both channels measures -20 LUFS`() {
        assertThat(measure(sine(48_000, 2, 3.0, 0.1), 48_000, 2)!!).isWithin(0.1).of(-20.0)
        assertThat(measure(sine(44_100, 2, 3.0, 0.1), 44_100, 2)!!).isWithin(0.1).of(-20.0)
    }

    @Test
    fun `a full-scale sine in one channel measures -3 LUFS`() {
        assertThat(measure(sine(48_000, 1, 2.0, 1.0), 48_000, 1)!!).isWithin(0.1).of(-3.01)
    }

    @Test
    fun `silence and very short audio have no loudness`() {
        assertThat(measure(FloatArray(48_000 * 2), 48_000, 1)).isNull()
        assertThat(measure(sine(48_000, 1, 0.3, 0.5), 48_000, 1)).isNull()
    }

    @Test
    fun `quiet passages below the relative gate do not pull the result down`() {
        val loud = sine(48_000, 2, 2.0, 0.1)
        val quiet = sine(48_000, 2, 2.0, 0.001)
        val mixed = loud + quiet
        // The quiet half is gated out; the three 400 ms blocks across the change
        // (75, 50 and 25 % loud) stay in, which lowers the mean by about 0.34 dB.
        assertThat(measure(mixed, 48_000, 2)!!).isWithin(0.1).of(-20.34)
    }

    @Test
    fun `the K-weighting filters at 48 kHz are the ones printed in BS 1770`() {
        val shelf = LoudnessMeter.Biquad.highShelf(48_000)
        assertThat(shelf.b0).isWithin(1e-9).of(1.53512485958697)
        assertThat(shelf.b1).isWithin(1e-9).of(-2.69169618940638)
        assertThat(shelf.b2).isWithin(1e-9).of(1.19839281085285)
        assertThat(shelf.a1).isWithin(1e-9).of(-1.69065929318241)
        assertThat(shelf.a2).isWithin(1e-9).of(0.73248077421585)
        val highPass = LoudnessMeter.Biquad.highPass(48_000)
        assertThat(highPass.a1).isWithin(1e-9).of(-1.99004745483398)
        assertThat(highPass.a2).isWithin(1e-9).of(0.99007225036621)
    }

    @Test
    fun `chunk boundaries do not change the result`() {
        val tone = sine(48_000, 2, 1.5, 0.2)
        assertThat(measure(tone, 48_000, 2, chunk = 7)).isEqualTo(measure(tone, 48_000, 2, chunk = 100_000))
    }
}
