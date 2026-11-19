package com.itsluminous.clearmovie.ui.editor.timeline

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Test tags. */
const val TIMELINE_TAG = "timeline"
const val TIMELINE_RULER_TAG = "timeline_ruler"
const val TIMELINE_PLAYHEAD_TAG = "timeline_playhead"
const val TIMELINE_MAIN_ROW_TAG = "timeline_main_row"
const val TIMELINE_ROWS_TOGGLE_TAG = "timeline_rows_toggle"

fun clipTag(id: ClipId) = "timeline_clip_${id.value}"

/** The ruler's label spacing (ms), for tests: it changes with the zoom. Never read aloud. */
val RulerTickKey = SemanticsPropertyKey<Long>("RulerTick")
var SemanticsPropertyReceiver.rulerTick by RulerTickKey

fun plusTag(boundary: Int) = "timeline_plus_$boundary"

fun markerTag(boundary: Int) = "timeline_transition_$boundary"

fun thumbTag(id: ClipId) = "timeline_thumbs_${id.value}"

fun rowTag(key: String) = "timeline_row_$key"

fun headerTag(type: RowType) = "timeline_header_${type.name}"

/** The header of the row with [key] (any row, MAIN included). */
fun rowHeaderTag(key: String) = "timeline_row_header_$key"

/** The "+" on [side] of item [anchor], on a row other than MAIN. */
fun timedPlusTag(
    anchor: ClipId,
    side: InsertSide,
) = "timeline_plus_${anchor.value}_${side.name}"

/** The "+" of the empty row with [key]. */
fun rowAddTag(key: String) = "timeline_row_add_$key"

const val TRIM_START_TAG = "timeline_trim_start"
const val TRIM_END_TAG = "timeline_trim_end"

/** The kinds of row, for the header icon and name. */
enum class RowType { MAIN, TEXT, FRAME, PIP, MUSIC, VOICE, SOUND }

/** Where a picture row is drawn: above [below] of the [count] picture rows (sound rows are mixed, not stacked). */
@Immutable
data class StackPlace(
    val below: Int,
    val count: Int,
)

/**
 * A timeline row in screen order. [number] tells two rows of one kind apart
 * ("Text 2"), and is null for the only row of its kind. [fixed] is a row the
 * preset manages (its frame, the frame's music, the closing message): its
 * item stays on it, and nothing else is dropped on it.
 */
@Immutable
data class TimelineRow(
    val key: String,
    val type: RowType,
    val clips: List<RowClip> = emptyList(),
    val enabled: Boolean = true,
    val muted: Boolean = false,
    val number: Int? = null,
    val stack: StackPlace? = null,
    val fixed: Boolean = false,
)

/** A picture for an item's thumbnail strip: the [MainClip] at [sourceMs] of its file. */
typealias ThumbSource = suspend (MainClip, Long) -> Bitmap?

/** What the timeline asks its owner to do. */
interface TimelineActions {
    fun select(id: ClipId?)

    fun seek(ms: Long)

    fun trim(
        id: ClipId,
        edge: TrimEdge,
        deltaMs: Double,
    )

    fun move(
        id: ClipId,
        toContentIndex: Int,
    )

    fun insert(
        anchor: ClipId,
        side: InsertSide,
    )

    fun menu(id: ClipId)

    /** Opens the transition from MAIN item [lead] into the next one (its marker was tapped). */
    fun transition(lead: ClipId)

    fun showAllRows()

    fun showFewerRows()

    /** A tap on a row's icon: the row is selected and the bar shows its actions. */
    fun selectRow(key: String) {}

    /** A long-press on a row's icon, or on its empty space, released without moving. */
    fun rowMenu(key: String) {}

    /** The "+" on an empty row: something new on it, at the playhead. */
    fun addHere(key: String) {}

    /** A row dragged by its icon: to [toIndex] of the screen order (counted with the row taken out). */
    fun moveRow(
        key: String,
        toIndex: Int,
    ) {}

    /** An item on a row other than MAIN dragged to [startMs], on row [toRow] (its own, or another of its kind). */
    fun moveTimed(
        id: ClipId,
        startMs: Long,
        toRow: String,
    ) {}
}

/** Heights at a font scale (ARCHITECTURE section 11.3: MAIN 72 dp, others 48 dp, growing with the text). */
data class RowMetrics(
    val fontScale: Float,
) {
    private val grow = fontScale.coerceAtLeast(1f)
    val rulerDp: Double get() = 28.0 * grow
    val mainDp: Double get() = 72.0 + 24.0 * (grow - 1)
    val rowDp: Double get() = 48.0 * grow

    /** The row headers are an icon each (the owner's call: no label, so the rows keep the width); 48 dp to touch. */
    val headerDp: Double get() = HEADER_DP
    val toggleDp: Double get() = 48.0 * grow

    fun heightOf(type: RowType): Double = if (type == RowType.MAIN) mainDp else rowDp
}

/** The width of a row's icon header: a 24 dp icon in a 48 dp touch target. */
private const val HEADER_DP = 48.0

/** How much of a text card's words TalkBack reads with it. */
private const val CARD_TEXT_SPOKEN = 60

/** Padding at each end of the time span, so the first and last "+" can be reached. */
private const val PAD_DP = 28.0

/**
 * The editor timeline (ARCHITECTURE sections 11.3 to 11.7): a ruler, the
 * playhead, and the rows in screen order, each with its type icon at its
 * left edge (the icon alone: TalkBack reads the row's name). The MAIN row
 * shows each item with thumbnails and its length, trim handles on the
 * selected item and the "+" insert points; every other row shows its items
 * at their times, with a "+" before and after each. One pointer handler runs
 * [TimelineGestureArbiter] over all of it, so a pinch, a scroll, a trim, a
 * scrub, a long-press drag and a tap never fight:
 *
 * - On a row's icon: a tap selects the row, a long-press then a drag up or
 *   down moves the row on screen (never in the video), and a long-press let
 *   go without moving opens the row menu.
 * - On an item: a long-press let go without moving opens its menu; a
 *   long-press then a drag reorders MAIN, or moves any other item in time
 *   and, dragged onto another row of its kind, to that row.
 *
 * Every gesture also has a button (the contextual bar, the menus, the zoom
 * buttons, the rows' and items' accessibility actions).
 */
