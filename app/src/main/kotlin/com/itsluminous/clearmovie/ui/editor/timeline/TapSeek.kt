package com.itsluminous.clearmovie.ui.editor.timeline

import kotlin.math.roundToLong

/**
 * Where a tap on a timeline item moves the cursor (cm-tap-seek), pure so it
 * is tested on the JVM.
 *
 * - While playing, a tap on any item (picture or sound) moves the cursor to
 *   the time tapped and the video plays on from there.
 * - While paused, a tap on a picture item (a photo, video, card, the intro or
 *   ending, a caption, a frame, a picture in picture) moves the cursor there
 *   too, so the preview shows that item at that moment. A tap on a sound
 *   (music, voice, a sound) only selects it, as before: it shows nothing.
 */
object TapSeek {
    /** Whether a tap on an item of a row of [type] moves the cursor. */
    fun seeks(
        type: RowType,
        playing: Boolean,
    ): Boolean = playing || type.isPicture()

    /**
     * The time a tap at [xDp] (from the left of the time span, as [ZoomState.xOf]
     * measures) on an item from [startMs] to [endMs] moves the cursor to: the
     * time under the finger, kept inside the item (its last millisecond at
     * most, so the item itself is what shows) and inside the video of [totalMs].
     */
    fun timeAt(
        zoom: ZoomState,
        xDp: Double,
        startMs: Long,
        endMs: Long,
        totalMs: Long,
    ): Long {
        val last = maxOf(startMs, endMs - 1)
        val t = zoom.timeAt(xDp).roundToLong().coerceIn(startMs, last)
        return t.coerceIn(0L, maxOf(0L, totalMs))
    }

    /** The rows whose items are seen in the preview. */
    fun RowType.isPicture(): Boolean =
        when (this) {
            RowType.MAIN, RowType.TEXT, RowType.FRAME, RowType.PIP -> true
            RowType.MUSIC, RowType.VOICE, RowType.SOUND -> false
        }
}
