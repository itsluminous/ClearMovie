package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/** "+" placement, handles and hit-testing on MAIN (ARCHITECTURE sections 11.5 and 11.6). */
class TimelineGeometryTest {
    // Intro 2 s, three items of 3 s, ending 2 s: 13 s.
    private val spans =
        listOf(
            SlotSpan("intro", 0, 2000, content = false),
            SlotSpan("a", 2000, 5000, content = true),
            SlotSpan("b", 5000, 8000, content = true),
            SlotSpan("c", 8000, 11_000, content = true),
            SlotSpan("end", 11_000, 13_000, content = false, ending = true),
        )

    private fun geometry(
        msPerDp: Double,
        selected: Int? = null,
        playheadMs: Double = 0.0,
        trimmable: Boolean = selected != null,
        scroll: Double = 0.0,
    ) = TimelineGeometry(
        spans = spans,
        zoom = ZoomState(msPerDp, scroll, fitAll = false),
        widthDp = 2000.0,
        rulerDp = 28.0,
        mainRowDp = 72.0,
        selected = selected,
        playheadMs = playheadMs,
        selectedTrimmable = trimmable,
    )

    @Test
    fun `nothing selected, no plus on the row itself and none between items`() {
        // cm-plus-position: MAIN's own "+" is in the column at its right end (RowEndPlus), not after its last item.
        for (scale in listOf(10.0, 100.0)) {
            assertThat(geometry(scale, playheadMs = 5000.0).plusPoints()).isEmpty()
        }
    }

    @Test
    fun `an empty MAIN, only the intro and ending, has its end plus after the intro`() {
        val g =
            TimelineGeometry(
                listOf(SlotSpan("intro", 0, 2000, content = false), SlotSpan("end", 2000, 4000, content = false, ending = true)),
                ZoomState(10.0, 0.0, false),
                2000.0,
                28.0,
                72.0,
                selected = null,
                playheadMs = 0.0,
                selectedTrimmable = false,
            )
        assertThat(g.plusPoints()).isEmpty()
        // The row's own "+" in the end column adds between the intro and the ending, wherever the cursor is
        // (on the ending it says "before this Ending", the same place).
        for (cursor in listOf(0L, 1000L, 2000L, 3000L, 4000L)) {
            val t = requireNotNull(RowEndPlus.main(g.spans, cursor))
            assertThat(t.anchorIndex + if (t.side == InsertSide.AFTER) 1 else 0).isEqualTo(1)
        }
    }

    @Test
    fun `selecting an item shows a plus on its two sides only, and moving the selection moves them`() {
        for (scale in listOf(10.0, 20.0)) {
            for (sel in listOf(1, 2)) {
                val plus = geometry(scale, selected = sel, playheadMs = -1e6).plusPoints()
                // Only those two: the row's own "+" in the end column hides on the selected item's row.
                assertThat(plus.map { it.anchorIndex to it.side }).containsExactly(sel to InsertSide.BEFORE, sel to InsertSide.AFTER)
            }
            val last = geometry(scale, selected = 3, playheadMs = -1e6).plusPoints()
            assertThat(last.map { it.anchorIndex to it.side }).containsExactly(3 to InsertSide.BEFORE, 3 to InsertSide.AFTER)
        }
    }

    @Test
    fun `plus and trim handle areas never meet, at the narrowest and widest zoom and at both ends of the view`() {
        // The narrowest (close-up, about 4 ms per dp) and widest (fit all of 13 s in 300 dp) scales,
        // with the first and the last item selected, at the start and the end of the view.
        val widest = 13_000 / 300.0
        for (scale in listOf(TimelineZoom.closeUpMsPerDp, 10.0, widest)) {
            for (sel in listOf(1, 3)) {
                for (scroll in listOf(0.0, 9000.0)) {
                    val g =
                        TimelineGeometry(
                            spans,
                            ZoomState(scale, scroll, false),
                            300.0,
                            28.0,
                            72.0,
                            selected = sel,
                            playheadMs = -1e6,
                            selectedTrimmable = true,
                        )
                    val (start, end) = g.handleHitAreas()!!
                    for (p in g.plusPoints()) {
                        val lo = p.xDp - TimelineGeometry.PLUS_HIT_DP / 2
                        val hi = p.xDp + TimelineGeometry.PLUS_HIT_DP / 2
                        assertThat(hi <= start.start || lo >= start.endInclusive).isTrue()
                        assertThat(hi <= end.start || lo >= end.endInclusive).isTrue()
                        // And a touch on either handle is the handle, never a "+".
                        assertThat(g.hitTest((start.start + start.endInclusive) / 2, g.mainCentre)).isEqualTo(HitTarget.TrimStart(sel))
                        assertThat(g.hitTest((end.start + end.endInclusive) / 2, g.mainCentre)).isEqualTo(HitTarget.TrimEnd(sel))
                    }
                }
            }
        }
    }

