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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ResizeEdge
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Test tags. */
const val TIMELINE_TAG = "timeline"
const val TIMELINE_RULER_TAG = "timeline_ruler"
const val TIMELINE_PLAYHEAD_TAG = "timeline_playhead"
const val TIMELINE_MAIN_ROW_TAG = "timeline_main_row"
const val TIMELINE_ROWS_TOGGLE_TAG = "timeline_rows_toggle"

/** The rows under the ruler, which scroll up and down. */
const val TIMELINE_ROWS_TAG = "timeline_rows"

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

/** The "+" in the column at the right end of the row with [key], which has items (cm-plus-position). */
fun rowEndPlusTag(key: String) = "timeline_row_end_plus_$key"

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

    /** Something new on row [key] at [atMs] (the playhead when null): an empty row's "+", or a row's own "+" in a gap. */
    fun addHere(
        key: String,
        atMs: Long? = null,
        otherRow: Boolean = false,
    ) {}

    /** A frame row's "+": a new frame from [atMs], taking over from frame [takeOver] (which ends there) if any. */
    fun addFrame(
        key: String,
        atMs: Long,
        takeOver: ClipId?,
    ) {}

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

    /** A handle of an item on a row other than MAIN let go: its [edge] goes to [toMs] (inside the item's limits). */
    fun resize(
        id: ClipId,
        edge: TrimEdge,
        toMs: Long,
    ) {}
}

/**
 * Heights at a font scale (ARCHITECTURE section 11.3: MAIN 72 dp, others
 * 48 dp, 8 dp between them, growing with the text). In a [compact] window (a
 * phone held sideways, under 480 dp tall) the rows are slimmer, MAIN 64 dp
 * and the others 40 dp with 4 dp between them, so at least four rows show on
 * a 411 dp tall phone (cm-timeline-scroll). MAIN keeps room for a transition
 * marker under its lifted "+" (TimelineGeometry.markerCentre is 48 dp down).
 */
data class RowMetrics(
    val fontScale: Float,
    val compact: Boolean = false,
) {
    private val grow = fontScale.coerceAtLeast(1f)
    val rulerDp: Double get() = 28.0 * grow
    val mainDp: Double get() = (if (compact) 64.0 else 72.0) + 24.0 * (grow - 1)
    val rowDp: Double get() = (if (compact) 40.0 else 48.0) * grow

    /** The gap under each row. */
    val gapDp: Double get() = if (compact) 4.0 else PreviewLayoutReducer.ROW_GAP_DP

    /** The row headers are an icon each (the owner's call: no label, so the rows keep the width); 48 dp to touch. */
    val headerDp: Double get() = HEADER_DP
    val toggleDp: Double get() = 48.0 * grow

    fun heightOf(type: RowType): Double = if (type == RowType.MAIN) mainDp else rowDp
}

/** The width of a row's icon header: a 24 dp icon in a 48 dp touch target. */
private const val HEADER_DP = 48.0

/** How much of a text card's words TalkBack reads with it. */
private const val CARD_TEXT_SPOKEN = 60

/** Padding at the start of the time span, so the first "+" can be reached (its end has the "+" column). */
private const val PAD_DP = 28.0

/** Rounding room for the nudge that brings a selected item's "+" into view. */
private const val NUDGE_SLACK_DP = 1.0

