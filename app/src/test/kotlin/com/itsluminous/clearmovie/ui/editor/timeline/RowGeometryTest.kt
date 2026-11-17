package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
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
    fun `nothing selected, one plus at the end of the row, just after the last item`() {
        val plus = plus(listOf(clip("a", 0, 1000), clip("b", 3000, 4000)))
        assertThat(plus.map { it.anchor.value to it.side }).containsExactly("b" to InsertSide.AFTER)
        assertThat(plus.single().xDp).isWithin(1e-9).of(400.0 + TimelineGeometry.PLUS_HIT_DP / 2)
        assertThat(plus(emptyList())).isEmpty()
    }

    @Test
    fun `the selected item gets a plus on each side, just outside it, and no other item does`() {
        val clips = listOf(clip("a", 1000, 2000), clip("b", 2000, 3000), clip("c", 3500, 3800))
        val sel = plus(clips, selected = "a")
        assertThat(sel.map { it.anchor.value to it.side })
            .containsExactly("a" to InsertSide.BEFORE, "a" to InsertSide.AFTER, "c" to InsertSide.AFTER)
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
        assertThat(atStart.first().xDp - TimelineGeometry.PLUS_HIT_DP / 2).isAtLeast(-TimelineGeometry.EDGE_ROOM_DP - 1e-9)
        assertThat(atStart.last().xDp + TimelineGeometry.PLUS_HIT_DP / 2).isAtMost(300.0 + TimelineGeometry.EDGE_ROOM_DP + 1e-9)
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
                EditAction.MOVE_EARLIER,
                EditAction.MOVE_LATER,
                EditAction.MOVE_TO_ROW,
                EditAction.DELETE,
            ).inOrder()
        assertThat(ClipActions.forTimed(SelectionKind.TEXT, facts).first { it.action == EditAction.MOVE_EARLIER }.enabled).isFalse()
        assertThat(ClipActions.forTimed(SelectionKind.PIP_VIDEO, facts).map { it.action }.take(3))
            .containsExactly(EditAction.TRIM, EditAction.VOLUME, EditAction.POSITION)
            .inOrder()
        assertThat(ClipActions.forTimed(SelectionKind.SOUND, facts.copy(fixed = true, isMusic = true)).map { it.action })
            .containsExactly(EditAction.TRIM, EditAction.VOLUME, EditAction.FADE, EditAction.LOWER_MUSIC, EditAction.DELETE)
            .inOrder()
        assertThat(ClipActions.forTimed(SelectionKind.FRAME, facts.copy(frameOn = false)).single().checked).isFalse()
        assertThat(ClipActions.forTimed(SelectionKind.OVERLAY, facts).map { it.action }).contains(EditAction.MOVE_TO_ROW)
    }
}
