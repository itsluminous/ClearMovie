package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * The shape of the finished video (cm-aspect-ratio). A preset has one (its
 * frame and intro are made for it); a video made without a preset gets the
 * one the user chose when the editor first opened it, and the editor can
 * change it later.
 *
 * Sizes are given by the short side, so each export quality keeps the same
 * detail across the narrow way of the picture: Best is 1920 x 1080 wide,
 * 1080 x 1920 upright and 1080 x 1080 square.
 */
@Serializable
enum class CanvasShape(
    /** Width over height, as a fraction. */
    val widthUnits: Int,
    val heightUnits: Int,
) {
    @SerialName("landscape")
    LANDSCAPE(16, 9),

    @SerialName("portrait")
    PORTRAIT(9, 16),

    @SerialName("square")
    SQUARE(1, 1),
    ;

    /** Width over height. */
    val aspect: Double get() = widthUnits.toDouble() / heightUnits

    /** The (width, height) in pixels for a short side of [shortSide], both even (encoders need it). */
    fun size(shortSide: Int): Pair<Int, Int> {
        val short = even(shortSide)
        val long = even((short * LONG_UNITS + SHORT_UNITS / 2) / SHORT_UNITS)
        return when (this) {
            LANDSCAPE -> long to short
            PORTRAIT -> short to long
            SQUARE -> short to short
        }
    }

    /** The canvas's long side over its short side (1 for a square). */
    val longOverShort: Double get() = maxOf(widthUnits, heightUnits).toDouble() / minOf(widthUnits, heightUnits)

    companion object {
        private const val LONG_UNITS = 16
        private const val SHORT_UNITS = 9

        /** The short side the caption sizes and the editor's preview images are measured on. */
        const val REFERENCE_SHORT_SIDE = 1080

        /** Pictures whose sides differ by less than this share count as square. */
        private const val SQUARE_TOLERANCE = 0.1

        /** [n] rounded to an even number, never below 2. */
        private fun even(n: Int): Int = ((n + 1) / 2 * 2).coerceAtLeast(2)

        /**
         * The shape that suits a picture [width] x [height] as it is shown (rotation applied): wide
         * pictures suggest landscape, tall ones portrait, near-square ones square. Null for no size.
         */
        fun suiting(
            width: Int,
            height: Int,
        ): CanvasShape? {
            if (width <= 0 || height <= 0) return null
            val ratio = width.toDouble() / height
            return when {
                abs(ratio - 1.0) < SQUARE_TOLERANCE -> SQUARE
                ratio > 1.0 -> LANDSCAPE
                else -> PORTRAIT
            }
        }
    }
}

/**
 * Where a picture of [inner] shape sits, centred and as large as it fits, on
 * a canvas of [outer] shape, in normalised canvas units: the whole canvas when
 * the shapes are the same. A preset frame made for another shape is drawn
 * there, so it is never stretched.
 */
fun containedRect(
    inner: CanvasShape,
    outer: CanvasShape,
): NormRect {
    if (inner == outer) return NormRect.FULL
    val ratio = inner.aspect / outer.aspect
    return if (ratio >= 1.0) {
        val h = 1.0 / ratio
        NormRect(0.0, (1.0 - h) / 2, 1.0, h)
    } else {
        NormRect((1.0 - ratio) / 2, 0.0, ratio, 1.0)
    }
}

/** [rect], given in the units of [box], in canvas units: a frame's window inside where the frame is drawn. */
fun NormRect.within(box: NormRect): NormRect = NormRect(box.x + x * box.w, box.y + y * box.h, w * box.w, h * box.h)