/**
 * The editor timeline (ARCHITECTURE sections 11.3 to 11.7): a ruler, the
 * playhead, and the rows in screen order, each with its type icon at its
 * left edge (the icon alone: TalkBack reads the row's name). The MAIN row
 * shows each item with thumbnails and its length, trim handles on the
 * selected item; every other row shows its items at their times. Every row
 * has its own "+" in a column at its right end, which adds right of the
 * cursor ([RowEndPlus]); the selected item has a "+" on each side instead. One pointer handler runs
 * [TimelineGestureArbiter] over all of it, so a pinch, a scroll, a trim, a
 * scrub, a long-press drag and a tap never fight:
 *
 * - On a row's icon: a tap selects the row, a long-press then a drag up or
 *   down moves the row on screen (never in the video), and a long-press let
 *   go without moving opens the row menu.
 * - On an item: a tap selects it and moves the cursor to the time tapped
 *   ([TapSeek]: while playing on any row, while paused on the rows the preview
 *   shows); a long-press let go without moving opens its menu; a
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
    /** How long a new photo is (the project's setting): the room a row's "+" looks for on a picture-in-picture row. */
    photoMs: Long = RowEndPlus.SEEN_MS,
) {
    val totalMs = slots.lastOrNull()?.endMs ?: 0L
    // The cursor the row-end "+" buttons describe, and act at for TalkBack: while playing, the time play started from,
    // so TalkBack is not told a new place every frame and adds where it said.
    val playedFrom = remember(playing) { playheadMs }
    val spokenMs = if (playing) playedFrom else playheadMs
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
        // While playing, moving the view along the time by hand (a swipe, a pinch) or moving or holding an item (a
        // drag, a trim, a long-press) stops the following until Play is pressed again, so the user can look ahead,
        // or at what was just moved, without being pulled back. A tap does not, and moving the cursor (a tap on an
        // item, cm-tap-seek, a tap or a scrub on the ruler) starts the following again from there.
        var followPaused by remember { mutableStateOf(false) }
        val latestPlaying by rememberUpdatedState(playing)
        LaunchedEffect(playing) { if (playing) followPaused = false }
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
        fun nudge(sel: ClipId?) {
            if (sel == null) return
            val timed = rows.firstNotNullOfOrNull { r -> r.clips.firstOrNull { it.id == sel } }
            val span =
                slots.firstOrNull { it.clip.id == sel }?.let { it.startMs to it.endMs }
                    ?: timed?.let { it.startMs to it.endMs }
                    ?: return
            val mainClip = slots.firstOrNull { it.clip.id == sel }?.clip
            // Beyond the trim handles on MAIN's photos, videos and cards, and on any other row's item that has them;
            // on the edge of the intro and the ending, which have none.
            val outward =
                when {
                    mainClip != null -> if (mainClip.isContent()) TimelineGeometry.DISPLACE_DP else 0.0
                    timed?.resize != null -> TimelineGeometry.DISPLACE_DP
                    else -> TimelineGeometry.PLUS_HIT_DP / 2
                }
            // How far in from each end of the time span the item's edge must be for its "+" there to show (negative: it
            // may be past the edge, into the span's padding). Null where nothing goes: before the intro, after the ending.
            val room = if (mainClip is IntroClip) null else outward + TimelineGeometry.PLUS_HIT_DP / 2 - PAD_DP
            val endRoom = if (mainClip is EndingClip) null else outward + TimelineGeometry.PLUS_HIT_DP / 2 - PAD_DP
            val left = z.xOf(span.first.toDouble())
            val right = z.xOf(span.second.toDouble())
            // Only a small shift: a "+" well off screen (a long item) is left for the item's menu.
            val reach = 2 * maxOf(room ?: 0.0, endRoom ?: 0.0, TimelineGeometry.PLUS_HIT_DP / 2) + NUDGE_SLACK_DP
            val dx =
                when {
                    room != null && left < room && room - left <= reach -> {
                        left - room
                    }

                    endRoom != null &&
                        right > spanWidth - endRoom &&
                        right - (spanWidth - endRoom) <= reach &&
                        right - left < spanWidth - maxOf(room ?: 0.0, 0.0) - maxOf(endRoom, 0.0) -> {
                        right - (spanWidth - endRoom)
                    }

                    else -> {
                        0.0
                    }
                }
            if (dx != 0.0) onZoom(TimelineZoom.nudgePastEnds(z, dx, room ?: 0.0, totalMs, spanWidth, endRoom ?: 0.0))
        }
        // While playing and following, the view is the following's: an item selected near the edge is not nudged
        // one way while the following turns the page the other. The nudge is kept for when playing stops, and runs
        // then only for that selection: a Pause with nothing newly selected moves nothing.
        var nudgeWaiting by remember { mutableStateOf<ClipId?>(null) }
        LaunchedEffect(selected) {
            if (playing && !followPaused) {
                nudgeWaiting = selected
                return@LaunchedEffect
            }
            nudgeWaiting = null
            nudge(selected)
        }
        LaunchedEffect(playing) {
            val waiting = nudgeWaiting ?: return@LaunchedEffect
            if (playing) return@LaunchedEffect
            nudgeWaiting = null
            if (waiting == selected) nudge(selected)
        }
        val spans = remember(slots) { slots.map { it.span() } }
        val selectedClip = selectedIndex?.let { slots[it].clip }
        var trim by remember { mutableStateOf<TrimPreview?>(null) }
        var drag by remember { mutableStateOf<DragPreview?>(null) }
        var rowDrag by remember { mutableStateOf<RowDragPreview?>(null) }
        var timedDrag by remember { mutableStateOf<TimedDragPreview?>(null) }
        // Read only by the row being trimmed, so a handle drag recomposes that row, not the whole timeline.
        val timedTrim = remember { mutableStateOf<TimedTrimPreview?>(null) }
        val timedTrimming by remember { derivedStateOf { timedTrim.value != null } }
        // The layout during a trim shows the trimmed item at its new length.
        val shownSlots = trim?.let { t -> relayout(slots, t.index, TrimMath.dragged(slots[t.index].clip, t.edge, t.deltaMs)) } ?: slots
        val shownSpans =
            if (trim ==
                null
            ) {
                spans
            } else {
                shownSlots.map { it.span() }
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
        val bands = RowOrder.bands(shown.map { it.key to metrics.heightOf(it.type) }, metrics.gapDp)
        val mainTopInRows = shown.indexOfFirst { it.type == RowType.MAIN }.let { i -> if (i >= 0) bands[i].topDp else 0.0 }
        val timedPlus =
            shown.filter { it.type != RowType.MAIN }.associate { row ->
                row.key to TimedRowGeometry.plusPoints(row.clips, z, selected, spanWidth)
            }
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        val scrollbar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        // What the rows keep in view (cm-timeline-scroll): the selected row, or the row of the selected item,
        // when it changes (a new caption on a new row at the bottom is seen at once), and again when the rows'
        // room changes (a turn of the phone, a panel opening); with nothing selected, MAIN when the editor opens
        // or the rows change size (a turn of the phone, a text size), never for a banner or a panel. Letting go
        // of a selection scrolls nothing, and a finger on the timeline stops the scroll at once.
        val mainKey = shown.firstOrNull { it.type == RowType.MAIN }?.key
        val focusKey =
            selectedRow ?: selected?.let { id ->
                if (slots.any { it.clip.id == id }) mainKey else shown.firstOrNull { r -> r.clips.any { it.id == id } }?.key
            }
        val latestBands by rememberUpdatedState(bands)
        val viewport = rowScroll.viewportSize
        val room = remember { RevealRoom() }
        var revealing by remember { mutableIntStateOf(0) }
        LaunchedEffect(focusKey, metrics, viewport) {
            // The first layout with room for the rows counts as a change of size (the editor opening).
            val resized = room.metrics != metrics || !room.opened
            room.metrics = metrics
            if (viewport > 0) room.opened = true
            if (focusKey != null || (resized && viewport > 0)) revealing++
        }
        LaunchedEffect(revealing, touching) {
            if (revealing == 0) return@LaunchedEffect
            // A finger on the timeline wins: the request is dropped, not kept for when it lifts.
            if (touching) {
                revealing = 0
                return@LaunchedEffect
            }
            val key = focusKey ?: mainKey ?: return@LaunchedEffect
            // After the frame that lays the rows out with it (a row just added has no room to scroll to before).
            withFrameNanos { }
            val band = latestBands.firstOrNull { it.key == key } ?: return@LaunchedEffect
            // With the gap under it, so a row brought up from below does not touch the bar.
            val target =
                RowOrder.revealScroll(
                    band.copy(heightDp = band.heightDp + metrics.gapDp),
                    density.density,
                    rowScroll.value,
                    rowScroll.viewportSize,
                )
            if (target != null) rowScroll.animateScrollTo(target.coerceAtMost(rowScroll.maxValue))
            revealing = 0
        }
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
                selected,
                photoMs,
                playing,
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
                                    timedTrim = { timedTrim.value = it },
                                ),
                            touched = { touching = it },
                            heldOrMoved = { if (latestPlaying) followPaused = true },
                            followAgain = { followPaused = false },
                        )
                    },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Ruler(z, totalMs, playheadMs, playing, metrics, spanWidth, latestActions)
                    // Scrolled by the pointer handler above (a vertical swipe), so a scroll never fights a trim, a drag
                    // or a pinch; TalkBack scrolls it through these semantics.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .testTag(TIMELINE_ROWS_TAG)
                            .semantics {
                                verticalScrollAxisRange =
                                    ScrollAxisRange(value = { rowScroll.value.toFloat() }, maxValue = { rowScroll.maxValue.toFloat() })
                                scrollBy { _, y ->
                                    rowScroll.dispatchRawDelta(y)
                                    true
                                }
                            }.drawWithContent {
                                drawContent()
                                // A thin bar at the right edge says there are more rows, and where these are among them.
                                val max = rowScroll.maxValue
                                if (max > 0 && max != Int.MAX_VALUE) {
                                    val view = size.height
                                    val thumb = maxOf(SCROLLBAR_MIN_DP.dp.toPx(), view * view / (view + max))
                                    val y = (view - thumb) * rowScroll.value / max
                                    val w = SCROLLBAR_DP.dp.toPx()
                                    drawRoundRect(
                                        scrollbar,
                                        topLeft = Offset(size.width - w - SCROLLBAR_INSET_DP.dp.toPx(), y),
                                        size = Size(w, thumb),
                                        cornerRadius = CornerRadius(w / 2),
                                    )
                                }
                            }.verticalScroll(rowScroll, enabled = false),
                    ) {
                        for ((i, row) in shown.withIndex()) {
                            val lift = rowLift(rowDrag, bands, i, row.key, metrics.gapDp)
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
                                        TimedRowContent(row, z, selected, playheadMs, timedDrag, timedTrim, latestActions)
                                    }
                                    // The row's own "+" floats over its right edge (cm-plus-position), items running on under it.
                                    // Its TalkBack stop is here, read right after the row's items; the circle itself is drawn in
                                    // the "+" layer above, over the playhead.
                                    val band = bands[i]
                                    val underRuler by remember(band, density) {
                                        derivedStateOf { band.centreDp * density.density < rowScroll.value }
                                    }
                                    // While anything is dragged the "+" fade out (below), so the row's "+" is not offered then either.
                                    val still = drag == null && rowDrag == null && timedDrag == null && trim == null && !timedTrimming
                                    EndPlusTarget(
                                        plus =
                                            rowPlus(row, shownSlots, shownSpans, selectedIndex, selected, spokenMs, totalMs, photoMs)
                                                .takeIf { still && !underRuler },
                                        row = row,
                                        slots = shownSlots,
                                        actions = latestActions,
                                        modifier = Modifier.align(Alignment.CenterEnd),
                                    )
                                }
                            }
                            Spacer(Modifier.height(metrics.gapDp.dp))
                        }
                    }
                }
                Playhead(z, playheadMs, metrics)
                // The "+" points over everything, the playhead line included, on every row. They fade out
                // while anything is dragged (a trim included) and come back at the new edges on release.
                // The layer reaches above the rows for a "+" lifted to MAIN's top edge over a transition marker.
                val lift = TimelineGeometry.ABOVE_ROW_DP
                androidx.compose.animation.AnimatedVisibility(
                    visible = drag == null && rowDrag == null && timedDrag == null && trim == null && !timedTrimming,
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
                            // A row whose middle is scrolled up under the ruler has no "+" (drawn or for TalkBack).
                            val underRuler by remember(band, density) {
                                derivedStateOf { band.centreDp * density.density < rowScroll.value }
                            }
                            if (underRuler) continue
                            // MAIN's box starts [lift] above its row, so a lifted "+" fits; the others start at their row.
                            val above = if (row.type == RowType.MAIN) 0.0 else lift
                            val isMain = row.type == RowType.MAIN
                            Box(
                                Modifier
                                    // The scroll is read when placing and drawing, not composing: it changes every frame of a scroll.
                                    .offset { IntOffset(0, (((band.topDp + above) * density.density) - rowScroll.value).roundToInt()) }
                                    .fillMaxWidth()
                                    .height((band.heightDp + lift - above).dp)
                                    // A row scrolled up under the ruler takes its "+" with it: nothing is drawn above the rows,
                                    // except MAIN's lifted "+" while MAIN's top is in view.
                                    .drawWithContent {
                                        val boxTop = (band.topDp + above) * density.density - rowScroll.value
                                        val rowsTop = lift * density.density
                                        val mainInView = isMain && boxTop >= 0
                                        val top = if (mainInView) 0f else (rowsTop - boxTop).toFloat()
                                        clipRect(top = top) { this@drawWithContent.drawContent() }
                                    },
                            ) {
                                // Every row's own "+" floats at its right edge, in the same place on every row
                                // (cm-plus-position); it adds at the cursor (RowEndPlus). On the row of the selected item it
                                // gives way to the "+" on each side of that item. Drawn here; its TalkBack stop and test tag
                                // are in the row (EndPlusTarget).
                                if (rowPlus(row, shownSlots, shownSpans, selectedIndex, selected, spokenMs, totalMs, photoMs) != null) {
                                    val centre = if (row.type == RowType.MAIN) lift + metrics.mainDp / 2 else band.heightDp / 2
                                    EndPlusCircle(spanWidth, centre)
                                }
                                if (row.type == RowType.MAIN && shownSlots.isNotEmpty()) {
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
                                } else if (row.type != RowType.MAIN) {
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
            // "N more" counts the rows not wholly in view where the rows are scrolled to now (cm-timeline-scroll).
            val outOfView =
                remember(shown, bands, hiddenRows) {
                    derivedStateOf {
                        val d = density.density
                        val top = rowScroll.value
                        val bottom = top + rowScroll.viewportSize
                        // Each row and gap above is laid out rounded to whole pixels: up to a pixel each.
                        val out =
                            shown.filterIndexed { i, _ ->
                                val slack = 1 + 2 * i
                                bands[i].topDp * d < top - slack || bands[i].bottomDp * d > bottom + slack
                            }
                        if (out.isEmpty() || rowScroll.viewportSize == 0) hiddenRows else out
                    }
                }
            // Read inside the control, so a scroll recomposes only it.
            RowsToggle({ outOfView.value }, allRowsRequested, hiddenRows.isNotEmpty() || allRowsRequested, metrics, actions)
        }
    }
}