    @Test
    fun `on the selected item the plus moves off the trim handles, at least 12 dp clear`() {
        val g = geometry(10.0, selected = 2, playheadMs = -1e6)
        val (start, end) = g.handleHitAreas()!!
        val plus = g.plusPoints()
        val before = plus.single { it.boundary == 2 }
        val after = plus.single { it.boundary == 3 }
        assertThat(before.displaced).isTrue()
        assertThat(after.displaced).isTrue()
        val half = TimelineGeometry.PLUS_HIT_DP / 2
        // The plus hit areas end at least 12 dp before the handles' hit areas start.
        assertThat(start.start - (before.xDp + half)).isAtLeast(TimelineGeometry.HANDLE_GAP_DP - 1e-9)
        assertThat((after.xDp - half) - end.endInclusive).isAtLeast(TimelineGeometry.HANDLE_GAP_DP - 1e-9)
        // And they still insert right next to the selected item.
        assertThat(before.anchorIndex to before.side).isEqualTo(2 to InsertSide.BEFORE)
        assertThat(after.anchorIndex to after.side).isEqualTo(2 to InsertSide.AFTER)
    }

    @Test
    fun `no two plus hit areas ever overlap, at any zoom, and the selected item's win`() {
        for (scale in listOf(4.0, 10.0, 20.0, 40.0, 47.0, 60.0, 100.0, 300.0)) {
            for (sel in listOf(null, 1, 2, 3)) {
                val plus = geometry(scale, selected = sel, playheadMs = 5000.0).plusPoints()
                for (i in plus.indices) {
                    for (j in i + 1 until plus.size) {
                        assertThat(abs(plus[i].xDp - plus[j].xDp)).isAtLeast(TimelineGeometry.PLUS_HIT_DP)
                    }
                }
                if (sel != null) assertThat(plus.count { it.anchorIndex == sel && it.displaced }).isAtLeast(1)
            }
        }
    }

    @Test
    fun `a plus never sits on a trim handle hit area`() {
        for (scale in listOf(4.0, 10.0, 25.0, 50.0, 100.0)) {
            val g = geometry(scale, selected = 2, playheadMs = 5000.0)
            val (start, end) = g.handleHitAreas()!!
            for (p in g.plusPoints()) {
                val lo = p.xDp - TimelineGeometry.PLUS_HIT_DP / 2
                val hi = p.xDp + TimelineGeometry.PLUS_HIT_DP / 2
                assertThat(hi <= start.start || lo >= start.endInclusive).isTrue()
                assertThat(hi <= end.start || lo >= end.endInclusive).isTrue()
            }
        }
    }

    @Test
    fun `with a narrow neighbour, no plus lands on the selected item's handles`() {
        // Item 2 selected; item 3 is narrow (20 dp) and the playhead sits on the boundary after it,
        // 20 dp from item 2's end handle: that "+" would cover the handle, so it is not drawn.
        val narrow =
            listOf(
                SlotSpan("intro", 0, 2000, content = false),
                SlotSpan("a", 2000, 5000, content = true),
                SlotSpan("b", 5000, 8000, content = true),
                SlotSpan("c", 8000, 8200, content = true),
                SlotSpan("d", 8200, 11_000, content = true),
            )
        val g =
            TimelineGeometry(
                narrow,
                ZoomState(10.0, 0.0, false),
                2000.0,
                28.0,
                72.0,
                selected = 2,
                playheadMs = 8200.0,
                selectedTrimmable = true,
            )
        val handles = g.handleHitAreas()!!.toList()
        val half = TimelineGeometry.PLUS_HIT_DP / 2 + TimelineGeometry.HANDLE_GAP_DP
        for (p in g.plusPoints()) {
            for (h in handles) assertThat(p.xDp + half <= h.start + 1e-9 || p.xDp - half >= h.endInclusive - 1e-9).isTrue()
        }
        // The boundary after the narrow item (x 820) is 20 dp past the end handle: no "+" there.
        assertThat(g.plusPoints().map { it.boundary }).doesNotContain(4)
        assertThat(g.hitTest(795.0, g.mainCentre)).isEqualTo(HitTarget.TrimEnd(2))
    }

    @Test
    fun `the intro and ending have no handles, so their plus points stay on the boundary`() {
        val g = geometry(10.0, selected = 0, trimmable = false, playheadMs = -1e6)
        assertThat(g.handleHitAreas()).isNull()
        val after = g.plusPoints().single { it.boundary == 1 }
        assertThat(after.displaced).isFalse()
        assertThat(after.xDp).isEqualTo(200.0)
    }

