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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
const val TIMELINE_MAIN_HEADER_TAG = "timeline_header_main"

fun clipTag(id: ClipId) = "timeline_clip_${id.value}"

/** The ruler's label spacing (ms), for tests: it changes with the zoom. Never read aloud. */
val RulerTickKey = SemanticsPropertyKey<Long>("RulerTick")
var SemanticsPropertyReceiver.rulerTick by RulerTickKey

fun plusTag(boundary: Int) = "timeline_plus_$boundary"

fun thumbTag(id: ClipId) = "timeline_thumbs_${id.value}"

fun rowTag(key: String) = "timeline_row_$key"

fun headerTag(type: RowType) = "timeline_header_${type.name}"

const val TRIM_START_TAG = "timeline_trim_start"
const val TRIM_END_TAG = "timeline_trim_end"

/** The kinds of row, for the header icon and name. */
enum class RowType { MAIN, TEXT, FRAME, PIP, MUSIC, VOICE, SOUND }

/** A row other than MAIN, drawn read-only until cm-tracks makes it editable. */
@Immutable
data class TimelineRow(
    val key: String,
    val type: RowType,
    val clips: List<Pair<Long, Long>> = emptyList(),
    val enabled: Boolean = true,
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

    fun showAllRows()

    fun showFewerRows()
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
 * playhead, and the rows in screen order, each with its type icon and name at
 * its left edge. The MAIN row shows each item with thumbnails and its length,
 * trim handles on the selected item and the "+" insert points; the other rows
 * are drawn read-only. One pointer handler runs [TimelineGestureArbiter]
 * over all of it, so a pinch, a scroll, a trim, a scrub, a long-press drag
 * and a tap never fight; every gesture also has a button (the contextual bar,
 * the zoom buttons, the item's accessibility actions).
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
        val spans = remember(slots) { slots.map { SlotSpan(it.clip.id.value, it.startMs, it.endMs, it.clip.isContent()) } }
        val selectedClip = selectedIndex?.let { slots[it].clip }
        var trim by remember { mutableStateOf<TrimPreview?>(null) }
        var drag by remember { mutableStateOf<DragPreview?>(null) }
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
        val mainTopInRows =
            shown.takeWhile { it.type != RowType.MAIN }.sumOf { metrics.heightOf(it.type) + PreviewLayoutReducer.ROW_GAP_DP }
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        // The handler outlives recompositions: it always calls the latest callbacks.
        val latestOnZoom by rememberUpdatedState(onZoom)
        val latestActionsState = rememberUpdatedState(actions)
        val latestActions =
            remember {
                object : TimelineActions {
                    override fun select(id: ClipId?) = latestActionsState.value.select(id)

                    override fun seek(ms: Long) = latestActionsState.value.seek(ms)

                    override fun trim(
                        id: ClipId,
                        edge: TrimEdge,
                        deltaMs: Double,
                    ) = latestActionsState.value.trim(id, edge, deltaMs)

                    override fun move(
                        id: ClipId,
                        toContentIndex: Int,
                    ) = latestActionsState.value.move(id, toContentIndex)

                    override fun insert(
                        anchor: ClipId,
                        side: InsertSide,
                    ) = latestActionsState.value.insert(anchor, side)

                    override fun menu(id: ClipId) = latestActionsState.value.menu(id)

                    override fun showAllRows() = latestActionsState.value.showAllRows()

                    override fun showFewerRows() = latestActionsState.value.showFewerRows()
                }
            }
        val current by rememberUpdatedState(Current(z, geometry, spans, slots, spanWidth, totalMs, mainTopInRows, selectedIndex))

        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .pointerInput(metrics, rowScroll) {
                        timelineGestures(
                            density = { density.density },
                            current = { current },
                            metrics = metrics,
                            rowScroll = rowScroll,
                            onZoom = { latestOnZoom(it) },
                            actions = latestActions,
                            longPressed = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            trimChanged = { trim = it },
                            dragChanged = { drag = it },
                            touched = { touching = it },
                        )
                    },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Ruler(z, totalMs, playheadMs, playing, metrics, spanWidth, latestActions)
                    Column(Modifier.fillMaxWidth().verticalScroll(rowScroll, enabled = false)) {
                        for (row in shown) {
                            Row(Modifier.fillMaxWidth().height(metrics.heightOf(row.type).dp).testTag(rowTag(row.key))) {
                                RowHeader(row.type, metrics)
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
                                        OtherRowContent(row, z)
                                    }
                                }
                            }
                            Spacer(Modifier.height(PreviewLayoutReducer.ROW_GAP_DP.dp))
                        }
                    }
                }
                Playhead(z, playheadMs, metrics)
                // The "+" points over everything, the playhead line included, on the MAIN row.
                if (drag == null && shown.any { it.type == RowType.MAIN }) {
                    val top = metrics.rulerDp + mainTopInRows
                    Box(
                        Modifier
                            // The scroll is read when placing, not composing: it changes every frame of a scroll.
                            .offset {
                                IntOffset(
                                    (metrics.headerDp * density.density).roundToInt(),
                                    ((top * density.density) - rowScroll.value).roundToInt(),
                                )
                            }.width((fullWidth.value - metrics.headerDp.toFloat()).dp)
                            .height(metrics.mainDp.dp)
                            .clipToBounds(),
                    ) {
                        val plus = geometry.plusPoints()
                        for (b in 0..shownSlots.size) {
                            PlusButton(plus.firstOrNull { it.boundary == b }, b, shownSlots, actions)
                        }
                    }
                }
            }
            RowsToggle(hiddenRows, allRowsRequested, rows.size > visibleRows.size || allRowsRequested, metrics, actions)
        }
    }
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