/** The room the rows were last kept in view for (not state: changing it recomposes nothing). */
private class RevealRoom {
    var metrics: RowMetrics? = null
    var opened = false
}

/** How far row [index] is drawn from its place while a row is dragged: the dragged one follows the finger, the others make room. */
private fun rowLift(
    drag: RowDragPreview?,
    bands: List<RowBand>,
    index: Int,
    key: String,
    gapDp: Double,
): Double {
    if (drag == null) return 0.0
    if (drag.key == key) return drag.dyDp
    val from = bands.indexOfFirst { it.key == drag.key }
    if (from < 0) return 0.0
    val room = bands[from].heightDp + gapDp
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

    override fun addHere(
        key: String,
        atMs: Long?,
        otherRow: Boolean,
    ) = latest().addHere(key, atMs, otherRow)

    override fun addFrame(
        key: String,
        atMs: Long,
        takeOver: ClipId?,
    ) = latest().addFrame(key, atMs, takeOver)

    override fun moveRow(
        key: String,
        toIndex: Int,
    ) = latest().moveRow(key, toIndex)

    override fun moveTimed(
        id: ClipId,
        startMs: Long,
        toRow: String,
    ) = latest().moveTimed(id, startMs, toRow)

    override fun resize(
        id: ClipId,
        edge: TrimEdge,
        toMs: Long,
    ) = latest().resize(id, edge, toMs)
}

