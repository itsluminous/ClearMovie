package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.NormRect
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
        val strip = NormRect(0.155, 0.7981, 0.825, 0.1222)
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
}
