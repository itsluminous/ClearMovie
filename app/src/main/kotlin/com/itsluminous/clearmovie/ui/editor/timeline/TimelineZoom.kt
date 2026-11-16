package com.itsluminous.clearmovie.ui.editor.timeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The timeline's zoom and horizontal scroll (ARCHITECTURE section 11.7).
 * [msPerDp] is the scale; [scrollMs] is the time at the left edge of the
 * time span. [fitAll] stays set while the user has asked for the whole video
 * on screen, so a rotation or a longer video keeps it fitted.
 */
data class ZoomState(
    val msPerDp: Double,
    val scrollMs: Double = 0.0,
    val fitAll: Boolean = true,
) {
    /** The x (dp, from the left of the time span) of [timeMs]. */
    fun xOf(timeMs: Double): Double = (timeMs - scrollMs) / msPerDp

    /** The time under [xDp]. */
    fun timeAt(xDp: Double): Double = scrollMs + xDp * msPerDp
}

/**
 * Zoom maths, pure so it is tested on the JVM. Widths are of the time span
 * only (the row headers and edge padding are left out by the caller).
 *
 * - **Fit all** (the widest scale): the whole video across the width, but
 *   never so stretched that one second spans more than 40% of it (a very
 *   short video).
 * - **Close-up** (the narrowest): one second spans 240 dp.
 * - Zooming multiplies the scale, so equal pinches change it by equal ratios.
 * - A zoom keeps the time under the focal point where it is, unless that
 *   would scroll past either end of the video.
 */
object TimelineZoom {
    const val CLOSE_UP_DP_PER_SECOND = 240.0
    const val SHORT_VIDEO_MAX_SECOND_FRACTION = 0.4

    /** The Zoom in and Zoom out buttons change the scale by this much, around the playhead. */
    const val BUTTON_FACTOR = 1.5

    /** Ruler label spacing (ms), the first one that leaves labels at least [MIN_TICK_DP] apart. */
    val TICKS_MS = longArrayOf(500, 1000, 5000, 10_000, 30_000, 60_000)
    const val MIN_TICK_DP = 64.0

    private const val EPSILON = 1e-6

    val closeUpMsPerDp: Double get() = 1000.0 / CLOSE_UP_DP_PER_SECOND

    /** The scale that fits [totalMs] into [widthDp]. */
    fun fitAllMsPerDp(
        totalMs: Long,
        widthDp: Double,
    ): Double {
        val w = max(widthDp, 1.0)
        val fit = totalMs.coerceAtLeast(0) / w
        val shortCap = 1000.0 / (SHORT_VIDEO_MAX_SECOND_FRACTION * w)
        return max(fit, shortCap)
    }

    /** The narrowest scale allowed (close-up, or fit all when that is narrower still). */
    fun minMsPerDp(
        totalMs: Long,
        widthDp: Double,
    ): Double = min(closeUpMsPerDp, fitAllMsPerDp(totalMs, widthDp))

    fun clampScale(
        msPerDp: Double,
        totalMs: Long,
        widthDp: Double,
    ): Double = msPerDp.coerceIn(minMsPerDp(totalMs, widthDp), fitAllMsPerDp(totalMs, widthDp))

    /** The furthest the time span can be scrolled: the video's end at the right edge. */
    fun maxScrollMs(
        msPerDp: Double,
        totalMs: Long,
        widthDp: Double,
    ): Double = max(0.0, totalMs - widthDp * msPerDp)

    /** The whole video on screen. */
    fun fitAll(
        totalMs: Long,
        widthDp: Double,
    ): ZoomState = ZoomState(fitAllMsPerDp(totalMs, widthDp), 0.0, fitAll = true)

