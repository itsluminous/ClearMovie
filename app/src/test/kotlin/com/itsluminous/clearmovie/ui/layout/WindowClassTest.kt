package com.itsluminous.clearmovie.ui.layout

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.testing.ScreenSize
import org.junit.Test

/** Window size classes decide the layout, never the raw orientation. */
class WindowClassTest {
    private fun of(size: ScreenSize) = WindowClass.of(size.size.width, size.size.height)

    @Test
    fun `a phone held upright is one pane`() {
        val w = of(ScreenSize.COMPACT)
        assertThat(w.width).isEqualTo(WidthClass.COMPACT)
        assertThat(w.twoPane).isFalse()
        assertThat(w.columns).isEqualTo(1)
    }

    @Test
    fun `a phone held sideways and a tablet are two panes`() {
        assertThat(of(ScreenSize.MEDIUM).twoPane).isTrue()
        assertThat(of(ScreenSize.EXPANDED).twoPane).isTrue()
        assertThat(of(ScreenSize.EXPANDED).columns).isEqualTo(3)
    }

    @Test
    fun `a short window is two panes even when narrow, and a tall tablet is two panes in portrait`() {
        assertThat(WindowClass.of(560.dp, 400.dp).twoPane).isTrue()
        assertThat(WindowClass.of(800.dp, 1280.dp).twoPane).isTrue()
        // Split screen on a phone: narrow and tall is one pane.
        assertThat(WindowClass.of(411.dp, 600.dp).twoPane).isFalse()
    }

    @Test
    fun `the breakpoints are Material's`() {
        assertThat(WindowClass.of(599.dp, 900.dp).width).isEqualTo(WidthClass.COMPACT)
        assertThat(WindowClass.of(600.dp, 900.dp).width).isEqualTo(WidthClass.MEDIUM)
        assertThat(WindowClass.of(840.dp, 900.dp).width).isEqualTo(WidthClass.EXPANDED)
        assertThat(WindowClass.of(840.dp, 479.dp).height).isEqualTo(HeightClass.COMPACT)
        assertThat(WindowClass.of(840.dp, 480.dp).height).isEqualTo(HeightClass.MEDIUM)
    }
}