private fun MainClip.isContent() = this is PhotoClip || this is VideoClip || this is CardClip

private fun MainSlot.span() = SlotSpan(clip.id.value, startMs, endMs, clip.isContent(), ending = clip is EndingClip)

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

/**
 * A handle of item [id] on row [key] dragged by [deltaMs]: the item as it
 * will be on release, kept inside its limits (the same ones the model uses).
 */
private data class TimedTrimPreview(
    val id: ClipId,
    val key: String,
    val edge: TrimEdge,
    val deltaMs: Double,
) {
    fun target(clip: RowClip): Long {
        val now = if (edge == TrimEdge.START) clip.startMs else clip.endMs
        val range = clip.resize ?: return now
        return range.clamp(edge.model(), (now + deltaMs).roundToLong())
    }

    fun span(clip: RowClip): Pair<Long, Long> = if (edge == TrimEdge.START) target(clip) to clip.endMs else clip.startMs to target(clip)
}

/** The model's name for a handle's side. */
fun TrimEdge.model(): ResizeEdge = if (this == TrimEdge.START) ResizeEdge.START else ResizeEdge.END

/** Where the pointer handler shows what a drag will do. */
private class Previews(
    val trim: (TrimPreview?) -> Unit,
    val drag: (DragPreview?) -> Unit,
    val row: (RowDragPreview?) -> Unit,
    val timed: (TimedDragPreview?) -> Unit,
    val timedTrim: (TimedTrimPreview?) -> Unit,
) {
    fun clear() {
        trim(null)
        drag(null)
        row(null)
        timed(null)
        timedTrim(null)
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
    val selectedId: ClipId?,
    val photoMs: Long,
    val playing: Boolean,
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
    heldOrMoved: () -> Unit,
    followAgain: () -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val firstEvent = awaitPointerEvent(PointerEventPass.Main)
            val first = firstEvent.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            val d = density()

            fun dp(px: Float) = (px / d).toDouble()
            val c0 = current()
            val start = hit(c0, metrics, dp(first.position.x), dp(first.position.y), rowScroll.value / d)
            val arbiter = TimelineGestureArbiter(dp(viewConfiguration.touchSlop), rowsScroll = rowScroll.maxValue > 0)
            // A row dragged by its icon moves with the rows when they scroll under it (an edge of the rows).
            val scrollAtDown = rowScroll.value
            arbiter.down(first.id.value, dp(first.position.x), dp(first.position.y), first.uptimeMillis, start.kind)
            first.consume()
            touched(true)
            // Whether the gesture moved the view along the time or moved or held an item (see ACTED), which stops
            // the view following the playhead.
            var acted = false
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
                        is Hit.OnRowEnd -> start.under != null
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
                    if (arbiter.mode in ACTED) acted = true
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
                                    if (next != live) acted = true
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
                                // The slop was spent getting here: the first step is from the down point, no jump.
                                val from = if (before != GestureMode.SCROLL) first.position else lastScroll
                                // One way only (cm-timeline-scroll): up and down the rows, or along the time.
                                if (arbiter.axis == ScrollAxis.ROWS) {
                                    rowScroll.dispatchRawDelta(from.y - ch.position.y)
                                } else {
                                    live = TimelineZoom.scrollBy(live, dp(from.x - ch.position.x), c.totalMs, c.spanWidth)
                                    onZoom(live)
                                    acted = true
                                }
                                lastScroll = ch.position
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.TRIM -> {
                            val ch = event.changes.firstOrNull { it.id == first.id }
                            val sel = c.selectedIndex
                            if (ch != null && start is Hit.OnTimedTrim) {
                                trimDelta = dp(ch.position.x - first.position.x) * c.zoom.msPerDp
                                val edge = if (start.kind == GestureStart.TRIM_START) TrimEdge.START else TrimEdge.END
                                previews.timedTrim(TimedTrimPreview(start.id, start.key, edge, trimDelta))
                            } else if (ch != null && sel != null) {
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
                                // Near the top or bottom of the rows, a row or an item being dragged scrolls them.
                                val moving = start.held()
                                if (moving is Hit.OnHeader || moving is Hit.OnTimedClip) {
                                    edgeScroll(rowScroll, dp(ch.position.y), metrics, size.height / d.toDouble(), d)
                                }
                                dragDx = dp(ch.position.x - first.position.x)
                                dragDy = dp(ch.position.y - first.position.y) + (rowScroll.value - scrollAtDown) / d
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
                            // cm-tap-seek: a tap on an item of a row of [type] from [startMs] to [endMs] moves the
                            // cursor to the time tapped (TapSeek says when), and the view follows it if playing.
                            fun seekInItem(
                                type: RowType,
                                enabled: Boolean,
                                startMs: Long,
                                endMs: Long,
                            ) {
                                if (!TapSeek.seeks(type, c.playing, enabled)) return
                                val x = dp(first.position.x) - metrics.headerDp - PAD_DP
                                actions.seek(TapSeek.timeAt(c.zoom, x, startMs, endMs, c.totalMs))
                                followAgain()
                            }
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

                                is Hit.OnRowEnd -> {
                                    c.row(start.key)?.let { r ->
                                        rowPlus(r, c.slots, c.spans, c.selectedIndex, c.selectedId, c.playheadMs, c.totalMs, c.photoMs)
                                            ?.let {
                                                // Where it goes is out of view: brought in, so what is added there is seen.
                                                val goes = goesAt(it, c.slots)
                                                if (goes != null) {
                                                    val shown =
                                                        TimelineZoom.reveal(
                                                            c.zoom,
                                                            goes.toDouble(),
                                                            c.totalMs,
                                                            c.spanWidth,
                                                        )
                                                    if (shown != c.zoom) onZoom(shown)
                                                }
                                                act(it, r, c.slots, actions)
                                            }
                                    }
                                }

                                is Hit.OnClip -> {
                                    c.slots.getOrNull(start.index)?.let { s ->
                                        actions.select(s.clip.id)
                                        seekInItem(RowType.MAIN, true, s.startMs, s.endMs)
                                    }
                                }

                                is Hit.OnTimedClip -> {
                                    actions.select(start.id)
                                    val row = c.row(start.key)
                                    row?.clips?.firstOrNull { it.id == start.id }?.let {
                                        seekInItem(
                                            row.type,
                                            row.enabled,
                                            it.startMs,
                                            it.endMs,
                                        )
                                    }
                                }

                                is Hit.OnHeader -> {
                                    actions.selectRow(start.key)
                                }

                                Hit.OnRuler -> {
                                    val t = c.zoom.timeAt(dp(first.position.x) - metrics.headerDp - PAD_DP)
                                    actions.seek(t.coerceIn(0.0, c.totalMs.toDouble()).toLong())
                                    followAgain()
                                }

                                // A tap on a trim handle keeps the item selected (it is about to be dragged), and is a
                                // tap on the item: on a narrow item the handles cover all of it.
                                is Hit.OnTrim -> {
                                    c.selectedIndex?.let { c.slots.getOrNull(it) }?.let { s ->
                                        seekInItem(RowType.MAIN, true, s.startMs, s.endMs)
                                    }
                                }

                                is Hit.OnTimedTrim -> {
                                    val row = c.row(start.key)
                                    row?.clips?.firstOrNull { it.id == start.id }?.let {
                                        seekInItem(
                                            row.type,
                                            row.enabled,
                                            it.startMs,
                                            it.endMs,
                                        )
                                    }
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
                                    if (start is Hit.OnTimedTrim) {
                                        // Where the finger let go, which may be further than the last move seen.
                                        event.changes.firstOrNull { it.id == first.id }?.let { ch ->
                                            trimDelta = dp(ch.position.x - first.position.x) * c.zoom.msPerDp
                                        }
                                        val edge = if (start.kind == GestureStart.TRIM_START) TrimEdge.START else TrimEdge.END
                                        val clip = c.row(start.key)?.clips?.firstOrNull { it.id == start.id }
                                        if (clip != null) {
                                            val preview = TimedTrimPreview(start.id, start.key, edge, trimDelta)
                                            val now = if (edge == TrimEdge.START) clip.startMs else clip.endMs
                                            val to = preview.target(clip)
                                            if (to != now) actions.resize(start.id, edge, to)
                                        }
                                        previews.timedTrim(null)
                                    } else if (sel != null) {
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
                                            dragDy = dp(ch.position.y - first.position.y) + (rowScroll.value - scrollAtDown) / d
                                        }
                                        // dragDy already holds the rows' scroll since the finger went down.
                                        val fingerInRows = dp(first.position.y) + dragDy - metrics.rulerDp + scrollAtDown / d
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
                                    followAgain()
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
                // A swipe along the time, a pinch, a trim, a drag or a long-press stops the view following the
                // playhead (see TimelineView); a tap, a scrub, a swipe up and down the rows, or a touch cancelled
                // before it was any of these, does not.
                if (acted) heldOrMoved()
                // Also when the handler is cancelled or a gesture throws: never left "touching" or mid-drag.
                touched(false)
                previews.clear()
            }
        }
    }
}

private val LIFTED = setOf(GestureMode.LONG_PRESSED, GestureMode.DRAG, GestureMode.MENU)

/**
 * The gestures that move or hold an item: any of them stops the view following the playhead. A swipe along the
 * time and a pinch stop it too, once they move the view (see the handler); a swipe up and down the rows, and a
 * scrub on the ruler, which moves the cursor the view follows, do not.
 */
private val ACTED =
    setOf(
        GestureMode.TRIM,
        GestureMode.DRAG,
        GestureMode.LONG_PRESSED,
        GestureMode.MENU,
    )

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

/**
 * A row or an item dragged with the finger within [EDGE_DP] of the top or
 * bottom of the rows scrolls them that way, faster nearer the edge (at most
 * [EDGE_STEP_DP] a move), so it can be taken to a row out of view.
 */
private fun edgeScroll(
    rowScroll: ScrollState,
    fingerYDp: Double,
    metrics: RowMetrics,
    heightDp: Double,
    density: Float,
) {
    if (rowScroll.maxValue == 0) return
    val toTop = fingerYDp - metrics.rulerDp
    val toBottom = heightDp - fingerYDp
    val stepDp =
        when {
            toTop < EDGE_DP -> -EDGE_STEP_DP * (1 - toTop.coerceAtLeast(0.0) / EDGE_DP)
            toBottom < EDGE_DP -> EDGE_STEP_DP * (1 - toBottom.coerceAtLeast(0.0) / EDGE_DP)
            else -> 0.0
        }
    if (stepDp != 0.0) rowScroll.dispatchRawDelta((stepDp * density).toFloat())
}

private const val EDGE_DP = 32.0

/** The rows' scroll bar: 4 dp wide, 2 dp in from the edge, never shorter than 24 dp. */
private const val SCROLLBAR_DP = 4
private const val SCROLLBAR_INSET_DP = 2
private const val SCROLLBAR_MIN_DP = 24
private const val EDGE_STEP_DP = 4.0

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

    /** A row's own "+", in the column at its right end: adds right of the cursor ([RowEndPlus]). */
    data class OnRowEnd(
        val key: String,
        /** What lies under the floating "+": a tap is the "+", but held or dragged it is that item (or the row's space). */
        val under: Hit? = null,
    ) : Hit {
        override val kind get() = under?.kind ?: GestureStart.PLUS
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

    /** A trim handle of the selected item [id] on row [key], a row other than MAIN. */
    data class OnTimedTrim(
        val key: String,
        val id: ClipId,
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
private fun Hit.held(): Hit =
    when {
        this is Hit.OnTimedPlus && under != null -> Hit.OnTimedClip(key, under)
        this is Hit.OnRowEnd && under != null -> under
        else -> this
    }

/**
 * A row's own "+" floating at its right edge (cm-plus-position), as it
 * stands with the cursor at a time: what a tap on it does, so its TalkBack
 * text, its tap and its drawing always agree.
 */
private sealed interface RowPlus {
    /** A frame row: a new frame from [atMs], taking over from [takeOver] (the frame under the cursor) if any. */
    data class Frame(
        val atMs: Long,
        val takeOver: RowClip?,
    ) : RowPlus

    /** An empty row: Add here from [atMs] (on MAIN, null: the first photos and videos). */
    data class Empty(
        val atMs: Long?,
    ) : RowPlus

    /** Add here from [atMs] (the cursor, or the last moment that keeps it in the video); [full]: on another row. */
    data class At(
        val atMs: Long,
        val full: Boolean = false,
    ) : RowPlus

    /** Right after [clip], the first place right of the cursor where the row has room. */
    data class After(
        val clip: RowClip,
    ) : RowPlus

    /** On MAIN, where [target] says. */
    data class OnMain(
        val target: MainEndTarget,
    ) : RowPlus
}

/**
 * [row]'s own "+" with the cursor at [cursorMs] in a video of [videoMs] (never
 * adding past its end), or null on the row of the selected item, whose own
 * "+" are on each side of it. Every other row has one:
 * - a frame row (the preset's included): a new frame from the cursor, the
 *   frame under the cursor handing over to it there ([RowPlus.Frame]);
 * - a row that never takes what it would add (the preset's music or closing
 *   message, a row switched off): at the cursor on another row of its kind,
 *   and it says so;
 * - any other row: [RowEndPlus.timed].
 */
private fun rowPlus(
    row: TimelineRow,
    slots: List<MainSlot>,
    spans: List<SlotSpan>,
    selectedIndex: Int?,
    selectedId: ClipId?,
    cursorMs: Long,
    videoMs: Long,
    photoMs: Long,
): RowPlus? {
    if (row.type == RowType.MAIN) {
        if (slots.isEmpty()) return RowPlus.Empty(null)
        if (selectedIndex != null) return null
        return RowEndPlus.main(spans, cursorMs)?.let { RowPlus.OnMain(it) }
    }
    if (row.clips.any { it.id == selectedId }) return null
    // Never later than a second before the end, so what is added is seen.
    val inVideo = cursorMs.coerceIn(0, maxOf(0, videoMs - RowEndPlus.MIN_SEEN_MS))
    if (row.type == RowType.FRAME) {
        // The frame shown at the cursor (a switched-off row shows none, so nothing hands over).
        val under = row.clips.firstOrNull { inVideo >= it.startMs && inVideo < it.endMs }?.takeIf { row.enabled }
        return RowPlus.Frame(inVideo, under)
    }
    if (row.fixed || !row.enabled) return RowPlus.At(inVideo, full = true)
    // A caption's (3 s) and a photo's (the project's setting) length are known before they are made; a sound's
    // only once picked.
    val length =
        when (row.type) {
            RowType.TEXT -> Limits.DEFAULT_TEXT_MS

            RowType.PIP -> photoMs

            // Not known until picked (or recorded): a second, so a sound added near the end is heard.
            else -> RowEndPlus.MIN_SEEN_MS
        }
    return when (val t = RowEndPlus.timed(row.clips, cursorMs, length, videoMs)) {
        is EndPlusTarget.At -> if (row.clips.isEmpty()) RowPlus.Empty(t.atMs) else RowPlus.At(t.atMs, t.full)
        is EndPlusTarget.After -> row.clips.firstOrNull { it.id == t.anchor }?.let { RowPlus.After(it) }
    }
}

/** The time a row's "+" adds at, to bring into view (null on MAIN, whose new item goes at an item's edge). */
private fun goesAt(
    plus: RowPlus,
    slots: List<MainSlot>,
): Long? =
    when (plus) {
        is RowPlus.Empty -> {
            plus.atMs
        }

        is RowPlus.At -> {
            plus.atMs
        }

        is RowPlus.Frame -> {
            plus.atMs
        }

        is RowPlus.After -> {
            plus.clip.endMs
        }

        is RowPlus.OnMain -> {
            slots.getOrNull(plus.target.anchorIndex)?.let {
                if (plus.target.side ==
                    InsertSide.AFTER
                ) {
                    it.endMs
                } else {
                    it.startMs
                }
            }
        }
    }

/**
 * A tap on [row]'s own "+" ([plus], worked out at [cursorMs]): the row is
 * selected and its chooser opens for that place, with the time fixed now, so
 * the video playing on, or the cursor moving, while the chooser is open does
 * not move it. MAIN's empty-row "+" opens the gallery, as Add photos does.
 */
private fun act(
    plus: RowPlus,
    row: TimelineRow,
    slots: List<MainSlot>,
    actions: TimelineActions,
) {
    when (plus) {
        is RowPlus.Empty -> {
            actions.addHere(row.key, plus.atMs)
        }

        is RowPlus.At -> {
            actions.addHere(row.key, plus.atMs, otherRow = plus.full)
        }

        is RowPlus.Frame -> {
            actions.addFrame(row.key, plus.atMs, plus.takeOver?.id)
        }

        is RowPlus.After -> {
            actions.selectRow(row.key)
            actions.insert(plus.clip.id, InsertSide.AFTER)
        }

        is RowPlus.OnMain -> {
            val anchor = slots.getOrNull(plus.target.anchorIndex) ?: return
            actions.selectRow(row.key)
            actions.insert(anchor.clip.id, plus.target.side)
        }
    }
}

private fun hit(
    c: Current,
    metrics: RowMetrics,
    xDp: Double,
    yDp: Double,
    rowScrollDp: Float,
): Hit {
    // A row's own "+" floats over the row's right edge (cm-plus-position) and wins over what is under it on its 48 dp
    // (also past a slim row into the gap), while the row's middle is in view, as it is drawn. A tap is the "+"; held
    // or dragged it is what lies under it, so an item running under the "+" can still be moved or opened.
    val sx = xDp - metrics.headerDp - PAD_DP
    if (yDp >= metrics.rulerDp && abs(sx - TimelineGeometry.endPlusX(c.spanWidth)) <= TimelineGeometry.PLUS_HIT_DP / 2) {
        val inRows = yDp - metrics.rulerDp + rowScrollDp

        // Where each row's "+" is centred: its middle.
        fun centre(r: Int) = c.bands[r].centreDp

        fun hasPlus(r: Int) =
            rowPlus(c.shown[r], c.slots, c.spans, c.selectedIndex, c.selectedId, c.playheadMs, c.totalMs, c.photoMs) != null
        val near =
            c.bands.indices
                .filter {
                    abs(
                        inRows - centre(it),
                    ) <= TimelineGeometry.PLUS_HIT_DP / 2 && c.bands[it].centreDp >= rowScrollDp && hasPlus(it)
                }.minByOrNull { abs(inRows - centre(it)) }
        if (near != null) {
            val key = c.shown[near].key
            val under =
                when (val h = hitBelow(c, metrics, xDp, yDp, rowScrollDp)) {
                    is Hit.OnClip -> h.takeIf { c.shown[near].type == RowType.MAIN }
                    is Hit.OnTimedClip -> h.takeIf { it.key == key }
                    is Hit.OnRowSpace -> h.takeIf { it.key == key }
                    else -> null
                }
            // Over an item only the drawn circle is the "+" (as for a "+" on a narrow item), so a tap on the visible
            // part of an item at the edge still selects it; over the row's empty space the "+" has its full 48 dp.
            val onItem = under is Hit.OnClip || under is Hit.OnTimedClip
            val offCircle =
                abs(sx - TimelineGeometry.endPlusX(c.spanWidth)) > TimelineGeometry.PLUS_CORE_DP ||
                    abs(inRows - centre(near)) > TimelineGeometry.PLUS_CORE_DP
            if (onItem && offCircle) return under!!
            return Hit.OnRowEnd(key, under)
        }
    }
    return hitBelow(c, metrics, xDp, yDp, rowScrollDp)
}

/** What a touch hits, the rows' own floating "+" left aside. */
private fun hitBelow(
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
        // Never below MAIN: in the slimmer sideways rows a marker's 48 dp hit area would reach the next row.
        if (gy < c.geometry.mainBottom) {
            when (val t = c.geometry.hitTest(xDp - metrics.headerDp - PAD_DP, gy)) {
                is HitTarget.Plus -> return Hit.OnPlus(t.point)
                is HitTarget.Marker -> return Hit.OnMarker(t.point)
                else -> Unit
            }
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
        if (row.clips.isEmpty()) return Hit.OnRowSpace(row.key)
        return when (val t = TimedRowGeometry.hitTest(row.clips, c.timedPlus[row.key].orEmpty(), c.zoom, sx, c.selectedId)) {
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

            is TimedHit.Handle -> {
                Hit.OnTimedTrim(row.key, t.id, if (t.start) GestureStart.TRIM_START else GestureStart.TRIM_END)
            }

            TimedHit.Space -> {
                Hit.OnRowSpace(row.key)
            }
        }
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
 * with the finger in time; the row it would land on is outlined. The
 * selected item has a trim handle on each side; while one is dragged the
 * item is drawn at the length it will have.
 */
@Composable
private fun TimedRowContent(
    row: TimelineRow,
    zoom: ZoomState,
    selected: ClipId?,
    playheadMs: Long,
    drag: TimedDragPreview?,
    trimState: State<TimedTrimPreview?>,
    actions: TimelineActions,
) {
    // Only the row being trimmed sees the preview change, so a handle drag recomposes that row alone.
    val trim by remember(row.key, trimState) { derivedStateOf { trimState.value?.takeIf { it.key == row.key } } }
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
            val preview = trim?.takeIf { it.id == clip.id }
            val (shownStart, shownEnd) = preview?.span(clip) ?: (clip.startMs to clip.endMs)
            val x = PAD_DP + zoom.xOf(shownStart.toDouble()) + if (dragged) drag.dxDp else 0.0
            val w = (shownEnd - shownStart) / zoom.msPerDp
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
                // On the selected item the handles take its edges: its icon and words sit between them.
                val inset = if (isSelected && clip.resize != null) TimelineGeometry.HANDLE_DP.dp + 4.dp else 6.dp
                Row(
                    Modifier.align(Alignment.CenterStart).padding(horizontal = inset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(rowClipIcon(clip.kind, row.type)),
                        contentDescription = null,
                        tint = on,
                        modifier = Modifier.size(16.dp),
                    )
                    if (words != null && w >= 48 + if (inset > 6.dp) 2 * TimelineGeometry.HANDLE_DP else 0.0) {
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
            if (isSelected && !dragged && clip.resize != null) {
                val left = PAD_DP + zoom.xOf(shownStart.toDouble())
                TrimHandlePair(left, left + w, vertical = 5.dp)
            }
        }
    }
}

/** How every "+" looks: a small tonal circle with a thin outline and a slight shadow, calm next to the items. */
@Composable
private fun PlusCircle() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        // A little lift: the row's own "+" floats over items, and every "+" looks the same.
        shadowElevation = 2.dp,
        modifier = Modifier.size(TimelineGeometry.PLUS_DP.dp),
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.padding(3.dp))
    }
}

/**
 * A row's own "+" for TalkBack and tests (cm-plus-position): 48 dp at the
 * row's right edge, read right after the row's items. It draws nothing: the
 * circle is drawn by [EndPlusCircle] in the "+" layer, over the playhead, and
 * taps come through the timeline's own handler.
 */
@Composable
private fun EndPlusTarget(
    plus: RowPlus?,
    row: TimelineRow,
    slots: List<MainSlot>,
    actions: TimelineActions,
    modifier: Modifier,
) {
    val description = plus?.let { rowPlusDescription(it, row, slots) }?.takeIf { it.isNotEmpty() } ?: return
    Box(
        modifier
            .size(TimelineGeometry.PLUS_HIT_DP.dp)
            .testTag(
                if (plus is RowPlus.Empty ||
                    (row.type != RowType.MAIN && row.clips.isEmpty())
                ) {
                    rowAddTag(row.key)
                } else {
                    rowEndPlusTag(row.key)
                },
            ).semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    // Where the text said (while playing: the time play started from).
                    act(plus, row, slots, actions)
                    true
                }
            },
    )
}