    /**
     * Zooms by [factor] (above 1 zooms in) around [focusXDp], the x of the
     * focal point from the left of the time span.
     */
    fun zoomAround(
        state: ZoomState,
        factor: Double,
        focusXDp: Double,
        totalMs: Long,
        widthDp: Double,
    ): ZoomState {
        require(factor > 0 && factor.isFinite()) { "factor must be positive" }
        val focusTime = state.timeAt(focusXDp)
        val scale = clampScale(state.msPerDp / factor, totalMs, widthDp)
        val scroll = (focusTime - focusXDp * scale).coerceIn(0.0, maxScrollMs(scale, totalMs, widthDp))
        val fitted = scale >= fitAllMsPerDp(totalMs, widthDp) * (1 - EPSILON)
        return ZoomState(scale, if (fitted) 0.0 else scroll, fitAll = fitted)
    }

    /** Zoom in or out by [BUTTON_FACTOR] around [playheadMs] (kept where it is on screen, or brought to the middle if it is off it). */
    fun zoomButton(
        state: ZoomState,
        zoomIn: Boolean,
        playheadMs: Double,
        totalMs: Long,
        widthDp: Double,
    ): ZoomState {
        val x = state.xOf(playheadMs)
        val focusX = if (x in 0.0..widthDp) x else widthDp / 2
        val anchored = if (x in 0.0..widthDp) state else state.copy(scrollMs = playheadMs - focusX * state.msPerDp)
        return zoomAround(anchored, if (zoomIn) BUTTON_FACTOR else 1 / BUTTON_FACTOR, focusX, totalMs, widthDp)
    }

    /** Scrolls by [dxDp] (positive moves later in the video), within the video. */
    fun scrollBy(
        state: ZoomState,
        dxDp: Double,
        totalMs: Long,
        widthDp: Double,
    ): ZoomState {
        val scroll = (state.scrollMs + dxDp * state.msPerDp).coerceIn(0.0, maxScrollMs(state.msPerDp, totalMs, widthDp))
        return state.copy(scrollMs = scroll)
    }

    /** Scrolls so that [timeMs] is on screen, at [atFraction] of the width when it has to move. */
    fun reveal(
        state: ZoomState,
        timeMs: Double,
        totalMs: Long,
        widthDp: Double,
        marginDp: Double = 24.0,
        atFraction: Double = 0.25,
    ): ZoomState {
        val x = state.xOf(timeMs)
        if (x >= marginDp && x <= widthDp - marginDp) return state
        val scroll = (timeMs - widthDp * atFraction * state.msPerDp).coerceIn(0.0, maxScrollMs(state.msPerDp, totalMs, widthDp))
        return state.copy(scrollMs = scroll)
    }

    /**
     * The same zoom for a new width or a new video length (a rotation, an
     * edit): fit all stays fit all, any other scale and scroll are clamped.
     */
    fun reclamp(
        state: ZoomState,
        totalMs: Long,
        widthDp: Double,
    ): ZoomState {
        if (state.fitAll) return fitAll(totalMs, widthDp)
        val scale = clampScale(state.msPerDp, totalMs, widthDp)
        val fitted = scale >= fitAllMsPerDp(totalMs, widthDp) * (1 - EPSILON)
        if (fitted) return fitAll(totalMs, widthDp)
        return ZoomState(scale, state.scrollMs.coerceIn(0.0, maxScrollMs(scale, totalMs, widthDp)), fitAll = false)
    }

    /** Ruler label spacing for [msPerDp]: labels at least [MIN_TICK_DP] apart. */
    fun tickMs(msPerDp: Double): Long {
        for (t in TICKS_MS) if (t / msPerDp >= MIN_TICK_DP - EPSILON) return t
        // Beyond a minute between labels: whole multiples of a minute.
        val minutes =
            kotlin.math
                .ceil(MIN_TICK_DP * msPerDp / 60_000.0)
                .toLong()
                .coerceAtLeast(1)
        return minutes * 60_000
    }

    /** Whether two scales are the same zoom, for tests and for skipping no-op updates. */
    fun same(
        a: Double,
        b: Double,
    ): Boolean = abs(a - b) <= EPSILON * max(abs(a), abs(b))
}
