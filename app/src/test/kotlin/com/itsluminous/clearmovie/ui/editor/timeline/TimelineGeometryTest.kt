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
            SlotSpan("end", 11_000, 13_000, content = false),
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
    fun `nothing selected, one plus at the end of the row and none between items`() {
        // 10 ms per dp: every item is at least 200 dp wide; at 100 ms per dp, 30 dp.
        for (scale in listOf(10.0, 100.0)) {
            val plus = geometry(scale, playheadMs = 5000.0).plusPoints()
            // After the last item (c), never after the ending.
            assertThat(plus.map { it.anchorIndex to it.side }).containsExactly(3 to InsertSide.AFTER)
            assertThat(plus.single().xDp).isWithin(1e-9).of(11_000 / scale)
        }
    }

    @Test
    fun `an empty MAIN, only the intro and ending, has one plus after the intro`() {
        val g =
            TimelineGeometry(
                listOf(SlotSpan("intro", 0, 2000, content = false), SlotSpan("end", 2000, 4000, content = false)),
                ZoomState(10.0, 0.0, false),
                2000.0,
                28.0,
                72.0,
                selected = null,
                playheadMs = 0.0,
                selectedTrimmable = false,
            )
        assertThat(g.plusPoints().map { it.anchorIndex to it.side }).containsExactly(0 to InsertSide.AFTER)
    }

    @Test
    fun `selecting an item shows a plus on its two sides only, and moving the selection moves them`() {
        // Zoomed out further, the selected item's "+" can sit where the end one would, which then gives way.
        for (scale in listOf(10.0, 20.0)) {
            for (sel in listOf(1, 2)) {
                val plus = geometry(scale, selected = sel, playheadMs = -1e6).plusPoints()
                val sides = plus.filter { it.anchorIndex == sel }.map { it.side }
                assertThat(sides).containsExactly(InsertSide.BEFORE, InsertSide.AFTER)
                // Besides those, only the row's end "+" (after c).
                assertThat(plus.filter { it.anchorIndex != sel }.map { it.anchorIndex to it.side }).containsExactly(3 to InsertSide.AFTER)
            }
            // The last item selected: its right "+" is also the end one.
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
}
