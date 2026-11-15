package com.itsluminous.clearmovie.data.preset

import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.NormRect
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** One decoded video frame as packed ARGB pixels, row by row. */
class FrameSample(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "frame size does not match its pixels" }
    }
}

/** CIELAB under D65, from sRGB. */
data class Lab(
    val l: Double,
    val a: Double,
    val b: Double,
) {
    /** CIE76 colour difference (Euclidean distance in Lab). */
    fun deltaE(other: Lab): Double {
        val dl = l - other.l
        val da = a - other.a
        val db = b - other.b
        return sqrt(dl * dl + da * da + db * db)
    }

    companion object {
        fun of(rgb: Int): Lab {
            val r = linear((rgb shr 16) and 0xFF)
            val g = linear((rgb shr 8) and 0xFF)
            val b = linear(rgb and 0xFF)
            val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
            val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
            val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
            val fx = f(x)
            val fy = f(y)
            val fz = f(z)
            return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
        }

        private fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        private fun f(t: Double): Double = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    }
}

/** What the key-colour check found in a frame file. */
data class KeyColorCheck(
    /** The green actually in the window. */
    val measured: ArgbColor,
    /** CIE76 distance from the preset's key colour. */
    val deltaE: Double,
) {
    /** True when the file's green differs enough that keying with the preset's colour would leave a fringe. */
    val needsAdjusting: Boolean get() = deltaE > MAX_DELTA_E

    companion object {
        /** ARCHITECTURE section 8: more than this, and the binding uses the measured green. */
        const val MAX_DELTA_E = 6.0

        /** How far in from each edge of the window to sample, so the border and the logo stay out. */
        const val INSET = 0.15

        /**
         * The key-colour check at bind (review F14). Samples the middle of the
         * window ([window], inset by [INSET] on each side) in each frame, takes
         * the per-channel median, and compares it with [keyColor]. A chat app
         * that re-encodes the frame shifts its green, and keying with the
         * preset's colour would then leave a green fringe. Returns null when
         * the samples are not a flat, clearly green area (the file is not a
         * green-screen frame), so nothing is adjusted.
         */
        fun measure(
            frames: List<FrameSample>,
            window: NormRect,
            keyColor: ArgbColor,
        ): KeyColorCheck? {
            if (frames.isEmpty()) return null
            val reds = ArrayList<Int>()
            val greens = ArrayList<Int>()
            val blues = ArrayList<Int>()
            for (frame in frames) {
                val x0 = ((window.x + window.w * INSET) * frame.width).toInt().coerceIn(0, frame.width - 1)
                val x1 = ((window.x + window.w * (1 - INSET)) * frame.width).toInt().coerceIn(x0 + 1, frame.width)
                val y0 = ((window.y + window.h * INSET) * frame.height).toInt().coerceIn(0, frame.height - 1)
                val y1 = ((window.y + window.h * (1 - INSET)) * frame.height).toInt().coerceIn(y0 + 1, frame.height)
                // At most about 4000 samples per frame, evenly spread.
                val stepX = maxOf(1, (x1 - x0) / 64)
                val stepY = maxOf(1, (y1 - y0) / 64)
                for (y in y0 until y1 step stepY) {
                    for (x in x0 until x1 step stepX) {
                        val p = frame.argb[y * frame.width + x]
                        reds += (p shr 16) and 0xFF
                        greens += (p shr 8) and 0xFF
                        blues += p and 0xFF
                    }
                }
            }
            val median = (median(reds) shl 16) or (median(greens) shl 8) or median(blues)
            val g = (median shr 8) and 0xFF
            val clearlyGreen = g >= 64 && g > ((median shr 16) and 0xFF) + 40 && g > (median and 0xFF) + 40
            if (!clearlyGreen) return null
            // Flat: most samples sit close to the median, as a keyed window does.
            val medianLab = Lab.of(median)
            val close =
                reds.indices.count { i ->
                    Lab.of((reds[i] shl 16) or (greens[i] shl 8) or blues[i]).deltaE(medianLab) <= FLAT_DELTA_E
                }
            if (close < reds.size * FLAT_SHARE) return null
            val measured = ArgbColor(0xFF000000L or median.toLong())
            return KeyColorCheck(measured, Lab.of(keyColor.argb.toInt()).deltaE(medianLab))
        }

        private const val FLAT_DELTA_E = 10.0
        private const val FLAT_SHARE = 0.8

        /** The median, without reordering [values] (their indices pair up across channels). */
        private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]
    }
}
