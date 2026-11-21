package com.itsluminous.clearmovie.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.domain.model.NormRect
import kotlin.math.hypot

/**
 * What part of a rectangle a drag holds: the whole of it ([BODY]), a
 * corner, or an edge. Each says which of the rectangle's sides it moves.
 */
enum class RectGrip(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
) {
    BODY(false, false, false, false),
    TOP_LEFT(true, true, false, false),
    TOP(false, true, false, false),
    TOP_RIGHT(false, true, true, false),
    RIGHT(false, false, true, false),
    BOTTOM_RIGHT(false, false, true, true),
    BOTTOM(false, false, false, true),
    BOTTOM_LEFT(true, false, false, true),
    LEFT(true, false, false, false),
    ;

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

/**
 * The maths of a rectangle the user moves and resizes on a picture
 * (cm-caption-box; the caption's box and the frame's picture area use it).
 * Rectangles are in normalised units (0..1 of the picture each way), so
 * they hold for every shape and size; touches are in pixels of the picture
 * as it shows. Pure, so the rules are unit-tested.
 *
 * The handles shown are the ones there is room for: a corner handle needs
 * the rectangle [CORNER_ROOM] each way on screen, an edge handle between two
 * corners twice that along the edge, so a touch on the box between the
 * handles always moves it. Without corners (a thin caption box) the side
 * handles stay and the top and bottom ones show once the box is
 * [EDGE_ROOM] wide.
 */
object RectDrag {
    /** How far from a handle a touch still holds it: half a 48 dp touch target. */
    val REACH: Dp = 24.dp

    /** The smallest height (and width) the body is touchable over, centred on a thinner box. */
    val BODY_MIN: Dp = 48.dp

    /** The size a rectangle must show at, each way, for its corner handles. */
    val CORNER_ROOM: Dp = 72.dp

    /** The length an edge must show at for a handle in its middle besides the corners (or the side ones). */
    val EDGE_ROOM: Dp = 144.dp

    /**
     * The handles shown on a rectangle [wPx] x [hPx] pixels on screen, given
     * the rooms in pixels. [corners] and [edges] say which kinds the caller
     * wants at all.
     */
    fun handles(
        wPx: Float,
        hPx: Float,
        cornerRoomPx: Float,
        edgeRoomPx: Float,
        corners: Boolean = true,
        edges: Boolean = true,
    ): List<RectGrip> {
        val withCorners = corners && wPx >= cornerRoomPx && hPx >= cornerRoomPx
        return buildList {
            if (withCorners) addAll(listOf(RectGrip.TOP_LEFT, RectGrip.TOP_RIGHT, RectGrip.BOTTOM_RIGHT, RectGrip.BOTTOM_LEFT))
            if (edges) {
                if (wPx >= edgeRoomPx) addAll(listOf(RectGrip.TOP, RectGrip.BOTTOM))
                if (!withCorners || hPx >= edgeRoomPx) addAll(listOf(RectGrip.LEFT, RectGrip.RIGHT))
            }
        }
    }

    /** Where [grip]'s handle sits on [r], in normalised units. */
    fun handlePoint(
        r: NormRect,
        grip: RectGrip,
    ): Pair<Double, Double> {
        val x =
            when {
                grip.left -> r.x
                grip.right -> r.x + r.w
                else -> r.x + r.w / 2
            }
        val y =
            when {
                grip.top -> r.y
                grip.bottom -> r.y + r.h
                else -> r.y + r.h / 2
            }
        return x to y
    }

    /**
     * What a touch at ([xPx], [yPx]) on a picture [widthPx] x [heightPx]
     * holds. The middle of [r] (all but [reachPx], or a quarter, from each
     * side) always moves it, so a thin box is still moved by its middle; then
     * the nearest of [handles] within [reachPx]; then the body when the touch
     * is on [r] (at least [bodyMinPx] each way, centred); else nothing, so the
     * touch is left to whatever is under the picture.
     */
    fun gripAt(
        r: NormRect,
        handles: List<RectGrip>,
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
        reachPx: Float,
        bodyMinPx: Float,
    ): RectGrip? {
        val l = r.x * widthPx
        val t = r.y * heightPx
        val w = r.w * widthPx
        val h = r.h * heightPx
        val insetX = minOf(reachPx.toDouble(), w / 4)
        val insetY = minOf(reachPx.toDouble(), h / 4)
        if (xPx > l + insetX && xPx < l + w - insetX && yPx > t + insetY && yPx < t + h - insetY) return RectGrip.BODY
        handles
            .map { g ->
                val (hx, hy) = handlePoint(r, g)
                g to hypot(hx * widthPx - xPx, hy * heightPx - yPx)
            }.filter { it.second <= reachPx }
            .minByOrNull { it.second }
            ?.let { return it.first }
        val padX = ((bodyMinPx - w) / 2).coerceAtLeast(0.0)
        val padY = ((bodyMinPx - h) / 2).coerceAtLeast(0.0)
        val inside = xPx >= l - padX && xPx <= l + w + padX && yPx >= t - padY && yPx <= t + h + padY
        return RectGrip.BODY.takeIf { inside }
    }

    /**
     * [start] moved whole by ([dx], [dy]), kept inside [bounds] (fitted into
     * it first when larger).
     */
    fun move(
        start: NormRect,
        dx: Double,
        dy: Double,
        bounds: NormRect,
    ): NormRect {
        val s = fit(start, bounds)
        val x = (s.x + dx).clampTo(bounds.x, bounds.x + bounds.w - s.w)
        val y = (s.y + dy).clampTo(bounds.y, bounds.y + bounds.h - s.h)
        return NormRect(x, y, s.w, s.h)
    }

    /**
     * [start] with the sides [grip] holds moved by ([dx], [dy]): kept inside
     * [bounds] and at least [minW] x [minH] (as much of it as [bounds]
     * allows). [RectGrip.BODY] moves it instead. The opposite sides stay.
     */
    fun resize(
        start: NormRect,
        grip: RectGrip,
        dx: Double,
        dy: Double,
        bounds: NormRect,
        minW: Double,
        minH: Double,
    ): NormRect {
        if (grip == RectGrip.BODY) return move(start, dx, dy, bounds)
        val s = fit(start, bounds)
        val mw = minW.coerceIn(0.0, bounds.w)
        val mh = minH.coerceIn(0.0, bounds.h)
        var l = s.x
        var t = s.y
        var r = s.x + s.w
        var b = s.y + s.h
        val right = bounds.x + bounds.w
        val bottom = bounds.y + bounds.h
        if (grip.left) l = (l + dx).clampTo(bounds.x, r - mw)
        if (grip.right) r = (r + dx).clampTo(l + mw, right)
        if (grip.top) t = (t + dy).clampTo(bounds.y, b - mh)
        if (grip.bottom) b = (b + dy).clampTo(t + mh, bottom)
        return NormRect(l, t, (r - l).coerceAtMost(right - l), (b - t).coerceAtMost(bottom - t))
    }

    /** [r] made to fit [bounds]: no larger, and inside it. */
    fun fit(
        r: NormRect,
        bounds: NormRect,
    ): NormRect {
        val w = r.w.coerceIn(0.0, bounds.w)
        val h = r.h.coerceIn(0.0, bounds.h)
        return NormRect(
            r.x.clampTo(bounds.x, bounds.x + bounds.w - w),
            r.y.clampTo(bounds.y, bounds.y + bounds.h - h),
            w,
            h,
        )
    }

    /** Like coerceIn, but a range turned around (no room) gives its low end instead of failing. */
    private fun Double.clampTo(
        lo: Double,
        hi: Double,
    ): Double = if (hi < lo) lo else coerceIn(lo, hi)
}

/**
 * A rectangle over a picture that the user moves by its body and resizes by
 * the handles there is room for (see [RectDrag]): an outline (dashed with
 * [dashed]) and small round handles, each held from [RectDrag.REACH] away,
 * so every target is 48 dp. Put it over the picture, filling it. A touch
 * elsewhere on the picture is not taken.
 *
 * [onDrag] gets the grip and how far the finger has gone since it went
 * down, in normalised units; the caller works out the new rectangle from the
 * one the drag started on, so clamping never drifts. [onDragEnd] follows
 * every drag, finished or cancelled. Without [enabled] only the outline
 * shows. [corners] and [edges] choose the kinds of handle offered.
 */
@Composable
fun ResizableRect(
    rect: NormRect,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    corners: Boolean = true,
    edges: Boolean = true,
    dashed: Boolean = false,
    gestureKey: Any? = null,
    onDragStart: (RectGrip) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (grip: RectGrip, dx: Double, dy: Double) -> Unit,
) {
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val latestRect by rememberUpdatedState(rect)
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cornerRoom = with(density) { RectDrag.CORNER_ROOM.toPx() }
        val edgeRoom = with(density) { RectDrag.EDGE_ROOM.toPx() }
        val shown = RectDrag.handles((rect.w * widthPx).toFloat(), (rect.h * heightPx).toFloat(), cornerRoom, edgeRoom, corners, edges)
        val latestHandles by rememberUpdatedState(shown)
        val latestW by rememberUpdatedState(widthPx)
        val latestH by rememberUpdatedState(heightPx)
        Canvas(Modifier.fillMaxSize()) {
            val l = (rect.x * size.width).toFloat()
            val t = (rect.y * size.height).toFloat()
            val w = (rect.w * size.width).toFloat()
            val h = (rect.h * size.height).toFloat()
            val corner = CornerRadius(4.dp.toPx())
            // A dark line under the coloured one, so the outline shows on light and dark pictures alike.
            drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(l, t), Size(w, h), corner, style = Stroke(4.dp.toPx()))
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null
            drawRoundRect(primary, Offset(l, t), Size(w, h), corner, style = Stroke(2.dp.toPx(), pathEffect = effect))
            if (enabled) {
                val radius = HANDLE_DOT.toPx() / 2
                for (g in shown) {
                    val (hx, hy) = RectDrag.handlePoint(rect, g)
                    val c = Offset((hx * size.width).toFloat(), (hy * size.height).toFloat())
                    drawCircle(Color.Black.copy(alpha = 0.6f), radius + 1.dp.toPx(), c)
                    drawCircle(handleFill, radius, c)
                    drawCircle(primary, radius, c, style = Stroke(2.dp.toPx()))
                }
            }
        }
        if (enabled) {
            // A handle near the screen's edge would start the system's Back swipe instead: each handle's 48 dp is
            // kept out of the system's gestures (Android allows a little of each edge for this).
            for (g in shown) {
                val (hx, hy) = RectDrag.handlePoint(rect, g)
                Box(
                    Modifier
                        .offset(maxWidth * hx.toFloat() - RectDrag.REACH, maxHeight * hy.toFloat() - RectDrag.REACH)
                        .size(RectDrag.REACH * 2)
                        .systemGestureExclusion(),
                )
            }
            val reach = with(density) { RectDrag.REACH.toPx() }
            val bodyMin = with(density) { RectDrag.BODY_MIN.toPx() }
            Box(
                Modifier.fillMaxSize().pointerInput(gestureKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val grip =
                            RectDrag.gripAt(
                                latestRect,
                                latestHandles,
                                down.position.x,
                                down.position.y,
                                latestW,
                                latestH,
                                reach,
                                bodyMin,
                            ) ?: return@awaitEachGesture
                        var total = Offset.Zero
                        val first =
                            awaitTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                total += over
                            } ?: return@awaitEachGesture
                        start(grip)
                        try {
                            move(grip, (total.x / latestW).toDouble(), (total.y / latestH).toDouble())
                            drag(first.id) { change ->
                                total += change.positionChange()
                                change.consume()
                                move(grip, (total.x / latestW).toDouble(), (total.y / latestH).toDouble())
                            }
                        } finally {
                            end()
                        }
                    }
                },
            )
        }
    }
}

/** The handles' drawn size: small, so they cover little of the picture; each is held from 48 dp around it. */
private val HANDLE_DOT = 12.dp

/** A node at [rect] on a picture [width] x [height] (at least [minSide] each way, centred), for semantics and tests. */
@Composable
fun RectAnchor(
    rect: NormRect,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    minSide: Dp = RectDrag.BODY_MIN,
) {
    val w = width * rect.w.toFloat()
    val h = height * rect.h.toFloat()
    val padX = ((minSide - w) / 2).coerceAtLeast(0.dp)
    val padY = ((minSide - h) / 2).coerceAtLeast(0.dp)
    // Placed first, so [modifier]'s semantics cover the box where it is.
    Box(
        Modifier
            .offset(width * rect.x.toFloat() - padX, height * rect.y.toFloat() - padY)
            .size(w + padX * 2, h + padY * 2)
            .then(modifier),
    )
}
