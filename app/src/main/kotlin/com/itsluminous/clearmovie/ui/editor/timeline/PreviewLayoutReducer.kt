package com.itsluminous.clearmovie.ui.editor.timeline

import kotlin.math.max
import kotlin.math.min

/** A corner the floating preview snaps to. */
enum class PopupCorner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** One timeline row as the layout sees it. */
data class RowSpec(
    val key: String,
    val heightDp: Double,
    val isMain: Boolean = false,
)

/** The measurements the layout is worked out from (ARCHITECTURE section 11.4, inputs). */
data class LayoutInput(
    val twoPane: Boolean,
    val windowWidthDp: Double,
    val windowHeightDp: Double,
    /** Everything but the preview and the rows: bars, playback and zoom controls, the contextual bar. */
    val chromeHeightDp: Double,
    /** The ruler above the rows. */
    val rulerDp: Double,
    /** The rows in screen order. */
    val rows: List<RowSpec>,
    /** The "Show all rows" / "Show fewer rows" control. */
    val toggleDp: Double,
    /** The row holding the selected item, which is kept on screen first after MAIN. */
    val selectedRow: String? = null,
    /** The width the preview gets when stacked. */
    val previewWidthDp: Double = windowWidthDp,
    /** The video's width over its height (cm-aspect-ratio): 16:9, upright 9:16 or square. */
    val canvasAspect: Double = 16.0 / 9.0,
)

/** Where the preview is and which rows show. */
sealed interface PreviewLayout {
    /** The rows shown, in screen order. */
    val visibleRows: List<String>

    /** Rows left out ("Show all rows (N more)"); empty when everything shows. */
    val hiddenRows: List<String>

    /** Two panes: the preview fills the left pane. */
    data class Docked(
        override val visibleRows: List<String>,
        override val hiddenRows: List<String>,
    ) : PreviewLayout

    /** Stacked, with the natural preview (the full width, at the video's shape) and every row. */
    data class Full(
        val previewDp: Double,
        override val visibleRows: List<String>,
    ) : PreviewLayout {
        override val hiddenRows: List<String> get() = emptyList()
    }

    /** Stacked, the preview shrunk to make room for every row. */
    data class Shrunk(
        val previewDp: Double,
        override val visibleRows: List<String>,
    ) : PreviewLayout {
        override val hiddenRows: List<String> get() = emptyList()
    }

    /** Stacked, the preview at its minimum and only the rows that fit. */
    data class Partial(
        val previewDp: Double,
        override val visibleRows: List<String>,
        override val hiddenRows: List<String>,
    ) : PreviewLayout

    /** The user asked for every row and they don't fit: the preview floats over them. */
    data class Floating(
        val corner: PopupCorner,
        override val visibleRows: List<String>,
    ) : PreviewLayout {
        override val hiddenRows: List<String> get() = emptyList()
    }
}

/** The reducer's state: the layout, plus what the user asked for. */
data class PreviewLayoutState(
    val layout: PreviewLayout? = null,
    val allRowsRequested: Boolean = false,
    val corner: PopupCorner = PopupCorner.BOTTOM_END,
    val input: LayoutInput? = null,
)

sealed interface LayoutEvent {
    /** A row was added or removed, the font scale or window size changed, or the screen rotated. */
    data class Measured(
        val input: LayoutInput,
    ) : LayoutEvent

    data object ShowAllRows : LayoutEvent

    /** "Show fewer rows", or the popup's close button. */
    data object ShowFewerRows : LayoutEvent

    /** The popup was dragged and snapped to [corner]. */
    data class PopupMoved(
        val corner: PopupCorner,
    ) : LayoutEvent
}

/**
 * The preview layout state machine (ARCHITECTURE section 11.4), a pure
 * function. With `space = windowHeight - chrome`:
 *
 * | State    | When (stacked)                                    |
 * |----------|---------------------------------------------------|
 * | Full     | `fullPreview + rows <= space`                     |
 * | Shrunk   | `minPreview + rows <= space < fullPreview + rows` |
 * | Partial  | `minPreview + rows > space`, all rows not asked   |
 * | Floating | `minPreview + rows > space`, all rows asked       |
 *
 * Two panes are always Docked. `minPreview = max(180, 0.28 x windowHeight)`,
 * except where the natural preview is already no taller than that (a phone
 * held upright: 231 dp against 249 dp), where it may shrink to 180 dp, so the
 * preview still makes room for rows on the phone that needs it most.
 * When everything fits again with the minimum preview, a Floating layout
 * returns to Shrunk and the request is spent; a move to two panes keeps it.
 */
