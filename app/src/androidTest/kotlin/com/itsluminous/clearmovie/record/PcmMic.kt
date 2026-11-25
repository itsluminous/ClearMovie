package com.itsluminous.clearmovie.record

import com.itsluminous.clearmovie.data.record.MicSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A microphone that plays a person's speech (cm-preset-voice-default): [pcm]
 * is raw mono 16-bit little-endian samples at [sampleRate], delivered at the
 * pace a real microphone captures them after [leadInMs] of silence (a person
 * starts speaking once the video plays), then silence. The emulator has no
 * microphone to speak into, so this is how a test records real words
 * through the editor's own Record voice.
 */
class PcmMic(
    pcm: File,
    override val sampleRate: Int = 44_100,
    private val leadInMs: Long = 0,
    private val nanoTime: () -> Long = System::nanoTime,
) : MicSource {
    private val samples: ShortArray =
        ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b ->
            ShortArray(b.remaining()).also { b.get(it) }
        }

    @Volatile
    private var released = false
    private var startNs = 0L
    private var frames = 0L

    @Volatile
    override var silenced: Boolean = false

    private fun captureNs(f: Long): Long = startNs + f * 1_000_000_000L / sampleRate

    override fun start(): Boolean {
        startNs = nanoTime()
        return true
    }

    override fun read(buffer: ShortArray): Int {
        if (released) return -1
        val n = buffer.size
        // A chunk is handed over once all of it has been "captured", as a microphone does.
        val wait = captureNs(frames + n) - nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        val lead = leadInMs * sampleRate / 1000
        for (i in 0 until n) {
            val f = frames + i - lead
            buffer[i] = if (f >= 0 && f < samples.size) samples[f.toInt()] else 0
        }
        frames += n
        return n
    }

    override fun timestamp(): Pair<Long, Long>? = if (frames == 0L) null else frames to captureNs(frames)

    override fun release() {
        released = true
    }
}
