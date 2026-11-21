package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.ui.components.RectGrip
import org.junit.Test

/** The caption editor's place choices and drag rules (cm-captions). */
class CaptionPlacesTest {
    /** The reference frame's window and the preset's caption box (ARCHITECTURE section 3). */
    private val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)
    private val usual = NormRect(0.16, 0.62, 0.81, 0.14)
    private val safe = CaptionPlaces.safeArea(window)

    /** Inside [r], allowing for boxes being stored to 1/10000 of the canvas. */
    private fun NormRect.inside(r: NormRect) =
        x >= r.x - slack && y >= r.y - slack && x + w <= r.x + r.w + slack && y + h <= r.y + r.h + slack

    private val slack = 2e-4

    @Test
    fun `the safe area is the window less an even margin`() {
        assertThat(safe.inside(window)).isTrue()
        // The same number of pixels on every side at 1920 x 1080.
        assertThat((safe.x - window.x) * 1920).isWithin(0.01).of((safe.y - window.y) * 1080)
        assertThat((safe.y - window.y) * 1080).isWithin(0.01).of(CaptionPlaces.MARGIN * 1080)
        assertThat(CaptionPlaces.safeArea(NormRect.FULL).inside(NormRect.FULL)).isTrue()
    }

    @Test
    fun `each place keeps the preset box's size and left edge, inside the safe area`() {
        assertThat(CaptionPlaces.box(CaptionPlace.USUAL, usual, safe)).isEqualTo(usual)
        val top = CaptionPlaces.box(CaptionPlace.TOP, usual, safe)
        val middle = CaptionPlaces.box(CaptionPlace.MIDDLE, usual, safe)
        val bottom = CaptionPlaces.box(CaptionPlace.BOTTOM, usual, safe)
        for (b in listOf(top, middle, bottom)) {
            assertThat(b.inside(safe)).isTrue()
            assertThat(b.x).isWithin(1e-4).of(usual.x)
            assertThat(b.w).isWithin(1e-4).of(usual.w)
            assertThat(b.h).isWithin(1e-4).of(usual.h)
        }
        assertThat(top.y).isWithin(1e-4).of(safe.y)
        assertThat(bottom.y + bottom.h).isWithin(1e-4).of(safe.y + safe.h)
        assertThat(middle.y + middle.h / 2).isWithin(1e-4).of(safe.y + safe.h / 2)
        for (p in CaptionPlace.entries) assertThat(CaptionPlaces.placeOf(CaptionPlaces.box(p, usual, safe), usual, safe)).isEqualTo(p)
    }

    @Test
    fun `a box bigger than the safe area is made to fit it`() {
        val tall = NormRect(0.0, 0.0, 1.0, 1.0)
        for (p in listOf(CaptionPlace.TOP, CaptionPlace.MIDDLE, CaptionPlace.BOTTOM)) {
            assertThat(CaptionPlaces.box(p, tall, safe).inside(safe)).isTrue()
        }
        assertThat(CaptionPlaces.dragged(tall, 0.0, 0.0, safe, usual).first.inside(safe)).isTrue()
    }

    @Test
    fun `a drag moves the box, stops at the safe area, and snaps to the preset's place`() {
        val (moved, snapped) = CaptionPlaces.dragged(usual, 0.0, -0.3, safe, usual)
        assertThat(snapped).isTrue() // x is still on the preset's
        assertThat(moved.y).isWithin(1e-4).of(usual.y - 0.3)
        val (far, _) = CaptionPlaces.dragged(usual, -2.0, -2.0, safe, usual)
        assertThat(far.x).isWithin(1e-4).of(safe.x)
        assertThat(far.y).isWithin(1e-4).of(safe.y)
        // The box is nearly as wide as the safe area: sideways it moves only from its left edge to the right one.
        val (wide, _) = CaptionPlaces.dragged(usual, 1.0, 0.0, safe, usual)
        assertThat(wide.inside(safe)).isTrue()
        val (left, _) = CaptionPlaces.dragged(usual, -1.0, 0.0, safe, usual)
        assertThat(left.x).isWithin(1e-4).of(safe.x)
        val (away, _) = CaptionPlaces.dragged(usual, -0.1, -0.3, safe, usual)
        assertThat(CaptionPlaces.placeOf(away, usual, safe)).isNull()
        // Dragged back near it, it lands exactly on the preset's box again.
        val (back, snappedBack) = CaptionPlaces.dragged(away, 0.095, 0.29, safe, usual)
        assertThat(snappedBack).isTrue()
        assertThat(CaptionPlaces.placeOf(back, usual, safe)).isEqualTo(CaptionPlace.USUAL)
    }

    @Test
    fun `a preset box with more decimals than stored still snaps and reads as the preset's place`() {
        val fine = NormRect(0.16123, 0.62456, 0.81234, 0.14321)
        val (dragged, _) = CaptionPlaces.dragged(fine, -0.05, 0.0, safe, fine)
        val (back, snapped) = CaptionPlaces.dragged(dragged, 0.049, 0.0, safe, fine)
        assertThat(snapped).isTrue()
        assertThat(CaptionPlaces.placeOf(back, fine, safe)).isEqualTo(CaptionPlace.USUAL)
    }

    @Test
    fun `a preset box on the frame below its window widens the safe area, and a drag can go back there`() {
        // cm-preset-manager: the built-in preset's caption sits on the frame's blue strip, under the window.
        val strip = NormRect(0.15, 0.7981, 0.83, 0.1222)
        val wide = CaptionPlaces.safeArea(window, strip)
        assertThat(strip.inside(wide)).isTrue()
        assertThat(wide.y + wide.h).isWithin(1e-9).of(strip.y + strip.h)
        // Only across the strip box's width: never over the logo beside the strip (columns 0-269 of 1920).
        assertThat(wide.x).isEqualTo(strip.x)
        assertThat(wide.w).isEqualTo(strip.w)
        assertThat(wide.x * 1920).isGreaterThan(269.0)
        // Bottom is the strip itself; Top and Middle stay in the window.
        assertThat(CaptionPlaces.box(CaptionPlace.BOTTOM, strip, wide)).isEqualTo(strip)
        assertThat(CaptionPlaces.box(CaptionPlace.TOP, strip, wide).y).isWithin(1e-4).of(safe.y)
        // Dragged up into the window and back down, it snaps onto the strip.
        val (up, _) = CaptionPlaces.dragged(strip, 0.0, -0.4, wide, strip)
        val (back, snapped) = CaptionPlaces.dragged(up, 0.0, 0.395, wide, strip)
        assertThat(snapped).isTrue()
        assertThat(back).isEqualTo(strip)
        // Without the preset's box it is the window's safe area, as before.
        assertThat(CaptionPlaces.safeArea(window, null)).isEqualTo(safe)
    }

    @Test
    fun `the preset editor's places leave out the preset's own place`() {
        val places = listOf(CaptionPlace.TOP, CaptionPlace.MIDDLE, CaptionPlace.BOTTOM)
        assertThat(CaptionPlaces.placeOf(usual, usual, safe, places)).isNull()
        assertThat(CaptionPlaces.placeOf(CaptionPlaces.box(CaptionPlace.TOP, usual, safe), usual, safe, places)).isEqualTo(CaptionPlace.TOP)
    }

    @Test
    fun `the margin is the same number of pixels both ways on an upright or square canvas`() {
        // cm-aspect-ratio: 2% of the height is MARGIN / aspect of the width.
        for (aspect in listOf(9.0 / 16, 1.0, 16.0 / 9)) {
            val safe = CaptionPlaces.safeArea(NormRect.FULL, aspect = aspect)
            assertThat(safe.y).isWithin(1e-12).of(CaptionPlaces.MARGIN)
            assertThat(safe.x).isWithin(1e-12).of(CaptionPlaces.MARGIN / aspect)
        }
    }

    // cm-caption-box: resizing. Words that need 0.7 of the canvas width on one line, 0.1 of its height a line.
    private val wordsWidth = 0.7
    private val lineH = 0.1

    private fun needed(w: Double) = Math.ceil(wordsWidth / w - 1e-9) * lineH

    private val minW = CaptionPlaces.minWidth(64, CanvasShape.LANDSCAPE)

    private fun resize(
        grip: RectGrip,
        dx: Double,
        dy: Double,
        start: NormRect = usual,
    ) = CaptionPlaces.resized(start, grip, dx, dy, safe, usual, minW, ::needed).first

    @Test
    fun `the narrowest box holds a few letters at the caption's size, on every shape`() {
        assertThat(CaptionPlaces.minWidth(64, CanvasShape.LANDSCAPE)).isWithin(1e-9).of((4 * 64 + 32) / 1920.0)
        assertThat(CaptionPlaces.minWidth(64, CanvasShape.PORTRAIT)).isWithin(1e-9).of((4 * 64 + 32) / 1080.0)
        assertThat(CaptionPlaces.minWidth(64, CanvasShape.SQUARE)).isWithin(1e-9).of((4 * 64 + 32) / 1080.0)
    }

    @Test
    fun `a side handle makes the box narrower, and it grows from its middle so the words keep their size`() {
        val narrow = resize(RectGrip.RIGHT, -0.5, 0.0)
        assertThat(narrow.x).isWithin(1e-4).of(usual.x)
        assertThat(narrow.w).isWithin(2e-4).of(0.31)
        // Three lines now: 0.3 tall, from the old middle, kept inside the safe area at the bottom.
        assertThat(narrow.h).isWithin(2e-4).of(0.3)
        assertThat(narrow.y + narrow.h).isWithin(2e-4).of(safe.y + safe.h)
        assertThat(narrow.inside(safe)).isTrue()
        // Narrower than the narrowest: it stops there.
        assertThat(resize(RectGrip.RIGHT, -2.0, 0.0).w).isWithin(2e-4).of(minW)
        // Wider: as far as the safe area, the height as it was (the words need less).
        val wide = resize(RectGrip.LEFT, -0.5, 0.0)
        assertThat(wide.x).isWithin(2e-4).of(safe.x)
        assertThat(wide.x + wide.w).isWithin(2e-4).of(usual.x + usual.w)
        assertThat(wide.h).isWithin(2e-4).of(usual.h)
    }

    @Test
    fun `the height can be changed, but never below what the words need`() {
        val taller = resize(RectGrip.TOP, 0.0, -0.1)
        assertThat(taller.y).isWithin(2e-4).of(usual.y - 0.1)
        assertThat(taller.y + taller.h).isWithin(2e-4).of(usual.y + usual.h)
        val flat = resize(RectGrip.BOTTOM, 0.0, -0.5)
        assertThat(flat.y).isWithin(2e-4).of(usual.y)
        assertThat(flat.h).isWithin(2e-4).of(lineH)
        // A corner pulled in: two lines; the side opposite the finger stays, so it grows at the top it holds.
        val corner = resize(RectGrip.TOP_RIGHT, -0.4, 0.0)
        assertThat(corner.w).isWithin(2e-4).of(0.41)
        assertThat(corner.h).isWithin(2e-4).of(0.2)
        assertThat(corner.y + corner.h).isWithin(2e-4).of(usual.y + usual.h)
        assertThat(corner.inside(safe)).isTrue()
        val held = resize(RectGrip.BOTTOM_LEFT, 0.4, 0.0, start = NormRect(0.16, 0.2, 0.81, 0.14))
        assertThat(held.y).isWithin(2e-4).of(0.2)
        assertThat(held.h).isWithin(2e-4).of(0.2)
    }

    @Test
    fun `a moved side lands on the preset's place when near it`() {
        val start = NormRect(0.16, 0.3, 0.6, 0.14)
        val (box, snapped) = CaptionPlaces.resized(start, RectGrip.RIGHT, 0.205, 0.0, safe, usual, minW, ::needed)
        assertThat(snapped).isTrue()
        assertThat(box.x + box.w).isWithin(1e-4).of(usual.x + usual.w)
        val (_, far) = CaptionPlaces.resized(start, RectGrip.RIGHT, 0.1, 0.0, safe, usual, minW, ::needed)
        assertThat(far).isFalse()
        // The body handle is the drag that moves it.
        assertThat(CaptionPlaces.resized(start, RectGrip.BODY, 0.0, 0.05, safe, usual, minW, ::needed))
            .isEqualTo(CaptionPlaces.dragged(start, 0.0, 0.05, safe, usual))
    }

    @Test
    fun `no drag ever takes the box out of the safe area or below its smallest`() {
        for (g in RectGrip.entries) {
            for (dx in listOf(-3.0, -0.2, 0.0, 0.2, 3.0)) {
                for (dy in listOf(-3.0, -0.2, 0.0, 0.2, 3.0)) {
                    val b = resize(g, dx, dy)
                    assertThat(b.inside(safe)).isTrue()
                    assertThat(b.isValid).isTrue()
                    assertThat(b.w).isAtLeast(minW - 2e-4)
                    assertThat(b.h).isAtLeast(minOf(needed(b.w), safe.h) - 2e-4)
                }
            }
        }
    }

    @Test
    fun `resizing holds on an upright canvas, in its own units`() {
        val upright = NormRect(0.05, 0.05, 0.9, 0.9)
        val box = NormRect(0.1, 0.7, 0.8, 0.06)
        val mw = CaptionPlaces.minWidth(64, CanvasShape.PORTRAIT)
        val (out, _) = CaptionPlaces.resized(box, RectGrip.LEFT, 0.9, 0.0, upright, box, mw) { 0.05 }
        assertThat(out.w).isWithin(2e-4).of(mw)
        assertThat(out.x + out.w).isWithin(2e-4).of(0.9)
        assertThat(out.h).isWithin(2e-4).of(0.06)
    }

    @Test
    fun `a caption in the picture can be made wider than the preset's strip, and one that wide stays in the picture`() {
        // The built-in preset: its caption box on the frame's strip, under the window, clear of the logo.
        val strip = NormRect(0.15, 0.7981, 0.83, 0.1222)
        val band = CaptionPlaces.safeArea(window, strip)
        val inner = CaptionPlaces.safeArea(window)
        val up = NormRect(0.15, 0.3, 0.83, 0.12)
        // Resizing a box in the picture: the whole window (and the strip's width); on the strip: the band.
        val picture = NormRect(inner.x, inner.y, maxOf(inner.x + inner.w, band.x + band.w) - inner.x, inner.h)
        assertThat(CaptionPlaces.dragBounds(up, RectGrip.LEFT, inner, band)).isEqualTo(picture)
        assertThat(CaptionPlaces.dragBounds(strip, RectGrip.LEFT, inner, band)).isEqualTo(band)
        val wide = CaptionPlaces.resized(up, RectGrip.LEFT, -0.5, 0.0, picture, strip, minW, ::needed).first
        assertThat(wide.x).isWithin(2e-4).of(inner.x)
        assertThat(wide.w).isGreaterThan(strip.w)
        // Moving: a box as wide as the strip goes onto it and back; a wider one stays in the picture.
        assertThat(CaptionPlaces.dragBounds(up, RectGrip.BODY, inner, band)).isEqualTo(band)
        assertThat(CaptionPlaces.dragBounds(wide, RectGrip.BODY, inner, band)).isEqualTo(picture)
        val moved = CaptionPlaces.dragged(wide, 0.0, 0.5, picture, strip).first
        assertThat(moved.w).isWithin(2e-4).of(wide.w)
        assertThat(moved.y + moved.h).isWithin(2e-4).of(inner.y + inner.h)
    }

    @Test
    fun `a place keeps the size of a box the user resized`() {
        val resized = NormRect(0.2, 0.3, 0.4, 0.2)
        val top = CaptionPlaces.box(CaptionPlace.TOP, usual, safe, resized)
        assertThat(top.w).isWithin(2e-4).of(0.4)
        assertThat(top.h).isWithin(2e-4).of(0.2)
        assertThat(top.y).isWithin(2e-4).of(safe.y)
        assertThat(CaptionPlaces.placeOf(top, usual, safe)).isEqualTo(CaptionPlace.TOP)
        // The preset's own place is the preset's box, whatever the caption's size.
        assertThat(CaptionPlaces.box(CaptionPlace.USUAL, usual, safe, resized)).isEqualTo(usual)
    }

    @Test
    fun `a box grows from its middle for bigger words, inside its bounds, and never shrinks`() {
        val b = NormRect(0.2, 0.4, 0.5, 0.1)
        val g = CaptionPlaces.grown(b, 0.2, safe)
        assertThat(g.h).isWithin(2e-4).of(0.2)
        assertThat(g.y + g.h / 2).isWithin(2e-4).of(0.45)
        assertThat(CaptionPlaces.grown(b, 0.05, safe)).isEqualTo(b)
        val low = NormRect(0.2, safe.y + safe.h - 0.1, 0.5, 0.1)
        val up = CaptionPlaces.grown(low, 0.3, safe)
        assertThat(up.y + up.h).isWithin(2e-4).of(safe.y + safe.h)
        assertThat(up.h).isWithin(2e-4).of(0.3)
    }

    @Test
    fun `a caption on the preset's strip is resized on the strip, never over the picture`() {
        val strip = NormRect(0.15, 0.7981, 0.83, 0.1222)
        val band = CaptionPlaces.safeArea(window, strip)
        val inner = CaptionPlaces.safeArea(window)
        val bounds = CaptionPlaces.dragBounds(strip, RectGrip.RIGHT, inner, band, strip)
        assertThat(bounds.y).isWithin(2e-4).of(strip.y)
        assertThat(bounds.h).isWithin(2e-4).of(strip.h)
        // Narrower words need three lines, but the box stays as high as the strip.
        val narrow = CaptionPlaces.resized(strip, RectGrip.RIGHT, -0.5, 0.0, bounds, strip, minW, ::needed).first
        assertThat(narrow.w).isWithin(2e-4).of(0.33)
        assertThat(narrow.y).isWithin(2e-4).of(strip.y)
        assertThat(narrow.h).isWithin(2e-4).of(strip.h)
        // Moving it is still free to go up into the picture.
        assertThat(CaptionPlaces.dragBounds(narrow, RectGrip.BODY, inner, band, strip)).isEqualTo(band)
    }
}
