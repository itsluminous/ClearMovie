package com.itsluminous.clearmovie.ui.editor.timeline

import kotlin.math.abs

/** One MAIN item as the timeline lays it out. [content] is false for the intro and the ending. */
data class SlotSpan(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val content: Boolean,
)

/** Which side of an item a "+" inserts on (mirrors the model's `Side`). */
enum class InsertSide { BEFORE, AFTER }

/**
 * A "+" insert point. [anchorIndex] and [side] say where the new item goes;
 * [xDp] is its centre on the time span. [boundary] is the boundary index
 * (0 before the first item, n after the last).
 */
data class PlusPoint(
    val boundary: Int,
    val anchorIndex: Int,
    val side: InsertSide,
    val xDp: Double,
    /** True when the point was moved off the selected item's trim handle. */
    val displaced: Boolean,
)

/** What a touch landed on, in priority order (ARCHITECTURE section 11.5). */
sealed interface HitTarget {
    data class Plus(
        val point: PlusPoint,
    ) : HitTarget

    data class TrimStart(
        val index: Int,
    ) : HitTarget

    data class TrimEnd(
        val index: Int,
    ) : HitTarget

    /** The ruler, or the playhead's handle on it: scrubs. */
    data object Ruler : HitTarget

    data class Clip(
        val index: Int,
    ) : HitTarget

    /** Anywhere else on the rows: scrolls. */
    data object Empty : HitTarget
}

/**
 * The MAIN row's geometry at one zoom (ARCHITECTURE sections 11.5 and 11.6),
 * in dp on the time span: x 0 is the left edge of the visible span, y 0 the
 * top of the ruler. It decides where the clips, the trim handles and the "+"
 * points go, and what a touch hits. Pure, so the rules are tested on the JVM:
 *
 * - One "+" per boundary where two items touch, half over each side. Never
 *   before the intro or after the ending.
 * - A "+" shows on every boundary when the items on both sides are at least
 *   [PLUS_ROOM_DP] wide; below that only around the selected item and on a
 *   boundary under the playhead.
 * - On the selected item the trim handles take its inner edges, so its "+"
 *   points move outside it, [HANDLE_GAP_DP] clear of the handles' hit area.
 * - No two "+" hit areas overlap: the one around the selected item wins,
 *   then the one under the playhead, then the earlier one.
 */