    @Test
    fun `hit testing ranks plus, then handles, then items, then empty`() {
        val g = geometry(10.0, selected = 2, playheadMs = -1e6)
        val y = g.mainCentre
        assertThat(g.hitTest(10.0, 5.0)).isEqualTo(HitTarget.Ruler)
        // On the start handle of item 2 (x 500..524), and its outer part.
        assertThat(g.hitTest(505.0, y)).isEqualTo(HitTarget.TrimStart(2))
        assertThat(g.hitTest(492.0, y)).isEqualTo(HitTarget.TrimStart(2))
        assertThat(g.hitTest(795.0, y)).isEqualTo(HitTarget.TrimEnd(2))
        // The moved plus before item 2.
        val before = g.plusPoints().single { it.boundary == 2 }
        assertThat(g.hitTest(before.xDp, y)).isEqualTo(HitTarget.Plus(before))
        // The middle of an item.
        assertThat(g.hitTest(650.0, y)).isEqualTo(HitTarget.Clip(2))
        assertThat(g.hitTest(350.0, y)).isEqualTo(HitTarget.Clip(1))
        // Past the end of the video.
        assertThat(g.hitTest(1900.0, y)).isEqualTo(HitTarget.Empty)
    }

    @Test
    fun `a dragged item lands among the others by the x of its centre`() {
        val zoom = ZoomState(10.0, 0.0, fitAll = false)
        // Item 1 (centre 350) dragged right past the middle of item 2 (650): content index 1.
        assertThat(TimelineGeometry.dropIndex(spans, 1, 700.0, zoom)).isEqualTo(1)
        // Past item 3's middle (950): last.
        assertThat(TimelineGeometry.dropIndex(spans, 1, 1000.0, zoom)).isEqualTo(2)
        // Item 3 dragged to the far left: first.
        assertThat(TimelineGeometry.dropIndex(spans, 3, 0.0, zoom)).isEqualTo(0)
    }

    @Test
    fun `visible items follow the scroll`() {
        val g = geometry(10.0, scroll = 6000.0)
        assertThat(g.visible()).isEqualTo(2..4)
    }

    // cm-timeline-start-gap: only the playhead's dot is kept between the row icons and 0:00.
    private val noIntro =
        listOf(
            SlotSpan("a", 0, 3000, content = true),
            SlotSpan("b", 3000, 6000, content = true),
        )

    private fun firstSelected(scroll: Double) =
        TimelineGeometry(noIntro, ZoomState(10.0, scroll, fitAll = false), 600.0, 28.0, 72.0, 0, 0.0, selectedTrimmable = true)

    @Test
    fun `the start room is only the playhead dot, so 0 00 sits 6 dp from the row icons`() {
        assertThat(TimelineGeometry.START_ROOM_DP).isEqualTo(6.0)
        // The rows' own "+" still floats in the room after the end.
        assertThat(TimelineGeometry.END_ROOM_DP).isEqualTo(28.0)
    }

    @Test
    fun `a first item at 0 00 keeps a 48 dp start handle, all of it right of the row icons`() {
        val g = firstSelected(scroll = 0.0)
        val (start, _) = requireNotNull(g.handleHitAreas())
        assertThat(start.endInclusive - start.start).isWithin(1e-9).of(TimelineGeometry.HANDLE_HIT_DP)
        assertThat(start.start).isAtLeast(-TimelineGeometry.START_ROOM_DP - 1e-9)
        // It still covers the whole drawn handle.
        assertThat(start.start).isAtMost(0.0)
        assertThat(start.endInclusive).isAtLeast(TimelineGeometry.HANDLE_DP)
        // A touch on the room before 0:00 takes the handle.
        assertThat(g.hitTest(-5.0, 28.0 + 36.0)).isEqualTo(HitTarget.TrimStart(0))
        assertThat(g.hitTest(40.0, 28.0 + 36.0)).isEqualTo(HitTarget.TrimStart(0))
    }

    @Test
    fun `a start handle away from the edge keeps its usual area, and one off screen is never moved onto it`() {
        assertThat(TimelineGeometry.startHandleArea(100.0)).isEqualTo(88.0..136.0)
        // Moved in by at most the 12 dp outside the item: a handle scrolled off the left stays off it.
        assertThat(TimelineGeometry.startHandleArea(-100.0)).isEqualTo(-100.0..-52.0)
        assertThat(TimelineGeometry.startHandleArea(-6.0)).isEqualTo(-6.0..42.0)
    }

