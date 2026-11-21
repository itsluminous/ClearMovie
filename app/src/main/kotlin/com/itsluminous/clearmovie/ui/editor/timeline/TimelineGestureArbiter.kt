package com.itsluminous.clearmovie.ui.editor.timeline

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where a gesture started, from [TimelineGeometry.hitTest] (or a row header).
 * [ROW] is a row other than MAIN away from its items: a long-press there opens
 * the row menu, anything else on it scrolls.
 */
enum class GestureStart { CLIP, TRIM_START, TRIM_END, RULER, PLUS, HEADER, ROW, EMPTY }

/** Which way a scroll moves: along the time (the zoom's scroll) or up and down the rows. */
enum class ScrollAxis { TIME, ROWS }

/** What a gesture has turned out to be. */
enum class GestureMode {
    /** Not decided yet: the finger is down and has not moved past the slop. */
    PENDING,
    PINCH,
    SCROLL,
    TRIM,
    SCRUB,

    /** Held 500 ms without moving: lifted, waiting for a drag or a release. */
    LONG_PRESSED,
    DRAG,

    /** Finished as a tap on [TimelineGestureArbiter.start]. */
    TAP,

    /** Finished as a long-press released without moving: opens the menu. */
    MENU,
    CANCELLED,
    ENDED,
}

/**
 * The timeline's pointer state machine (ARCHITECTURE section 11.5), pure so
 * it is tested with synthetic pointer streams. Times are in ms, positions in
 * dp. The rules:
 *
 * 1. A second finger before a long-press fires makes it a **pinch** (a
 *    scroll in progress gives way; a trim or scrub does only within the first
 *    [PINCH_WINDOW_MS]).
 * 2. Movement past the touch slop before [LONG_PRESS_MS] is a **scroll**, or
 *    a **trim** or **scrub** when it started on a handle or the ruler. A
 *    scroll moves one way only ([axis], cm-timeline-scroll): up and down the
 *    rows when they scroll ([rowsScroll]) and the finger went more up or
 *    down than sideways, otherwise along the time. On a handle only a
 *    clearly upright move ([UPRIGHT] times more up than sideways) scrolls the
 *    rows; anything else trims.
 * 3. [LONG_PRESS_MS] without movement on an item, a row header or a row's
 *    empty space is a **long-press**; moving afterwards more than twice the
 *    slop **drags** (an item, or a row by its header; on a row's empty space
 *    it scrolls), releasing opens the **menu**.
 * 4. Releasing before that without moving is a **tap** on what was touched.
 *
 * Handles and the ruler beat the items under them because [start] comes from
 * the geometry's hit test, which ranks them first.
 */
class TimelineGestureArbiter(
    private val touchSlopDp: Double = DEFAULT_SLOP_DP,
    /** Whether the rows are taller than their room, so an upright move scrolls them. */
    private val rowsScroll: Boolean = false,
) {
    var mode: GestureMode = GestureMode.ENDED
        private set
    var start: GestureStart = GestureStart.EMPTY
        private set

    /** The way a [GestureMode.SCROLL] moves, decided once when it starts. */
    var axis: ScrollAxis = ScrollAxis.TIME
        private set

    private var firstId = -1L
    private var downAt = 0L
    private var downX = 0.0
    private var downY = 0.0
    private val pointers = mutableSetOf<Long>()

    val isActive: Boolean get() = mode !in DONE

    /** A finger went down. */
    fun down(
        id: Long,
        x: Double,
        y: Double,
        timeMs: Long,
        target: GestureStart,
    ): GestureMode {
        if (!isActive) {
            pointers.clear()
            pointers += id
            firstId = id
            downAt = timeMs
            downX = x
            downY = y
            start = target
            axis = ScrollAxis.TIME
            mode = GestureMode.PENDING
            return mode
        }
        pointers += id
        if (pointers.size >= 2) {
            val early = timeMs - downAt <= PINCH_WINDOW_MS
            mode =
                when (mode) {
                    GestureMode.PENDING, GestureMode.SCROLL -> GestureMode.PINCH
                    GestureMode.TRIM, GestureMode.SCRUB -> if (early) GestureMode.PINCH else mode
                    else -> mode
                }
        }
        return mode
    }

    /** A finger moved. */
    fun move(
        id: Long,
        x: Double,
        y: Double,
        timeMs: Long,
    ): GestureMode {
        tick(timeMs)
        if (id != firstId) return mode
        val distance = hypot(x - downX, y - downY)
        // Once held, a hand that shakes a little still opens the menu: a drag needs twice the slop.
        val slop = if (mode == GestureMode.LONG_PRESSED) touchSlopDp * HELD_SLOP_FACTOR else touchSlopDp
        if (distance <= slop) return mode
        val dx = abs(x - downX)
        val dy = abs(y - downY)
        val next =
            when (mode) {
                GestureMode.PENDING -> {
                    when (start) {
                        GestureStart.TRIM_START, GestureStart.TRIM_END -> {
                            if (rowsScroll && dy > UPRIGHT * dx) GestureMode.SCROLL else GestureMode.TRIM
                        }

                        GestureStart.RULER -> {
                            GestureMode.SCRUB
                        }

                        else -> {
                            GestureMode.SCROLL
                        }
                    }
                }

                GestureMode.LONG_PRESSED -> {
                    // A row's empty space has nothing to drag: held then moved, it scrolls as it always did.
                    if (start == GestureStart.ROW) GestureMode.SCROLL else GestureMode.DRAG
                }

                else -> {
                    mode
                }
            }
        if (next == GestureMode.SCROLL && mode != GestureMode.SCROLL) {
            axis = if (rowsScroll && dy > dx) ScrollAxis.ROWS else ScrollAxis.TIME
        }
        mode = next
        return mode
    }

    /** Time passed with the finger still down: fires the long-press. */
    fun tick(timeMs: Long): GestureMode {
        if (mode == GestureMode.PENDING && timeMs - downAt >= LONG_PRESS_MS && start in LONG_PRESSABLE) {
            mode = GestureMode.LONG_PRESSED
        }
        return mode
    }

    /** A finger lifted. */
    fun up(
        id: Long,
        timeMs: Long,
    ): GestureMode {
        tick(timeMs)
        pointers -= id
        if (pointers.isNotEmpty() && mode == GestureMode.PINCH) return mode
        if (pointers.isNotEmpty() && id != firstId) return mode
        mode =
            when (mode) {
                GestureMode.PENDING -> GestureMode.TAP
                GestureMode.LONG_PRESSED -> GestureMode.MENU
                else -> GestureMode.ENDED
            }
        pointers.clear()
        return mode
    }

    /** The system took the gesture away (a rotation, a scroll parent): nothing happens. */
    fun cancel(): GestureMode {
        pointers.clear()
        mode = GestureMode.CANCELLED
        return mode
    }

    companion object {
        const val LONG_PRESS_MS = 500L
        const val PINCH_WINDOW_MS = 150L

        /** Android's usual touch slop (8 dp). */
        const val DEFAULT_SLOP_DP = 8.0

        /** After a long-press, how many touch slops the finger must move before it drags. */
        const val HELD_SLOP_FACTOR = 2.0

        /** How many times more up or down than sideways a move on a trim handle must go to scroll the rows. */
        const val UPRIGHT = 2.0

        private val LONG_PRESSABLE = setOf(GestureStart.CLIP, GestureStart.HEADER, GestureStart.ROW)
        private val DONE = setOf(GestureMode.TAP, GestureMode.MENU, GestureMode.CANCELLED, GestureMode.ENDED)
    }
}
