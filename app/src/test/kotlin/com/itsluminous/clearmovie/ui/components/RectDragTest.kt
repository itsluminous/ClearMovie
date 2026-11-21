package com.itsluminous.clearmovie.ui.components

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.NormRect
import org.junit.Test

/** cm-caption-box: the shared maths of a rectangle moved and resized on a picture. */
class RectDragTest {
    private val bounds = NormRect(0.1, 0.1, 0.8, 0.8)
    private val r = NormRect(0.25, 0.4, 0.5, 0.2)

    // A 1000 x 500 px picture, 3 px a dp: reach 72 px, body 144 px, corner room 216 px, edge room 432 px.
    private val w = 1000f
    private val h = 500f
    private val reach = 72f
    private val bodyMin = 144f

    private fun handles(rect: NormRect) = RectDrag.handles((rect.w * w).toFloat(), (rect.h * h).toFloat(), 216f, 432f)

    private fun grip(
        rect: NormRect,
        x: Float,
        y: Float,
    ) = RectDrag.gripAt(rect, handles(rect), x, y, w, h, reach, bodyMin)

    @Test
    fun `a thin box gets side handles, and top and bottom ones once it is wide enough`() {
        // 500 x 100 px: too thin for corners; wide enough for top and bottom.
        assertThat(handles(r)).containsExactly(RectGrip.TOP, RectGrip.BOTTOM, RectGrip.LEFT, RectGrip.RIGHT)
        // 300 x 100 px: only the sides.
        assertThat(handles(NormRect(0.3, 0.4, 0.3, 0.2))).containsExactly(RectGrip.LEFT, RectGrip.RIGHT)
        // 500 x 250 px: corners and the top and bottom; the sides are too short for a middle handle.
        val big = NormRect(0.2, 0.2, 0.5, 0.5)
        assertThat(handles(big)).containsExactly(
            RectGrip.TOP_LEFT,
            RectGrip.TOP_RIGHT,
            RectGrip.BOTTOM_RIGHT,
            RectGrip.BOTTOM_LEFT,
            RectGrip.TOP,
            RectGrip.BOTTOM,
        )
        // Corners only, as the frame's picture area asks.
        assertThat(RectDrag.handles(500f, 250f, 216f, 432f, edges = false)).hasSize(4)
        assertThat(RectDrag.handles(500f, 250f, 216f, 432f, edges = false).all { it.isCorner }).isTrue()
    }

    @Test
    fun `the middle of a box moves it, a handle resizes it, and a touch away from it is left alone`() {
        // r is 250..750 x 200..300 px.
        assertThat(grip(r, 500f, 250f)).isEqualTo(RectGrip.BODY)
        // On the left edge's handle, and just outside it.
        assertThat(grip(r, 250f, 250f)).isEqualTo(RectGrip.LEFT)
        assertThat(grip(r, 200f, 260f)).isEqualTo(RectGrip.LEFT)
        assertThat(grip(r, 750f, 240f)).isEqualTo(RectGrip.RIGHT)
        // Above the middle of the top edge: the top handle; inside the thin box's middle band: still the body.
        assertThat(grip(r, 500f, 190f)).isEqualTo(RectGrip.TOP)
        assertThat(grip(r, 500f, 240f)).isEqualTo(RectGrip.BODY)
        assertThat(grip(r, 500f, 310f)).isEqualTo(RectGrip.BOTTOM)
        // On the box away from every handle.
        assertThat(grip(r, 360f, 205f)).isEqualTo(RectGrip.BODY)
        // Far away: nothing, so the picture underneath gets the touch.
        assertThat(grip(r, 50f, 50f)).isNull()
        assertThat(grip(r, 500f, 450f)).isNull()
    }

    @Test
    fun `a box thinner than a finger is touchable over 48 dp`() {
        val thin = NormRect(0.3, 0.5, 0.4, 0.02) // 10 px tall
        assertThat(grip(thin, 400f, 250f + 60f)).isEqualTo(RectGrip.BODY)
        assertThat(grip(thin, 400f, 250f + 80f)).isNull()
    }

    @Test
    fun `a corner is held when the box has room for corners`() {
        val big = NormRect(0.2, 0.2, 0.5, 0.5) // 200..700 x 100..350 px
        assertThat(grip(big, 205f, 105f)).isEqualTo(RectGrip.TOP_LEFT)
        assertThat(grip(big, 710f, 360f)).isEqualTo(RectGrip.BOTTOM_RIGHT)
        assertThat(grip(big, 450f, 225f)).isEqualTo(RectGrip.BODY)
    }

