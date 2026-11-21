package com.itsluminous.clearmovie.domain.model

import kotlin.math.sqrt

/**
 * The chroma key's arithmetic (cm-frame-area), shared by the export's GL
 * keyer (`ChromaKeyEffect`, which runs the same formula in its shader) and
 * the frame look screen, which keys a still on the CPU to show what the
 * picked colour removes. Pure Kotlin, so it is unit-tested on the JVM.
 *
 * A pixel's distance to the key is measured in the BT.709 Cb/Cr plane, to
 * the nearer of the key and the key as a BT.601 decoder shows it (see
 * `ChromaKeyEffect`), and its alpha rises from 0 at [KeySpec.tolerance] to 1
 * at tolerance + [KeySpec.softness]. Brightness is not compared, so a key
 * must have colour: grey, white and black cannot be keyed ([canKey]).
 */
object KeyMath {
    /** The softness used whenever the user picks a colour: the preset frame's default. */
    const val SOFTNESS = FrameSpec.DEFAULT_SOFTNESS

    /** The tolerance the picker offers, from little removed to a lot. */
    const val MIN_TOLERANCE = 0.06
    const val MAX_TOLERANCE = 0.36

    /** A key colour's chroma must be at least this far from grey, or every grey in the frame would be keyed with it. */
    const val MIN_KEY_CHROMA = 0.12

    /** The smallest picture area, as a share of each side of the frame. */
    const val MIN_AREA_SIDE = 0.1

    /** BT.709 (Cb, Cr) of [rgb] (0xRRGGBB, alpha ignored), each in -0.5..0.5. */
    fun chroma(rgb: Int): Pair<Double, Double> {
        val r = ((rgb shr 16) and 0xFF) / 255.0
        val g = ((rgb shr 8) and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return (b - y) / 1.8556 to (r - y) / 1.5748
    }

    /**
     * [color] as a decoder shows it when it encodes with BT.709 (the file's
     * matrix) and decodes with BT.601 (what a sampler ignoring the tag uses).
     */
    fun misdecodedAsBt601(color: ArgbColor): ArgbColor {
        val r = ((color.argb shr 16) and 0xFF) / 255.0
        val g = ((color.argb shr 8) and 0xFF) / 255.0
        val b = (color.argb and 0xFF) / 255.0
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val cb = (b - y) / 1.8556
        val cr = (r - y) / 1.5748

        fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toLong()
        val r2 = byte(y + 1.402 * cr)
        val g2 = byte(y - 0.344136 * cb - 0.714136 * cr)
        val b2 = byte(y + 1.772 * cb)
        return ArgbColor(0xFF000000L or (r2 shl 16) or (g2 shl 8) or b2)
    }

    /** How far [color] is from grey in the Cb/Cr plane. */
    fun chromaStrength(color: ArgbColor): Double {
        val (cb, cr) = chroma(color.argb.toInt())
        return sqrt(cb * cb + cr * cr)
    }

    /** Whether [color] has enough colour to be keyed out without keying out every grey with it. */
    fun canKey(color: ArgbColor): Boolean = chromaStrength(color) >= MIN_KEY_CHROMA

    /** Whether the GL keyer pulls green spill down in the soft band: only for a green key, whose spill is green. */
    fun suppressesGreenSpill(color: ArgbColor): Boolean {
        val r = (color.argb shr 16) and 0xFF
        val g = (color.argb shr 8) and 0xFF
        val b = color.argb and 0xFF
        return g > r && g > b
    }

    /** A keyer for one [key]: the alpha it gives each pixel, as the export's shader does. */
    class Keyer(
        key: KeySpec,
    ) {
        private val k1cb: Double
        private val k1cr: Double
        private val k2cb: Double
        private val k2cr: Double
        private val tolerance = key.tolerance
        private val softness = key.softness.coerceAtLeast(1e-4)

        init {
            val (a, b) = chroma(key.color.argb.toInt())
            val (c, d) = chroma(misdecodedAsBt601(key.color).argb.toInt())
            k1cb = a
            k1cr = b
            k2cb = c
            k2cr = d
        }

        /** 0 (keyed out) to 1 (kept) for [rgb] (0xRRGGBB); no allocation, as it runs per pixel. */
        fun alpha(rgb: Int): Double {
            val r = ((rgb shr 16) and 0xFF) / 255.0
            val g = ((rgb shr 8) and 0xFF) / 255.0
            val b = (rgb and 0xFF) / 255.0
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val cb = (b - y) / 1.8556
            val cr = (r - y) / 1.5748
            val d1 = (cb - k1cb) * (cb - k1cb) + (cr - k1cr) * (cr - k1cr)
            val d2 = (cb - k2cb) * (cb - k2cb) + (cr - k2cr) * (cr - k2cr)
            val t = ((sqrt(minOf(d1, d2)) - tolerance) / softness).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }

    /**
     * The see-through part of a frame picture ([argb], [width] x [height],
     * row by row) keyed with [key], as the box of the rows and columns that
     * are at least [LINE_SHARE] keyed out, in units of the picture (0..1).
     * Null when less than [MIN_AREA_SIDE] of each side is keyed out: the
     * picked colour cuts no window, and the picture area starts as the
     * whole frame instead.
     */
    fun keyedArea(
        argb: IntArray,
        width: Int,
        height: Int,
        key: KeySpec,
    ): NormRect? {
        require(width > 0 && height > 0 && argb.size == width * height) { "picture size does not match its pixels" }
        val keyer = Keyer(key)
        val clear = BooleanArray(argb.size) { keyer.alpha(argb[it]) < 0.5 }
        val rows = (0 until height).filter { y -> (0 until width).count { clear[y * width + it] } >= width * LINE_SHARE }
        val cols = (0 until width).filter { x -> (0 until height).count { clear[it * width + x] } >= height * LINE_SHARE }
        if (rows.isEmpty() || cols.isEmpty()) return null

        fun r(v: Double) = Math.round(v * 10000) / 10000.0
        val rect =
            NormRect(
                r(cols.first().toDouble() / width),
                r(rows.first().toDouble() / height),
                r((cols.last() + 1 - cols.first()).toDouble() / width),
                r((rows.last() + 1 - rows.first()).toDouble() / height),
            )
        return rect.takeIf { isUsableArea(it) }
    }

    /** A picture area the app keeps: inside the frame and at least [MIN_AREA_SIDE] across each way. */
    fun isUsableArea(area: NormRect): Boolean = area.isValid && area.w >= MIN_AREA_SIDE - 1e-9 && area.h >= MIN_AREA_SIDE - 1e-9

    /** A row or column is part of the see-through part when at least this share of it is keyed out. */
    const val LINE_SHARE = 0.2
}
