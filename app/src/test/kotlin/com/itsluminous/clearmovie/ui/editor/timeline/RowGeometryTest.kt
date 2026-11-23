package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.ResizeRange
import org.junit.Test

/** The "+" points on rows other than MAIN, and where a row dragged by its icon lands (cm-tracks). */
class RowGeometryTest {
    /** 10 ms per dp: a 1 s item is 100 dp wide. */
    private val zoom = ZoomState(msPerDp = 10.0, scrollMs = 0.0, fitAll = false)

    private fun clip(
        id: String,
        start: Long,
        end: Long,
    ) = RowClip(ClipId(id), start, end, RowClipKind.TEXT, id)

    private fun plus(
        clips: List<RowClip>,
        selected: String? = null,
        playheadMs: Double = -10_000.0,
        z: ZoomState = zoom,
    ) = TimedRowGeometry.plusPoints(clips, z, selected?.let(::ClipId))

    @Test
    fun `nothing selected, no plus on the row itself, its plus is in the column at its end`() {
        // cm-plus-position: the row's own "+" is drawn in the column at its right end (RowEndPlus), not after its last item.
        assertThat(plus(listOf(clip("a", 0, 1000), clip("b", 3000, 4000)))).isEmpty()
        assertThat(plus(emptyList())).isEmpty()
    }

    @Test
    fun `the selected item gets a plus on each side, just outside it, and no other item does`() {
        val clips = listOf(clip("a", 1000, 2000), clip("b", 2000, 3000), clip("c", 3500, 3800))
        val sel = plus(clips, selected = "a")
        assertThat(sel.map { it.anchor.value to it.side })
            .containsExactly("a" to InsertSide.BEFORE, "a" to InsertSide.AFTER)
            .inOrder()
        // Just outside the item: its own area stays the item's.
        assertThat(sel[0].xDp + TimelineGeometry.PLUS_HIT_DP / 2).isWithin(1e-9).of(100.0)
        assertThat(sel[1].xDp - TimelineGeometry.PLUS_HIT_DP / 2).isWithin(1e-9).of(200.0)
        // A narrow selected item (30 dp): both, outside it, and the item is still hit between them.
        val narrow = plus(clips, selected = "c")
        assertThat(narrow.map { it.anchor.value to it.side }).containsExactly("c" to InsertSide.BEFORE, "c" to InsertSide.AFTER).inOrder()
        assertThat(TimedRowGeometry.hitTest(clips, narrow, zoom, 365.0)).isEqualTo(TimedHit.Clip(ClipId("c")))
        // Hit areas never overlap.
        for (p in listOf(sel, narrow)) assertThat(p.zipWithNext().all { (l, r) -> r.xDp - l.xDp >= TimelineGeometry.PLUS_HIT_DP }).isTrue()
    }

    @Test
    fun `a touch hits a plus first, then an item, then the row's empty space`() {
        val clips = listOf(clip("a", 1000, 3000))
        val points = plus(clips, selected = "a")
        // The "+" before the item, outside it (x 76).
        assertThat(TimedRowGeometry.hitTest(clips, points, zoom, 80.0)).isEqualTo(TimedHit.Plus(points.first()))
        // Kept whole on screen: an item at the very start has its "+" pulled in to the padding's edge.
        val atStart = TimedRowGeometry.plusPoints(listOf(clip("s", 0, 1000)), zoom, ClipId("s"), 300.0)
        assertThat(atStart.first().xDp - TimelineGeometry.PLUS_HIT_DP / 2).isAtLeast(-TimelineGeometry.START_ROOM_DP - 1e-9)
        assertThat(atStart.last().xDp + TimelineGeometry.PLUS_HIT_DP / 2).isAtMost(300.0 + TimelineGeometry.END_ROOM_DP + 1e-9)
        // At the end, its "+" after it stays whole inside the span's padding.
        val atEnd = TimedRowGeometry.plusPoints(listOf(clip("e", 2000, 2950)), zoom, ClipId("e"), 300.0)
        assertThat(atEnd.last().side).isEqualTo(InsertSide.AFTER)
        assertThat(atEnd.last().xDp + TimelineGeometry.PLUS_HIT_DP / 2).isAtMost(300.0 + TimelineGeometry.END_ROOM_DP + 1e-9)
        assertThat(TimedRowGeometry.hitTest(clips, points, zoom, 200.0)).isEqualTo(TimedHit.Clip(ClipId("a")))
        // Inside the item, past the "+" next to it: the item.
        assertThat(TimedRowGeometry.hitTest(clips, points, zoom, 104.0)).isEqualTo(TimedHit.Clip(ClipId("a")))
        assertThat(TimedRowGeometry.hitTest(clips, points, zoom, 600.0)).isEqualTo(TimedHit.Space)
    }