@Composable
fun TimelineView(
    slots: List<MainSlot>,
    rows: List<TimelineRow>,
    visibleRows: List<String>,
    hiddenRows: List<TimelineRow>,
    allRowsRequested: Boolean,
    selected: ClipId?,
    missing: Set<ClipId>,
    playheadMs: Long,
    playing: Boolean,
    zoom: ZoomState?,
    onZoom: (ZoomState) -> Unit,
    thumbnail: ThumbSource,
    actions: TimelineActions,
    rowScroll: ScrollState,
    metrics: RowMetrics,
    modifier: Modifier = Modifier,
    onSpanWidth: (Double) -> Unit = {},
    selectedRow: String? = null,
) {
    val totalMs = slots.lastOrNull()?.endMs ?: 0L
    BoxWithConstraints(modifier.fillMaxWidth().testTag(TIMELINE_TAG)) {
        val fullWidth = maxWidth
        val spanWidth = (maxWidth.value - metrics.headerDp - 2 * PAD_DP).coerceAtLeast(1.0)
        val z = zoom ?: TimelineZoom.fitAll(totalMs, spanWidth)
        LaunchedEffect(spanWidth) { onSpanWidth(spanWidth) }
        // A new width (rotation) or length (an edit): the same zoom, clamped; fit all stays fit all.
        LaunchedEffect(spanWidth, totalMs, zoom == null) {
            val clamped = TimelineZoom.reclamp(z, totalMs, spanWidth)
            if (zoom == null || clamped != zoom) onZoom(clamped)
        }
        // While playing, the view follows the playhead.
        // A finger on the timeline wins: the view follows the playhead only when nobody is touching it.
        var touching by remember { mutableStateOf(false) }
        // Moving the view by hand while playing stops the following until Play is pressed again,
        // so the user can look ahead without being pulled back.
        var followPaused by remember { mutableStateOf(false) }
        LaunchedEffect(playing) { if (playing) followPaused = false }
        LaunchedEffect(touching) { if (touching && playing) followPaused = true }
        LaunchedEffect(playheadMs, playing, touching, followPaused) {
            if (playing && !touching && !followPaused) {
                val revealed = TimelineZoom.reveal(z, playheadMs.toDouble(), totalMs, spanWidth)
                if (revealed != z) onZoom(revealed)
            }
        }
        val selectedIndex = slots.indexOfFirst { it.clip.id == selected }.takeIf { it >= 0 }
        // A newly selected item shows a "+" on each side (on MAIN beyond its trim handles). When one would be
        // cut off by the end of the view, the view moves just enough (past the video's own start or end if it
        // must) to show it. Only a small shift: a long item already well in view never jumps.
        LaunchedEffect(selected) {
            val sel = selected ?: return@LaunchedEffect
            val onMain = slots.any { it.clip.id == sel }
            val span =
                slots.firstOrNull { it.clip.id == sel }?.let { it.startMs to it.endMs }
                    ?: rows.firstNotNullOfOrNull { r -> r.clips.firstOrNull { it.id == sel }?.let { it.startMs to it.endMs } }
                    ?: return@LaunchedEffect
            val outward = if (onMain) TimelineGeometry.DISPLACE_DP else TimelineGeometry.PLUS_HIT_DP / 2
            val room = outward + TimelineGeometry.PLUS_HIT_DP / 2 - PAD_DP
            val left = z.xOf(span.first.toDouble())
            val right = z.xOf(span.second.toDouble())
            val dx =
                when {
                    left < room && room - left <= 2 * room -> {
                        left - room
                    }

                    right > spanWidth - room && right - (spanWidth - room) <= 2 * room && right - left < spanWidth - 2 * room -> {
                        right -
                            (spanWidth - room)
                    }

                    else -> {
                        0.0
                    }
                }
            if (dx != 0.0) onZoom(TimelineZoom.nudgePastEnds(z, dx, room, totalMs, spanWidth))
        }
        val spans = remember(slots) { slots.map { SlotSpan(it.clip.id.value, it.startMs, it.endMs, it.clip.isContent()) } }
        val selectedClip = selectedIndex?.let { slots[it].clip }
        var trim by remember { mutableStateOf<TrimPreview?>(null) }
        var drag by remember { mutableStateOf<DragPreview?>(null) }
        var rowDrag by remember { mutableStateOf<RowDragPreview?>(null) }
        var timedDrag by remember { mutableStateOf<TimedDragPreview?>(null) }
        // The layout during a trim shows the trimmed item at its new length.
        val shownSlots = trim?.let { t -> relayout(slots, t.index, TrimMath.dragged(slots[t.index].clip, t.edge, t.deltaMs)) } ?: slots
        val shownSpans =
            if (trim ==
                null
            ) {
                spans
            } else {
                shownSlots.map { SlotSpan(it.clip.id.value, it.startMs, it.endMs, it.clip.isContent()) }
            }
        val geometry =
            TimelineGeometry(
                spans = shownSpans,
                zoom = z,
                widthDp = spanWidth,
                rulerDp = metrics.rulerDp,
                mainRowDp = metrics.mainDp,
                selected = selectedIndex,
                playheadMs = playheadMs.toDouble(),
                selectedTrimmable = selectedClip?.isContent() == true && drag == null,
            )
        val shown = rows.filter { it.key in visibleRows }
        val bands = RowOrder.bands(shown.map { it.key to metrics.heightOf(it.type) }, PreviewLayoutReducer.ROW_GAP_DP)
        val mainTopInRows = shown.indexOfFirst { it.type == RowType.MAIN }.let { i -> if (i >= 0) bands[i].topDp else 0.0 }
        val timedPlus =
            shown.filter { it.type != RowType.MAIN }.associate { row ->
                row.key to TimedRowGeometry.plusPoints(row.clips, z, selected, spanWidth)
            }
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        // The handler outlives recompositions: it always calls the latest callbacks.
        val latestOnZoom by rememberUpdatedState(onZoom)
        val latestActionsState = rememberUpdatedState(actions)
        val latestActions = remember { LatestActions { latestActionsState.value } }
        val current by rememberUpdatedState(
            Current(
                z,
                geometry,
                spans,
                slots,
                spanWidth,
                totalMs,
                mainTopInRows,
                selectedIndex,
                shown,
                bands,
                timedPlus,
                rows.map { it.key },
                playheadMs,
            ),
        )

        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    // The "+" layer spans every row; rows scrolled out of view must not draw over the controls below.
                    .clipToBounds()
                    .pointerInput(metrics, rowScroll) {
                        timelineGestures(
                            density = { density.density },
                            current = { current },
                            metrics = metrics,
                            rowScroll = rowScroll,
                            onZoom = { latestOnZoom(it) },
                            actions = latestActions,
                            longPressed = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            previews =
                                Previews(
                                    trim = { trim = it },
                                    drag = { drag = it },
                                    row = { rowDrag = it },
                                    timed = { timedDrag = it },
                                ),
                            touched = { touching = it },
                        )
                    },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Ruler(z, totalMs, playheadMs, playing, metrics, spanWidth, latestActions)
                    Column(Modifier.fillMaxWidth().verticalScroll(rowScroll, enabled = false)) {
                        for ((i, row) in shown.withIndex()) {
                            val lift = rowLift(rowDrag, bands, i, row.key)
                            val dragged = rowDrag?.key == row.key
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(metrics.heightOf(row.type).dp)
                                    .offset { IntOffset(0, (lift * density.density).roundToInt()) }
                                    .zIndex(if (dragged) 1f else 0f)
                                    .then(if (dragged) Modifier.shadow(8.dp) else Modifier)
                                    .then(
                                        if (timedDrag?.toRow == row.key && timedDrag?.fromRow != row.key) {
                                            Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary))
                                        } else {
                                            Modifier
                                        },
                                    ).testTag(rowTag(row.key)),
                            ) {
                                RowHeader(row, row.key == selectedRow, metrics, shown, rows.map { it.key }, latestActions)
                                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                                    if (row.type == RowType.MAIN) {
                                        MainRowContent(
                                            playheadMs,
                                            shownSlots,
                                            geometry,
                                            selectedIndex,
                                            missing,
                                            thumbnail,
                                            actions,
                                            drag,
                                        )
                                    } else {
                                        TimedRowContent(row, z, selected, playheadMs, timedDrag, latestActions)
                                    }
                                }
                            }
                            Spacer(Modifier.height(PreviewLayoutReducer.ROW_GAP_DP.dp))
                        }
                    }
                }
                Playhead(z, playheadMs, metrics)
                // The "+" points over everything, the playhead line included, on every row. They fade out
                // while anything is dragged (a trim included) and come back at the new edges on release.
                // The layer reaches above the rows for a "+" lifted to MAIN's top edge over a transition marker.
                val lift = TimelineGeometry.ABOVE_ROW_DP
                androidx.compose.animation.AnimatedVisibility(
                    visible = drag == null && rowDrag == null && timedDrag == null && trim == null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (metrics.headerDp * density.density).roundToInt(),
                                    ((metrics.rulerDp - lift) * density.density).roundToInt(),
                                )
                            }.width((fullWidth.value - metrics.headerDp.toFloat()).dp)
                            .height(((bands.lastOrNull()?.bottomDp ?: 0.0) + lift).dp)
                            .clipToBounds(),
                    ) {
                        for ((i, row) in shown.withIndex()) {
                            val band = bands[i]
                            // MAIN's box starts [lift] above its row, so a lifted "+" fits; the others start at their row.
                            val above = if (row.type == RowType.MAIN) 0.0 else lift
                            Box(
                                Modifier
                                    // The scroll is read when placing, not composing: it changes every frame of a scroll.
                                    .offset { IntOffset(0, (((band.topDp + above) * density.density) - rowScroll.value).roundToInt()) }
                                    .fillMaxWidth()
                                    .height((band.heightDp + lift - above).dp),
                            ) {
                                if (row.type == RowType.MAIN && shownSlots.isEmpty()) {
                                    // Nothing on MAIN yet: its one "+" adds the first photos and videos.
                                    Box(Modifier.fillMaxSize().padding(top = lift.dp)) {
                                        RowAddButton(row, addHereX(z, playheadMs, spanWidth), latestActions)
                                    }
                                } else if (row.type == RowType.MAIN) {
                                    // In this box the row's top edge is at y = lift.
                                    val markerY = lift + (geometry.markerCentre - geometry.mainTop)
                                    for (m in geometry.markers()) TransitionMarker(m, markerY, shownSlots, actions)
                                    val plus = geometry.plusPoints()
                                    for (b in 0..shownSlots.size) {
                                        PlusButton(
                                            plus.firstOrNull { it.boundary == b },
                                            b,
                                            shownSlots,
                                            actions,
                                            lift,
                                            lift + metrics.mainDp / 2,
                                        )
                                    }
                                } else if (row.clips.isEmpty() && !row.fixed) {
                                    // An empty row still has its "+": something new on it, at the playhead.
                                    RowAddButton(row, addHereX(z, playheadMs, spanWidth), latestActions)
                                } else {
                                    for (p in timedPlus[row.key].orEmpty()) {
                                        if (p.xDp < -TimelineGeometry.PLUS_HIT_DP ||
                                            p.xDp > spanWidth + TimelineGeometry.PLUS_HIT_DP
                                        ) {
                                            continue
                                        }
                                        TimedPlusButton(p, row, actions)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            RowsToggle(hiddenRows, allRowsRequested, rows.size > visibleRows.size || allRowsRequested, metrics, actions)
        }
    }
}

/** How far row [index] is drawn from its place while a row is dragged: the dragged one follows the finger, the others make room. */
private fun rowLift(
    drag: RowDragPreview?,
    bands: List<RowBand>,
    index: Int,
    key: String,
): Double {
    if (drag == null) return 0.0
    if (drag.key == key) return drag.dyDp
    val from = bands.indexOfFirst { it.key == drag.key }
    if (from < 0) return 0.0
    val room = bands[from].heightDp + PreviewLayoutReducer.ROW_GAP_DP
    return when {
        index in (from + 1)..drag.dropShownIndex -> -room
        index in drag.dropShownIndex until from -> room
        else -> 0.0
    }
}

/** The latest [TimelineActions], read when called: the pointer handler outlives recompositions. */
private class LatestActions(
    private val latest: () -> TimelineActions,
) : TimelineActions {
    override fun select(id: ClipId?) = latest().select(id)

    override fun seek(ms: Long) = latest().seek(ms)

    override fun trim(
        id: ClipId,
        edge: TrimEdge,
        deltaMs: Double,
    ) = latest().trim(id, edge, deltaMs)

    override fun move(
        id: ClipId,
        toContentIndex: Int,
    ) = latest().move(id, toContentIndex)

    override fun insert(
        anchor: ClipId,
        side: InsertSide,
    ) = latest().insert(anchor, side)

    override fun menu(id: ClipId) = latest().menu(id)

    override fun transition(lead: ClipId) = latest().transition(lead)

    override fun showAllRows() = latest().showAllRows()

    override fun showFewerRows() = latest().showFewerRows()

    override fun selectRow(key: String) = latest().selectRow(key)

    override fun rowMenu(key: String) = latest().rowMenu(key)

    override fun addHere(key: String) = latest().addHere(key)

    override fun moveRow(
        key: String,
        toIndex: Int,
    ) = latest().moveRow(key, toIndex)

    override fun moveTimed(
        id: ClipId,
        startMs: Long,
        toRow: String,
    ) = latest().moveTimed(id, startMs, toRow)
}

private fun MainClip.isContent() = this is PhotoClip || this is VideoClip || this is CardClip

/** The slots with item [index] replaced, re-laid out (the live trim). */
private fun relayout(
    slots: List<MainSlot>,
    index: Int,
    replacement: MainClip,
): List<MainSlot> {
    var start = 0L
    return slots.mapIndexed { i, s ->
        val clip = if (i == index) replacement else s.clip
        MainSlot(clip, i, start, clip.durationMs, s.overlapToNextMs).also { start += clip.durationMs - s.overlapToNextMs }
    }
}

private data class TrimPreview(
    val index: Int,
    val edge: TrimEdge,
    val deltaMs: Double,
)

private data class DragPreview(
    val index: Int,
    val dxDp: Double,
    val dropIndex: Int,
)

/** A row being dragged by its icon: [dyDp] from its place, landing at [dropShownIndex] among the shown rows. */
private data class RowDragPreview(
    val key: String,
    val dyDp: Double,
    val dropShownIndex: Int,
)

/** An item on a row other than MAIN being dragged: [dxDp] in time, over row [toRow]. */
private data class TimedDragPreview(
    val id: ClipId,
    val fromRow: String,
    val dxDp: Double,
    val toRow: String,
)

/** Where the pointer handler shows what a drag will do. */
private class Previews(
    val trim: (TrimPreview?) -> Unit,
    val drag: (DragPreview?) -> Unit,
    val row: (RowDragPreview?) -> Unit,
    val timed: (TimedDragPreview?) -> Unit,
) {
    fun clear() {
        trim(null)
        drag(null)
        row(null)
        timed(null)
    }
}

private data class Current(
    val zoom: ZoomState,
    val geometry: TimelineGeometry,
    val spans: List<SlotSpan>,
    val slots: List<MainSlot>,
    val spanWidth: Double,
    val totalMs: Long,
    val mainTopInRows: Double,
    val selectedIndex: Int?,
    val shown: List<TimelineRow>,
    val bands: List<RowBand>,
    val timedPlus: Map<String, List<TimedPlus>>,
    val allKeys: List<String>,
    val playheadMs: Long,
) {
    fun row(key: String): TimelineRow? = shown.firstOrNull { it.key == key }

    /** The row a timed item dragged to [inRowsDp] (from the top of the rows) would land on. */
    fun dropRow(
        from: TimelineRow,
        inRowsDp: Double,
    ): TimelineRow {
        if (from.fixed) return from
        val band = bands.firstOrNull { inRowsDp >= it.topDp && inRowsDp < it.bottomDp } ?: return from
        val target = row(band.key) ?: return from
        return if (target.type == from.type && !target.fixed) target else from
    }
}

/** The pointer handler: feeds [TimelineGestureArbiter] and acts on what it decides. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.timelineGestures(
    density: () -> Float,
    current: () -> Current,
    metrics: RowMetrics,
    rowScroll: ScrollState,
    onZoom: (ZoomState) -> Unit,
    actions: TimelineActions,
    longPressed: () -> Unit,
    previews: Previews,
    touched: (Boolean) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val firstEvent = awaitPointerEvent(PointerEventPass.Main)
            val first = firstEvent.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            val d = density()

            fun dp(px: Float) = (px / d).toDouble()
            val c0 = current()
            val start = hit(c0, metrics, dp(first.position.x), dp(first.position.y), rowScroll.value / d)
            val arbiter = TimelineGestureArbiter(dp(viewConfiguration.touchSlop))
            arbiter.down(first.id.value, dp(first.position.x), dp(first.position.y), first.uptimeMillis, start.kind)
            first.consume()
            touched(true)
            try {
                // Any other finger already down in the same event (two fingers landing together).
                firstEvent.changes
                    .filter { it.pressed && !it.previousPressed && it.id != first.id }
                    .forEach { arbiter.down(it.id.value, dp(it.position.x), dp(it.position.y), it.uptimeMillis, start.kind) }
                val downAt = first.uptimeMillis
                // Only an item, a row's icon or a row's empty space can be long-pressed; nothing else waits.
                var waiting =
                    when (start) {
                        is Hit.OnClip -> c0.slots.getOrNull(start.index) != null
                        is Hit.OnHeader, is Hit.OnTimedClip, is Hit.OnRowSpace -> true
                        is Hit.OnTimedPlus -> start.under != null
                        else -> false
                    }
                var lastSpread: Double? = null
                var lastCentroid: Offset? = null
                var lastScroll = first.position
                var trimDelta = 0.0
                var dragDx = 0.0
                var dragDy = 0.0

                // Whether the gesture became a drag, even if only in the event that ended it.
                var dragged = false
                var lastSeek = 0L
                var now = first.uptimeMillis
                // Several events can arrive before the next frame recomposes: zoom and scroll build on
                // this gesture's own latest value, not on the last composed one.
                var live = c0.zoom
                while (arbiter.isActive) {
                    val wait =
                        if (waiting &&
                            arbiter.mode == GestureMode.PENDING
                        ) {
                            (downAt + TimelineGestureArbiter.LONG_PRESS_MS + 1)
                        } else {
                            null
                        }
                    val event =
                        if (wait != null) {
                            withTimeoutOrNull(maxOf(1L, wait - now)) { awaitPointerEvent(PointerEventPass.Main) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                    if (event == null) {
                        // Timed out once: the long-press fired or never will; no more timed waits.
                        waiting = false
                        now = downAt + TimelineGestureArbiter.LONG_PRESS_MS
                        if (arbiter.tick(now) == GestureMode.LONG_PRESSED) longPressed()
                        continue
                    }
                    now = event.changes.maxOf { it.uptimeMillis }
                    val before = arbiter.mode
                    for (ch in event.changes) {
                        when {
                            ch.pressed && !ch.previousPressed -> {
                                arbiter.down(ch.id.value, dp(ch.position.x), dp(ch.position.y), ch.uptimeMillis, start.kind)
                            }

                            !ch.pressed && ch.previousPressed -> {
                                // Where the finger lets go counts first, judged from where it went down (a
                                // move just before the release may not have reached this handler), so a
                                // long-press that moved is a drag, never the menu.
                                arbiter.move(ch.id.value, dp(ch.position.x), dp(ch.position.y), ch.uptimeMillis)
                                if (arbiter.mode == GestureMode.DRAG) dragged = true
                                arbiter.up(ch.id.value, ch.uptimeMillis)
                            }

                            ch.positionChanged() -> {
                                arbiter.move(ch.id.value, dp(ch.position.x), dp(ch.position.y), ch.uptimeMillis)
                            }
                        }
                    }
                    if (arbiter.mode == GestureMode.DRAG) dragged = true
                    if (before == GestureMode.PENDING && arbiter.mode in LIFTED && waiting) longPressed()
                    val c = current()
                    val pressed = event.changes.filter { it.pressed }
                    when (arbiter.mode) {
                        GestureMode.PINCH -> {
                            if (pressed.size >= 2) {
                                val centroid = pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat()
                                val spread =
                                    pressed
                                        .map {
                                            hypot(
                                                (it.position.x - centroid.x).toDouble(),
                                                (it.position.y - centroid.y).toDouble(),
                                            )
                                        }.average()
                                val prevSpread = lastSpread
                                val prevCentroid = lastCentroid
                                if (prevSpread != null && prevCentroid != null && prevSpread > 0 && spread > 0) {
                                    val focus = dp(centroid.x) - metrics.headerDp - PAD_DP
                                    var next = TimelineZoom.zoomAround(live, spread / prevSpread, focus, c.totalMs, c.spanWidth)
                                    val pan = dp(prevCentroid.x - centroid.x)
                                    if (abs(pan) > 0 && !next.fitAll) next = TimelineZoom.scrollBy(next, pan, c.totalMs, c.spanWidth)
                                    live = next
                                    onZoom(next)
                                }
                                lastSpread = spread
                                lastCentroid = centroid
                            } else {
                                // A finger lifted: the next two-finger event starts the ratio afresh, with no jump.
                                lastSpread = null
                                lastCentroid = null
                            }
                            previews.clear()
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.SCROLL -> {
                            val ch = event.changes.firstOrNull { it.id == first.id } ?: pressed.firstOrNull()
                            if (ch != null) {
                                val dx = dp(lastScroll.x - ch.position.x)
                                val dy = lastScroll.y - ch.position.y
                                if (before != GestureMode.SCROLL) {
                                    // The slop was spent getting here: start from the down point, no jump.
                                    val sx = dp(first.position.x - ch.position.x)
                                    live = TimelineZoom.scrollBy(live, sx, c.totalMs, c.spanWidth)
                                    rowScroll.dispatchRawDelta(first.position.y - ch.position.y)
                                } else {
                                    live = TimelineZoom.scrollBy(live, dx, c.totalMs, c.spanWidth)
                                    rowScroll.dispatchRawDelta(dy)
                                }
                                onZoom(live)
                                lastScroll = ch.position
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.TRIM -> {
                            val ch = event.changes.firstOrNull { it.id == first.id }
                            val sel = c.selectedIndex
                            if (ch != null && sel != null) {
                                trimDelta = dp(ch.position.x - first.position.x) * c.zoom.msPerDp
                                val edge = if (start.kind == GestureStart.TRIM_START) TrimEdge.START else TrimEdge.END
                                previews.trim(TrimPreview(sel, edge, trimDelta))
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.SCRUB -> {
                            val ch = event.changes.firstOrNull { it.id == first.id }
                            if (ch != null) {
                                val t =
                                    c.zoom
                                        .timeAt(
                                            dp(ch.position.x) - metrics.headerDp - PAD_DP,
                                        ).coerceIn(0.0, c.totalMs.toDouble())
                                        .toLong()
                                if (ch.uptimeMillis - lastSeek >= SEEK_EVERY_MS) {
                                    lastSeek = ch.uptimeMillis
                                    actions.seek(t)
                                }
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.DRAG -> {
                            val ch = event.changes.firstOrNull { it.id == first.id }
                            if (ch != null) {
                                dragDx = dp(ch.position.x - first.position.x)
                                dragDy = dp(ch.position.y - first.position.y)
                                when (val held = start.held()) {
                                    is Hit.OnClip -> {
                                        val i = held.index
                                        if (c.slots
                                                .getOrNull(i)
                                                ?.clip
                                                ?.isContent() == true
                                        ) {
                                            val centre = (c.geometry.left(i) + c.geometry.right(i)) / 2 + dragDx
                                            val drop = TimelineGeometry.dropIndex(c.spans, i, centre, c.zoom)
                                            previews.drag(DragPreview(i, dragDx, drop))
                                        }
                                    }

                                    is Hit.OnHeader -> {
                                        previews.row(RowDragPreview(held.key, dragDy, RowOrder.dropShownIndex(c.bands, held.key, dragDy)))
                                    }

                                    is Hit.OnTimedClip -> {
                                        // The preset's own items (its frame, music and closing voice) follow the video: never dragged.
                                        val from = c.row(held.key)?.takeUnless { it.fixed }
                                        if (from != null) {
                                            val to = c.dropRow(from, dp(ch.position.y) - metrics.rulerDp + rowScroll.value / d)
                                            // Shown where it will land: never before the start nor past the end.
                                            val clip = from.clips.firstOrNull { it.id == held.id }
                                            val shownDx =
                                                if (clip == null) {
                                                    dragDx
                                                } else {
                                                    val latest =
                                                        maxOf((c.totalMs - (clip.endMs - clip.startMs)).toDouble(), clip.startMs.toDouble())
                                                    ((clip.startMs + dragDx * c.zoom.msPerDp).coerceIn(0.0, latest) - clip.startMs) /
                                                        c.zoom.msPerDp
                                                }
                                            previews.timed(TimedDragPreview(held.id, held.key, shownDx, to.key))
                                        }
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.TAP -> {
                            when (start) {
                                is Hit.OnPlus -> {
                                    val s = c.slots.getOrNull(start.point.anchorIndex)
                                    if (s != null) actions.insert(s.clip.id, start.point.side)
                                }

                                is Hit.OnMarker -> {
                                    c.slots.getOrNull(start.point.leadIndex)?.let { actions.transition(it.clip.id) }
                                }

                                is Hit.OnTimedPlus -> {
                                    actions.insert(start.plus.anchor, start.plus.side)
                                }

                                is Hit.OnRowAdd -> {
                                    actions.addHere(start.key)
                                }

                                is Hit.OnClip -> {
                                    c.slots.getOrNull(start.index)?.let { actions.select(it.clip.id) }
                                }

                                is Hit.OnTimedClip -> {
                                    actions.select(start.id)
                                }

                                is Hit.OnHeader -> {
                                    actions.selectRow(start.key)
                                }

                                Hit.OnRuler -> {
                                    val t = c.zoom.timeAt(dp(first.position.x) - metrics.headerDp - PAD_DP)
                                    actions.seek(t.coerceIn(0.0, c.totalMs.toDouble()).toLong())
                                }

                                // A tap on a trim handle keeps the item selected (it is about to be dragged).
                                is Hit.OnTrim -> {
                                    Unit
                                }

                                else -> {
                                    actions.select(null)
                                }
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.MENU -> {
                            when (val held = start.held()) {
                                is Hit.OnClip -> c.slots.getOrNull(held.index)?.let { actions.menu(it.clip.id) }
                                is Hit.OnTimedClip -> actions.menu(held.id)
                                is Hit.OnHeader -> actions.rowMenu(held.key)
                                is Hit.OnRowSpace -> actions.rowMenu(held.key)
                                else -> Unit
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.ENDED -> {
                            when (before) {
                                GestureMode.TRIM -> {
                                    val sel = c.selectedIndex
                                    if (sel != null) {
                                        val edge = if (start.kind == GestureStart.TRIM_START) TrimEdge.START else TrimEdge.END
                                        actions.trim(c.slots[sel].clip.id, edge, trimDelta)
                                    }
                                    previews.trim(null)
                                }

                                GestureMode.DRAG, GestureMode.LONG_PRESSED -> {
                                    if (dragged) {
                                        // Where the finger let go, which may be further than the last move seen.
                                        event.changes.firstOrNull { it.id == first.id }?.let { ch ->
                                            dragDx = dp(ch.position.x - first.position.x)
                                            dragDy = dp(ch.position.y - first.position.y)
                                        }
                                        val fingerInRows = dp(first.position.y) + dragDy - metrics.rulerDp + rowScroll.value / d
                                        endDrag(start.held(), c, dragDx, dragDy, fingerInRows, actions)
                                    }
                                    previews.clear()
                                }

                                GestureMode.SCRUB -> {
                                    val ch = event.changes.firstOrNull { it.id == first.id }
                                    if (ch != null) {
                                        val t = c.zoom.timeAt(dp(ch.position.x) - metrics.headerDp - PAD_DP)
                                        actions.seek(t.coerceIn(0.0, c.totalMs.toDouble()).toLong())
                                    }
                                }

                                else -> {
                                    Unit
                                }
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.CANCELLED -> {
                            previews.clear()
                        }

                        else -> {
                            if (arbiter.mode != GestureMode.PENDING) event.changes.forEach(PointerInputChange::consume)
                        }
                    }
                    if (event.changes.none { it.pressed } && arbiter.isActive) {
                        arbiter.cancel()
                        previews.clear()
                    }
                }
            } finally {
                // Also when the handler is cancelled or a gesture throws: never left "touching" or mid-drag.
                touched(false)
                previews.clear()
            }
        }
    }
}

private val LIFTED = setOf(GestureMode.LONG_PRESSED, GestureMode.DRAG, GestureMode.MENU)

/** A drag let go: the MAIN item, the row or the timed item goes where it was dropped. */
private fun endDrag(
    start: Hit,
    c: Current,
    dragDx: Double,
    dragDy: Double,
    fingerInRowsDp: Double,
    actions: TimelineActions,
) {
    when (start) {
        is Hit.OnClip -> {
            val i = start.index
            if (c.slots
                    .getOrNull(i)
                    ?.clip
                    ?.isContent() == true
            ) {
                val centre = (c.geometry.left(i) + c.geometry.right(i)) / 2 + dragDx
                actions.move(c.slots[i].clip.id, TimelineGeometry.dropIndex(c.spans, i, centre, c.zoom))
            }
        }

        is Hit.OnHeader -> {
            val shownIndex = RowOrder.dropShownIndex(c.bands, start.key, dragDy)
            val from = c.shown.indexOfFirst { it.key == start.key }
            if (from >= 0 && shownIndex != from) {
                actions.moveRow(start.key, RowOrder.fullIndex(c.allKeys, c.shown.map { it.key }, start.key, shownIndex))
            }
        }

        is Hit.OnTimedClip -> {
            val from = c.row(start.key)?.takeUnless { it.fixed } ?: return
            val clip = from.clips.firstOrNull { it.id == start.id } ?: return
            // The row under the finger, as the drag showed it.
            val to = c.dropRow(from, fingerInRowsDp)
            // Never before the start, and never past the end, where it would no longer be seen.
            val latest = (c.totalMs - (clip.endMs - clip.startMs)).coerceAtLeast(0).toDouble()
            val startMs = (clip.startMs + dragDx * c.zoom.msPerDp).coerceIn(0.0, maxOf(latest, clip.startMs.toDouble())).toLong()
            if (to.key != from.key || startMs != clip.startMs) actions.moveTimed(start.id, startMs, to.key)
        }

        else -> {
            Unit
        }
    }
}

private const val SEEK_EVERY_MS = 80L

/** What a touch landed on, with the details the handler needs. */
private sealed interface Hit {
    val kind: GestureStart

    data class OnPlus(
        val point: PlusPoint,
    ) : Hit {
        override val kind get() = GestureStart.PLUS
    }

    data class OnMarker(
        val point: MarkerPoint,
    ) : Hit {
        // A tap target like a "+": no drag, no long-press.
        override val kind get() = GestureStart.PLUS
    }

    /**
     * A "+" on a row other than MAIN. Its touch area reaches over the edge of
     * item [under]: a tap is the "+", but held or dragged it is that item.
     */
    data class OnTimedPlus(
        val plus: TimedPlus,
        val key: String,
        val under: ClipId?,
    ) : Hit {
        override val kind get() = if (under != null) GestureStart.CLIP else GestureStart.PLUS
    }

    /** The "+" on an empty row other than MAIN: Add here. */
    data class OnRowAdd(
        val key: String,
    ) : Hit {
        override val kind get() = GestureStart.PLUS
    }

    data class OnClip(
        val index: Int,
    ) : Hit {
        override val kind get() = GestureStart.CLIP
    }

    data class OnTimedClip(
        val key: String,
        val id: ClipId,
    ) : Hit {
        override val kind get() = GestureStart.CLIP
    }

    data class OnTrim(
        override val kind: GestureStart,
    ) : Hit

    data object OnRuler : Hit {
        override val kind get() = GestureStart.RULER
    }

    data class OnHeader(
        val key: String,
    ) : Hit {
        override val kind get() = GestureStart.HEADER
    }

    /** A row other than MAIN, away from its items. */
    data class OnRowSpace(
        val key: String,
    ) : Hit {
        override val kind get() = GestureStart.ROW
    }

    data object Elsewhere : Hit {
        override val kind get() = GestureStart.EMPTY
    }
}

/** What a hold or a drag acts on: a "+" over an item's edge is that item. */
private fun Hit.held(): Hit = if (this is Hit.OnTimedPlus && under != null) Hit.OnTimedClip(key, under) else this

/** Where an empty row's "+" (Add here) sits: at the playhead, kept on screen. */
internal fun addHereX(
    zoom: ZoomState,
    playheadMs: Long,
    spanWidth: Double,
): Double {
    val half = TimelineGeometry.PLUS_HIT_DP / 2
    return zoom.xOf(playheadMs.toDouble()).coerceIn(half, (spanWidth - half).coerceAtLeast(half))
}

private fun hit(
    c: Current,
    metrics: RowMetrics,
    xDp: Double,
    yDp: Double,
    rowScrollDp: Float,
): Hit {
    if (xDp >= metrics.headerDp && yDp >= metrics.rulerDp) {
        // A "+" lifted over a transition marker reaches above the MAIN row, and wins there over the
        // rows (as every "+" and marker does over what is under it), but never over the ruler: a
        // touch on the ruler always scrubs, so the playhead can't be lost under a "+".
        val gy = metrics.rulerDp + (yDp - metrics.rulerDp + rowScrollDp - c.mainTopInRows)
        when (val t = c.geometry.hitTest(xDp - metrics.headerDp - PAD_DP, gy)) {
            is HitTarget.Plus -> return Hit.OnPlus(t.point)
            is HitTarget.Marker -> return Hit.OnMarker(t.point)
            else -> Unit
        }
    }
    if (yDp < metrics.rulerDp) return if (xDp < metrics.headerDp) Hit.Elsewhere else Hit.OnRuler
    val inRows = yDp - metrics.rulerDp + rowScrollDp
    val i = c.bands.indexOfFirst { inRows >= it.topDp && inRows < it.bottomDp }
    if (i < 0) return Hit.Elsewhere
    val band = c.bands[i]
    val row = c.shown[i]
    if (xDp < metrics.headerDp) return Hit.OnHeader(row.key)
    val sx = xDp - metrics.headerDp - PAD_DP
    if (row.type != RowType.MAIN) {
        if (row.clips.isEmpty() && !row.fixed &&
            abs(sx - addHereX(c.zoom, c.playheadMs, c.spanWidth)) <= TimelineGeometry.PLUS_HIT_DP / 2
        ) {
            return Hit.OnRowAdd(row.key)
        }
        return when (val t = TimedRowGeometry.hitTest(row.clips, c.timedPlus[row.key].orEmpty(), c.zoom, sx)) {
            is TimedHit.Plus -> {
                val at = c.zoom.timeAt(sx)
                val under =
                    row.clips
                        .firstOrNull { at >= it.startMs && at < it.endMs }
                        ?.id
                        ?.takeUnless { row.fixed }
                Hit.OnTimedPlus(t.plus, row.key, under)
            }

            is TimedHit.Clip -> {
                Hit.OnTimedClip(row.key, t.id)
            }

            TimedHit.Space -> {
                Hit.OnRowSpace(row.key)
            }
        }
    }
    if (c.slots.isEmpty() && abs(sx - addHereX(c.zoom, c.playheadMs, c.spanWidth)) <= TimelineGeometry.PLUS_HIT_DP / 2) {
        return Hit.OnRowAdd(row.key)
    }
    val mainY = inRows - band.topDp
    return when (val t = c.geometry.hitTest(sx, metrics.rulerDp + mainY)) {
        is HitTarget.Plus -> Hit.OnPlus(t.point)

        is HitTarget.Marker -> Hit.OnMarker(t.point)

        is HitTarget.TrimStart -> Hit.OnTrim(GestureStart.TRIM_START)

        is HitTarget.TrimEnd -> Hit.OnTrim(GestureStart.TRIM_END)

        is HitTarget.Clip -> Hit.OnClip(t.index)

        HitTarget.Ruler -> Hit.OnRuler

        // MAIN's empty space, after its last item: held, it opens the row menu like any row's.
        HitTarget.Empty -> Hit.OnRowSpace(row.key)
    }
}

@Composable
private fun Ruler(
    zoom: ZoomState,
    totalMs: Long,
    playheadMs: Long,
    playing: Boolean,
    metrics: RowMetrics,
    spanWidth: Double,
    actions: TimelineActions,
) {
    // While playing, TalkBack is not told the time every frame: it hears the time play started from.
    var spokenMs by remember { mutableStateOf(playheadMs) }
    if (!playing) spokenMs = playheadMs
    val tick = TimelineZoom.tickMs(zoom.msPerDp, metrics.fontScale)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val label = stringResource(R.string.timeline_ruler)
    val now = stringResource(R.string.timeline_playhead, durationText(spokenMs * 1000))
    val total = totalMs.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.rulerDp.dp)
            .testTag(TIMELINE_RULER_TAG)
            // One TalkBack stop that reads the time and moves it (the tick labels are not read one by one).
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = now
                rulerTick = tick
                // One step per ruler label, so the volume keys move by a sensible amount.
                progressBarRangeInfo =
                    ProgressBarRangeInfo(spokenMs.toFloat() / total, 0f..1f, steps = (total / tick).toInt().coerceIn(0, 200) - 1)
                setProgress { f ->
                    actions.seek((f.coerceIn(0f, 1f) * total).toLong())
                    true
                }
            },
    ) {
        Spacer(Modifier.width(metrics.headerDp.dp))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = PAD_DP.dp.toPx()
                drawLine(line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx())
                val firstTick = (zoom.scrollMs.coerceAtLeast(0.0) / tick).toLong() * tick
                var t = firstTick
                val end = zoom.timeAt(spanWidth + PAD_DP)
                while (t <= minOf(end, totalMs.toDouble() + tick)) {
                    val x = pad + (zoom.xOf(t.toDouble()) * density).toFloat()
                    drawLine(color, Offset(x, size.height * 0.55f), Offset(x, size.height), 1.dp.toPx())
                    t += tick
                }
            }
            // Labels at each tick, as text so they scale with the font.
            val firstTick = (zoom.scrollMs.coerceAtLeast(0.0) / tick).toLong() * tick
            var t = firstTick
            val end = zoom.timeAt(spanWidth + PAD_DP)
            while (t <= minOf(end, totalMs.toDouble())) {
                val x = PAD_DP + zoom.xOf(t.toDouble())
                Text(
                    durationText(t * 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    modifier = Modifier.offset(x = (x + 3).dp, y = 0.dp),
                )
                t += tick
            }
        }
    }
}