class TimelineGeometry(
    val spans: List<SlotSpan>,
    val zoom: ZoomState,
    val widthDp: Double,
    val rulerDp: Double,
    val mainRowDp: Double,
    val selected: Int?,
    val playheadMs: Double,
    /** Whether the selected item has trim handles (a video, photo or card; not the intro or ending). */
    val selectedTrimmable: Boolean,
) {
    val mainTop: Double get() = rulerDp
    val mainBottom: Double get() = rulerDp + mainRowDp
    val mainCentre: Double get() = rulerDp + mainRowDp / 2

    fun left(i: Int): Double = zoom.xOf(spans[i].startMs.toDouble())

    fun right(i: Int): Double = zoom.xOf(spans[i].endMs.toDouble())

    fun width(i: Int): Double = right(i) - left(i)

    /** Indices of items at least partly on screen. */
    fun visible(): IntRange {
        val first = spans.indexOfFirst { zoom.xOf(it.endMs.toDouble()) >= -OFFSCREEN_DP }
        if (first < 0) return IntRange.EMPTY
        val last = spans.indexOfLast { zoom.xOf(it.startMs.toDouble()) <= widthDp + OFFSCREEN_DP }
        return if (last < first) IntRange.EMPTY else first..last
    }

    /** The x of boundary [b]: before item b, or after the last item. */
    fun boundaryX(b: Int): Double = if (b < spans.size) left(b) else right(spans.lastIndex)

    private fun insertAllowed(b: Int): Boolean {
        if (spans.isEmpty()) return false
        val before = spans.getOrNull(b - 1)
        val after = spans.getOrNull(b)
        // Nothing goes before the intro or after the ending.
        if (before == null && after?.content == false) return false
        if (after == null && before?.content == false) return false
        return true
    }

    private fun anchorFor(b: Int): Pair<Int, InsertSide> = if (b > 0) (b - 1) to InsertSide.AFTER else b to InsertSide.BEFORE

    private fun wideEnough(i: Int?): Boolean = i == null || i !in spans.indices || width(i) >= PLUS_ROOM_DP

    /** The "+" points to draw. */
    fun plusPoints(): List<PlusPoint> {
        if (spans.isEmpty()) return emptyList()
        val sel = selected?.takeIf { it in spans.indices }
        val candidates = mutableListOf<Pair<PlusPoint, Int>>() // point to priority (lower wins)
        val playheadX = zoom.xOf(playheadMs)
        for (b in 0..spans.size) {
            if (!insertAllowed(b)) continue
            val x = boundaryX(b)
            val (anchor, side) = anchorFor(b)
            val aroundSelected = sel != null && (b == sel || b == sel + 1)
            val underPlayhead = abs(playheadX - x) <= PLUS_HIT_DP / 2
            val roomy = wideEnough(b - 1) && wideEnough(b)
            if (!aroundSelected && !underPlayhead && !roomy) continue
            if (aroundSelected) {
                // Around the selected item the "+" always inserts next to it; with trim
                // handles it moves outside the item, on the neighbour's side.
                val s = if (b == sel) InsertSide.BEFORE else InsertSide.AFTER
                val outward = if (b == sel) -1.0 else 1.0
                val px = if (selectedTrimmable) x + outward * DISPLACE_DP else x
                candidates += PlusPoint(b, sel!!, s, px, displaced = selectedTrimmable) to 0
            } else {
                candidates += PlusPoint(b, anchor, side, x, displaced = false) to if (underPlayhead) 1 else 2
            }
        }
        val kept = mutableListOf<PlusPoint>()
        for ((point, _) in candidates.sortedWith(compareBy({ it.second }, { it.first.boundary }))) {
            if (kept.none { abs(it.xDp - point.xDp) < PLUS_HIT_DP }) kept += point
        }
        return kept.sortedBy { it.boundary }
    }

    /** The trim handles' hit areas (x ranges) on the selected item, or null. */
    fun handleHitAreas(): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>>? {
        val sel = selected?.takeIf { it in spans.indices && selectedTrimmable } ?: return null
        val l = left(sel)
        val r = right(sel)
        val outside = (HANDLE_HIT_DP - HANDLE_DP) / 2
        return (l - outside)..(l + HANDLE_DP + outside) to (r - HANDLE_DP - outside)..(r + outside)
    }

    /** What a touch at ([x], [y]) hits. */
    fun hitTest(
        x: Double,
        y: Double,
    ): HitTarget {
        if (y < mainTop) return HitTarget.Ruler
        val onMain = y in mainTop..mainBottom
        if (onMain) {
            val plus =
                plusPoints()
                    .filter { abs(it.xDp - x) <= PLUS_HIT_DP / 2 && abs(y - mainCentre) <= PLUS_HIT_DP / 2 }
                    .minByOrNull { abs(it.xDp - x) }
            if (plus != null) return HitTarget.Plus(plus)
            handleHitAreas()?.let { (start, end) ->
                val inStart = x in start
                val inEnd = x in end
                val sel = selected!!
                if (inStart && inEnd) {
                    return if (abs(x - left(sel)) <= abs(x - right(sel))) HitTarget.TrimStart(sel) else HitTarget.TrimEnd(sel)
                }
                if (inStart) return HitTarget.TrimStart(sel)
                if (inEnd) return HitTarget.TrimEnd(sel)
            }
            val i = spans.indices.firstOrNull { x >= left(it) && x < right(it) }
            if (i != null) return HitTarget.Clip(i)
        }
        return HitTarget.Empty
    }

    companion object {
        /** A "+" shows on every boundary once both neighbours are this wide on screen. */
        const val PLUS_ROOM_DP = 64.0
        const val PLUS_DP = 24.0
        const val PLUS_HIT_DP = 48.0
        const val HANDLE_DP = 24.0
        const val HANDLE_HIT_DP = 48.0

        /** The clear space between a moved "+" hit area and a trim handle's. */
        const val HANDLE_GAP_DP = 12.0

        /** How far a "+" moves off the selected item's edge: half a handle hit outside, the gap, half a "+" hit. */
        const val DISPLACE_DP = (HANDLE_HIT_DP - HANDLE_DP) / 2 + HANDLE_GAP_DP + PLUS_HIT_DP / 2

        private const val OFFSCREEN_DP = 64.0

        /**
         * Where a dragged content item lands: its content index among the
         * other content items, from the x of its centre. [spans] are the
         * items as they were before the drag.
         */
        fun dropIndex(
            spans: List<SlotSpan>,
            dragged: Int,
            centreXDp: Double,
            zoom: ZoomState,
        ): Int {
            val others = spans.indices.filter { it != dragged && spans[it].content }
            return others.count { i ->
                val mid = (spans[i].startMs + spans[i].endMs) / 2.0
                zoom.xOf(mid) < centreXDp
            }
        }
    }
}
