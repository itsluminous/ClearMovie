package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.LayoutEvent
import com.itsluminous.clearmovie.ui.editor.timeline.PopupCorner
import com.itsluminous.clearmovie.ui.editor.timeline.PreviewLayoutReducer
import com.itsluminous.clearmovie.ui.editor.timeline.PreviewLayoutState
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState

/** A panel that replaces the contextual bar while it is open. */
enum class Panel {
    TRIM,
    DURATION,
    SPLIT,
    SPEED,
    LOOP,
    VOLUME,
    FIT,

    /** The "+" chooser: photo or video, text card, colour card. */
    INSERT,

    /** The text of a new text card, or of the selected card. */
    CARD_TEXT,

    /** The colour of a new colour card, or of the selected card. */
    CARD_COLOUR,

    /** A long-press on an item without moving. */
    ITEM_MENU,

    /** The transition from the item into the next one (cm-effects). */
    TRANSITION,

    /** The item's start, end and during animations (cm-effects). */
    ANIMATION,
}

/** An open panel: what it is about ([clipId]) and, for an insert, where the new item goes. */
data class OpenPanel(
    val panel: Panel,
    val clipId: ClipId,
    val side: InsertSide? = null,
)

/**
 * The editor's on-screen state (ARCHITECTURE section 11.11): the selection,
 * the open panel or menu, the zoom and scroll, whether all rows were asked
 * for and the floating preview's corner. All of it is saved with the screen,
 * so a rotation, a recreation or a process death brings it back; the
 * playhead is kept by the view model. A drag in progress is not kept.
 */
@Stable
class EditorScreenState(
    selected: ClipId? = null,
    panel: OpenPanel? = null,
    zoom: ZoomState? = null,
    allRowsRequested: Boolean = false,
    corner: PopupCorner = PopupCorner.BOTTOM_END,
) {
    var selected by mutableStateOf(selected)
    var panel by mutableStateOf(panel)
    var zoom by mutableStateOf(zoom)
    var layout by mutableStateOf(PreviewLayoutState(allRowsRequested = allRowsRequested, corner = corner))
        private set

    /** The width of the timeline's time span (dp), for the zoom buttons; measured, not saved. */
    var spanWidth by mutableStateOf<Double?>(null)

    fun layoutEvent(event: LayoutEvent) {
        layout = PreviewLayoutReducer.reduce(layout, event)
    }

    companion object {
        val Saver: Saver<EditorScreenState, Any> =
            Saver(
                save = { s ->
                    arrayListOf<Any?>(
                        s.selected?.value,
                        s.panel?.panel?.name,
                        s.panel?.clipId?.value,
                        s.panel?.side?.name,
                        s.zoom?.msPerDp,
                        s.zoom?.scrollMs,
                        s.zoom?.fitAll,
                        s.layout.allRowsRequested,
                        s.layout.corner.name,
                    )
                },
                restore = { saved ->
                    @Suppress("UNCHECKED_CAST")
                    val v = saved as List<Any?>
                    val panelName = v[1] as String?
                    val panelClip = v[2] as String?
                    val panel =
                        if (panelName != null && panelClip != null) {
                            runCatching {
                                OpenPanel(Panel.valueOf(panelName), ClipId(panelClip), (v[3] as String?)?.let { InsertSide.valueOf(it) })
                            }.getOrNull()
                        } else {
                            null
                        }
                    val msPerDp = v[4] as Double?
                    EditorScreenState(
                        selected = (v[0] as String?)?.let { runCatching { ClipId(it) }.getOrNull() },
                        panel = panel,
                        zoom = msPerDp?.let { ZoomState(it, v[5] as Double, v[6] as Boolean) },
                        allRowsRequested = v[7] as Boolean,
                        corner = runCatching { PopupCorner.valueOf(v[8] as String) }.getOrDefault(PopupCorner.BOTTOM_END),
                    )
                },
            )
    }
}

@Composable
fun rememberEditorScreenState(): EditorScreenState = rememberSaveable(saver = EditorScreenState.Saver) { EditorScreenState() }