/** What a row's own "+" says to TalkBack: where it adds. */
@Composable
private fun rowPlusDescription(
    plus: RowPlus,
    row: TimelineRow,
    slots: List<MainSlot>,
): String =
    when (plus) {
        is RowPlus.Empty -> {
            if (plus.atMs == null) {
                stringResource(R.string.add_here_title, rowName(row))
            } else {
                stringResource(R.string.timeline_add_at, rowName(row), durationText(plus.atMs * 1000))
            }
        }

        is RowPlus.Frame -> {
            // The preset's frame row keeps only its own frame: a new one goes on another frame row, and it says so.
            val at = durationText(plus.atMs * 1000)
            if (row.fixed || !row.enabled) {
                stringResource(
                    R.string.timeline_add_frame_other_row,
                    at,
                )
            } else {
                stringResource(R.string.timeline_add_frame_from, at)
            }
        }

        is RowPlus.At -> {
            val kind = stringResource(rowLook(row.type).second)
            if (plus.full) {
                stringResource(R.string.timeline_add_other_row, kind, durationText(plus.atMs * 1000))
            } else {
                stringResource(R.string.timeline_add_at, rowName(row), durationText(plus.atMs * 1000))
            }
        }

        is RowPlus.After -> {
            stringResource(R.string.timeline_add_after, timedItemLabel(plus.clip, row))
        }

        is RowPlus.OnMain -> {
            if (plus.target.anchorIndex !in slots.indices) return ""
            val name = mainItemLabel(slots, plus.target.anchorIndex)
            stringResource(if (plus.target.side == InsertSide.BEFORE) R.string.timeline_add_before else R.string.timeline_add_after, name)
        }
    }

