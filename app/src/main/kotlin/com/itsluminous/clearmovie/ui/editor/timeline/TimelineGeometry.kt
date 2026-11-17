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
    /**
     * True when it sits on the row's top edge, above a transition marker
     * (ARCHITECTURE section 11.6), rather than on the row's centre line.
     */
    val top: Boolean = false,
)

/**
 * A transition marker (ARCHITECTURE section 11.10): a bow tie on the row's
 * centre line, in the middle of the overlap between item [leadIndex] and the
 * next one. Tapping it opens that transition.
 */
data class MarkerPoint(
    val boundary: Int,
    val leadIndex: Int,
    val xDp: Double,
)

/** What a touch landed on, in priority order (ARCHITECTURE section 11.5). */
sealed interface HitTarget {
    data class Plus(
        val point: PlusPoint,
    ) : HitTarget

    data class Marker(
        val point: MarkerPoint,
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
 * - One "+" at the end of the row, and one on each side of the selected item
 *   only (see [plusPoints]). Never before the intro or after the ending.
 * - On the selected item the trim handles take its inner edges, so its "+"
 *   points move outside it, [HANDLE_GAP_DP] clear of the handles' hit area.
 * - No two "+" hit areas overlap, and none overlaps a trim handle's.
 * - A junction with a transition has a marker in the middle of the overlap,
 *   in the lower part of the row; its "+" moves up to the row's top edge
 *   (ARCHITECTURE section 11.6), its hit area just above the marker's, so
 *   the two never share a spot and both keep a 48 dp hit area. A "+" that
 *   would come within a marker's hit area moves up the same way. A marker
 *   gives way to the selected item's trim handles (the bar's Transition
 *   opens it then) and to an earlier marker closer than its hit area.
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

    private fun insertAllowed(b: Int): Boolean {
        if (spans.isEmpty()) return false
        val before = spans.getOrNull(b - 1)
        val after = spans.getOrNull(b)
        // Nothing goes before the intro or after the ending.
        if (before == null && after?.content == false) return false
        if (after == null && before?.content == false) return false
        return true
    }

    /** Whether items b - 1 and b overlap (a transition joins them). */
    private fun overlaps(b: Int): Boolean = b in 1 until spans.size && spans[b - 1].endMs > spans[b].startMs

    /** The transition markers to draw (worked out once: the geometry never changes). */
    fun markers(): List<MarkerPoint> = markerCache

    private val markerCache: List<MarkerPoint> by lazy { computeMarkers() }

    private fun computeMarkers(): List<MarkerPoint> {
        val handles = handleHitAreas()?.toList().orEmpty()
        val kept = mutableListOf<MarkerPoint>()
        for (b in 1 until spans.size) {
            if (!overlaps(b)) continue
            val x = (left(b) + right(b - 1)) / 2
            val clear =
                handles.all { h ->
                    x + MARKER_HIT_DP / 2 + HANDLE_GAP_DP <= h.start ||
                        x - MARKER_HIT_DP / 2 - HANDLE_GAP_DP >= h.endInclusive
                }
            if (!clear) continue
            if (kept.any { abs(it.xDp - x) < MARKER_HIT_DP }) continue
            kept += MarkerPoint(b, b - 1, x)
        }
        return kept
    }

    /** The centre of [p]'s hit area, in the geometry's y. */
    fun plusCentreY(p: PlusPoint): Double = if (p.top) mainTop else mainCentre

    /** The centre of a marker's hit area: right under a lifted "+", so the two hit areas touch and never overlap. */
    val markerCentre: Double get() = mainTop + PLUS_HIT_DP / 2 + MARKER_HIT_DP / 2