@Composable
private fun Playhead(
    zoom: ZoomState,
    playheadMs: Long,
    metrics: RowMetrics,
) {
    val color = MaterialTheme.colorScheme.primary
    val x = metrics.headerDp + PAD_DP + zoom.xOf(playheadMs.toDouble())
    Box(
        Modifier
            .offset(x = (x - 8).dp)
            .width(16.dp)
            .fillMaxHeight()
            .testTag(TIMELINE_PLAYHEAD_TAG)
            // Drawn only: its time is the ruler's, and it must not cover the items under it for TalkBack.
            .clearAndSetSemantics { },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            drawLine(color, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx())
            drawCircle(color, radius = 6.dp.toPx(), center = Offset(cx, (metrics.rulerDp * 0.5).dp.toPx()))
        }
    }
}

@Composable
private fun RowHeader(
    row: TimelineRow,
    selected: Boolean,
    metrics: RowMetrics,
    shown: List<TimelineRow>,
    allKeys: List<String>,
    actions: TimelineActions,
) {
    val (icon, _) = rowLook(row.type)
    val label = rowName(row)
    val state = rowState(row, selected)
    val index = shown.indexOfFirst { it.key == row.key }
    val moveUp = stringResource(R.string.row_move_up)
    val moveDown = stringResource(R.string.row_move_down)
    val menu = stringResource(R.string.row_menu)
    val primary = MaterialTheme.colorScheme.primary
    // The icon alone; its row's name is what TalkBack reads. A long-press here is the row menu or,
    // moved, a row drag, so there is no tooltip. Taps and drags come through the timeline's own
    // handler; these semantics give TalkBack the same actions.
    Box(
        Modifier
            .width(metrics.headerDp.dp)
            .fillMaxHeight()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, primary)) else Modifier)
            .testTag(rowHeaderTag(row.key))
            .semantics {
                contentDescription = label
                if (state.isNotEmpty()) stateDescription = state
                role = Role.Button
                this.selected = selected
                onClick {
                    actions.selectRow(row.key)
                    true
                }
                onLongClick(menu) {
                    actions.rowMenu(row.key)
                    true
                }
                customActions =
                    listOfNotNull(
                        CustomAccessibilityAction(moveUp) {
                            actions.moveRow(row.key, shownStep(allKeys, shown, row.key, -1))
                            true
                        }.takeIf { index > 0 },
                        CustomAccessibilityAction(moveDown) {
                            actions.moveRow(row.key, shownStep(allKeys, shown, row.key, 1))
                            true
                        }.takeIf { index in 0 until shown.lastIndex },
                    )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp).alpha(if (row.enabled) 1f else 0.5f),
            tint = MaterialTheme.colorScheme.primary,
        )
        row.stack?.takeIf { it.count > 1 }?.let { StackBadge(it, Modifier.align(Alignment.TopEnd).padding(4.dp)) }
        if (row.muted) {
            Icon(
                painterResource(R.drawable.ic_volume_off),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Move row up" or "down" by one SHOWN row (rows folded away keep their
 * places): the screen-order index, with the row taken out, for `moveRow`.
 */
fun shownStep(
    allKeys: List<String>,
    shown: List<TimelineRow>,
    key: String,
    step: Int,
): Int {
    val keys = shown.map { it.key }
    val i = keys.indexOf(key)
    return RowOrder.fullIndex(allKeys, keys, key, (i + step).coerceIn(0, keys.lastIndex))
}

/**
 * Three small bars, the one for this row's part of the stack (top, middle or
 * bottom third) filled: where the row is drawn in the video, at a glance.
 * Drawn only; the header's state says it in words.
 */
@Composable
private fun StackBadge(
    place: StackPlace,
    modifier: Modifier,
) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    // 0 is the bottom bar, 2 the top one.
    val level = if (place.count <= 1) 2 else ((place.below * 3) / place.count).coerceIn(0, 2)
    Canvas(modifier.size(width = 10.dp, height = 10.dp).clearAndSetSemantics { }) {
        val h = size.height / 5
        for (bar in 0..2) {
            val y = size.height - (bar * 2 + 1) * h
            drawRect(if (bar == level) on else off, topLeft = Offset(0f, y), size = Size(size.width, h))
        }
    }
}