    @Test
    fun `a row dragged down two rows lands after them, folded rows keep their places`() {
        val bands = RowOrder.bands(listOf("t" to 48.0, "f" to 48.0, "m" to 72.0, "a" to 48.0), 8.0)
        assertThat(bands.map { it.topDp }).containsExactly(0.0, 56.0, 112.0, 192.0).inOrder()
        // Down by two rows' worth: past "f" and "m".
        assertThat(RowOrder.dropShownIndex(bands, "t", 140.0)).isEqualTo(2)
        assertThat(RowOrder.dropShownIndex(bands, "t", 3.0)).isEqualTo(0)
        assertThat(RowOrder.dropShownIndex(bands, "a", -200.0)).isEqualTo(0)
        // All rows shown: the index in the full order is the shown one.
        val all = listOf("t", "f", "m", "a")
        assertThat(RowOrder.fullIndex(all, all, "t", 2)).isEqualTo(2)
        // "x" is folded away between "f" and "m": putting "t" after "m" keeps "x" where it is.
        val withHidden = listOf("t", "f", "x", "m", "a")
        assertThat(RowOrder.fullIndex(withHidden, all, "t", 2)).isEqualTo(3)
        assertThat(RowOrder.fullIndex(withHidden, all, "t", 3)).isEqualTo(4)
        assertThat(RowOrder.fullIndex(withHidden, all, "a", 0)).isEqualTo(0)
    }

    @Test
    fun `the bar for a row and for items on it`() {
        val row =
            RowFacts(
                isMain = false,
                isVisual = true,
                isSound = false,
                isMusic = false,
                isPresetFrame = false,
                removable = true,
                canMoveUp = false,
                canMoveDown = true,
                canGoForward = false,
                canGoBackward = true,
                muted = false,
                enabled = true,
                lowerWhenTalking = true,
            )
        val text = ClipActions.forRow(row)
        assertThat(text.map { it.action })
            .containsExactly(
                EditAction.ADD_HERE,
                EditAction.SHOW_ON_TOP,
                EditAction.BRING_FORWARD,
                EditAction.SEND_BACKWARD,
                EditAction.SHOW_AT_BOTTOM,
                EditAction.MOVE_ROW_UP,
                EditAction.MOVE_ROW_DOWN,
                EditAction.REMOVE_ROW,
            ).inOrder()
        // On top already: Show on top and Bring forward are off; at the top of the screen, Move row up is.
        assertThat(text.filter { !it.enabled }.map { it.action })
            .containsExactly(EditAction.SHOW_ON_TOP, EditAction.BRING_FORWARD, EditAction.MOVE_ROW_UP)
        val music = ClipActions.forRow(row.copy(isVisual = false, isSound = true, isMusic = true, muted = true))
        assertThat(music.map { it.action }).containsAtLeast(EditAction.MUTE_ROW, EditAction.LOWER_MUSIC)
        assertThat(music.first { it.action == EditAction.MUTE_ROW }.checked).isTrue()
        assertThat(music.map { it.action }).doesNotContain(EditAction.SHOW_ON_TOP)
        val main = ClipActions.forRow(row.copy(isMain = true, removable = false))
        assertThat(main.first().action).isEqualTo(EditAction.ADD_MEDIA)
        assertThat(main.map { it.action }).doesNotContain(EditAction.REMOVE_ROW)

        val facts = TimedFacts(canMoveEarlier = false, canMoveLater = true, fixed = false)
        assertThat(ClipActions.forTimed(SelectionKind.TEXT, facts).map { it.action })
            .containsExactly(
                EditAction.EDIT_TEXT,
                EditAction.ANIMATION,
                EditAction.MOVE_EARLIER,
                EditAction.MOVE_LATER,
                EditAction.MOVE_TO_ROW,
                EditAction.DELETE,
            ).inOrder()
        assertThat(ClipActions.forTimed(SelectionKind.TEXT, facts).first { it.action == EditAction.MOVE_EARLIER }.enabled).isFalse()
        assertThat(ClipActions.forTimed(SelectionKind.PIP_VIDEO, facts).map { it.action }.take(3))
            .containsExactly(EditAction.TRIM, EditAction.ADD_CAPTION, EditAction.VOLUME)
            .inOrder()
        assertThat(ClipActions.forTimed(SelectionKind.SOUND, facts.copy(fixed = true, isMusic = true)).map { it.action })
            .containsExactly(EditAction.TRIM, EditAction.VOLUME, EditAction.FADE, EditAction.LOWER_MUSIC, EditAction.DELETE)
            .inOrder()
        // cm-frame-area: a frame's Screen colour and Picture area follow Show frame, and lead an added frame's bar.
        val frame = ClipActions.forTimed(SelectionKind.FRAME, facts.copy(frameOn = false))
        assertThat(frame.map { it.action })
            .containsExactly(EditAction.FRAME_ON, EditAction.KEY_COLOUR, EditAction.PICTURE_AREA)
            .inOrder()
        assertThat(frame.first().checked).isFalse()
        val overlay = ClipActions.forTimed(SelectionKind.OVERLAY, facts).map { it.action }
        assertThat(overlay.take(2)).containsExactly(EditAction.KEY_COLOUR, EditAction.PICTURE_AREA).inOrder()
        assertThat(overlay).contains(EditAction.MOVE_TO_ROW)
    }

