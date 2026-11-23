package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.ui.editor.timeline.TapSeek.isPicture
import org.junit.Test

/** [TapSeek]: which taps move the cursor, and to which time, at any zoom and scroll (cm-tap-seek). */
class TapSeekTest {
    @Test
    fun `fit all, the time under the finger`() {
        val z = ZoomState(msPerDp = 20.0, scrollMs = 0.0, fitAll = true)
        // An item from 0 to 4 s: 100 dp in is 2 s.
        assertThat(TapSeek.timeAt(z, 100.0, 0, 4000, 14_000)).isEqualTo(2000)
    }

    @Test
    fun `zoomed in and scrolled, the scroll is counted`() {
        val z = ZoomState(msPerDp = 5.0, scrollMs = 3000.0, fitAll = false)
        // 100 dp in at 5 ms a dp, the view starting at 3 s: 3.5 s.
        assertThat(TapSeek.timeAt(z, 100.0, 2000, 6000, 14_000)).isEqualTo(3500)
        // Zoomed out further on the same spot of the screen: a later time.
        val out = ZoomState(msPerDp = 40.0, scrollMs = 3000.0, fitAll = false)
        assertThat(TapSeek.timeAt(out, 100.0, 2000, 9000, 14_000)).isEqualTo(7000)
    }

    @Test
    fun `scrolled past the video's start, a negative scroll is counted too`() {
        val z = ZoomState(msPerDp = 10.0, scrollMs = -500.0, fitAll = false)
        assertThat(TapSeek.timeAt(z, 100.0, 0, 3000, 14_000)).isEqualTo(500)
    }

    @Test
    fun `fractions of a millisecond are rounded`() {
        val z = ZoomState(msPerDp = 7.3, scrollMs = 0.0)
        assertThat(TapSeek.timeAt(z, 10.0, 0, 3000, 14_000)).isEqualTo(73)
        assertThat(TapSeek.timeAt(z, 10.07, 0, 3000, 14_000)).isEqualTo(74)
    }

    @Test
    fun `a tap on an item's very edge stays inside the item, so the item itself shows`() {
        val z = ZoomState(msPerDp = 20.0)
        // Just left of its start (the edge of the touch), and on its end, which belongs to the next item.
        assertThat(TapSeek.timeAt(z, 99.0, 2000, 4000, 14_000)).isEqualTo(2000)
        assertThat(TapSeek.timeAt(z, 200.0, 2000, 4000, 14_000)).isEqualTo(3999)
        assertThat(TapSeek.timeAt(z, 900.0, 2000, 4000, 14_000)).isEqualTo(3999)
    }

    @Test
    fun `never past the video's end, nor before its start`() {
        val z = ZoomState(msPerDp = 20.0)
        // An item running on past the end of a 5 s video.
        assertThat(TapSeek.timeAt(z, 290.0, 4000, 8000, 5000)).isEqualTo(5000)
        assertThat(TapSeek.timeAt(z, -50.0, -100, 2000, 5000)).isEqualTo(0)
        // An empty video: 0.
        assertThat(TapSeek.timeAt(z, 50.0, 0, 0, 0)).isEqualTo(0)
    }

    @Test
    fun `in a transition the time tapped is kept, inside the item tapped`() {
        // MAIN: a 4 s item, then one starting 0.6 s before it ends (a crossfade), as the timeline lays them out.
        val z = ZoomState(msPerDp = 10.0)
        val first = 0L to 4000L
        val second = 3400L to 7400L
        // A tap at 3.6 s on the first item: 3.6 s, in the crossfade.
        assertThat(TapSeek.timeAt(z, 360.0, first.first, first.second, 7400)).isEqualTo(3600)
        // The same spot hit as the second item: the same time.
        assertThat(TapSeek.timeAt(z, 360.0, second.first, second.second, 7400)).isEqualTo(3600)
        // A tap at 3.2 s seen as the second item (it cannot be, but if it were) is kept on it: 3.4 s.
        assertThat(TapSeek.timeAt(z, 320.0, second.first, second.second, 7400)).isEqualTo(3400)
    }

    @Test
    fun `while playing every row's items move the cursor`() {
        for (type in RowType.entries) assertThat(TapSeek.seeks(type, playing = true)).isTrue()
    }

    @Test
    fun `while paused only the items seen in the preview move it`() {
        val seen = RowType.entries.filter { TapSeek.seeks(it, playing = false) }
        assertThat(seen).containsExactly(RowType.MAIN, RowType.TEXT, RowType.FRAME, RowType.PIP)
        assertThat(RowType.entries.filterNot { it.isPicture() }).containsExactly(RowType.MUSIC, RowType.VOICE, RowType.SOUND)
    }
}
