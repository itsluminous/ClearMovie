package com.itsluminous.clearmovie.record

import com.itsluminous.clearmovie.data.record.MicSource
import kotlin.math.PI
import kotlin.math.sin

/**
 * A microphone for tests (the emulator has nothing to speak into): it
 * delivers frames in real time, as a microphone does, each one
 * [latencyNs] after the moment it "reached the microphone", and reports
 * honest capture timestamps for them. It plays a quiet hum, and a loud
 * 20 ms click (2 kHz) for each moment in [clicksAtNs]: the frame captured
 * at that moment starts the click. A test can add clicks while it records,
 * for moments still ahead of what was delivered.
 */
class FakeMic(
    override val sampleRate: Int = 44_100,
    private val latencyNs: Long = 20_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : MicSource {
    val clicksAtNs: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile
    var started = false
        private set

    @Volatile
    var released = false
        private set

    private var startNs = 0L
    private var frames = 0L

    @Volatile
    override var silenced: Boolean = false

    /** When frame [f] reached the microphone. */
    fun captureNs(f: Long): Long = startNs + f * 1_000_000_000L / sampleRate

    override fun start(): Boolean {
        startNs = nanoTime()
        started = true
        return true
    }

    override fun read(buffer: ShortArray): Int {
        if (released) return -1
        val n = buffer.size
        // The chunk ending at frame frames + n is delivered once it has been captured, plus the latency.
        val due = captureNs(frames + n) + latencyNs
        val wait = due - nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        val clickFrames = synchronized(clicksAtNs) { clicksAtNs.map { ((it - startNs) * sampleRate / 1_000_000_000L) } }
        for (i in 0 until n) {
            val f = frames + i
            val inClick = clickFrames.any { f >= it && f < it + sampleRate / 50 }
            val v =
                if (inClick) {
                    0.8 * sin(2 * PI * 2000 * f / sampleRate)
                } else {
                    0.02 * sin(2 * PI * 220 * f / sampleRate)
                }
            buffer[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        frames += n
        return n
    }

    override fun timestamp(): Pair<Long, Long>? = if (frames == 0L) null else frames to captureNs(frames)

    override fun release() {
        released = true
    }
}