private data class Current(
    val zoom: ZoomState,
    val geometry: TimelineGeometry,
    val spans: List<SlotSpan>,
    val slots: List<MainSlot>,
    val spanWidth: Double,
    val totalMs: Long,
    val mainTopInRows: Double,
    val selectedIndex: Int?,
)

/** The pointer handler: feeds [TimelineGestureArbiter] and acts on what it decides. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.timelineGestures(
    density: () -> Float,
    current: () -> Current,
    metrics: RowMetrics,
    rowScroll: ScrollState,
    onZoom: (ZoomState) -> Unit,
    actions: TimelineActions,
    longPressed: () -> Unit,
    trimChanged: (TrimPreview?) -> Unit,
    dragChanged: (DragPreview?) -> Unit,
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
                // Only an item can be long-pressed (the row header's menu is cm-tracks'); nothing else waits.
                var waiting = start is Hit.OnClip && c0.slots.getOrNull(start.index) != null
                var lastSpread: Double? = null
                var lastCentroid: Offset? = null
                var lastScroll = first.position
                var trimDelta = 0.0
                var dragDx = 0.0
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
                                arbiter.up(ch.id.value, ch.uptimeMillis)
                            }

                            ch.positionChanged() -> {
                                arbiter.move(ch.id.value, dp(ch.position.x), dp(ch.position.y), ch.uptimeMillis)
                            }
                        }
                    }
                    if (before == GestureMode.PENDING && arbiter.mode == GestureMode.LONG_PRESSED && start is Hit.OnClip) longPressed()
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
                            trimChanged(null)
                            dragChanged(null)
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
                                trimChanged(TrimPreview(sel, edge, trimDelta))
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
                            val i = (start as? Hit.OnClip)?.index
                            if (ch != null && i != null && c.slots[i].clip.isContent()) {
                                dragDx = dp(ch.position.x - first.position.x)
                                val centre = (c.geometry.left(i) + c.geometry.right(i)) / 2 + dragDx
                                val drop = TimelineGeometry.dropIndex(c.spans, i, centre, c.zoom)
                                dragChanged(DragPreview(i, dragDx, drop))
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        }

                        GestureMode.TAP -> {
                            when (start) {
                                is Hit.OnPlus -> {
                                    val s = c.slots.getOrNull(start.point.anchorIndex)
                                    if (s != null) actions.insert(s.clip.id, start.point.side)
                                }

                                is Hit.OnClip -> {
                                    c.slots.getOrNull(start.index)?.let { actions.select(it.clip.id) }
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
                            (start as? Hit.OnClip)?.let { s -> c.slots.getOrNull(s.index)?.let { actions.menu(it.clip.id) } }
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
                                    trimChanged(null)
                                }

                                GestureMode.DRAG -> {
                                    val i = (start as? Hit.OnClip)?.index
                                    if (i != null && c.slots[i].clip.isContent()) {
                                        val centre = (c.geometry.left(i) + c.geometry.right(i)) / 2 + dragDx
                                        actions.move(c.slots[i].clip.id, TimelineGeometry.dropIndex(c.spans, i, centre, c.zoom))
                                    }
                                    dragChanged(null)
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
                            trimChanged(null)
                            dragChanged(null)
                        }

                        else -> {
                            if (arbiter.mode != GestureMode.PENDING) event.changes.forEach(PointerInputChange::consume)
                        }
                    }
                    if (event.changes.none { it.pressed } && arbiter.isActive) {
                        arbiter.cancel()
                        trimChanged(null)
                        dragChanged(null)
                    }
                }
            } finally {
                // Also when the handler is cancelled or a gesture throws: never left "touching".
                touched(false)
            }
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

    data class OnClip(
        val index: Int,
    ) : Hit {
        override val kind get() = GestureStart.CLIP
    }

    data class OnTrim(
        override val kind: GestureStart,
    ) : Hit

    data object OnRuler : Hit {
        override val kind get() = GestureStart.RULER
    }

    data object OnHeader : Hit {
        override val kind get() = GestureStart.HEADER
    }

    data object Elsewhere : Hit {
        override val kind get() = GestureStart.EMPTY
    }
}

private fun hit(
    c: Current,
    metrics: RowMetrics,
    xDp: Double,
    yDp: Double,
    rowScrollDp: Float,
): Hit {
    if (yDp < metrics.rulerDp) return if (xDp < metrics.headerDp) Hit.Elsewhere else Hit.OnRuler
    if (xDp < metrics.headerDp) return Hit.OnHeader
    val inRows = yDp - metrics.rulerDp + rowScrollDp
    val mainY = inRows - c.mainTopInRows
    if (mainY < 0 || mainY > metrics.mainDp) return Hit.Elsewhere
    val sx = xDp - metrics.headerDp - PAD_DP
    return when (val t = c.geometry.hitTest(sx, metrics.rulerDp + mainY)) {
        is HitTarget.Plus -> Hit.OnPlus(t.point)
        is HitTarget.TrimStart -> Hit.OnTrim(GestureStart.TRIM_START)
        is HitTarget.TrimEnd -> Hit.OnTrim(GestureStart.TRIM_END)
        is HitTarget.Clip -> Hit.OnClip(t.index)
        HitTarget.Ruler -> Hit.OnRuler
        HitTarget.Empty -> Hit.Elsewhere
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
                val firstTick = (zoom.scrollMs / tick).toLong() * tick
                var t = firstTick
                val end = zoom.timeAt(spanWidth + PAD_DP)
                while (t <= minOf(end, totalMs.toDouble() + tick)) {
                    val x = pad + (zoom.xOf(t.toDouble()) * density).toFloat()
                    drawLine(color, Offset(x, size.height * 0.55f), Offset(x, size.height), 1.dp.toPx())
                    t += tick
                }
            }
            // Labels at each tick, as text so they scale with the font.
            val firstTick = (zoom.scrollMs / tick).toLong() * tick
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
    type: RowType,
    metrics: RowMetrics,
) {
    val (icon, name) = rowLook(type)
    val label = stringResource(name)
    // The icon alone; its row's name is what TalkBack reads. A long-press here is kept for
    // the row menu (cm-tracks), so there is no tooltip.
    Box(
        Modifier
            .width(metrics.headerDp.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag(if (type == RowType.MAIN) TIMELINE_MAIN_HEADER_TAG else headerTag(type))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
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

@Composable
private fun OtherRowContent(
    row: TimelineRow,
    zoom: ZoomState,
) {
    val media = LocalMediaColors.current
    val (fill, on) =
        when (row.type) {
            RowType.TEXT -> media.textClip to media.onTextClip
            RowType.FRAME -> media.frameClip to media.onFrameClip
            RowType.PIP -> media.visualClip to media.onVisualClip
            else -> media.audioClip to media.onAudioClip
        }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        for ((start, end) in row.clips) {
            val x = PAD_DP + zoom.xOf(start.toDouble())
            val w = (end - start) / zoom.msPerDp
            if (x + w < -64 || w <= 0) continue
            Box(
                Modifier
                    .offset(x = x.dp)
                    // An item can be far wider than the screen when zoomed in: never squeezed to the row's width.
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(w.dp)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (row.enabled) fill else fill.copy(alpha = 0.4f)),
            ) {
                Icon(
                    painterResource(rowLook(row.type).first),
                    contentDescription = null,
                    tint = on,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(18.dp),
                )
            }
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

@Composable
private fun PlusButton(
    point: PlusPoint?,
    boundary: Int,
    slots: List<MainSlot>,
    actions: TimelineActions,
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
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset(x = (PAD_DP + p.xDp - TimelineGeometry.PLUS_HIT_DP / 2).dp)
                    .size(TimelineGeometry.PLUS_HIT_DP.dp)
                    .align(Alignment.CenterStart)
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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
                    modifier = Modifier.size(TimelineGeometry.PLUS_DP.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.padding(2.dp))
                }
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
        Text(label, modifier = Modifier.weight(1f, fill = false))
        if (!allRowsRequested) {
            // The kinds of the hidden rows, so it is clear what is behind the control.
            for (row in hidden.distinctBy { it.type }) {
                Spacer(Modifier.width(6.dp))
                Icon(painterResource(rowLook(row.type).first), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
