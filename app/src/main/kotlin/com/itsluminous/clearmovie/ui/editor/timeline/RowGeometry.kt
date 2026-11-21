package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.ResizeRange
import kotlin.math.abs
import kotlin.math.roundToInt

/** What kind of item a clip on a row other than MAIN is, for its icon, colour and spoken name. */
enum class RowClipKind { TEXT, PHOTO, VIDEO, OVERLAY, SOUND }

/**
 * One item on a row other than MAIN. [text] is a caption's words. [resize]
 * is where its handles may take each side (null: no handles).
 */
data class RowClip(
    val id: ClipId,
    val startMs: Long,
    val endMs: Long,
    val kind: RowClipKind,
    val text: String? = null,
    val resize: ResizeRange? = null,
)

/** A "+" on a row other than MAIN: a new item goes on [side] of [anchor]; [xDp] is its centre on the time span. */
data class TimedPlus(
    val anchor: ClipId,
    val side: InsertSide,
    val xDp: Double,
)

/** What a touch on a row other than MAIN landed on. */
sealed interface TimedHit {
    data class Plus(
        val plus: TimedPlus,
    ) : TimedHit

    data class Clip(
        val id: ClipId,
    ) : TimedHit

    /** A trim handle of the selected item: [start] for its start, else its end. */
    data class Handle(
        val id: ClipId,
        val start: Boolean,
    ) : TimedHit

    /** The row, away from its items. */
    data object Space : TimedHit
}

/**
 * The "+" points and hits of a row whose items have their own times (text,
 * picture in picture, frame, music, voice), in dp on the time span (the
 * owner's rule, the same as MAIN's): on the selected item only, one on each
 * side, just outside it (beyond its trim handles, as on MAIN, when it has
 * them); none between unselected items. The row's own "+" is in the column
 * at its right end, drawn by the timeline itself ([RowEndPlus],
 * cm-plus-position). The selected item's trim handles take its inner edges,
 * 24 dp drawn and 48 dp to touch. Pure, so the rules are tested on the JVM.
 */
object TimedRowGeometry {
    fun plusPoints(
        clips: List<RowClip>,
        zoom: ZoomState,
        selected: ClipId?,
        widthDp: Double = Double.POSITIVE_INFINITY,
    ): List<TimedPlus> {
        val sorted = clips.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val half = TimelineGeometry.PLUS_HIT_DP / 2
        // Kept whole inside the span's padding at its start or the "+" column at its end, so its full 48 dp is on screen.
        val lo = -TimelineGeometry.EDGE_ROOM_DP + half
        val hi = widthDp + TimelineGeometry.END_DP - half

        fun TimedPlus.kept() = copy(xDp = xDp.coerceIn(lo, maxOf(lo, hi)))
        val out = mutableListOf<TimedPlus>()
        val sel = sorted.firstOrNull { it.id == selected }
        val handles = sel?.let { handleHitAreas(it, zoom) }

        fun clear(p: TimedPlus) =
            handles == null ||
                handles.toList().all { h ->
                    p.xDp + half + TimelineGeometry.HANDLE_GAP_DP <= h.start ||
                        p.xDp - half - TimelineGeometry.HANDLE_GAP_DP >= h.endInclusive
                }
        // The selected item: one "+" on each side, just outside it (and its handles), so the item stays easy to touch.
        // One pulled in from past the view's edge onto a handle is left out, as on MAIN: the item's menu has Add before and after.
        sel?.let { c ->
            val outward = if (handles != null) TimelineGeometry.DISPLACE_DP else half
            out +=
                listOf(
                    TimedPlus(c.id, InsertSide.BEFORE, zoom.xOf(c.startMs.toDouble()) - outward).kept(),
                    TimedPlus(c.id, InsertSide.AFTER, zoom.xOf(c.endMs.toDouble()) + outward).kept(),
                ).filter(::clear)
        }
        return out.sortedBy { it.xDp }
    }

    /** The selected item's two handle hit areas (x ranges, start then end), or null when it has no handles. */
    fun handleHitAreas(
        clip: RowClip,
        zoom: ZoomState,
    ): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>>? {
        if (clip.resize == null || clip.endMs <= clip.startMs) return null
        val l = zoom.xOf(clip.startMs.toDouble())
        val r = zoom.xOf(clip.endMs.toDouble())
        val outside = (TimelineGeometry.HANDLE_HIT_DP - TimelineGeometry.HANDLE_DP) / 2
        return (l - outside)..(l + TimelineGeometry.HANDLE_DP + outside) to
            (r - TimelineGeometry.HANDLE_DP - outside)..(r + outside)
    }

