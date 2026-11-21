package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.domain.model.ClipId

/** Where the "+" at a row's right end adds on a row other than MAIN, worked out from the cursor when it is tapped. */
sealed interface EndPlusTarget {
    /** At the cursor: nothing is on that row at the cursor ("Add here", which starts at the playhead). */
    data object AtCursor : EndPlusTarget

    /** Right after item [anchor]: the cursor is on it, or on the items touching it from the left. */
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
 * the cursor (the playhead), so it starts at the cursor. When the cursor is
 * on an item of that row, the new item goes right after that item. Pure, so
 * the rules are tested on the JVM.
 *
 * - A row other than MAIN: in a gap, at the cursor. On an item, right after
 *   it; when the next item starts exactly where that one ends (the space
 *   after it is taken too), after the last of the items that touch, the first
 *   moment the row is free. Where the new item is then too long for the gap,
 *   the model puts it on the highest free row of its kind or a new one, at
 *   the same time, as every insert on these rows already does.
 * - MAIN (one item after another): at the cursor when it is exactly between
 *   two items; on an item, right after it, and every later item moves later.
 *   In a transition two items are under the cursor: after the later one, so
 *   the new item never lands left of the cursor. Never before the intro or
 *   after the ending: on the ending, or past the end, it goes after the last
 *   photo, video or card.
 */
object RowEndPlus {
    fun timed(
        clips: List<RowClip>,
        cursorMs: Long,
    ): EndPlusTarget {
        val live = clips.filter { it.endMs > it.startMs }
        var under =
            live.filter { cursorMs >= it.startMs && cursorMs < it.endMs }.maxByOrNull { it.endMs }
                ?: return EndPlusTarget.AtCursor
        // Each step ends later, so this stops.
        while (true) {
            val end = under.endMs
            under = live.filter { end >= it.startMs && end < it.endMs }.maxByOrNull { it.endMs } ?: break
        }
        return EndPlusTarget.After(under.id)
    }

    fun main(
        spans: List<SlotSpan>,
        cursorMs: Long,
    ): MainEndTarget? {
        if (spans.isEmpty()) return null
        val n = spans.size

        fun allowed(b: Int): Boolean {
            val before = spans.getOrNull(b - 1)
            val after = spans.getOrNull(b)
            // The same rule as TimelineGeometry: nothing before the intro, nothing after the ending.
            if (before == null && after?.content == false) return false
            if (after == null && before?.content == false) return false
            return true
        }

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
        val lastContent = spans.indexOfLast { it.content }
        return when {
            lastContent >= 0 -> MainEndTarget(lastContent, InsertSide.AFTER)
            allowed(1) -> MainEndTarget(0, InsertSide.AFTER)
            allowed(0) -> MainEndTarget(0, InsertSide.BEFORE)
            else -> null
        }
    }
}