/** A row's name for TalkBack: "Text", or "Text 2" when there are several. */
@Composable
fun rowName(row: TimelineRow): String {
    val base = stringResource(rowLook(row.type).second)
    return row.number?.let { stringResource(R.string.timeline_item_numbered, base, it) } ?: base
}

/** What TalkBack says about a row besides its name: where it is drawn, and whether it is off or muted. */
@Composable
private fun rowState(
    row: TimelineRow,
    selected: Boolean,
): String {
    val parts = mutableListOf<String>()
    if (selected) parts += stringResource(R.string.timeline_selected)
    row.stack?.let { s ->
        parts +=
            when {
                s.count <= 1 -> return@let
                s.below == s.count - 1 -> stringResource(R.string.stack_on_top)
                s.below == 0 -> stringResource(R.string.stack_at_bottom)
                else -> pluralStringResource(R.plurals.stack_above_rows, s.below, s.below)
            }
    }
    if (!row.enabled) parts += stringResource(R.string.row_off)
    if (row.muted) parts += stringResource(R.string.row_muted)
    return parts.joinToString(stringResource(R.string.list_separator))
}

fun rowLook(type: RowType): Pair<Int, Int> =
    when (type) {
        RowType.MAIN -> R.drawable.ic_photo_library to R.string.editor_row_main
        RowType.TEXT -> R.drawable.ic_text_fields to R.string.timeline_row_text
        RowType.FRAME -> R.drawable.ic_frame to R.string.timeline_row_frame
        RowType.PIP -> R.drawable.ic_picture_in_picture to R.string.timeline_row_pip
        RowType.MUSIC -> R.drawable.ic_music_note to R.string.timeline_row_music
        RowType.VOICE -> R.drawable.ic_record_voice to R.string.timeline_row_voice
        RowType.SOUND -> R.drawable.ic_volume_up to R.string.timeline_row_sound
    }

