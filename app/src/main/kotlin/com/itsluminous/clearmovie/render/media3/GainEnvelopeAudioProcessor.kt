package com.itsluminous.clearmovie.render.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.render.plan.GainEnvelope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Applies a [GainEnvelope] (volume, ducking, fades, crossfade) to one item's
 * sound (ARCHITECTURE section 7). Time is the item's own timeline time: it
 * starts at the position Media3 passes on flush (0 at the item's start, or
 * the seek position inside it) and advances with every frame processed.
 * The gain is evaluated every [BLOCK_FRAMES] frames and interpolated
 * linearly in between, so ramps have no zipper noise.
 *
 * 16-bit and float PCM are processed in place of a copy; other encodings
 * are refused at configure time, so Media3 converts them first.
 */
@UnstableApi
open class GainEnvelopeAudioProcessor(
    private val envelope: GainEnvelope,
) : BaseAudioProcessor() {
    private var positionUs = 0L
    private var framesSinceFlush = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (envelope.isUnity) AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frameBytes = format.bytesPerFrame
        val frames = remaining / frameBytes
        val out = replaceOutputBuffer(frames * frameBytes).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val channels = format.channelCount
        val rate = format.sampleRate.toLong()
        var frame = 0
        while (frame < frames) {
            val blockEnd = minOf(frames, frame + BLOCK_FRAMES)
            val g0 = envelope.gainAt(timeOf(framesSinceFlush + frame, rate))
            val g1 = envelope.gainAt(timeOf(framesSinceFlush + blockEnd, rate))
            val span = (blockEnd - frame).toFloat()
            for (f in frame until blockEnd) {
                val g = g0 + (g1 - g0) * ((f - frame) / span)
                repeat(channels) {
                    if (format.encoding == C.ENCODING_PCM_16BIT) {
                        out.putShort(scale16(input.getShort(), g))
                    } else {
                        out.putFloat(input.getFloat() * g)
                    }
                }
            }
            frame = blockEnd
        }
        // Any trailing partial frame is dropped, as other processors do.
        inputBuffer.position(inputBuffer.limit())
        framesSinceFlush += frames
        out.flip()
    }

    private fun timeOf(
        frames: Long,
        rate: Long,
    ): Long = positionUs + frames * 1_000_000L / rate

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        positionUs = streamMetadata.positionOffsetUs.coerceAtLeast(0)
        framesSinceFlush = 0
    }

    override fun onReset() {
        positionUs = 0
        framesSinceFlush = 0
    }

    companion object {
        const val BLOCK_FRAMES = 64

        internal fun scale16(
            sample: Short,
            gain: Float,
        ): Short = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
