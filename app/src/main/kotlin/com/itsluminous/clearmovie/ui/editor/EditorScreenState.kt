package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.ProjectCodec
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackId
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

    /** A long-press on a row's icon (or its empty space) without moving: stacking, Add here, Mute, Remove row. */
    ROW_MENU,

    /** "Add row": which kind of row. */
    ADD_ROW,

    /** The words of a new caption (at a "+" or "Add here"), or of the selected one. */
    TEXT,

    /** Trim for a sound. */
    SOUND_TRIM,

    /** Fade in and out for a sound. */
    FADE,

    /** Lower for talk on a music row: on or off, and how low (cm-timeline-handles). */
    TALK_LOWER,

    /** Where a picture-in-picture item sits, and how big. */
    PIP_PLACE,

    /** "Move to another row". */
    MOVE_TO_ROW,

    /** "Remove row" on a row that still has items: asks first. */
    REMOVE_ROW,
}

/**
 * An open panel: what it is about ([clipId], or row [trackId] for the row's
 * own panels) and, for an insert, where the new item goes. An insert with a
 * [trackId] and no [clipId] is "Add here" on that row, at the playhead.
 */
data class OpenPanel(
    val panel: Panel,
    val clipId: ClipId? = null,
    val side: InsertSide? = null,
    val trackId: TrackId? = null,
)

/**
 * The editor's on-screen state (ARCHITECTURE section 11.11): the selected
 * item or row,
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
    selectedRow: TrackId? = null,
    behindNoteShown: Boolean = false,
    captionTab: String = CaptionTab.WORDS.name,
    captionStartText: String? = null,
    captionStartStyle: String? = null,
) {
    /** The selected item, on any row. */
    var selected by mutableStateOf(selected)

    /** The selected row (its icon was tapped); never set together with [selected]. */
    var selectedRow by mutableStateOf(selectedRow)

    /** Whether "This row is now behind your photos and videos" was said (once per editor). */
    var behindNoteShown by mutableStateOf(behindNoteShown)
    var panel by mutableStateOf(panel)
    var zoom by mutableStateOf(zoom)
    var layout by mutableStateOf(PreviewLayoutState(allRowsRequested = allRowsRequested, corner = corner))
        private set

    /** The caption editor's open tab, and the caption as the visit found it (text, style JSON), for Cancel. */
    var captionTab by mutableStateOf(captionTab)
    var captionStartText by mutableStateOf(captionStartText)
    var captionStartStyle by mutableStateOf(captionStartStyle)

    /** A visit to the caption editor of [caption] starts: the Words tab, and a snapshot for Cancel. */
    fun startCaptionVisit(caption: TextClip) {
        captionTab = CaptionTab.WORDS.name
        captionStartText = caption.text
        captionStartStyle = ProjectCodec.json.encodeToString(CaptionStyleOverride.serializer(), caption.style)
    }

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
                        s.selectedRow?.value,
                        s.panel?.trackId?.value,
                        s.behindNoteShown,
                        s.captionTab,
                        s.captionStartText,
                        s.captionStartStyle,
                    )
                },
                restore = { saved ->
                    @Suppress("UNCHECKED_CAST")
                    val v = saved as List<Any?>
                    val panelName = v[1] as String?
                    val panelClip = v[2] as String?
                    val panelTrack = v.getOrNull(10) as String?
                    val panel =
                        if (panelName != null && (panelClip != null || panelTrack != null)) {
                            runCatching {
                                OpenPanel(
                                    Panel.valueOf(panelName),
                                    panelClip?.let(::ClipId),
                                    (v[3] as String?)?.let { InsertSide.valueOf(it) },
                                    panelTrack?.let(::TrackId),
                                )
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
                        selectedRow = (v.getOrNull(9) as String?)?.let { runCatching { TrackId(it) }.getOrNull() },
                        behindNoteShown = v.getOrNull(11) as Boolean? ?: false,
                        captionTab = v.getOrNull(12) as String? ?: CaptionTab.WORDS.name,
                        captionStartText = v.getOrNull(13) as String?,
                        captionStartStyle = v.getOrNull(14) as String?,
                    )
                },
            )
    }
}

@Composable
fun rememberEditorScreenState(): EditorScreenState = rememberSaveable(saver = EditorScreenState.Saver) { EditorScreenState() }
