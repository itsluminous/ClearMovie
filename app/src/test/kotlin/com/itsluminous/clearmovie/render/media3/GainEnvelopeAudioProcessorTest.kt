package com.itsluminous.clearmovie.render.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.render.plan.GainEnvelope
import com.itsluminous.clearmovie.render.plan.GainPoint
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GainEnvelopeAudioProcessorTest {
    private val rate = 48_000
    private val stereo16 = AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    private fun pcm16(
        frames: Int,
        value: Short = 10_000,
    ): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putShort(value) }
            flip()
        }

    private fun run(
        p: GainEnvelopeAudioProcessor,
        input: ByteBuffer,
    ): ShortArray {
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    @Test
    fun `a constant gain scales every sample`() {
        val p = GainEnvelopeAudioProcessor(GainEnvelope.constant(-6.0206))
        p.configure(stereo16)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val out = run(p, pcm16(480))
        assertThat(out.size).isEqualTo(960)
        assertThat(out.all { it in 4999..5001 }).isTrue()
    }

    @Test
    fun `the curve follows the item's time from the flush position`() {
        // 0 dB until 10 ms, silent from 20 ms.
        val env = GainEnvelope(listOf(GainPoint(10_000, 0.0), GainPoint(20_000, GainEnvelope.SILENT_DB)))
        val p = GainEnvelopeAudioProcessor(env)
        p.configure(stereo16)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val first = run(p, pcm16(480)) // 0-10 ms
        val second = run(p, pcm16(480)) // 10-20 ms
        val third = run(p, pcm16(480)) // 20-30 ms
        assertThat(first.first()).isEqualTo(10_000.toShort())
        assertThat(second[0].toInt()).isAtLeast(9_000)
        assertThat(second.last().toInt()).isLessThan(100)
        assertThat(third.all { it.toInt() == 0 }).isTrue()
        // After a seek to 25 ms the curve picks up there: silent.
        p.flush(AudioProcessor.StreamMetadata(25_000))
        assertThat(run(p, pcm16(48)).all { it.toInt() == 0 }).isTrue()
    }

    @Test
    fun `float pcm is scaled too, and loud samples clip instead of wrapping`() {
        val p = GainEnvelopeAudioProcessor(GainEnvelope.constant(12.0))
        p.configure(stereo16)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertThat(run(p, pcm16(4, 30_000)).all { it == Short.MAX_VALUE }).isTrue()
        assertThat(GainEnvelopeAudioProcessor.scale16(Short.MIN_VALUE, 2f)).isEqualTo(Short.MIN_VALUE)
        val f = GainEnvelopeAudioProcessor(GainEnvelope.constant(-20.0))
        f.configure(AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))
        f.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val buf =
            ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
                repeat(4) { putFloat(0.5f) }
                flip()
            }
        f.queueInput(buf)
        val out = f.output.order(ByteOrder.nativeOrder())
        assertThat(out.getFloat()).isWithin(1e-6f).of(0.05f)
    }

    @Test
    fun `a unity curve is inactive and other encodings are refused`() {
        val p = GainEnvelopeAudioProcessor(GainEnvelope.UNITY)
        p.configure(stereo16)
        assertThat(p.isActive).isFalse()
        val q = GainEnvelopeAudioProcessor(GainEnvelope.constant(-3.0))
        val threw =
            try {
                q.configure(AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))
                false
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                true
            }
        assertThat(threw).isTrue()
    }
}
