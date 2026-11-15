package com.itsluminous.clearmovie.data.preset

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness in LUFS (ITU-R BS.1770-4, EBU R128): K-weighting,
 * 400 ms blocks every 100 ms, an absolute gate at -70 LUFS and a relative
 * gate 10 LU below the gated mean. Feed interleaved samples in -1..1 with
 * [add]; read [integratedLufs]. Every channel is weighted 1.0 (the preset
 * files are mono or stereo). Pure Kotlin, so it is tested on the JVM.
 */
class LoudnessMeter(
    private val sampleRate: Int,
    private val channels: Int,
) {
    init {
        require(sampleRate in 8000..192_000 && channels in 1..8) { "unsupported audio format" }
    }

    private val shelf = Array(channels) { Biquad.highShelf(sampleRate) }
    private val highPass = Array(channels) { Biquad.highPass(sampleRate) }
    private val subBlockFrames = sampleRate / 10
    private var framesInSubBlock = 0
    private var subBlockSum = 0.0
    private val subBlocks = ArrayList<Double>()
    private var channel = 0

    /** Samples seen, per channel. */
    var frames: Long = 0
        private set

    /** Adds [count] interleaved samples from [samples]. */
    fun add(
        samples: FloatArray,
        count: Int = samples.size,
    ) {
        for (i in 0 until count) {
            val x = samples[i].toDouble()
            val y = highPass[channel].process(shelf[channel].process(x))
            subBlockSum += y * y
            channel++
            if (channel == channels) {
                channel = 0
                frames++
                framesInSubBlock++
                if (framesInSubBlock == subBlockFrames) {
                    subBlocks += subBlockSum / subBlockFrames
                    subBlockSum = 0.0
                    framesInSubBlock = 0
                }
            }
        }
    }

    /** Integrated loudness, or null for silence or less than 400 ms of audio. */
    fun integratedLufs(): Double? {
        if (subBlocks.size < 4) return null
        val blocks = (0..subBlocks.size - 4).map { i -> (subBlocks[i] + subBlocks[i + 1] + subBlocks[i + 2] + subBlocks[i + 3]) / 4 }
        val aboveAbsolute = blocks.filter { loudness(it) > ABSOLUTE_GATE }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = loudness(aboveAbsolute.average()) - RELATIVE_GATE_LU
        val gated = aboveAbsolute.filter { loudness(it) > relativeGate }
        if (gated.isEmpty()) return null
        return loudness(gated.average())
    }

    private fun loudness(meanSquare: Double): Double = if (meanSquare <= 0) Double.NEGATIVE_INFINITY else -0.691 + 10 * log10(meanSquare)

    /** A direct form I biquad, normalised so a0 = 1. */
    internal class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            /**
             * The K-weighting pre-filter (the head's acoustic effect) for [rate],
             * by the bilinear design libebur128 uses; at 48 kHz it gives the
             * coefficients printed in BS.1770 itself.
             */
            fun highShelf(rate: Int): Biquad {
                val k = tan(PI * SHELF_HZ / rate)
                val vh = 10.0.pow(SHELF_GAIN_DB / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / SHELF_Q + k * k
                return Biquad(
                    b0 = (vh + vb * k / SHELF_Q + k * k) / a0,
                    b1 = 2 * (k * k - vh) / a0,
                    b2 = (vh - vb * k / SHELF_Q + k * k) / a0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / SHELF_Q + k * k) / a0,
                )
            }

            /** The RLB high-pass of K-weighting for [rate] (same design). */
            fun highPass(rate: Int): Biquad {
                val k = tan(PI * HIGH_PASS_HZ / rate)
                val a0 = 1 + k / HIGH_PASS_Q + k * k
                return Biquad(
                    b0 = 1.0,
                    b1 = -2.0,
                    b2 = 1.0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / HIGH_PASS_Q + k * k) / a0,
                )
            }

            private const val SHELF_HZ = 1681.974450955533
            private const val SHELF_GAIN_DB = 3.999843853973347
            private const val SHELF_Q = 0.7071752369554196
            private const val HIGH_PASS_HZ = 38.13547087602444
            private const val HIGH_PASS_Q = 0.5003270373238773
        }
    }

    companion object {
        const val ABSOLUTE_GATE = -70.0
        const val RELATIVE_GATE_LU = 10.0
    }
}