object PreviewLayoutReducer {
    const val MIN_PREVIEW_DP = 180.0
    const val MIN_PREVIEW_FRACTION = 0.28

    fun minPreviewDp(
        windowHeightDp: Double,
        fullPreviewDp: Double = Double.MAX_VALUE,
    ): Double {
        val planned = max(MIN_PREVIEW_DP, MIN_PREVIEW_FRACTION * windowHeightDp)
        return if (planned < fullPreviewDp) planned else min(fullPreviewDp, MIN_PREVIEW_DP)
    }

    /** The preview's natural height: the whole width at the video's shape ([aspect], width over height). */
    fun fullPreviewDp(
        widthDp: Double,
        aspect: Double = 16.0 / 9.0,
    ): Double = widthDp / aspect

    fun reduce(
        state: PreviewLayoutState,
        event: LayoutEvent,
    ): PreviewLayoutState =
        when (event) {
            is LayoutEvent.Measured -> {
                evaluate(state.copy(input = event.input))
            }

            LayoutEvent.ShowAllRows -> {
                evaluate(state.copy(allRowsRequested = true))
            }

            LayoutEvent.ShowFewerRows -> {
                evaluate(state.copy(allRowsRequested = false))
            }

            is LayoutEvent.PopupMoved -> {
                val next = state.copy(corner = event.corner)
                if (next.layout is PreviewLayout.Floating) {
                    next.copy(
                        layout = PreviewLayout.Floating(event.corner, next.layout.visibleRows),
                    )
                } else {
                    next
                }
            }
        }

    private fun evaluate(state: PreviewLayoutState): PreviewLayoutState {
        val input = state.input ?: return state
        val all = input.rows.map { it.key }
        val rowsDp = rowsHeight(input, input.rows)
        if (input.twoPane) {
            // The right pane: the rows below the ruler, the rest behind "Show all rows" (which then scrolls).
            val space = input.windowHeightDp - input.chromeHeightDp
            val layout =
                if (state.allRowsRequested || rowsDp <= space) {
                    PreviewLayout.Docked(all, emptyList())
                } else {
                    val shown = fitting(input, space - input.toggleDp)
                    PreviewLayout.Docked(shown, all - shown.toSet())
                }
            return state.copy(layout = layout)
        }
        val space = input.windowHeightDp - input.chromeHeightDp
        val full = fullPreviewDp(input.previewWidthDp, input.canvasAspect)
        val min = minPreviewDp(input.windowHeightDp, full)
        return when {
            full + rowsDp <= space -> {
                state.copy(layout = PreviewLayout.Full(full, all))
            }

            min + rowsDp <= space -> {
                // Everything fits with a smaller preview: a request for all rows is satisfied.
                state.copy(layout = PreviewLayout.Shrunk(space - rowsDp, all), allRowsRequested = false)
            }

            state.allRowsRequested -> {
                state.copy(layout = PreviewLayout.Floating(state.corner, all))
            }

            else -> {
                val shown = fitting(input, space - min - input.toggleDp)
                state.copy(layout = PreviewLayout.Partial(min, shown, all - shown.toSet()))
            }
        }
    }

    /** The total height of [rows] under the ruler, with the gaps between them. */
    fun rowsHeight(
        input: LayoutInput,
        rows: List<RowSpec>,
    ): Double = input.rulerDp + rows.sumOf { it.heightDp } + ROW_GAP_DP * rows.size

    /**
     * The rows that fit in [spaceDp], in screen order. MAIN is always shown,
     * then the row of the selected item, then the others top first
     * (ARCHITECTURE section 11.3).
     */
    fun fitting(
        input: LayoutInput,
        spaceDp: Double,
    ): List<String> {
        val byKey = input.rows.associateBy { it.key }
        val priority =
            buildList {
                input.rows.filter { it.isMain }.forEach { add(it.key) }
                input.selectedRow?.takeIf { it in byKey && it !in this }?.let { add(it) }
                input.rows
                    .map { it.key }
                    .filter { it !in this }
                    .forEach { add(it) }
            }
        var used = input.rulerDp
        val chosen = mutableSetOf<String>()
        for (key in priority) {
            val row = byKey.getValue(key)
            val next = used + row.heightDp + ROW_GAP_DP
            if (next > spaceDp && !row.isMain) continue
            used = next
            chosen += key
        }
        return input.rows.map { it.key }.filter { it in chosen }
    }

    const val ROW_GAP_DP = 8.0
}
