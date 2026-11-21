package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Limits

/** Where the "+" at a row's right end adds on a row other than MAIN, worked out from the cursor when it is tapped. */
sealed interface EndPlusTarget {
    /**
     * At [atMs] ("Add here" from that time): the cursor, or the last moment
     * that leaves the new item seen. [full]: the row has no room left there,
     * so the item goes on another row of its kind (the model's rule).
     */
    data class At(
        val atMs: Long,
        val full: Boolean = false,
    ) : EndPlusTarget

    /** Right after item [anchor]: the first place to the right of the cursor where the row is free. */
    data class After(
        val anchor: ClipId,
    ) : EndPlusTarget
}

/** Where the "+" at the MAIN row's right end adds: on [side] of item [anchorIndex]. */
data class MainEndTarget(
    val anchorIndex: Int,
    val side: InsertSide,
)

/**
 * The "+" at the right end of every row (cm-plus-position, the owner's rule):
 * it sits in the same place on every row, and a new item goes to the right of
 * the cursor (the playhead), starting at the cursor. When the cursor is on an
 * item of that row, the new item goes right after that item. Pure, so the
 * rules are tested on the JVM.
 *
 * - A row other than MAIN: at the cursor when the row is free there for the
 *   new item's length ([lengthMs]: 3 s for a caption, the project's photo
 *   length for a photo; a second for a sound or a frame, whose length is
 *   known only once it is picked).
 *   Otherwise right after the item under the cursor (or the next one, when
 *   the cursor is in a gap too short), and past every item that still leaves
 *   no room: the first place right of the cursor where it fits. A picked file
 *   longer than that room goes, as every insert on these rows does, on the
 *   highest free row of its kind or a new one, at the same time.
 * - Never where it would not be seen: no later than its length, or [SEEN_MS]
 *   when that is less, before the end of the video ([videoMs]). From a cursor
 *   nearer the end the rule starts at that moment. When the first free place
 *   is later still: right after the item in the way while at least
 *   [MIN_SEEN_MS] of video is left after it (a caption is then cut to end with
 *   the video), else at that last moment on another row of its kind, the row
 *   being full to the end ([EndPlusTarget.At.full]).
 * - MAIN (one item after another): at the cursor when it is exactly between
 *   two items; on an item, right after it, and every later item moves later.
 *   In a transition two items are under the cursor: after the later one, so
 *   the new item never starts left of the cursor. Never before the intro or
 *   after the ending: on the ending, or past the end, it goes just before the
 *   ending.
 */
object RowEndPlus {
    /** A new caption's length, and the most of a new item that must be inside the video. */
    const val SEEN_MS = 3000L

    /** Less than this left of the video after an item, and the row has no room after it. */
    const val MIN_SEEN_MS = 1000L

    fun timed(
        clips: List<RowClip>,
        cursorMs: Long,
        lengthMs: Long = 0,
        videoMs: Long = Long.MAX_VALUE,
    ): EndPlusTarget {
        val live = clips.filter { it.endMs > it.startMs }
        val length = lengthMs.coerceAtLeast(1)
        val seen = minOf(length, SEEN_MS)
        val latest = if (videoMs == Long.MAX_VALUE) Long.MAX_VALUE else (videoMs - seen).coerceAtLeast(0)
        val from = cursorMs.coerceIn(0, latest)

        // The items in the way of a new item from [start].
        fun blocking(start: Long) = live.filter { it.startMs < start + length && it.endMs > start }
        var anchor =
            live.filter { from >= it.startMs && from < it.endMs }.maxByOrNull { it.endMs }
                ?: blocking(from).minByOrNull { it.startMs }
                ?: return EndPlusTarget.At(from)
        // Each step ends later, so this stops; past the last item the row is always free.
        while (true) {
            anchor = blocking(anchor.endMs).minByOrNull { it.startMs } ?: break
        }
        // (The item in the way always reaches past [latest] here, so the row is never free at [latest] then.)
        return when {
            anchor.endMs <= latest -> EndPlusTarget.After(anchor.id)
            anchor.endMs <= videoMs - minOf(MIN_SEEN_MS, seen).coerceAtLeast(Limits.MIN_CLIP_MS) -> EndPlusTarget.After(anchor.id)
            else -> EndPlusTarget.At(latest, full = true)
        }
    }

    fun main(
        spans: List<SlotSpan>,
        cursorMs: Long,
    ): MainEndTarget? {
        if (spans.isEmpty()) return null
        val n = spans.size

        fun allowed(b: Int) = TimelineGeometry.insertAllowed(spans, b)

        fun at(b: Int) = if (b > 0) MainEndTarget(b - 1, InsertSide.AFTER) else MainEndTarget(0, InsertSide.BEFORE)

        // Exactly between two items (no transition there), at either end, or at the very start.
        for (b in 0..n) {
            val time =
                when {
                    b == 0 -> spans[0].startMs
                    b == n -> spans[n - 1].endMs
                    spans[b - 1].endMs <= spans[b].startMs -> spans[b].startMs
                    else -> null
                }
            if (time == cursorMs && allowed(b)) return at(b)
        }
        val under = spans.indices.lastOrNull { cursorMs >= spans[it].startMs && cursorMs < spans[it].endMs }
        if (under != null) {
            if (allowed(under + 1)) return MainEndTarget(under, InsertSide.AFTER)
            // The ending: just before it.
            if (allowed(under)) return MainEndTarget(under, InsertSide.BEFORE)
        }
        // Past the end (or nothing under the cursor): the last place allowed, just before an ending.
        return (n downTo 0).firstOrNull { allowed(it) }?.let { at(it) }
    }
}