    @Test
    fun `moving keeps the size and stops at the bounds`() {
        val moved = RectDrag.move(r, 0.9, -0.9, bounds)
        assertThat(moved.x).isWithin(1e-9).of(0.4)
        assertThat(moved.y).isWithin(1e-9).of(0.1)
        assertThat(moved.w).isWithin(1e-9).of(r.w)
        assertThat(moved.h).isWithin(1e-9).of(r.h)
        // A box larger than the bounds is fitted into them first.
        assertThat(RectDrag.move(NormRect(0.0, 0.0, 1.0, 1.0), 0.0, 0.0, bounds)).isEqualTo(bounds)
    }

    @Test
    fun `each handle moves only its sides, within the bounds and never below the smallest size`() {
        val wider = RectDrag.resize(r, RectGrip.RIGHT, 0.5, 0.3, bounds, 0.1, 0.05)
        assertThat(wider.x).isWithin(1e-9).of(0.25)
        assertThat(wider.x + wider.w).isWithin(1e-9).of(0.9)
        assertThat(wider.y).isEqualTo(r.y)
        assertThat(wider.h).isWithin(1e-9).of(r.h)
        val narrow = RectDrag.resize(r, RectGrip.LEFT, 0.9, 0.0, bounds, 0.1, 0.05)
        assertThat(narrow.w).isWithin(1e-9).of(0.1)
        assertThat(narrow.x + narrow.w).isWithin(1e-9).of(0.75)
        val corner = RectDrag.resize(r, RectGrip.TOP_LEFT, -1.0, -1.0, bounds, 0.1, 0.05)
        assertThat(corner.x).isWithin(1e-9).of(0.1)
        assertThat(corner.y).isWithin(1e-9).of(0.1)
        assertThat(corner.w).isWithin(1e-9).of(0.65)
        assertThat(corner.h).isWithin(1e-9).of(0.5)
        val flat = RectDrag.resize(r, RectGrip.BOTTOM, 0.0, -1.0, bounds, 0.1, 0.05)
        assertThat(flat.h).isWithin(1e-9).of(0.05)
        assertThat(flat.y).isEqualTo(r.y)
        for (g in RectGrip.entries) {
            for ((dx, dy) in listOf(-2.0 to -2.0, 2.0 to 2.0, -2.0 to 2.0, 2.0 to -2.0)) {
                val out = RectDrag.resize(r, g, dx, dy, bounds, 0.1, 0.05)
                assertThat(out.x).isAtLeast(bounds.x - 1e-9)
                assertThat(out.y).isAtLeast(bounds.y - 1e-9)
                assertThat(out.x + out.w).isAtMost(bounds.x + bounds.w + 1e-9)
                assertThat(out.y + out.h).isAtMost(bounds.y + bounds.h + 1e-9)
                assertThat(out.w).isAtLeast(0.1 - 1e-9)
                assertThat(out.h).isAtLeast(0.05 - 1e-9)
            }
        }
    }

    @Test
    fun `a minimum larger than the bounds, or a box already smaller than it, never fails`() {
        val tiny = NormRect(0.4, 0.4, 0.01, 0.01)
        val out = RectDrag.resize(tiny, RectGrip.LEFT, 0.0, 0.0, bounds, 2.0, 2.0)
        assertThat(out.isValid).isTrue()
        val grown = RectDrag.resize(tiny, RectGrip.RIGHT, 0.0, 0.0, bounds, 0.2, 0.0)
        assertThat(grown.w).isWithin(1e-9).of(0.2)
    }

    @Test
    fun `handle points sit on the corners and in the middle of each edge`() {
        assertThat(RectDrag.handlePoint(r, RectGrip.TOP_LEFT)).isEqualTo(0.25 to 0.4)
        assertThat(RectDrag.handlePoint(r, RectGrip.RIGHT).first).isWithin(1e-9).of(0.75)
        assertThat(RectDrag.handlePoint(r, RectGrip.RIGHT).second).isWithin(1e-9).of(0.5)
        assertThat(RectDrag.handlePoint(r, RectGrip.BOTTOM).first).isWithin(1e-9).of(0.5)
        assertThat(RectDrag.handlePoint(r, RectGrip.BOTTOM).second).isWithin(1e-9).of(0.6)
    }
}
