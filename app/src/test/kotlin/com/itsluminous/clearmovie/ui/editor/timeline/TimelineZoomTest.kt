package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Zoom maths (ARCHITECTURE section 11.7, ROADMAP cm-timeline: "the focal time stays within 1 dp; clamps; re-clamping on width change"). */
class TimelineZoomTest {
    private val total = 60_000L
    private val width = 300.0

    @Test
    fun `fit all spans the whole video, and a very short one is capped at 40 percent per second`() {
        assertThat(TimelineZoom.fitAllMsPerDp(total, width)).isWithin(1e-9).of(200.0)
        // 2 s in 300 dp would make one second 150 dp (50%): capped so it is 120 dp (40%).
        assertThat(1000 / TimelineZoom.fitAllMsPerDp(2_000, width)).isWithin(1e-9).of(120.0)
    }

    @Test
    fun `the time under the focal point stays within 1 dp through a pinch`() {
        var z = TimelineZoom.fitAll(total, width)
        val focus = 120.0
        val before = z.timeAt(focus)
        // Ten small pinch steps in, then five out, as a real pinch arrives frame by frame.
        repeat(10) { z = TimelineZoom.zoomAround(z, 1.15, focus, total, width) }
        assertThat(z.xOf(before)).isWithin(1.0).of(focus)
        repeat(5) { z = TimelineZoom.zoomAround(z, 1 / 1.1, focus, total, width) }
        assertThat(z.xOf(before)).isWithin(1.0).of(focus)
    }

    @Test
    fun `equal pinches change the scale by equal ratios`() {
        val z0 = TimelineZoom.fitAll(total, width)
        val z1 = TimelineZoom.zoomAround(z0, 1.5, 100.0, total, width)
        val z2 = TimelineZoom.zoomAround(z1, 1.5, 100.0, total, width)
        assertThat(z0.msPerDp / z1.msPerDp).isWithin(1e-9).of(z1.msPerDp / z2.msPerDp)
    }

    @Test
    fun `the scale is clamped between fit all and a close-up of 1 s per 240 dp`() {
        var z = TimelineZoom.fitAll(total, width)
        repeat(60) { z = TimelineZoom.zoomAround(z, 2.0, 150.0, total, width) }
        assertThat(1000 / z.msPerDp).isWithin(1e-6).of(240.0)
        repeat(60) { z = TimelineZoom.zoomAround(z, 0.5, 150.0, total, width) }
        assertThat(z.fitAll).isTrue()
        assertThat(z.msPerDp).isWithin(1e-9).of(TimelineZoom.fitAllMsPerDp(total, width))
        assertThat(z.scrollMs).isEqualTo(0.0)
    }

    @Test
    fun `a zoom never scrolls past either end of the video`() {
        val zoomed = TimelineZoom.zoomAround(TimelineZoom.fitAll(total, width), 8.0, 299.0, total, width)
        assertThat(zoomed.scrollMs + width * zoomed.msPerDp).isAtMost(total + 1e-6)
        val atStart = TimelineZoom.zoomAround(TimelineZoom.fitAll(total, width), 8.0, 0.0, total, width)
        assertThat(atStart.scrollMs).isEqualTo(0.0)
    }

    @Test
    fun `scrolling stays within the video`() {
        val z = TimelineZoom.zoomAround(TimelineZoom.fitAll(total, width), 4.0, 0.0, total, width)
        assertThat(TimelineZoom.scrollBy(z, -1000.0, total, width).scrollMs).isEqualTo(0.0)
        val far = TimelineZoom.scrollBy(z, 1e9, total, width)
        assertThat(far.scrollMs).isWithin(1e-6).of(TimelineZoom.maxScrollMs(z.msPerDp, total, width))
    }

    @Test
    fun `fit all stays fit all on a new width, and any other zoom is re-clamped`() {
        val fitted = TimelineZoom.fitAll(total, width)
        val wider = TimelineZoom.reclamp(fitted, total, 700.0)
        assertThat(wider.fitAll).isTrue()
        assertThat(wider.msPerDp).isWithin(1e-9).of(total / 700.0)

        val close = TimelineZoom.zoomAround(fitted, 10.0, 150.0, total, width)
        val rotated = TimelineZoom.reclamp(close, total, 700.0)
        assertThat(rotated.fitAll).isFalse()
        assertThat(rotated.msPerDp).isWithin(1e-9).of(close.msPerDp)
        assertThat(rotated.scrollMs).isAtMost(TimelineZoom.maxScrollMs(rotated.msPerDp, total, 700.0))
        // A zoom wider than the new fit all becomes fit all.
        val shorter = TimelineZoom.reclamp(close.copy(msPerDp = 500.0), 10_000, width)
        assertThat(shorter.fitAll).isTrue()
    }

    @Test
    fun `the buttons zoom by 1_5 around the playhead`() {
        val z = TimelineZoom.fitAll(total, width)
        val playhead = 30_000.0
        val x = z.xOf(playhead)
        val inZ = TimelineZoom.zoomButton(z, zoomIn = true, playheadMs = playhead, totalMs = total, widthDp = width)
        assertThat(z.msPerDp / inZ.msPerDp).isWithin(1e-9).of(1.5)
        assertThat(inZ.xOf(playhead)).isWithin(1.0).of(x)
        val back = TimelineZoom.zoomButton(inZ, zoomIn = false, playheadMs = playhead, totalMs = total, widthDp = width)
        assertThat(back.fitAll).isTrue()
    }

    @Test
    fun `ruler labels are at least 64 dp apart, from 0_5 s up to whole minutes`() {
        assertThat(TimelineZoom.tickMs(TimelineZoom.closeUpMsPerDp)).isEqualTo(500)
        assertThat(TimelineZoom.tickMs(10.0)).isEqualTo(1000)
        assertThat(TimelineZoom.tickMs(50.0)).isEqualTo(5000)
        assertThat(TimelineZoom.tickMs(200.0)).isEqualTo(30_000)
        assertThat(TimelineZoom.tickMs(900.0)).isEqualTo(60_000)
        assertThat(TimelineZoom.tickMs(2000.0)).isEqualTo(180_000)
        for (scale in listOf(3.0, 7.0, 20.0, 77.0, 333.0, 1500.0)) {
            assertThat(TimelineZoom.tickMs(scale) / scale).isAtLeast(TimelineZoom.MIN_TICK_DP - 1e-6)
        }
    }

    @Test
    fun `reveal brings the playhead back on screen while playing`() {
        val z = TimelineZoom.zoomAround(TimelineZoom.fitAll(total, width), 6.0, 0.0, total, width)
        val off = 40_000.0
        assertThat(z.xOf(off)).isGreaterThan(width)
        val revealed = TimelineZoom.reveal(z, off, total, width)
        assertThat(revealed.xOf(off)).isWithin(1.0).of(width * 0.25)
        assertThat(TimelineZoom.reveal(revealed, off, total, width)).isEqualTo(revealed)
    }
}