    /** What a touch at [x] (dp on the time span) hits on a row with [clips] and [points]. */
    fun hitTest(
        clips: List<RowClip>,
        points: List<TimedPlus>,
        zoom: ZoomState,
        x: Double,
        selected: ClipId? = null,
    ): TimedHit {
        val t = zoom.timeAt(x)
        val clip = clips.firstOrNull { t >= it.startMs && t < it.endMs }
        // Every "+" here sits outside its item, so it keeps its full 48 dp.
        points
            .filter { abs(it.xDp - x) <= TimelineGeometry.PLUS_HIT_DP / 2 }
            .minByOrNull { abs(it.xDp - x) }
            ?.let { return TimedHit.Plus(it) }
        // Then the selected item's handles, over whatever is under them; on a narrow item the nearer edge wins.
        clips.firstOrNull { it.id == selected }?.let { sel ->
            val (start, end) = handleHitAreas(sel, zoom) ?: return@let
            val inStart = x in start
            val inEnd = x in end
            if (inStart && inEnd) {
                val nearStart = abs(x - zoom.xOf(sel.startMs.toDouble())) <= abs(x - zoom.xOf(sel.endMs.toDouble()))
                return TimedHit.Handle(sel.id, nearStart)
            }
            if (inStart) return TimedHit.Handle(sel.id, true)
            if (inEnd) return TimedHit.Handle(sel.id, false)
        }
        return if (clip != null) TimedHit.Clip(clip.id) else TimedHit.Space
    }
}

/** One shown row's band in the row list (dp from the top of the rows, under the ruler). */
data class RowBand(
    val key: String,
    val topDp: Double,
    val heightDp: Double,
) {
    val bottomDp: Double get() = topDp + heightDp
    val centreDp: Double get() = topDp + heightDp / 2
}

object RowOrder {
    /** The bands of [shownHeights] (key to height) stacked with [gapDp] between them. */
    fun bands(
        shownHeights: List<Pair<String, Double>>,
        gapDp: Double,
    ): List<RowBand> {
        var top = 0.0
        return shownHeights.map { (key, h) -> RowBand(key, top, h).also { top += h + gapDp } }
    }

    /**
     * The scroll (px) that brings [band] whole into a viewport of
     * [viewportPx] scrolled to [scrollPx], moving as little as it can, or
     * null when it is already whole in view (or nothing is laid out yet). A
     * row taller than the viewport shows its top.
     */
    fun revealScroll(
        band: RowBand,
        density: Float,
        scrollPx: Int,
        viewportPx: Int,
    ): Int? {
        if (viewportPx <= 0) return null
        val top = (band.topDp * density).roundToInt()
        val bottom = (band.bottomDp * density).roundToInt()
        return when {
            top < scrollPx -> top
            bottom > scrollPx + viewportPx -> minOf(bottom - viewportPx, top)
            else -> null
        }
    }

    /**
     * Where a row dragged by its icon lands among the SHOWN rows: the number
     * of other shown rows whose centre is above the dragged row's centre,
     * moved by [dyDp].
     */
    fun dropShownIndex(
        bands: List<RowBand>,
        key: String,
        dyDp: Double,
    ): Int {
        val dragged = bands.firstOrNull { it.key == key } ?: return 0
        val centre = dragged.centreDp + dyDp
        return bands.count { it.key != key && it.centreDp < centre }
    }

    /**
     * The index in the FULL screen order (after taking the row out, as
     * `Project.moveRow` counts) that puts [key] at [shownIndex] among the
     * [shown] rows. Rows that are folded away keep their places: the row goes
     * just before the shown row that will follow it, or just after the one
     * that will precede it when it goes last.
     */
    fun fullIndex(
        all: List<String>,
        shown: List<String>,
        key: String,
        shownIndex: Int,
    ): Int {
        val others = all - key
        val shownOthers = shown - key
        val k = shownIndex.coerceIn(0, shownOthers.size)
        val following = shownOthers.getOrNull(k)
        if (following != null) return others.indexOf(following)
        val preceding = shownOthers.getOrNull(k - 1) ?: return 0
        return others.indexOf(preceding) + 1
    }
}
