package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * The transition marker and the "+" at a junction (ARCHITECTURE sections
 * 11.6 and 11.10): the marker sits in the middle of the overlap on the row's
 * centre line, the "+" moves up to the row's top edge, and their hit areas
 * never overlap, at any zoom.
 */
class TransitionMarkerGeometryTest {
    // Intro 2 s, a, b, c of 3 s; a 1 s transition a->b and a 0.5 s one b->c; ending 2 s.
    private val spans =
        listOf(
            SlotSpan("intro", 0, 2000, content = false),
            SlotSpan("a", 2000, 5000, content = true),
            SlotSpan("b", 4000, 7000, content = true),
            SlotSpan("c", 6500, 9500, content = true),
            SlotSpan("end", 9500, 11_500, content = false, ending = true),
        )

    private fun geometry(
        msPerDp: Double,
        selected: Int? = null,
    ) = TimelineGeometry(
        spans = spans,
        zoom = ZoomState(msPerDp, 0.0, fitAll = false),
        widthDp = 2000.0,
        rulerDp = 28.0,
        mainRowDp = 72.0,
        selected = selected,
        playheadMs = -10_000.0,
        selectedTrimmable = selected != null,
    )

    private data class Box(
        val cx: Double,
        val cy: Double,
        val size: Double,
    ) {
        fun overlaps(o: Box) = abs(cx - o.cx) < (size + o.size) / 2 && abs(cy - o.cy) < (size + o.size) / 2
    }

    @Test
    fun `a marker sits in the middle of each overlap, and its plus is lifted`() {
        val g = geometry(10.0)
        val markers = g.markers()
        assertThat(markers.map { it.boundary }).containsExactly(2, 3).inOrder()
        assertThat(markers[0].leadIndex).isEqualTo(1)
        assertThat(markers[0].xDp).isWithin(1e-9).of(g.zoom.xOf(4500.0))
        assertThat(markers[1].xDp).isWithin(1e-9).of(g.zoom.xOf(6750.0))
        // Nothing selected: no "+" on the row itself (its own "+" is in the column at its right end).
        assertThat(g.plusPoints()).isEmpty()
        assertThat(g.markerCentre + TimelineGeometry.MARKER_HIT_DP / 2).isAtMost(g.mainBottom)
    }

    @Test
    fun `a marker never reaches into the plus column at the row's right end`() {
        // 10 ms per dp: the a->b marker is at 450 dp and the b->c one at 675 dp.
        for (width in listOf(474.0, 476.0, 700.0, 2000.0)) {
            val g = TimelineGeometry(spans, ZoomState(10.0, 0.0, false), width, 28.0, 72.0, null, -1e4, selectedTrimmable = false)
            for (m in g.markers()) assertThat(m.xDp + TimelineGeometry.MARKER_HIT_DP / 2).isAtMost(width)
            // A marker that is kept is still tapped; one left out is opened from the bar's Transition.
            assertThat(g.markers().size).isEqualTo(listOf(450.0, 675.0).count { it + TimelineGeometry.MARKER_HIT_DP / 2 <= width })
        }
    }

    @Test
    fun `a plus that would touch a marker moves up to the row's top edge`() {
        // The intro (no trim handles) selected, with a 0.5 s transition into a: its "+" after it sits on the
        // junction, next to the marker in the middle of the overlap, so it goes up to the top edge.
        val withIntroTransition =
            listOf(
                SlotSpan("intro", 0, 2000, content = false),
                SlotSpan("a", 1500, 4500, content = true),
                SlotSpan("b", 4500, 7500, content = true),
            )
        val g =
            TimelineGeometry(withIntroTransition, ZoomState(10.0, 0.0, false), 2000.0, 28.0, 72.0, 0, -1e4, selectedTrimmable = false)
        val marker = g.markers().single()
        val after = g.plusPoints().single { it.anchorIndex == 0 && it.side == InsertSide.AFTER }
        assertThat(after.top).isTrue()
        assertThat(g.plusCentreY(after)).isEqualTo(g.mainTop)
        assertThat(abs(after.xDp - marker.xDp)).isLessThan((TimelineGeometry.PLUS_HIT_DP + TimelineGeometry.MARKER_HIT_DP) / 2)
        // Their hit areas touch and never overlap.
        val p = Box(after.xDp, g.plusCentreY(after), TimelineGeometry.PLUS_HIT_DP)
        val m = Box(marker.xDp, g.markerCentre, TimelineGeometry.MARKER_HIT_DP)
        assertThat(p.overlaps(m)).isFalse()
    }

    @Test
    fun `marker and plus hit areas never overlap, at three zoom levels and with a selection`() {
        for (msPerDp in listOf(4.0, 20.0, 60.0)) {
            for (selected in listOf(null, 1, 2)) {
                val g = geometry(msPerDp, selected)
                val boxes =
                    g.markers().map { Box(it.xDp, g.markerCentre, TimelineGeometry.MARKER_HIT_DP) } +
                        g.plusPoints().map { Box(it.xDp, g.plusCentreY(it), TimelineGeometry.PLUS_HIT_DP) }
                for (i in boxes.indices) {
                    for (j in i + 1 until boxes.size) {
                        assertThat(boxes[i].overlaps(boxes[j])).isFalse()
                    }
                }
                // What a tap on each hits is that one thing.
                for (m in g.markers()) {
                    assertThat(g.hitTest(m.xDp, g.markerCentre)).isEqualTo(HitTarget.Marker(m))
                }
                for (p in g.plusPoints()) {
                    assertThat(g.hitTest(p.xDp, g.plusCentreY(p))).isEqualTo(HitTarget.Plus(p))
                }
            }
        }
    }

    @Test
    fun `a marker gives way to the selected item's trim handles, and to a neighbour too close`() {
        // Zoomed far out the two markers are 22.5 dp apart: only the first is drawn.
        assertThat(geometry(100.0).markers().map { it.boundary }).containsExactly(2)
        // Item b selected at a close zoom: its handles sit on both overlaps, so no marker crowds them.
        val g = geometry(4.0, selected = 2)
        val handles = g.handleHitAreas()!!.toList()
        for (m in g.markers()) {
            for (h in handles) {
                assertThat(
                    m.xDp + TimelineGeometry.MARKER_HIT_DP / 2 <= h.start || m.xDp - TimelineGeometry.MARKER_HIT_DP / 2 >= h.endInclusive,
                ).isTrue()
            }
        }
    }

    @Test
    fun `junctions without a transition have no marker`() {
        val plain =
            TimelineGeometry(
                spans = listOf(SlotSpan("a", 0, 3000, true), SlotSpan("b", 3000, 6000, true)),
                zoom = ZoomState(10.0, 0.0, fitAll = false),
                widthDp = 2000.0,
                rulerDp = 28.0,
                mainRowDp = 72.0,
                selected = null,
                playheadMs = 0.0,
                selectedTrimmable = false,
            )
        assertThat(plain.markers()).isEmpty()
        assertThat(plain.plusPoints().none { it.top }).isTrue()
    }
}
