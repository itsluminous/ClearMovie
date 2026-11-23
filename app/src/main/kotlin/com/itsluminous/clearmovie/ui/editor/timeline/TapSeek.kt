package com.itsluminous.clearmovie.ui.editor.timeline

/**
 * Where a tap on a timeline item moves the cursor (cm-tap-seek), pure so it
 * is tested on the JVM.
 *
 * - While playing, a tap on any item (picture or sound) moves the cursor to
 *   the time tapped and the video plays on from there.
 * - While paused, a tap on a picture item (on MAIN, a caption, a frame, a
 *   picture in picture) moves the cursor there too, so the preview shows that
 *   item at that moment. A tap on a sound (music, voice, a sound), or on an
 *   item of a row switched off, only selects it, as before: the preview shows
 *   nothing of it.
 */
object TapSeek {
    /** Whether a tap on an item of a row of [type] (switched on: [enabled]) moves the cursor. */
    fun seeks(
        type: RowType,
        playing: Boolean,
        enabled: Boolean = true,
    ): Boolean = playing || (enabled && type.isPicture())

    /**
     * The time a tap at [xDp] (from the left of the time span, as [ZoomState.xOf]
     * measures) on an item from [startMs] to [endMs] moves the cursor to: the
     * time under the finger, to the millisecond below as the ruler takes it,
     * kept inside the item (its last millisecond at most, never the next
     * item's first) and inside the video of [totalMs], short of its very end
     * (there the preview stops, and while playing it must play on).
     */
    fun timeAt(
        zoom: ZoomState,
        xDp: Double,
        startMs: Long,
        endMs: Long,
        totalMs: Long,
    ): Long {
        val last = maxOf(startMs, endMs - 1)
        val t = zoom.timeAt(xDp).toLong().coerceIn(startMs, last)
        return t.coerceIn(0L, maxOf(0L, totalMs - 1))
    }

    /** The rows whose items are seen in the preview. */
    fun RowType.isPicture(): Boolean =
        when (this) {
            RowType.MAIN, RowType.TEXT, RowType.FRAME, RowType.PIP -> true
            RowType.MUSIC, RowType.VOICE, RowType.SOUND -> false
        }
}