    /**
     * The "+" points to draw (the owner's rule, replacing cm-timeline's):
     * - one at the end of the row: after the last photo, video or card, or
     *   after the intro when there are none yet;
     * - on the selected item only, one on each side, placed beyond its trim
     *   handles so neither touch area overlaps a handle's;
     * - none between unselected items.
     * A "+" whose hit area would touch a transition marker's moves up to the
     * row's top edge at the same x (section 11.6). A side "+" past the end of
     * the view is left out: selecting moves the view so it shows (TimelineView),
     * and the item's menu has Add before and Add after.
     */
    fun plusPoints(): List<PlusPoint> {
        val markers = markers()
        if (spans.isEmpty()) return emptyList()
        val sel = selected?.takeIf { it in spans.indices }
        val out = mutableListOf<PlusPoint>()
        val handles = handleHitAreas()?.toList().orEmpty()
        val half = PLUS_HIT_DP / 2

        fun clearOfHandles(x: Double) =
            handles.all { h -> x + half + HANDLE_GAP_DP <= h.start || x - half - HANDLE_GAP_DP >= h.endInclusive }

        fun onScreen(x: Double) = x - half >= -EDGE_ROOM_DP - EPS && x + half <= widthDp + EDGE_ROOM_DP + EPS
        if (sel != null) {
            val outward = if (selectedTrimmable) DISPLACE_DP else 0.0
            val before = left(sel) - outward
            val after = right(sel) + outward
            if (insertAllowed(sel) && onScreen(before)) out += PlusPoint(sel, sel, InsertSide.BEFORE, before, displaced = selectedTrimmable)
            if (insertAllowed(sel + 1) &&
                onScreen(after)
            ) {
                out += PlusPoint(sel + 1, sel, InsertSide.AFTER, after, displaced = selectedTrimmable)
            }
        }
        val lastContent = spans.indexOfLast { it.content }
        val end =
            when {
                lastContent >= 0 -> PlusPoint(lastContent + 1, lastContent, InsertSide.AFTER, right(lastContent), displaced = false)
                insertAllowed(1) -> PlusPoint(1, 0, InsertSide.AFTER, right(0), displaced = false)
                insertAllowed(0) -> PlusPoint(0, 0, InsertSide.BEFORE, left(0), displaced = false)
                else -> null
            }
        // The selected item's own "+" already covers the end when it is the last one.
        if (end != null && out.none { it.boundary == end.boundary || abs(it.xDp - end.xDp) < PLUS_HIT_DP } && clearOfHandles(end.xDp)) {
            out += end
        }
        // Never on a transition marker: a "+" that would touch one goes up to the row's top edge.
        return out
            .map { p -> if (markers.any { abs(it.xDp - p.xDp) < (PLUS_HIT_DP + MARKER_HIT_DP) / 2 }) p.copy(top = true) else p }
            .sortedBy { it.boundary }
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
        // The "+" points and markers come first, over whatever is under them (a lifted "+" reaches above the row).
        // The end "+" sits on an item's edge. Over a narrow item (under two "+" wide) it takes only taps on
        // its circle, so that item can still be selected; everywhere else it has its full 48 dp.
        val under = spans.indices.firstOrNull { x >= left(it) && x < right(it) }
        val plus =
            plusPoints()
                .filter {
                    val narrow = under != null && !it.displaced && !it.top && width(under) < 2 * PLUS_HIT_DP
                    val reach = if (narrow) PLUS_CORE_DP else PLUS_HIT_DP / 2
                    abs(it.xDp - x) <= reach && abs(y - plusCentreY(it)) <= PLUS_HIT_DP / 2
                }.minByOrNull { abs(it.xDp - x) }
        if (plus != null) return HitTarget.Plus(plus)
        val marker =
            markers()
                .filter { abs(it.xDp - x) <= MARKER_HIT_DP / 2 && abs(y - markerCentre) <= MARKER_HIT_DP / 2 }
                .minByOrNull { abs(it.xDp - x) }
        if (marker != null) return HitTarget.Marker(marker)
        if (y < mainTop) return HitTarget.Ruler
        val onMain = y in mainTop..mainBottom
        if (onMain) {
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
        const val PLUS_DP = 24.0
        const val PLUS_HIT_DP = 48.0
        const val HANDLE_DP = 24.0
        const val MARKER_DP = 24.0
        const val MARKER_HIT_DP = 48.0

        /** How far a lifted "+" hit area reaches above the row (half of it: it is centred on the top edge). */
        const val ABOVE_ROW_DP = PLUS_HIT_DP / 2
        const val HANDLE_HIT_DP = 48.0

        /** The clear space between a moved "+" hit area and a trim handle's. */
        const val HANDLE_GAP_DP = 12.0

        /** How far a "+" moves off the selected item's edge: half a handle hit outside, the gap, half a "+" hit. */
        const val DISPLACE_DP = (HANDLE_HIT_DP - HANDLE_DP) / 2 + HANDLE_GAP_DP + PLUS_HIT_DP / 2

        private const val OFFSCREEN_DP = 64.0

        /** How far past the time span a "+" may sit: the span's padding at each end (TimelineView's PAD_DP). */
        const val EDGE_ROOM_DP = 28.0

        /** Over a narrow item, how close to the end "+" a tap must be: its drawn circle. */
        const val PLUS_CORE_DP = 12.0

        private const val EPS = 1e-6

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