    @Test
    fun `the first item's plus before it shows once the view moves past the start, clear of its handle`() {
        // At scroll 0 its "+" would be cut by the row icons, so it is not drawn (TimelineView then moves the view).
        assertThat(firstSelected(scroll = 0.0).plusPoints().map { it.side }).containsExactly(InsertSide.AFTER)
        // TimelineView's nudge: the item's edge goes this far in from the span's start.
        val room = TimelineGeometry.DISPLACE_DP + TimelineGeometry.PLUS_HIT_DP / 2 - TimelineGeometry.START_ROOM_DP
        val nudged = firstSelected(scroll = -room * 10.0)
        val before = nudged.plusPoints().first { it.side == InsertSide.BEFORE }
        assertThat(before.xDp - TimelineGeometry.PLUS_HIT_DP / 2).isAtLeast(-TimelineGeometry.START_ROOM_DP - 1e-9)
        val (start, _) = requireNotNull(nudged.handleHitAreas())
        assertThat(start.start - (before.xDp + TimelineGeometry.PLUS_HIT_DP / 2)).isAtLeast(TimelineGeometry.HANDLE_GAP_DP - 1e-9)
    }

    @Test
    fun `a short selected intro gets its plus after it once the view moves past the start`() {
        // A 2 s intro at 216 ms a dp (a long video at fit all on a small phone): 9.3 dp wide.
        val intro = listOf(SlotSpan("intro", 0, 2000, content = false), SlotSpan("a", 2000, 60_000, content = true))

        fun g(scroll: Double) = TimelineGeometry(intro, ZoomState(216.0, scroll, fitAll = false), 278.0, 28.0, 72.0, 0, 0.0, false)
        // Its end within half a "+" of the start: the "+" after it would be cut by the row icons, so it is left out...
        assertThat(g(0.0).plusPoints()).isEmpty()
        // ...until TimelineView moves the view so its end is that far in (the "+" then reaches just to the icons).
        val introEnd = TimelineGeometry.PLUS_HIT_DP / 2 - TimelineGeometry.START_ROOM_DP
        val nudged = g(-(introEnd - 2000 / 216.0) * 216.0).plusPoints()
        assertThat(nudged.map { it.side }).containsExactly(InsertSide.AFTER)
        assertThat(nudged.single().xDp - TimelineGeometry.PLUS_HIT_DP / 2).isWithin(1e-6).of(-TimelineGeometry.START_ROOM_DP)
    }

    @Test
    fun `a transition marker near 0 00 moves right so its 48 dp touch area is clear of the row icons`() {
        val clear = TimelineGeometry.MARKER_HIT_DP / 2 - TimelineGeometry.START_ROOM_DP
        assertThat(TimelineGeometry.markerX(4.0)).isEqualTo(clear)
        assertThat(TimelineGeometry.markerX(-6.0)).isEqualTo(clear)
        // Away from the start, where its overlap is; scrolled off, it goes off with it (moved at most 24 dp).
        assertThat(TimelineGeometry.markerX(30.0)).isEqualTo(30.0)
        assertThat(TimelineGeometry.markerX(-30.0)).isEqualTo(-6.0)
        // A 3 s first photo with a 0.5 s crossfade into the next, at 100 ms a dp: the overlap's middle is at 27.5 dp, kept.
        val spans = listOf(SlotSpan("a", 0, 3000, content = true), SlotSpan("b", 2500, 6000, content = true))
        val g = TimelineGeometry(spans, ZoomState(100.0, 0.0, fitAll = false), 600.0, 28.0, 72.0, null, 0.0, false)
        assertThat(g.markers().single().xDp).isWithin(1e-9).of(27.5)
        // The same at 600 ms a dp: the middle at 4.6 dp, drawn at 18 dp, hit there.
        val far = TimelineGeometry(spans, ZoomState(600.0, 0.0, fitAll = false), 600.0, 28.0, 72.0, null, 0.0, false)
        val m = far.markers().single()
        assertThat(m.xDp).isEqualTo(clear)
        assertThat(far.hitTest(-5.0, far.markerCentre)).isEqualTo(HitTarget.Marker(m))
    }

    @Test
    fun `of two markers in the start zone, the one with its whole touch area clear of the icons stays`() {
        // Two junctions 14 dp apart near the start.
        val close =
            listOf(
                SlotSpan("a", 0, 1000, content = true),
                SlotSpan("b", 800, 1700, content = true),
                SlotSpan("c", 1500, 6000, content = true),
            )
        // At 50 ms a dp, scrolled to 1.4 s: middles at (900 - 1400) / 50 = -10 dp and (1600 - 1400) / 50 = 4 dp.
        val h = TimelineGeometry(close, ZoomState(50.0, 1400.0, fitAll = false), 600.0, 28.0, 72.0, null, 0.0, false)
        // Both map near 18 (14 and 18 dp): too close; the later one, whose 48 dp is all clear of the icons, stays.
        assertThat(TimelineGeometry.markerX(-10.0)).isWithin(1e-9).of(14.0)
        assertThat(h.markers().map { it.boundary }).containsExactly(2)
        assertThat(h.markers().single().xDp).isWithin(1e-9).of(18.0)
    }
}
