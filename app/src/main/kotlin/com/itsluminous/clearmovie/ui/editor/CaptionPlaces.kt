package com.itsluminous.clearmovie.ui.editor

import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.render.media3.CaptionBitmapRenderer
import com.itsluminous.clearmovie.ui.components.RectDrag
import com.itsluminous.clearmovie.ui.components.RectGrip
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
 * axis of the video's canvas, whatever its shape). Pure, so the rules are
 * unit-tested.
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

    /** The narrowest box holds about this many letters' widths at the caption's size. */
    const val MIN_WIDTH_EMS = 4

    /**
     * [window] less [MARGIN] on every side (the horizontal margin is as many
     * pixels as the vertical one on a canvas [aspect] wide per unit of
     * height); with the preset's [usual] box outside it, the band from the
     * window's top to that box, as wide as the box.
     */
    fun safeArea(
        window: NormRect,
        usual: NormRect? = null,
        aspect: Double = CanvasShape.LANDSCAPE.aspect,
    ): NormRect {
        val mx = minOf(MARGIN / aspect, window.w / 4)
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

    /**
     * The box for [place]: the preset's [usual] box itself, or a box the size of [current] (the caption's
     * own, so a box the user resized keeps its size; the preset's by default) moved to the top, middle or
     * bottom of [safe], keeping its left edge where it fits. With [inner], the frame window's own safe
     * area (cm-caption-box): when the preset's place is on the frame's strip, a box that is not the strip's
     * size goes to the top, middle or bottom of the picture instead ([dragBounds]), so it never straddles
     * the strip and the photo.
     */
    fun box(
        place: CaptionPlace,
        usual: NormRect,
        safe: NormRect,
        current: NormRect = usual,
        inner: NormRect = safe,
    ): NormRect {
        if (place == CaptionPlace.USUAL) return usual
        val onStrip = !within(usual, inner)
        val fitsStrip = current.w <= safe.w + TOLERANCE && current.h <= usual.h + TOLERANCE
        val area = if (onStrip && !fitsStrip) picture(inner, safe) else safe
        val w = minOf(current.w, area.w)
        val h = minOf(current.h, area.h)
        val x = current.x.coerceIn(area.x, maxOf(area.x, area.x + area.w - w))
        val y =
            when (place) {
                CaptionPlace.TOP -> area.y
                CaptionPlace.MIDDLE -> area.y + (area.h - h) / 2
                else -> area.y + area.h - h
            }
        return NormRect(x, y, w, h).rounded()
    }

    /** The picture a caption may be resized in: [inner]'s height, across it and the strip's band ([safe]). */
    private fun picture(
        inner: NormRect,
        safe: NormRect,
    ): NormRect {
        val left = minOf(inner.x, safe.x)
        val right = maxOf(inner.x + inner.w, safe.x + safe.w)
        return NormRect(left, inner.y, right - left, inner.h)
    }

    /**
     * The narrowest width from [from] up to [to] at which the words still fit in [room] of height when
     * drawn as small as a caption is drawn to fit ([shrunkNeed], their height at that size, in units of
     * the canvas height): narrowing a caption past it would cut words off. [from] when they fit there.
     */
    fun narrowestFitting(
        from: Double,
        to: Double,
        room: Double,
        shrunkNeed: (w: Double) -> Double,
    ): Double {
        if (to <= from || shrunkNeed(from) <= room + TOLERANCE) return from
        if (shrunkNeed(to) > room + TOLERANCE) return to
        var lo = from
        var hi = to
        repeat(14) {
            val mid = (lo + hi) / 2
            if (shrunkNeed(mid) <= room + TOLERANCE) hi = mid else lo = mid
        }
        return Math.ceil(hi * PRECISION) / PRECISION
    }

    /** The place among [places] that [box] is at, or null when it was dragged somewhere else. */
    fun placeOf(
        box: NormRect,
        usual: NormRect,
        safe: NormRect,
        places: List<CaptionPlace> = CaptionPlace.entries,
        inner: NormRect = safe,
    ): CaptionPlace? = places.firstOrNull { same(box(it, usual, safe, box, inner), box) }

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

    /**
     * [box] made at least [need] tall (in units of the canvas height),
     * growing from its middle inside [bounds], never shorter; as tall as
     * [bounds] allows when that is less (cm-caption-box: a bigger size or
     * font keeps its words their size).
     */
    fun grown(
        box: NormRect,
        need: Double,
        bounds: NormRect,
    ): NormRect {
        if (box.h >= need - TOLERANCE) return box
        val h = minOf(need, maxOf(box.h, bounds.h))
        if (h <= box.h + TOLERANCE) return box
        val top = (box.y + box.h / 2 - h / 2).coerceIn(minOf(bounds.y, box.y), maxOf(bounds.y, bounds.y + bounds.h - h))
        return NormRect(box.x, top, box.w, h).rounded()
    }

    /**
     * Where a drag with [grip] may take [box] (cm-caption-box). [inner] is
     * the frame window's safe area, [safe] the caption's (which reaches the
     * preset's strip under the window, across the preset box's width only).
     * A box inside the window (as wide as the window and the strip
     * together) is resized within all of it, so a
     * caption in the picture can be made wider than the strip; a box across
     * the strip's width moves within [safe] (onto the strip and back); a
     * wider one moves within the window only. A box on the strip is
     * resized on the strip: no higher than the preset's [usual] box, so it
     * never spills over the picture (words that need more lines than the
     * strip holds are drawn smaller there; moved up into the picture, the
     * box can grow). Anything else stays in [safe].
     */
    fun dragBounds(
        box: NormRect,
        grip: RectGrip,
        inner: NormRect,
        safe: NormRect,
        usual: NormRect? = null,
    ): NormRect {
        // The picture: the window's height, and its width joined with the strip's (which may reach a little further).
        val picture = picture(inner, safe)
        if (grip == RectGrip.BODY) {
            // Compared as stored (to 1/10000 of the canvas), as boxes are.
            val acrossStrip = box.x >= safe.x - TOLERANCE && box.x + box.w <= safe.x + safe.w + TOLERANCE
            return if (acrossStrip || !within(box, picture)) safe else picture
        }
        if (within(box, picture)) return picture
        if (usual != null && !within(usual, picture)) {
            // On the strip: its own height, across the band's width.
            val top = maxOf(safe.y, minOf(usual.y, box.y))
            val bottom = minOf(safe.y + safe.h, maxOf(usual.y + usual.h, box.y + box.h))
            if (bottom > top) return NormRect(safe.x, top, safe.w, bottom - top)
        }
        return safe
    }

    private fun within(
        a: NormRect,
        b: NormRect,
    ) = a.x >= b.x - TOLERANCE && a.y >= b.y - TOLERANCE && a.x + a.w <= b.x + b.w + TOLERANCE && a.y + a.h <= b.y + b.h + TOLERANCE

    /**
     * The narrowest a caption's box may be made, in units of the canvas
     * width: room for about [MIN_WIDTH_EMS] letters at [sizePx] and the
     * box's padding, on a canvas of [shape].
     */
    fun minWidth(
        sizePx: Int,
        shape: CanvasShape,
    ): Double {
        val canvasWidth = shape.size(CanvasShape.REFERENCE_SHORT_SIDE).first.toDouble()
        return (MIN_WIDTH_EMS * sizePx + 2 * CaptionBitmapRenderer.PADDING_PX) / canvasWidth
    }

    /**
     * [start] resized by [grip] moving ([dx], [dy]) (cm-caption-box): kept
     * inside [safe], at least [minW] wide, and never shorter than its words
     * need at their size at the new width ([neededH], in units of the canvas
     * height), so they wrap again and keep their size. When the box must
     * grow for that, the side opposite the handle stays, as in any resize,
     * and it grows at the side the finger holds (from its middle for a side
     * handle), as far as [safe] allows; past that the words are drawn
     * smaller, as for any box too small for them. A moved
     * side within [SNAP] of the same side of the preset's [usual] box lands
     * on it. [RectGrip.BODY] is [dragged]. Returns the box and whether it
     * snapped.
     */
    fun resized(
        start: NormRect,
        grip: RectGrip,
        dx: Double,
        dy: Double,
        safe: NormRect,
        usual: NormRect,
        minW: Double,
        neededH: (w: Double) -> Double,
    ): Pair<NormRect, Boolean> {
        if (grip == RectGrip.BODY) return dragged(start, dx, dy, safe, usual)
        // The width first: it decides how many lines the words need.
        val across = RectDrag.resize(start, grip, dx, 0.0, safe, minW, 0.0)
        var l = across.x
        var r = across.x + across.w
        var snapped = false
        if (grip.left && abs(l - usual.x) < SNAP && usual.x >= safe.x - EPS && r - usual.x >= minW - EPS) {
            l = usual.x
            snapped = true
        }
        if (grip.right && abs(r - (usual.x + usual.w)) < SNAP && usual.x + usual.w <= safe.x + safe.w + EPS &&
            usual.x + usual.w - l >= minW - EPS
        ) {
            r = usual.x + usual.w
            snapped = true
        }
        val need = neededH(r - l).coerceIn(0.0, safe.h)
        val moved = RectDrag.resize(NormRect(l, across.y, r - l, across.h), grip, 0.0, dy, safe, 0.0, need)
        var t = moved.y
        var b = moved.y + moved.h
        if (grip.top && abs(t - usual.y) < SNAP && usual.y >= safe.y - EPS && b - usual.y >= need - EPS) {
            t = usual.y
            snapped = true
        }
        if (grip.bottom && abs(b - (usual.y + usual.h)) < SNAP && usual.y + usual.h <= safe.y + safe.h + EPS &&
            usual.y + usual.h - t >= need - EPS
        ) {
            b = usual.y + usual.h
            snapped = true
        }
        if (b - t < need) {
            // Taller for the words: away from the side held, or from the middle, inside the safe area.
            val top = safe.y
            val bottom = safe.y + safe.h
            when {
                grip.top -> {
                    t = b - need
                }

                grip.bottom -> {
                    b = t + need
                }

                else -> {
                    t = (t + b - need) / 2
                    b = t + need
                }
            }
            if (t < top) {
                t = top
                b = t + need
            }
            if (b > bottom) {
                b = bottom
                t = b - need
            }
        }
        return NormRect(l, t, r - l, b - t).rounded() to snapped
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
