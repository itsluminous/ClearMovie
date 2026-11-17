package com.itsluminous.clearmovie.ui.editor

import com.itsluminous.clearmovie.domain.model.NormRect
import kotlin.math.abs

/** The places the caption editor offers, besides dragging the box on the preview. */
enum class CaptionPlace {
    /** The preset's own box: where the scene description sits in the frame. */
    USUAL,
    TOP,
    MIDDLE,
    BOTTOM,
}

/**
 * Where a caption's box may go and the boxes the editor's place choices
 * give (cm-captions). All of it is in normalised canvas units (0..1 on each
 * axis of the 16:9 canvas). Pure, so the rules are unit-tested.
 *
 * The safe area is the frame's window (or the whole canvas without a
 * frame) less a small margin, so a caption never touches the frame's
 * border. The place choices keep the preset box's width, height and left
 * edge (which keep it clear of the frame's logo) and move it to the top,
 * middle or bottom of the safe area. A drag moves the box inside the safe
 * area and snaps to the preset's box when it comes near it.
 */
object CaptionPlaces {
    /** The margin inside the window, as a share of the canvas height (about 22 px at 1080p). */
    const val MARGIN = 0.02

    /** How near (in canvas units) a dragged box snaps to the preset's box. */
    const val SNAP = 0.02

    private const val CANVAS_ASPECT = 16.0 / 9.0

    /** [window] less [MARGIN] on every side (the horizontal margin is as many pixels as the vertical one). */
    fun safeArea(window: NormRect): NormRect {
        val mx = minOf(MARGIN / CANVAS_ASPECT, window.w / 4)
        val my = minOf(MARGIN, window.h / 4)
        return NormRect(window.x + mx, window.y + my, window.w - 2 * mx, window.h - 2 * my)
    }

    /** The box for [place]: the preset's [usual] box itself, or that box moved into [safe]. */
    fun box(
        place: CaptionPlace,
        usual: NormRect,
        safe: NormRect,
    ): NormRect {
        if (place == CaptionPlace.USUAL) return usual
        val w = minOf(usual.w, safe.w)
        val h = minOf(usual.h, safe.h)
        val x = usual.x.coerceIn(safe.x, safe.x + safe.w - w)
        val y =
            when (place) {
                CaptionPlace.TOP -> safe.y
                CaptionPlace.MIDDLE -> safe.y + (safe.h - h) / 2
                else -> safe.y + safe.h - h
            }
        return NormRect(x, y, w, h).rounded()
    }

    /** The place [box] is at, or null when it was dragged somewhere else. */
    fun placeOf(
        box: NormRect,
        usual: NormRect,
        safe: NormRect,
    ): CaptionPlace? = CaptionPlace.entries.firstOrNull { same(box(it, usual, safe), box) }

    /**
     * [start] moved by ([dx], [dy]) and kept inside [safe] (a box larger than
     * the safe area is first made to fit). Within [SNAP] of the preset's
     * [usual] box on an axis, it lands exactly on it. Returns the box and
     * whether it snapped.
     */
    fun dragged(
        start: NormRect,
        dx: Double,
        dy: Double,
        safe: NormRect,
        usual: NormRect,
    ): Pair<NormRect, Boolean> {
        val w = minOf(start.w, safe.w)
        val h = minOf(start.h, safe.h)
        var x = (start.x + dx).coerceIn(safe.x, safe.x + safe.w - w)
        var y = (start.y + dy).coerceIn(safe.y, safe.y + safe.h - h)
        var snapped = false
        if (w == usual.w && abs(x - usual.x) < SNAP && usual.x >= safe.x - EPS && usual.x + w <= safe.x + safe.w + EPS) {
            x = usual.x
            snapped = true
        }
        if (h == usual.h && abs(y - usual.y) < SNAP && usual.y >= safe.y - EPS && usual.y + h <= safe.y + safe.h + EPS) {
            y = usual.y
            snapped = true
        }
        return NormRect(x, y, w, h).rounded() to snapped
    }

    private fun same(
        a: NormRect,
        b: NormRect,
    ) = abs(a.x - b.x) < EPS && abs(a.y - b.y) < EPS && abs(a.w - b.w) < EPS && abs(a.h - b.h) < EPS

    /** To 1/10000 of the canvas (a fifth of a pixel at 1080p): stored boxes stay short and stable. */
    private fun NormRect.rounded(): NormRect {
        fun r(v: Double) = Math.round(v * PRECISION) / PRECISION
        val rx = r(x)
        val ry = r(y)
        // Rounding never pushes the far edge past the canvas.
        return NormRect(rx, ry, minOf(r(w), 1.0 - rx), minOf(r(h), 1.0 - ry))
    }

    private const val EPS = 1e-6
    private const val PRECISION = 10000.0
}