    // ---------------------------------------------------------------- handles (cm-timeline-handles)

    private fun handled(
        id: String,
        start: Long,
        end: Long,
    ) = clip(id, start, end).copy(resize = ResizeRange(0, end - 500, start + 500, 100_000))

    @Test
    fun `the selected item's plus moves beyond its handles, never on one, as on MAIN`() {
        val clips = listOf(handled("a", 1000, 3000), handled("b", 5000, 6000))
        val points = plus(clips, selected = "a")
        val (start, end) = TimedRowGeometry.handleHitAreas(clips[0], zoom)!!
        // 48 dp hit areas: 12 dp outside the item, 24 dp drawn inside, 12 dp more.
        assertThat(start.start to start.endInclusive).isEqualTo(88.0 to 136.0)
        assertThat(end.start to end.endInclusive).isEqualTo(264.0 to 312.0)
        val half = TimelineGeometry.PLUS_HIT_DP / 2
        for (p in points) {
            for (h in listOf(start, end)) {
                assertThat(
                    p.xDp + half <= h.start - TimelineGeometry.HANDLE_GAP_DP ||
                        p.xDp - half >= h.endInclusive + TimelineGeometry.HANDLE_GAP_DP,
                ).isTrue()
            }
        }
        assertThat(
            points
                .first {
                    it.anchor.value == "a" && it.side == InsertSide.BEFORE
                }.xDp,
        ).isWithin(1e-9).of(100.0 - TimelineGeometry.DISPLACE_DP)
        // An item running past the view's end: its "+" after it would be pulled onto its end handle, so it is left out.
        val long = listOf(handled("l", 1000, 3000))
        val inView = TimedRowGeometry.plusPoints(long, zoom, ClipId("l"), widthDp = 290.0)
        val (_, lEnd) = TimedRowGeometry.handleHitAreas(long[0], zoom)!!
        assertThat(
            inView.none { it.xDp + half > lEnd.start - TimelineGeometry.HANDLE_GAP_DP && it.xDp - half < lEnd.endInclusive },
        ).isTrue()
        assertThat(inView.map { it.side }).containsExactly(InsertSide.BEFORE)
        // Without handles (nothing to resize), the plus sits just outside, as before.
        assertThat(plus(listOf(clip("a", 1000, 3000)), selected = "a").first().xDp).isWithin(1e-9).of(100.0 - half)
    }