/** The name of an item on a row other than MAIN, as TalkBack says it. */
fun rowClipName(
    kind: RowClipKind,
    rowType: RowType,
): Int =
    when (kind) {
        RowClipKind.TEXT -> R.string.timeline_row_text
        RowClipKind.PHOTO -> R.string.editor_item_photo
        RowClipKind.VIDEO -> R.string.editor_item_video
        RowClipKind.OVERLAY -> R.string.timeline_row_frame
        RowClipKind.SOUND -> rowLook(rowType).second
    }

private fun rowClipIcon(
    kind: RowClipKind,
    rowType: RowType,
): Int =
    when (kind) {
        RowClipKind.PHOTO -> R.drawable.ic_image
        RowClipKind.VIDEO -> R.drawable.ic_videocam
        else -> rowLook(rowType).first
    }

/** How much of a caption's words are drawn on its item and read with it. */
private const val TEXT_SHOWN = 60

/**
 * A row other than MAIN: its items at their times, each one a TalkBack stop
 * that selects it (and, held, opens its menu). The item being dragged moves
 * with the finger in time; the row it would land on is outlined.
 */
@Composable
private fun TimedRowContent(
    row: TimelineRow,
    zoom: ZoomState,
    selected: ClipId?,
    playheadMs: Long,
    drag: TimedDragPreview?,
    actions: TimelineActions,
) {
    val media = LocalMediaColors.current
    val (fill, on) =
        when (row.type) {
            RowType.TEXT -> media.textClip to media.onTextClip
            RowType.FRAME -> media.frameClip to media.onFrameClip
            RowType.PIP -> media.visualClip to media.onVisualClip
            else -> media.audioClip to media.onAudioClip
        }
    val primary = MaterialTheme.colorScheme.primary
    val selectedLabel = stringResource(R.string.timeline_selected)
    val playingLabel = stringResource(R.string.editor_item_current)
    val menuLabel = stringResource(R.string.row_menu)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        for (clip in row.clips) {
            val dragged = drag?.id == clip.id
            val x = PAD_DP + zoom.xOf(clip.startMs.toDouble()) + if (dragged) drag.dxDp else 0.0
            val w = (clip.endMs - clip.startMs) / zoom.msPerDp
            if (x + w < -64 || w <= 0) continue
            val isSelected = clip.id == selected
            val words = clip.text?.takeIf { it.isNotBlank() }?.take(TEXT_SHOWN)
            val kind = stringResource(rowClipName(clip.kind, row.type))
            val name = if (words != null) stringResource(R.string.timeline_named_item, kind, words) else kind
            val description =
                stringResource(
                    R.string.timeline_timed_item_description,
                    name,
                    durationText(clip.startMs * 1000),
                    durationText(clip.endMs * 1000),
                )
            val playing = playheadMs in clip.startMs until clip.endMs
            Box(
                Modifier
                    .offset { IntOffset((x * density).roundToInt(), 0) }
                    // An item can be far wider than the screen when zoomed in: never squeezed to the row's width.
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(w.coerceAtLeast(2.0).dp)
                    .fillMaxHeight()
                    .zIndex(if (dragged) 1f else 0f)
                    .testTag(clipTag(clip.id))
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        role = Role.Button
                        this.selected = isSelected
                        if (isSelected) {
                            stateDescription = selectedLabel
                        } else if (playing) {
                            stateDescription = playingLabel
                        }
                        onClick {
                            actions.select(clip.id)
                            true
                        }
                        onLongClick(menuLabel) {
                            actions.menu(clip.id)
                            true
                        }
                    }.padding(vertical = 5.dp, horizontal = 1.dp)
                    .then(if (dragged) Modifier.shadow(8.dp, RoundedCornerShape(6.dp)) else Modifier)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (row.enabled) fill else fill.copy(alpha = 0.4f))
                    .then(if (isSelected) Modifier.border(BorderStroke(3.dp, primary), RoundedCornerShape(6.dp)) else Modifier),
            ) {
                Row(
                    Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(rowClipIcon(clip.kind, row.type)),
                        contentDescription = null,
                        tint = on,
                        modifier = Modifier.size(16.dp),
                    )
                    if (words != null && w >= 48) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            words,
                            style = MaterialTheme.typography.labelMedium,
                            color = on,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
            }
        }
    }
}

