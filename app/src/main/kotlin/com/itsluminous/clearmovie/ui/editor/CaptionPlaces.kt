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
 * border. A preset may put its caption on the frame itself (the reference
 * frame's blue strip, under the window): then the safe area reaches down to
 * that box, across the box's own width only (beside the strip is the
 * frame's logo), so the caption can always go back there. The place choices keep the preset box's width, height and left
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

    /**
     * [window] less [MARGIN] on every side (the horizontal margin is as many
     * pixels as the vertical one); with the preset's [usual] box outside it,
     * the band from the window's top to that box, as wide as the box.
     */
    fun safeArea(
        window: NormRect,
        usual: NormRect? = null,
    ): NormRect {
        val mx = minOf(MARGIN / CANVAS_ASPECT, window.w / 4)
        val my = minOf(MARGIN, window.h / 4)
        val inner = NormRect(window.x + mx, window.y + my, window.w - 2 * mx, window.h - 2 * my)
        if (usual == null || !usual.isValid) return inner
        val inside =
            usual.x >= inner.x - EPS && usual.y >= inner.y - EPS &&
                usual.x + usual.w <= inner.x + inner.w + EPS && usual.y + usual.h <= inner.y + inner.h + EPS
        if (inside) return inner
        // Outside the window (on the frame's strip): up and down from the window's top to the box's bottom,
        // but only across the box's own width, which keeps it clear of the frame's logo beside the strip.
        val y0 = minOf(inner.y, usual.y)
        val y1 = minOf(1.0, maxOf(inner.y + inner.h, usual.y + usual.h))
        return NormRect(usual.x, y0, usual.w, y1 - y0)
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
        val x = usual.x.coerceIn(safe.x, maxOf(safe.x, safe.x + safe.w - w))
        val y =
            when (place) {
                CaptionPlace.TOP -> safe.y
                CaptionPlace.MIDDLE -> safe.y + (safe.h - h) / 2
                else -> safe.y + safe.h - h
            }
        return NormRect(x, y, w, h).rounded()
    }

    /** The place among [places] that [box] is at, or null when it was dragged somewhere else. */
    fun placeOf(
        box: NormRect,
        usual: NormRect,
        safe: NormRect,
        places: List<CaptionPlace> = CaptionPlace.entries,
    ): CaptionPlace? = places.firstOrNull { same(box(it, usual, safe), box) }

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
        var w = minOf(start.w, safe.w)
        var h = minOf(start.h, safe.h)
        var x = (start.x + dx).coerceIn(safe.x, maxOf(safe.x, safe.x + safe.w - w))
        var y = (start.y + dy).coerceIn(safe.y, maxOf(safe.y, safe.y + safe.h - h))
        var snapped = false
        if (abs(w - usual.w) < TOLERANCE && abs(x - usual.x) < SNAP && usual.x >= safe.x - EPS && usual.x + w <= safe.x + safe.w + EPS) {
            x = usual.x
            if (abs(w - usual.w) < TOLERANCE) w = usual.w
            snapped = true
        }
        if (abs(h - usual.h) < TOLERANCE && abs(y - usual.y) < SNAP && usual.y >= safe.y - EPS && usual.y + h <= safe.y + safe.h + EPS) {
            y = usual.y
            if (abs(h - usual.h) < TOLERANCE) h = usual.h
            snapped = true
        }
        return NormRect(x, y, w, h).rounded() to snapped
    }

    private fun same(
        a: NormRect,
        b: NormRect,
    ) = abs(a.x - b.x) < TOLERANCE && abs(a.y - b.y) < TOLERANCE && abs(a.w - b.w) < TOLERANCE && abs(a.h - b.h) < TOLERANCE

    /** To 1/10000 of the canvas (a fifth of a pixel at 1080p): stored boxes stay short and stable. */
    private fun NormRect.rounded(): NormRect {
        fun r(v: Double) = Math.round(v * PRECISION) / PRECISION
        val rx = r(x)
        val ry = r(y)
        // Rounding never pushes the far edge past the canvas.
        return NormRect(rx, ry, minOf(r(w), 1.0 - rx), minOf(r(h), 1.0 - ry))
    }

    private const val EPS = 1e-6

    /** Boxes are stored to 1/10000 of the canvas, so two boxes this close are the same place. */
    private const val TOLERANCE = 1.5e-4
    private const val PRECISION = 10000.0
}
