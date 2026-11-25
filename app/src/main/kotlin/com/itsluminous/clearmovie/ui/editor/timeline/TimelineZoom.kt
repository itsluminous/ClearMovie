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
 * - **Close-up** (the narrowest): one second spans 240 dp, unless that would
 *   make the whole video wider than [MAX_SPAN_DP] (a video of more than about
 *   3.5 minutes): then the whole video at [MAX_SPAN_DP] is as close as it goes.
 * - Zooming multiplies the scale, so equal pinches change it by equal ratios.
 * - A zoom keeps the time under the focal point where it is, unless that
 *   would scroll past either end of the video.
 */
object TimelineZoom {
    const val CLOSE_UP_DP_PER_SECOND = 240.0

    /**
     * The widest the whole video is ever laid out, in dp. Compose cannot
     * measure a box wider than 262,143 px, and each MAIN item is laid out at
     * its full width (its pictures need it; the other rows lay out only their
     * part on screen), so at 4.5 px per dp (the densest phones, with a large
     * display size) the whole video must stay under 58,000 dp. At 240 dp a
     * second a video of more than 4 minutes is wider than that, and zooming
     * in all the way on a 7-minute video crashed a 450 dpi phone
     * (cm-crash-after-media-pick).
     */
    const val MAX_SPAN_DP = 50_000.0
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

    /** The narrowest scale allowed: close-up, or fit all when that is narrower still, never wider than [MAX_SPAN_DP]. */
    fun minMsPerDp(
        totalMs: Long,
        widthDp: Double,
    ): Double {
        val fit = fitAllMsPerDp(totalMs, widthDp)
        return min(fit, max(min(closeUpMsPerDp, fit), totalMs.coerceAtLeast(0) / MAX_SPAN_DP))
    }

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

    /**
     * Scrolls by [dxDp] (positive moves later in the video), within the video. A view moved past an end for a
     * selected item's "+" ([nudgePastEnds]) walks back from there, rather than jumping to the end at the first move.
     */
    fun scrollBy(
        state: ZoomState,
        dxDp: Double,
        totalMs: Long,
        widthDp: Double,
    ): ZoomState {
        val end = maxScrollMs(state.msPerDp, totalMs, widthDp)
        val scroll = (state.scrollMs + dxDp * state.msPerDp).coerceIn(min(0.0, state.scrollMs), max(end, state.scrollMs))
        return state.copy(scrollMs = scroll)
    }

    /**
     * Scrolls by [dxDp] for a selected item's "+" buttons, allowed up to
     * [roomDp] past the video's start and [endRoomDp] past its end. Leaves fit
     * all (the scale stays); the next scroll by hand comes back within the video.
     */
    fun nudgePastEnds(
        state: ZoomState,
        dxDp: Double,
        roomDp: Double,
        totalMs: Long,
        widthDp: Double,
        endRoomDp: Double = roomDp,
    ): ZoomState {
        val extra = roomDp.coerceAtLeast(0.0) * state.msPerDp
        val endExtra = endRoomDp.coerceAtLeast(0.0) * state.msPerDp
        val scroll = (state.scrollMs + dxDp * state.msPerDp).coerceIn(-extra, maxScrollMs(state.msPerDp, totalMs, widthDp) + endExtra)
        return state.copy(scrollMs = scroll, fitAll = false)
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
     * The zoom to lay the timeline out with now: [state]'s scale fitted to this
     * [totalMs] and [widthDp], its scroll as it is. A stored zoom can be from
     * before the video's length or the width changed (the frames before the
     * project loads keep fit all of an empty video), and the timeline is laid
     * out in that frame, before [reclamp] can store the new zoom.
     */
    fun forLayout(
        state: ZoomState,
        totalMs: Long,
        widthDp: Double,
    ): ZoomState {
        if (state.fitAll) return fitAll(totalMs, widthDp).copy(scrollMs = state.scrollMs)
        val scale = clampScale(state.msPerDp, totalMs, widthDp)
        return if (scale == state.msPerDp) state else state.copy(msPerDp = scale)
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

    /** Ruler label spacing for [msPerDp]: labels at least [MIN_TICK_DP] apart, times the font scale. */
    fun tickMs(
        msPerDp: Double,
        fontScale: Float = 1f,
    ): Long {
        // Labels grow with the text, so their spacing does too.
        val minDp = MIN_TICK_DP * fontScale.coerceAtLeast(1f)
        for (t in TICKS_MS) if (t / msPerDp >= minDp - EPSILON) return t
        // Beyond a minute between labels: whole multiples of a minute.
        val minutes =
            kotlin.math
                .ceil(minDp * msPerDp / 60_000.0)
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
