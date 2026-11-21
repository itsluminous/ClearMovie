package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The preview layout state machine: every transition in ARCHITECTURE section 11.4. */
class PreviewLayoutReducerTest {
    private fun rows(
        n: Int,
        height: Double = 48.0,
    ) = listOf(RowSpec("main", 72.0, isMain = true)) + (1 until n).map { RowSpec("r$it", height) }

    private fun input(
        rows: List<RowSpec>,
        windowHeight: Double = 891.0,
        twoPane: Boolean = false,
        chrome: Double = 300.0,
        selected: String? = null,
    ) = LayoutInput(
        twoPane = twoPane,
        windowWidthDp = 411.0,
        windowHeightDp = windowHeight,
        chromeHeightDp = chrome,
        rulerDp = 28.0,
        rows = rows,
        toggleDp = 48.0,
        selectedRow = selected,
    )

    private fun measured(
        state: PreviewLayoutState,
        input: LayoutInput,
    ) = PreviewLayoutReducer.reduce(state, LayoutEvent.Measured(input))

    // On a 411 dp wide phone the natural preview (231 dp) is below the minimum (28% of 891 = 249.5), so it is
    // either Full or Partial. A wider window (700 dp: preview 393.75 dp) shows Shrunk between the two.

    private fun wide(
        rows: List<RowSpec>,
        selected: String? = null,
    ) = input(rows, windowHeight = 891.0, chrome = 250.0, selected = selected).copy(windowWidthDp = 700.0, previewWidthDp = 700.0)