/** How every "+" looks: a small tonal circle with a thin outline, calm next to the items. */
@Composable
private fun PlusCircle() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(TimelineGeometry.PLUS_DP.dp),
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.padding(3.dp))
    }
}

/** The "+" of an empty row (24 dp drawn, 48 dp to touch): Add here, at the playhead. */
@Composable
private fun RowAddButton(
    row: TimelineRow,
    xDp: Double,
    actions: TimelineActions,
) {
    val description = stringResource(R.string.add_here_title, rowName(row))
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(x = (PAD_DP + xDp - TimelineGeometry.PLUS_HIT_DP / 2).dp)
                .size(TimelineGeometry.PLUS_HIT_DP.dp)
                .align(Alignment.CenterStart)
                .testTag(rowAddTag(row.key))
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        actions.addHere(row.key)
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PlusCircle()
        }
    }
}

/** A "+" on a row other than MAIN (24 dp drawn, 48 dp to touch), centred on its item's edge. */
@Composable
private fun TimedPlusButton(
    p: TimedPlus,
    row: TimelineRow,
    actions: TimelineActions,
) {
    val clip = row.clips.firstOrNull { it.id == p.anchor } ?: return
    val kind = stringResource(rowClipName(clip.kind, row.type))
    val words = clip.text?.takeIf { it.isNotBlank() }?.take(TEXT_SHOWN)
    val name = if (words != null) stringResource(R.string.timeline_named_item, kind, words) else kind
    val description = stringResource(if (p.side == InsertSide.BEFORE) R.string.timeline_add_before else R.string.timeline_add_after, name)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(x = (PAD_DP + p.xDp - TimelineGeometry.PLUS_HIT_DP / 2).dp)
                .size(TimelineGeometry.PLUS_HIT_DP.dp)
                .align(Alignment.CenterStart)
                .testTag(timedPlusTag(p.anchor, p.side))
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        actions.insert(p.anchor, p.side)
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PlusCircle()
        }
    }
}

