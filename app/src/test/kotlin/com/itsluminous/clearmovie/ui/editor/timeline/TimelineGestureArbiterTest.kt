package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The gesture arbiter with synthetic pointer streams (ROADMAP cm-timeline acceptance, ARCHITECTURE section 11.5). */
class TimelineGestureArbiterTest {
    private fun arbiter() = TimelineGestureArbiter(touchSlopDp = 8.0)

    @Test
    fun `a second finger before the long-press makes a pinch`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.CLIP)
        a.move(1, 102.0, 50.0, 60)
        assertThat(a.down(2, 200.0, 50.0, 120, GestureStart.CLIP)).isEqualTo(GestureMode.PINCH)
        // It stays a pinch, never becoming a long-press or a drag.
        assertThat(a.tick(900)).isEqualTo(GestureMode.PINCH)
        assertThat(a.move(1, 60.0, 50.0, 950)).isEqualTo(GestureMode.PINCH)
        a.up(2, 1000)
        assertThat(a.mode).isEqualTo(GestureMode.PINCH)
        assertThat(a.up(1, 1010)).isEqualTo(GestureMode.ENDED)
    }

    @Test
    fun `pinch beats a long-press that has not fired, even late`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.CLIP)
        assertThat(a.down(2, 140.0, 50.0, 400, GestureStart.CLIP)).isEqualTo(GestureMode.PINCH)
    }

    @Test
    fun `a scroll gives way to a second finger`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.EMPTY)
        assertThat(a.move(1, 130.0, 50.0, 80)).isEqualTo(GestureMode.SCROLL)
        assertThat(a.down(2, 220.0, 60.0, 300, GestureStart.EMPTY)).isEqualTo(GestureMode.PINCH)
    }

    @Test
    fun `movement past the slop before 500 ms is a scroll`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.CLIP)
        assertThat(a.move(1, 105.0, 50.0, 50)).isEqualTo(GestureMode.PENDING)
        assertThat(a.move(1, 120.0, 50.0, 100)).isEqualTo(GestureMode.SCROLL)
        // Holding still afterwards never becomes a long-press.
        assertThat(a.tick(900)).isEqualTo(GestureMode.SCROLL)
        assertThat(a.up(1, 950)).isEqualTo(GestureMode.ENDED)
    }

    @Test
    fun `a hold without movement opens the menu`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.CLIP)
        a.move(1, 103.0, 52.0, 200)
        assertThat(a.tick(499)).isEqualTo(GestureMode.PENDING)
        assertThat(a.tick(500)).isEqualTo(GestureMode.LONG_PRESSED)
        assertThat(a.up(1, 800)).isEqualTo(GestureMode.MENU)
    }

    @Test
    fun `a hold then movement is a drag`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.CLIP)
        assertThat(a.tick(520)).isEqualTo(GestureMode.LONG_PRESSED)
        assertThat(a.move(1, 104.0, 50.0, 540)).isEqualTo(GestureMode.LONG_PRESSED)
        assertThat(a.move(1, 160.0, 50.0, 600)).isEqualTo(GestureMode.DRAG)
        // A second finger after the long-press does not turn the drag into a pinch.
        assertThat(a.down(2, 250.0, 50.0, 650, GestureStart.CLIP)).isEqualTo(GestureMode.DRAG)
        a.up(2, 660)
        assertThat(a.up(1, 700)).isEqualTo(GestureMode.ENDED)
    }

    @Test
    fun `once held, a shake under twice the slop still opens the menu`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.HEADER)
        assertThat(a.tick(500)).isEqualTo(GestureMode.LONG_PRESSED)
        // 12 dp: past the 8 dp slop, under the 16 dp a held finger needs to drag.
        assertThat(a.move(1, 100.0, 62.0, 600)).isEqualTo(GestureMode.LONG_PRESSED)
        assertThat(a.up(1, 700)).isEqualTo(GestureMode.MENU)
        val b = arbiter()
        b.down(1, 100.0, 50.0, 0, GestureStart.HEADER)
        b.tick(500)
        assertThat(b.move(1, 100.0, 70.0, 600)).isEqualTo(GestureMode.DRAG)
        assertThat(b.up(1, 700)).isEqualTo(GestureMode.ENDED)
    }

    @Test
    fun `a row's empty space held then moved scrolls, and held then let go opens the menu`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.ROW)
        assertThat(a.tick(500)).isEqualTo(GestureMode.LONG_PRESSED)
        assertThat(a.move(1, 160.0, 50.0, 600)).isEqualTo(GestureMode.SCROLL)
        val b = arbiter()
        b.down(1, 100.0, 50.0, 0, GestureStart.ROW)
        b.tick(500)
        assertThat(b.up(1, 700)).isEqualTo(GestureMode.MENU)
    }

    @Test
    fun `handles beat items, so movement from a handle trims, never scrolls, and a hold does not lift`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.TRIM_END)
        assertThat(a.tick(700)).isEqualTo(GestureMode.PENDING)
        assertThat(a.move(1, 130.0, 50.0, 720)).isEqualTo(GestureMode.TRIM)
        // After the first 150 ms a second finger does not take the trim away.
        assertThat(a.down(2, 300.0, 50.0, 800, GestureStart.CLIP)).isEqualTo(GestureMode.TRIM)
    }

    @Test
    fun `a second finger within 150 ms of a trim makes it a pinch`() {
        val a = arbiter()
        a.down(1, 100.0, 50.0, 0, GestureStart.TRIM_START)
        a.move(1, 120.0, 50.0, 40)
        assertThat(a.mode).isEqualTo(GestureMode.TRIM)
        assertThat(a.down(2, 300.0, 50.0, 100, GestureStart.CLIP)).isEqualTo(GestureMode.PINCH)
    }

    @Test
    fun `movement on the ruler scrubs, and a tap there is a tap`() {
        val a = arbiter()
        a.down(1, 50.0, 10.0, 0, GestureStart.RULER)
        assertThat(a.move(1, 80.0, 10.0, 30)).isEqualTo(GestureMode.SCRUB)
        val b = arbiter()
        b.down(1, 50.0, 10.0, 0, GestureStart.RULER)
        assertThat(b.up(1, 90)).isEqualTo(GestureMode.TAP)
        assertThat(b.start).isEqualTo(GestureStart.RULER)
    }

    @Test
    fun `a quick release without movement is a tap on what was touched`() {
        val a = arbiter()
        a.down(1, 50.0, 10.0, 0, GestureStart.PLUS)
        assertThat(a.up(1, 120)).isEqualTo(GestureMode.TAP)
        assertThat(a.start).isEqualTo(GestureStart.PLUS)
        // A "+" is not long-pressable: a slow tap is still a tap.
        val b = arbiter()
        b.down(1, 50.0, 10.0, 0, GestureStart.PLUS)
        assertThat(b.tick(900)).isEqualTo(GestureMode.PENDING)
        assertThat(b.up(1, 950)).isEqualTo(GestureMode.TAP)
    }

    @Test
    fun `a long-press on a row header lifts it as on an item`() {
        val a = arbiter()
        a.down(1, 10.0, 80.0, 0, GestureStart.HEADER)
        assertThat(a.tick(500)).isEqualTo(GestureMode.LONG_PRESSED)
    }

    @Test
    fun `a cancel ends the gesture with nothing done, and the next down starts afresh`() {
        val a = arbiter()
        a.down(1, 10.0, 80.0, 0, GestureStart.CLIP)
        a.tick(600)
        assertThat(a.cancel()).isEqualTo(GestureMode.CANCELLED)
        assertThat(a.isActive).isFalse()
        assertThat(a.down(5, 10.0, 80.0, 1000, GestureStart.EMPTY)).isEqualTo(GestureMode.PENDING)
    }

    // ---------------------------------------------------------------- one way at a time (cm-timeline-scroll)

    private fun rowsArbiter() = TimelineGestureArbiter(touchSlopDp = 8.0, rowsScroll = true)

    @Test
    fun `an upright swipe scrolls the rows and a sideways one the time, decided once`() {
        val a = rowsArbiter()
        a.down(1, 100.0, 200.0, 0, GestureStart.ROW)
        assertThat(a.move(1, 104.0, 188.0, 40)).isEqualTo(GestureMode.SCROLL)
        assertThat(a.axis).isEqualTo(ScrollAxis.ROWS)
        // Turning sideways later does not change it.
        a.move(1, 180.0, 186.0, 80)
        assertThat(a.axis).isEqualTo(ScrollAxis.ROWS)
        a.up(1, 120)
        a.down(2, 100.0, 200.0, 200, GestureStart.CLIP)
        a.move(2, 112.0, 205.0, 240)
        assertThat(a.mode).isEqualTo(GestureMode.SCROLL)
        assertThat(a.axis).isEqualTo(ScrollAxis.TIME)
        a.move(2, 112.0, 120.0, 280)
        assertThat(a.axis).isEqualTo(ScrollAxis.TIME)
    }

    @Test
    fun `when the rows all fit every scroll moves the time`() {
        val a = arbiter()
        a.down(1, 100.0, 200.0, 0, GestureStart.HEADER)
        assertThat(a.move(1, 100.0, 150.0, 40)).isEqualTo(GestureMode.SCROLL)
        assertThat(a.axis).isEqualTo(ScrollAxis.TIME)
    }

    @Test
    fun `a trim handle trims unless the move is clearly upright and the rows scroll`() {
        val a = rowsArbiter()
        a.down(1, 100.0, 200.0, 0, GestureStart.TRIM_END)
        // More up than sideways, but not twice: still a trim.
        assertThat(a.move(1, 112.0, 180.0, 40)).isEqualTo(GestureMode.TRIM)
        a.up(1, 80)
        a.down(2, 100.0, 200.0, 200, GestureStart.TRIM_START)
        assertThat(a.move(2, 103.0, 180.0, 240)).isEqualTo(GestureMode.SCROLL)
        assertThat(a.axis).isEqualTo(ScrollAxis.ROWS)
        a.up(2, 280)
        // Rows that all fit: the same upright move trims.
        val b = arbiter()
        b.down(3, 100.0, 200.0, 0, GestureStart.TRIM_START)
        assertThat(b.move(3, 103.0, 180.0, 40)).isEqualTo(GestureMode.TRIM)
    }

    @Test
    fun `long-press drags and the ruler scrub are not taken by the rows' scroll`() {
        val a = rowsArbiter()
        a.down(1, 100.0, 200.0, 0, GestureStart.CLIP)
        assertThat(a.tick(500)).isEqualTo(GestureMode.LONG_PRESSED)
        assertThat(a.move(1, 100.0, 160.0, 560)).isEqualTo(GestureMode.DRAG)
        a.up(1, 600)
        a.down(2, 100.0, 10.0, 700, GestureStart.RULER)
        assertThat(a.move(2, 100.0, 60.0, 740)).isEqualTo(GestureMode.SCRUB)
        a.up(2, 780)
        // A row's empty space held, then moved up: it scrolls the rows, as it scrolled before.
        a.down(3, 100.0, 200.0, 800, GestureStart.ROW)
        a.tick(1300)
        assertThat(a.move(3, 100.0, 150.0, 1340)).isEqualTo(GestureMode.SCROLL)
        assertThat(a.axis).isEqualTo(ScrollAxis.ROWS)
    }

    @Test
    fun `a second finger still pinches out of an upright scroll`() {
        val a = rowsArbiter()
        a.down(1, 100.0, 200.0, 0, GestureStart.ROW)
        a.move(1, 100.0, 170.0, 40)
        assertThat(a.down(2, 200.0, 200.0, 80, GestureStart.ROW)).isEqualTo(GestureMode.PINCH)
    }
}
