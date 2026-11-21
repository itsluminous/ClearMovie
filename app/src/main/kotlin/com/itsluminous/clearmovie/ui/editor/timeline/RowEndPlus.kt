package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.domain.model.ClipId

/** Where the "+" at a row's right end adds on a row other than MAIN, worked out from the cursor when it is tapped. */
sealed interface EndPlusTarget {
    /** At the cursor: nothing is on that row there ("Add here", from that time). */
    data object AtCursor : EndPlusTarget

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
 *   new item's length ([lengthMs]: a caption's 3 s on a text row; one moment
 *   where the length is not known until a file is picked). Otherwise right
 *   after the item under the cursor (or the next one, when the cursor is in a
 *   gap too short), and past every item that still leaves no room: the first
 *   place to the right of the cursor where it fits. A picked file longer than
 *   that room goes, as every insert on these rows does, on the highest free
 *   row of its kind or a new one, at the same time.
 * - MAIN (one item after another): at the cursor when it is exactly between
 *   two items; on an item, right after it, and every later item moves later.
 *   In a transition two items are under the cursor: after the later one, so
 *   the new item never starts left of the cursor. Never before the intro or
 *   after the ending: on the ending, or past the end, it goes just before the
 *   ending.
 */
object RowEndPlus {
    fun timed(
        clips: List<RowClip>,
        cursorMs: Long,
        lengthMs: Long = 0,
    ): EndPlusTarget {
        val live = clips.filter { it.endMs > it.startMs }
        val length = lengthMs.coerceAtLeast(1)

        // The items in the way of a new item from [start].
        fun blocking(start: Long) = live.filter { it.startMs < start + length && it.endMs > start }
        var anchor =
            live.filter { cursorMs >= it.startMs && cursorMs < it.endMs }.maxByOrNull { it.endMs }
                ?: blocking(cursorMs).minByOrNull { it.startMs }
                ?: return EndPlusTarget.AtCursor
        // Each step ends later, so this stops; past the last item the row is always free.
        while (true) {
            anchor = blocking(anchor.endMs).minByOrNull { it.startMs } ?: break
        }
        return EndPlusTarget.After(anchor.id)
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