@Composable
private fun MainRowContent(
    playheadMs: Long,
    slots: List<MainSlot>,
    geometry: TimelineGeometry,
    selectedIndex: Int?,
    missing: Set<ClipId>,
    thumbnail: ThumbSource,
    actions: TimelineActions,
    drag: DragPreview?,
) {
    val zoom = geometry.zoom
    // While dragging, the other items make room where the dragged one will land.
    val order =
        if (drag == null) {
            slots.indices.toList()
        } else {
            val content = slots.indices.filter { slots[it].clip.isContent() && it != drag.index }
            val head = slots.indices.takeWhile { !slots[it].clip.isContent() }
            val tail =
                slots.indices
                    .reversed()
                    .takeWhile { !slots[it].clip.isContent() }
                    .reversed()
            head + content.toMutableList().apply { add(drag.dropIndex.coerceIn(0, size), drag.index) } + tail
        }
    val reflowStart = mutableMapOf<Int, Double>()
    var t = 0.0
    for (i in order) {
        reflowStart[i] = t
        t += (slots[i].durationMs - slots[i].overlapToNextMs)
    }
    val contentCount = slots.count { it.clip.isContent() }
    // Each item's place among the photos, videos and cards, worked out once rather than per item.
    val contentBefore = remember(slots) { slots.runningFold(0) { n, sl -> if (sl.clip.isContent()) n + 1 else n } }
    Box(Modifier.fillMaxSize().testTag(TIMELINE_MAIN_ROW_TAG)) {
        val visible = geometry.visible()
        for (i in slots.indices) {
            val slot = slots[i]
            val dragged = drag?.index == i
            if (i !in visible && !dragged) continue
            // The others make room; the dragged one stays under the finger (from where it was).
            val start = if (drag != null && !dragged) reflowStart.getValue(i) else slot.startMs.toDouble()
            val x = PAD_DP + zoom.xOf(start) + if (dragged) drag.dxDp else 0.0
            val w = slot.durationMs / zoom.msPerDp
            val contentIndex = contentBefore[i]
            ClipTile(
                playing = playheadMs in slot.startMs until slot.endMs,
                slot = slot,
                xDp = x,
                widthDp = w,
                msPerDp = zoom.msPerDp,
                selected = i == selectedIndex,
                lifted = dragged,
                missing = slot.clip.id in missing,
                thumbnail = thumbnail,
                position = contentIndex + 1,
                count = contentCount,
                actions = actions,
            )
        }
        if (drag == null) {
            selectedIndex?.let { sel ->
                if (geometry.selectedTrimmable) TrimHandles(geometry, sel)
            }
        }
    }
}

