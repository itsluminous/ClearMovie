package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GainEnvelopeTest {
    private val ramp = GainEnvelope(listOf(GainPoint(1000, 0.0), GainPoint(3000, -20.0)))

    @Test
    fun `values are flat outside the points and linear between them`() {
        assertThat(ramp.dbAt(0)).isEqualTo(0.0)
        assertThat(ramp.dbAt(2000)).isWithin(1e-9).of(-10.0)
        assertThat(ramp.dbAt(9000)).isEqualTo(-20.0)
        assertThat(ramp.gainAt(3000)).isWithin(1e-6f).of(0.1f)
    }

    @Test
    fun `sums add in dB over the union of points and floor at silence`() {
        val sum = ramp + GainEnvelope.constant(-6.0)
        assertThat(sum.dbAt(2000)).isWithin(1e-9).of(-16.0)
        val silent = GainEnvelope.constant(-90.0) + GainEnvelope.constant(-90.0)
        assertThat(silent.dbAt(0)).isEqualTo(GainEnvelope.SILENT_DB)
        assertThat(silent.gainAt(0)).isEqualTo(0f)
    }

    @Test
    fun `slices shift to zero and keep the shape`() {
        val s = ramp.slice(2000, 5000)
        assertThat(s.dbAt(0)).isWithin(1e-9).of(-10.0)
        assertThat(s.dbAt(1000)).isWithin(1e-9).of(-20.0)
        assertThat(s.dbAt(3000)).isWithin(1e-9).of(-20.0)
    }

    @Test
    fun `simplify drops collinear points only`() {
        val e = GainEnvelope(listOf(GainPoint(0, 0.0), GainPoint(1, -1.0), GainPoint(2, -2.0), GainPoint(3, 0.0)))
        assertThat(e.simplified().points.map { it.timeUs }).containsExactly(0L, 2L, 3L).inOrder()
        assertThat(GainEnvelope.UNITY.isUnity).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `points out of order are refused`() {
        GainEnvelope(listOf(GainPoint(5, 0.0), GainPoint(1, 0.0)))
    }

    @Test
    fun `alpha ramps are linear in clip time`() {
        val r = AlphaRamp(100, 300, 0f, 1f)
        assertThat(r.alphaAt(0)).isEqualTo(0f)
        assertThat(r.alphaAt(200)).isWithin(1e-6f).of(0.5f)
        assertThat(r.alphaAt(400)).isEqualTo(1f)
    }
}