    @Test
    fun `full when the natural preview and every row fit`() {
        val s = measured(PreviewLayoutState(), input(rows(2)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Full::class.java)
        assertThat(s.layout!!.visibleRows).containsExactly("main", "r1").inOrder()
    }

    @Test
    fun `adding rows goes Full, then Shrunk, then Partial, and removing them goes back`() {
        // space 641; full preview 393.75; min 249.5. Row block = 28 + 72 + 8 + (n-1) x 56.
        var s = measured(PreviewLayoutState(), wide(rows(3)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Full::class.java)
        s = measured(s, wide(rows(5)))
        val shrunk = s.layout as PreviewLayout.Shrunk
        assertThat(shrunk.previewDp).isWithin(1e-9).of(641.0 - (28 + 72 + 8 + 4 * 56))
        assertThat(shrunk.previewDp).isAtLeast(249.5)
        s = measured(s, wide(rows(9)))
        val partial = s.layout as PreviewLayout.Partial
        assertThat(partial.previewDp).isWithin(1e-9).of(PreviewLayoutReducer.minPreviewDp(891.0, 393.75))
        // Every row is on the timeline (it scrolls); the ones below those that fit are "N more".
        assertThat(partial.visibleRows).isEqualTo(rows(9).map { it.key })
        assertThat(partial.hiddenRows).isNotEmpty()
        val fits = partial.visibleRows - partial.hiddenRows.toSet()
        assertThat(partial.hiddenRows).isEqualTo(partial.visibleRows.drop(fits.size))
        // The rows that fit do so under the minimum preview, with the Show all rows control; one more would not.
        val fitHeight = 28 + 80 + (fits.size - 1) * 56.0
        assertThat(fitHeight + 48).isAtMost(641.0 - partial.previewDp)
        assertThat(fitHeight + 56 + 48).isGreaterThan(641.0 - partial.previewDp)
        s = measured(s, wide(rows(5)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Shrunk::class.java)
        s = measured(s, wide(rows(2)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Full::class.java)
    }

    @Test
    fun `a larger font makes rows taller and moves the layout along`() {
        val normal = measured(PreviewLayoutState(), wide(rows(3)))
        assertThat(normal.layout).isInstanceOf(PreviewLayout.Full::class.java)
        val big = measured(normal, wide(rows(3, height = 96.0).map { if (it.isMain) it.copy(heightDp = 96.0) else it }))
        assertThat(big.layout).isInstanceOf(PreviewLayout.Shrunk::class.java)
        val huge = measured(big, wide(rows(6, height = 96.0)))
        assertThat(huge.layout).isInstanceOf(PreviewLayout.Partial::class.java)
    }

    @Test
    fun `Partial puts every row on the timeline in screen order, and counts the ones below those that fit`() {
        // MAIN in the middle and a selected row at the bottom: nothing is taken out of order (the timeline
        // scrolls to the selected row instead).
        val many = (1..5).map { RowSpec("a$it", 48.0) } + RowSpec("main", 72.0, isMain = true) + (1..6).map { RowSpec("b$it", 48.0) }
        val s = measured(PreviewLayoutState(), wide(many, selected = "b6"))
        val layout = s.layout as PreviewLayout.Partial
        assertThat(layout.visibleRows).isEqualTo(many.map { it.key })
        val fits = layout.visibleRows - layout.hiddenRows.toSet()
        assertThat(fits).isEqualTo(many.map { it.key }.take(fits.size))
        assertThat(layout.hiddenRows).contains("b6")
    }

    @Test
    fun `the gap under each row is the one measured, slimmer sideways`() {
        val few = rows(3)
        val normal = input(few)
        assertThat(PreviewLayoutReducer.rowsHeight(normal, few)).isWithin(1e-9).of(28.0 + 72 + 48 + 48 + 3 * 8)
        assertThat(PreviewLayoutReducer.rowsHeight(normal.copy(rowGapDp = 4.0), few)).isWithin(1e-9).of(28.0 + 72 + 48 + 48 + 3 * 4)
    }

    @Test
    fun `Show all rows floats the preview at the last corner, and Show fewer rows docks it again`() {
        var s = measured(PreviewLayoutState(), wide(rows(12)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Partial::class.java)
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.ShowAllRows)
        val floating = s.layout as PreviewLayout.Floating
        assertThat(floating.corner).isEqualTo(PopupCorner.BOTTOM_END)
        assertThat(floating.visibleRows).hasSize(12)
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.PopupMoved(PopupCorner.TOP_START))
        assertThat((s.layout as PreviewLayout.Floating).corner).isEqualTo(PopupCorner.TOP_START)
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.ShowFewerRows)
        assertThat(s.layout).isInstanceOf(PreviewLayout.Partial::class.java)
        assertThat(s.allRowsRequested).isFalse()
        // The corner is remembered for the next time.
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.ShowAllRows)
        assertThat((s.layout as PreviewLayout.Floating).corner).isEqualTo(PopupCorner.TOP_START)
    }

    @Test
    fun `Floating stays while needed, and closes into Shrunk once everything fits`() {
        var s = measured(PreviewLayoutState(), wide(rows(12)))
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.ShowAllRows)
        s = measured(s, wide(rows(11)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Floating::class.java)
        s = measured(s, wide(rows(5)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Shrunk::class.java)
        assertThat(s.allRowsRequested).isFalse()
    }

    @Test
    fun `a move to two panes docks the preview and keeps the request, and back to stacked it floats again`() {
        var s = measured(PreviewLayoutState(), wide(rows(12)))
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.ShowAllRows)
        s = PreviewLayoutReducer.reduce(s, LayoutEvent.PopupMoved(PopupCorner.TOP_END))
        s = measured(s, wide(rows(12)).copy(twoPane = true, windowHeightDp = 411.0))
        val docked = s.layout as PreviewLayout.Docked
        assertThat(docked.visibleRows).hasSize(12)
        assertThat(s.allRowsRequested).isTrue()
        s = measured(s, wide(rows(12)))
        assertThat((s.layout as PreviewLayout.Floating).corner).isEqualTo(PopupCorner.TOP_END)
    }

    @Test
    fun `two panes put every row on the scrolling timeline and have no Show all rows`() {
        val s = measured(PreviewLayoutState(), input(rows(12), windowHeight = 411.0, twoPane = true, chrome = 200.0))
        val docked = s.layout as PreviewLayout.Docked
        assertThat(docked.visibleRows).hasSize(12)
        assertThat(docked.hiddenRows).isEmpty()
        val all = PreviewLayoutReducer.reduce(s, LayoutEvent.ShowAllRows).layout as PreviewLayout.Docked
        assertThat(all).isEqualTo(docked)
    }

    @Test
    fun `the minimum preview is 180 dp or 28 percent of the window, and 180 dp on an upright phone`() {
        assertThat(PreviewLayoutReducer.minPreviewDp(500.0, 400.0)).isEqualTo(180.0)
        assertThat(PreviewLayoutReducer.minPreviewDp(1000.0, 400.0)).isWithin(1e-9).of(280.0)
        // 411 x 891: the natural preview (231 dp) is under 28% (249 dp), so it may shrink to 180 dp.
        assertThat(PreviewLayoutReducer.minPreviewDp(891.0, 411.0 * 9 / 16)).isEqualTo(180.0)
    }

    @Test
    fun `on an upright phone the preview shrinks before rows are hidden`() {
        // space 591; full 231.2; min 180. Rows: 28 + 80 + (n - 1) x 56.
        var s = measured(PreviewLayoutState(), input(rows(5)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Full::class.java)
        s = measured(s, input(rows(6)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Shrunk::class.java)
        assertThat((s.layout as PreviewLayout.Shrunk).previewDp).isWithin(1e-9).of(591.0 - (28 + 80 + 5 * 56))
        s = measured(s, input(rows(8)))
        assertThat(s.layout).isInstanceOf(PreviewLayout.Partial::class.java)
        assertThat((s.layout as PreviewLayout.Partial).previewDp).isEqualTo(180.0)
    }

    @Test
    fun `nothing happens before the first measurement`() {
        val s = PreviewLayoutReducer.reduce(PreviewLayoutState(), LayoutEvent.ShowAllRows)
        assertThat(s.layout).isNull()
        assertThat(s.allRowsRequested).isTrue()
    }

    @Test
    fun `an upright or square video's preview is as tall as the rows leave room for, never past its shape`() {
        // cm-aspect-ratio: the natural preview is the full width at the video's shape.
        assertThat(PreviewLayoutReducer.fullPreviewDp(411.0, 9.0 / 16)).isWithin(1e-9).of(411.0 * 16 / 9)
        assertThat(PreviewLayoutReducer.fullPreviewDp(411.0, 1.0)).isWithin(1e-9).of(411.0)
        val few = rows(2)
        val upright = measured(PreviewLayoutState(), input(few).copy(canvasAspect = 9.0 / 16)).layout
        // 730 dp tall does not fit with the rows: the preview takes the room the rows leave.
        assertThat(upright).isInstanceOf(PreviewLayout.Shrunk::class.java)
        val space = 891.0 - 300.0 - PreviewLayoutReducer.rowsHeight(input(few), few)
        assertThat((upright as PreviewLayout.Shrunk).previewDp).isWithin(1e-9).of(space)
        val square = measured(PreviewLayoutState(), input(few, chrome = 200.0).copy(canvasAspect = 1.0)).layout
        assertThat(square).isEqualTo(PreviewLayout.Full(411.0, few.map { it.key }))
    }
}