@Composable
private fun ClipTile(
    playing: Boolean,
    slot: MainSlot,
    xDp: Double,
    widthDp: Double,
    msPerDp: Double,
    selected: Boolean,
    lifted: Boolean,
    missing: Boolean,
    thumbnail: ThumbSource,
    position: Int,
    count: Int,
    actions: TimelineActions,
) {
    val media = LocalMediaColors.current
    val clip = slot.clip
    // A text card is named by what it says, so two cards can be told apart by ear.
    val cardText = (clip as? CardClip)?.text?.takeIf { it.isNotBlank() }?.take(CARD_TEXT_SPOKEN)
    val name = if (cardText != null) stringResource(R.string.timeline_text_card_named, cardText) else stringResource(itemName(clip))
    val length = durationText(slot.durationMs * 1000)
    val description =
        when {
            !clip.isContent() -> stringResource(R.string.timeline_part_description, name, length)
            missing -> stringResource(R.string.timeline_item_missing_description, name, position, count, length)
            else -> stringResource(R.string.timeline_item_description, name, position, count, length)
        }
    val selectedLabel = stringResource(R.string.timeline_selected)
    val playingLabel = stringResource(R.string.editor_item_current)
    val moveLeft = stringResource(R.string.action_move_left)
    val moveRight = stringResource(R.string.action_move_right)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .offset { IntOffset((xDp * density).roundToInt(), 0) }
            // Zoomed in, an item is often wider than the screen: never squeezed to the row's width.
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width(widthDp.coerceAtLeast(2.0).dp)
            .fillMaxHeight()
            // The tag and semantics cover the item's whole time span; the padding below is the visible gap.
            .testTag(clipTag(clip.id))
            // One TalkBack stop per item; the length drawn on it is already in the description.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                this.selected = selected
                if (selected) {
                    stateDescription = selectedLabel
                } else if (playing) {
                    stateDescription = playingLabel
                }
                onClick {
                    actions.select(clip.id)
                    true
                }
                if (clip.isContent()) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(moveLeft) {
                                if (position > 1) actions.move(clip.id, position - 2)
                                position > 1
                            },
                            CustomAccessibilityAction(moveRight) {
                                if (position < count) actions.move(clip.id, position)
                                position < count
                            },
                        )
                }
            }.padding(vertical = 4.dp, horizontal = 1.dp)
            .then(if (lifted) Modifier.shadow(8.dp, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (missing) MaterialTheme.colorScheme.errorContainer else media.visualClip)
            .then(if (selected) Modifier.border(BorderStroke(3.dp, primary), RoundedCornerShape(8.dp)) else Modifier),
    ) {
        if (!missing) ThumbStripContent(clip, widthDp, msPerDp, thumbnail, Modifier.fillMaxSize().testTag(thumbTag(clip.id)))
        val onFill = if (missing) MaterialTheme.colorScheme.onErrorContainer else media.onVisualClip
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background((if (missing) MaterialTheme.colorScheme.errorContainer else media.visualClip).copy(alpha = 0.85f))
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(if (missing) R.drawable.ic_broken_image else itemIcon(clip)),
                contentDescription = null,
                tint = onFill,
                modifier = Modifier.size(14.dp),
            )
            if (widthDp >= 56) {
                Spacer(Modifier.width(2.dp))
                Text(
                    length,
                    style = MaterialTheme.typography.labelSmall,
                    color = onFill,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

fun itemName(clip: MainClip): Int =
    when (clip) {
        is IntroClip -> R.string.preset_slot_intro
        is EndingClip -> R.string.preset_slot_ending
        is PhotoClip -> R.string.editor_item_photo
        is VideoClip -> R.string.editor_item_video
        is CardClip -> if (clip.text.isNullOrBlank()) R.string.timeline_colour_card else R.string.timeline_text_card
    }

fun itemIcon(clip: MainClip): Int =
    when (clip) {
        is IntroClip -> R.drawable.ic_movie
        is EndingClip -> if (clip.kind == EndingKind.AUDIO) R.drawable.ic_music_note else R.drawable.ic_movie
        is PhotoClip -> R.drawable.ic_image
        is VideoClip -> R.drawable.ic_videocam
        is CardClip -> if (clip.text.isNullOrBlank()) R.drawable.ic_palette else R.drawable.ic_text_fields
    }

@Composable
private fun TrimHandles(
    geometry: TimelineGeometry,
    index: Int,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    for ((edge, x) in listOf(TrimEdge.START to geometry.left(index), TrimEdge.END to geometry.right(index) - TimelineGeometry.HANDLE_DP)) {
        Box(
            Modifier
                .offset(x = (PAD_DP + x).dp)
                .width(TimelineGeometry.HANDLE_DP.dp)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clip(
                    RoundedCornerShape(
                        if (edge ==
                            TrimEdge.START
                        ) {
                            8.dp
                        } else {
                            0.dp
                        },
                        if (edge ==
                            TrimEdge.END
                        ) {
                            8.dp
                        } else {
                            0.dp
                        },
                        if (edge == TrimEdge.END) 8.dp else 0.dp,
                        if (edge == TrimEdge.START) 8.dp else 0.dp,
                    ),
                ).background(primary)
                .testTag(if (edge == TrimEdge.START) TRIM_START_TAG else TRIM_END_TAG)
                // A drag target only; TalkBack trims from the bar's Trim or Duration panel.
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width = 4.dp, height = 20.dp)) {
                drawLine(onPrimary, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
            }
        }
    }
}

/** A transition's marker: a bow tie on the row's centre line ([centreDp] in the overlay) that opens it. */
@Composable
private fun TransitionMarker(
    point: MarkerPoint,
    centreDp: Double,
    slots: List<MainSlot>,
    actions: TimelineActions,
) {
    val lead = slots.getOrNull(point.leadIndex) ?: return
    val kind = lead.clip.transitionToNext?.kind ?: return
    val kindName = stringResource(transitionName(kind))
    val leadName = stringResource(itemName(lead.clip))
    val description = stringResource(R.string.timeline_transition_marker, leadName, kindName)
    Box(
        Modifier
            .offset(
                x = (PAD_DP + point.xDp - TimelineGeometry.MARKER_HIT_DP / 2).dp,
                y = (centreDp - TimelineGeometry.MARKER_HIT_DP / 2).dp,
            ).size(TimelineGeometry.MARKER_HIT_DP.dp)
            .testTag(markerTag(point.boundary))
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    actions.transition(lead.clip.id)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The same size and shape as the "+", in a quiet surface colour, so the row reads calmly.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(TimelineGeometry.MARKER_DP.dp),
        ) {
            Icon(painterResource(R.drawable.ic_transition), contentDescription = null, modifier = Modifier.padding(2.dp))
        }
    }
}

@Composable
private fun PlusButton(
    point: PlusPoint?,
    boundary: Int,
    slots: List<MainSlot>,
    actions: TimelineActions,
    topDp: Double,
    centreDp: Double,
) {
    AnimatedVisibility(point != null, enter = fadeIn(), exit = fadeOut()) {
        val p = point ?: return@AnimatedVisibility
        val anchor = slots.getOrNull(p.anchorIndex) ?: return@AnimatedVisibility
        val kind = stringResource(itemName(anchor.clip))
        // "Video 2": the position among the photos, videos and cards, so every "+" reads differently.
        val position = slots.take(p.anchorIndex + 1).count { it.clip.isContent() }
        val name = if (anchor.clip.isContent()) stringResource(R.string.timeline_item_numbered, kind, position) else kind
        val description =
            stringResource(if (p.side == InsertSide.BEFORE) R.string.timeline_add_before else R.string.timeline_add_after, name)
        val y = (if (p.top) topDp else centreDp) - TimelineGeometry.PLUS_HIT_DP / 2
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset(x = (PAD_DP + p.xDp - TimelineGeometry.PLUS_HIT_DP / 2).dp, y = y.dp)
                    .size(TimelineGeometry.PLUS_HIT_DP.dp)
                    .testTag(plusTag(boundary))
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                        onClick {
                            actions.insert(anchor.clip.id, p.side)
                            true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PlusCircle()
            }
        }
    }
}

@Composable
private fun RowsToggle(
    hidden: List<TimelineRow>,
    allRowsRequested: Boolean,
    show: Boolean,
    metrics: RowMetrics,
    actions: TimelineActions,
) {
    if (!show) return
    val label =
        if (allRowsRequested) {
            stringResource(R.string.timeline_show_fewer_rows)
        } else {
            pluralStringResource(R.plurals.timeline_show_all_rows, hidden.size, hidden.size)
        }
    val kinds = if (allRowsRequested) emptyList() else hidden.distinctBy { it.type }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The kinds of the hidden rows, so it is clear what is behind the control, only when they fit beside
        // the words at full size; the words come first (they shrink, then take two lines, never three).
        val wordsDp =
            with(density) {
                measurer
                    .measure(label, style)
                    .size.width
                    .toDp()
            }
        val showKinds = 24.dp + 24.dp + 8.dp + wordsDp + 24.dp * kinds.size <= maxWidth
        TextButton(
            onClick = { if (allRowsRequested) actions.showFewerRows() else actions.showAllRows() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.toggleDp.dp)
                    .testTag(TIMELINE_ROWS_TOGGLE_TAG),
        ) {
            Icon(
                painterResource(if (allRowsRequested) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            AutoSizeLabel(label, style = style, modifier = Modifier.weight(1f, fill = false))
            if (showKinds) {
                for (row in kinds) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(rowLook(row.type).first), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