/** A row's own "+" as drawn (24 dp, as every "+"), floating at the row's right edge, centred on [centreY] in the layer's box. */
@Composable
private fun EndPlusCircle(
    spanWidth: Double,
    centreY: Double,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(
                    x = (PAD_DP + TimelineGeometry.endPlusX(spanWidth) - TimelineGeometry.PLUS_HIT_DP / 2).dp,
                    y = (centreY - TimelineGeometry.PLUS_HIT_DP / 2).dp,
                ).size(TimelineGeometry.PLUS_HIT_DP.dp)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            PlusCircle()
        }
    }
}

/** An item on a row other than MAIN as a "+" names it: its kind, and a caption's words. */
@Composable
private fun timedItemLabel(
    clip: RowClip,
    row: TimelineRow,
): String {
    val kind = stringResource(rowClipName(clip.kind, row.type))
    val words = clip.text?.takeIf { it.isNotBlank() }?.take(TEXT_SHOWN)
    return if (words != null) stringResource(R.string.timeline_named_item, kind, words) else kind
}

/** A MAIN item as a "+" names it: "Video 2", its place among the photos, videos and cards, so every "+" reads differently. */
@Composable
private fun mainItemLabel(
    slots: List<MainSlot>,
    index: Int,
): String {
    val anchor = slots[index]
    val kind = stringResource(itemName(anchor.clip))
    val position = slots.take(index + 1).count { it.clip.isContent() }
    return if (anchor.clip.isContent()) stringResource(R.string.timeline_item_numbered, kind, position) else kind
}