    @Test
    fun `a touch on a handle is that handle, the nearer one on a narrow item, and only on the selected item`() {
        val clips = listOf(handled("a", 1000, 3000), handled("n", 4000, 4300))
        val pa = plus(clips, selected = "a")
        assertThat(TimedRowGeometry.hitTest(clips, pa, zoom, 110.0, ClipId("a"))).isEqualTo(TimedHit.Handle(ClipId("a"), true))
        assertThat(TimedRowGeometry.hitTest(clips, pa, zoom, 290.0, ClipId("a"))).isEqualTo(TimedHit.Handle(ClipId("a"), false))
        assertThat(TimedRowGeometry.hitTest(clips, pa, zoom, 200.0, ClipId("a"))).isEqualTo(TimedHit.Clip(ClipId("a")))
        // Not selected: the same spot is the item.
        assertThat(TimedRowGeometry.hitTest(clips, plus(clips), zoom, 110.0, null)).isEqualTo(TimedHit.Clip(ClipId("a")))
        // A 30 dp item: its two hit areas overlap, the nearer edge wins.
        val pn = plus(clips, selected = "n")
        assertThat(TimedRowGeometry.hitTest(clips, pn, zoom, 405.0, ClipId("n"))).isEqualTo(TimedHit.Handle(ClipId("n"), true))
        assertThat(TimedRowGeometry.hitTest(clips, pn, zoom, 425.0, ClipId("n"))).isEqualTo(TimedHit.Handle(ClipId("n"), false))
    }

    @Test
    fun `a row scrolls into view by as little as it can, and a row taller than the view shows its top`() {
        val bands = RowOrder.bands(listOf("t" to 48.0, "m" to 72.0, "a" to 48.0, "b" to 48.0), 8.0)
        // At 2 px per dp: t 0-96, m 112-256, a 272-368, b 384-480. A 300 px view at the top.
        assertThat(RowOrder.revealScroll(bands[0], 2f, 0, 300)).isNull()
        assertThat(RowOrder.revealScroll(bands[2], 2f, 0, 300)).isEqualTo(368 - 300)
        assertThat(RowOrder.revealScroll(bands[3], 2f, 0, 300)).isEqualTo(480 - 300)
        // Scrolled past it: back to its top.
        assertThat(RowOrder.revealScroll(bands[1], 2f, 200, 300)).isEqualTo(112)
        // A view shorter than the row: its top.
        assertThat(RowOrder.revealScroll(bands[1], 2f, 0, 100)).isEqualTo(112)
        // Nothing laid out yet.
        assertThat(RowOrder.revealScroll(bands[3], 2f, 0, 0)).isNull()
    }

    @Test
    fun `sideways rows are slimmer, with a slimmer gap, and still grow with the text`() {
        val upright = RowMetrics(1f)
        assertThat(listOf(upright.mainDp, upright.rowDp, upright.gapDp)).containsExactly(72.0, 48.0, 8.0).inOrder()
        val sideways = RowMetrics(1f, compact = true)
        assertThat(listOf(sideways.mainDp, sideways.rowDp, sideways.gapDp)).containsExactly(64.0, 40.0, 4.0).inOrder()
        // MAIN keeps its transition marker inside it: centred 48 dp down, 12 dp each way.
        assertThat(
            sideways.mainDp,
        ).isAtLeast(TimelineGeometry.PLUS_HIT_DP / 2 + TimelineGeometry.MARKER_HIT_DP / 2 + TimelineGeometry.MARKER_DP / 2 + 4)
        val big = RowMetrics(2f, compact = true)
        assertThat(big.rowDp).isEqualTo(80.0)
        assertThat(big.mainDp).isEqualTo(88.0)
        // Four rows (MAIN and three) in the timeline of a 411 dp tall phone held sideways: 411 less the
        // status and gesture bars (24 dp each), the 56 dp top and bottom bars and the 28 dp ruler.
        val room = 411.0 - 48 - 56 - 56 - sideways.rulerDp
        assertThat(sideways.mainDp + sideways.gapDp + 3 * (sideways.rowDp + sideways.gapDp)).isAtMost(room)
    }
}
