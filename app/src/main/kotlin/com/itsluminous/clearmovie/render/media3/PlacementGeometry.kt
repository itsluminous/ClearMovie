package com.itsluminous.clearmovie.render.media3

import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.render.plan.Placement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shader inputs that put one picture on the canvas (pure maths, no GL,
 * so it is unit-tested on the JVM). Every rectangle is normalised canvas or
 * picture space, origin top-left, y down.
 *
 * - [content]: where the picture's pixels land.
 * - [source]: which part of the (rotated) picture fills [content].
 * - [window]: the area painted around [content] (bars or blur); equal to
 *   [content] when nothing is painted.
 * - [background]: the part of the picture blurred behind it ([Mode.BLUR]).
 * - [borderX], [borderY]: a white frame drawn just outside [content] (PiP).
 */
data class PlacementGeometry(
    val content: NormRect,
    val source: NormRect,
    val window: NormRect,
    val background: NormRect,
    val mode: Mode,
    val quarterTurns: Int,
    val borderX: Double = 0.0,
    val borderY: Double = 0.0,
) {
    enum class Mode(
        val code: Int,
    ) {
        PLAIN(0),
        BARS(1),
        BLUR(2),
    }

    companion object {
        private val FULL = NormRect.FULL

        /** PiP border: 4 px at 1080p. */
        private const val BORDER_PX_AT_1080 = 4.0

        fun of(
            placement: Placement,
            inputWidth: Int,
            inputHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ): PlacementGeometry {
            require(inputWidth > 0 && inputHeight > 0 && canvasWidth > 0 && canvasHeight > 0) { "Sizes must be positive" }
            return when (placement) {
                Placement.FullFrame -> {
                    PlacementGeometry(FULL, FULL, FULL, FULL, Mode.PLAIN, 0)
                }

                is Placement.Box -> {
                    PlacementGeometry(placement.rect, FULL, placement.rect, FULL, Mode.PLAIN, 0)
                }

                is Placement.Window -> {
                    window(placement.rect, placement.fit, placement.rotation, inputWidth, inputHeight, canvasWidth, canvasHeight)
                }

                is Placement.Pip -> {
                    val g = window(placement.rect, Fit.FILL_CROP, placement.rotation, inputWidth, inputHeight, canvasWidth, canvasHeight)
                    // Measured on the short side, so an upright video's border is as thin as a landscape one's.
                    val px = max(1.0, (BORDER_PX_AT_1080 * min(canvasWidth, canvasHeight) / 1080.0).roundToInt().toDouble())
                    g.copy(borderX = px / canvasWidth, borderY = px / canvasHeight)
                }
            }
        }

        private fun window(
            rect: NormRect,
            fit: Fit,
            rotation: Rotation,
            inputWidth: Int,
            inputHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ): PlacementGeometry {
            val turns = rotation.degrees / 90
            // The picture as the user sees it, after rotation.
            val pw = if (turns % 2 == 1) inputHeight.toDouble() else inputWidth.toDouble()
            val ph = if (turns % 2 == 1) inputWidth.toDouble() else inputHeight.toDouble()
            val dw = rect.w * canvasWidth
            val dh = rect.h * canvasHeight
            val cover = cover(pw, ph, dw, dh)
            return when (fit) {
                Fit.FILL_CROP -> {
                    PlacementGeometry(rect, cover, rect, FULL, Mode.PLAIN, turns)
                }

                Fit.FIT_BARS, Fit.FIT_BLUR -> {
                    val scale = min(dw / pw, dh / ph)
                    val cw = pw * scale / canvasWidth
                    val ch = ph * scale / canvasHeight
                    val content = NormRect(rect.x + (rect.w - cw) / 2, rect.y + (rect.h - ch) / 2, cw, ch)
                    val mode = if (fit == Fit.FIT_BARS) Mode.BARS else Mode.BLUR
                    PlacementGeometry(content, FULL, rect, cover, mode, turns)
                }
            }
        }

        /** The centred part of a pw x ph picture that covers a dw x dh box at the same aspect. */
        private fun cover(
            pw: Double,
            ph: Double,
            dw: Double,
            dh: Double,
        ): NormRect {
            val scale = max(dw / pw, dh / ph)
            val sw = (dw / scale / pw).coerceAtMost(1.0)
            val sh = (dh / scale / ph).coerceAtMost(1.0)
            return NormRect((1 - sw) / 2, (1 - sh) / 2, sw, sh)
        }
    }
}