/** A "+" on a row other than MAIN (24 dp drawn, 48 dp to touch), centred on its item's edge. */
@Composable
private fun TimedPlusButton(
    p: TimedPlus,
    row: TimelineRow,
    actions: TimelineActions,
) {
    val clip = row.clips.firstOrNull { it.id == p.anchor } ?: return
    val name = timedItemLabel(clip, row)
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
    TrimHandlePair(PAD_DP + geometry.left(index), PAD_DP + geometry.right(index), vertical = 4.dp)
}

/**
 * The two trim handles of the selected item, drawn on its inner edges from
 * [leftDp] to [rightDp] (in the row): 24 dp each, the item's height less
 * [vertical] at top and bottom. Drag targets only (the timeline's handler
 * hits them, 48 dp wide); TalkBack trims from the bar's panels or the item's
 * menu instead.
 */
@Composable
private fun TrimHandlePair(
    leftDp: Double,
    rightDp: Double,
    vertical: Dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    for ((edge, x) in listOf(TrimEdge.START to leftDp, TrimEdge.END to rightDp - TimelineGeometry.HANDLE_DP)) {
        val outer = 8.dp
        val shape =
            if (edge == TrimEdge.START) {
                RoundedCornerShape(topStart = outer, bottomStart = outer)
            } else {
                RoundedCornerShape(topEnd = outer, bottomEnd = outer)
            }
        Box(
            Modifier
                .offset(x = x.dp)
                .width(TimelineGeometry.HANDLE_DP.dp)
                .fillMaxHeight()
                .padding(vertical = vertical)
                .clip(shape)
                .background(primary)
                .testTag(if (edge == TrimEdge.START) TRIM_START_TAG else TRIM_END_TAG)
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
        val name = mainItemLabel(slots, p.anchorIndex)
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
    hiddenNow: () -> List<TimelineRow>,
    allRowsRequested: Boolean,
    show: Boolean,
    metrics: RowMetrics,
    actions: TimelineActions,
) {
    if (!show) return
    val hidden = hiddenNow()
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
