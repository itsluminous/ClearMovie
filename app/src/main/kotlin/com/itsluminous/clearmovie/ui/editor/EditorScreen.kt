package com.itsluminous.clearmovie.ui.editor

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.canJoin
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.duplicateClip
import com.itsluminous.clearmovie.domain.model.joinClips
import com.itsluminous.clearmovie.domain.model.moveClip
import com.itsluminous.clearmovie.domain.model.moveMainClip
import com.itsluminous.clearmovie.domain.model.moveRow
import com.itsluminous.clearmovie.domain.model.removeRow
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.domain.model.resizeClip
import com.itsluminous.clearmovie.domain.model.rotate
import com.itsluminous.clearmovie.domain.model.setPhotoDuration
import com.itsluminous.clearmovie.domain.model.trimVideo
import com.itsluminous.clearmovie.render.plan.frameWindow
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.ShapeOutline
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.ClipActions
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.LayoutEvent
import com.itsluminous.clearmovie.ui.editor.timeline.LayoutInput
import com.itsluminous.clearmovie.ui.editor.timeline.PopupCorner
import com.itsluminous.clearmovie.ui.editor.timeline.PreviewLayout
import com.itsluminous.clearmovie.ui.editor.timeline.PreviewLayoutReducer
import com.itsluminous.clearmovie.ui.editor.timeline.RowMetrics
import com.itsluminous.clearmovie.ui.editor.timeline.RowSpec
import com.itsluminous.clearmovie.ui.editor.timeline.RowType
import com.itsluminous.clearmovie.ui.editor.timeline.SelectionKind
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbSource
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineActions
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineView
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineZoom
import com.itsluminous.clearmovie.ui.editor.timeline.TrimEdge
import com.itsluminous.clearmovie.ui.editor.timeline.TrimMath
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import com.itsluminous.clearmovie.ui.editor.timeline.itemName
import com.itsluminous.clearmovie.ui.editor.timeline.model
import com.itsluminous.clearmovie.ui.editor.timeline.rowName
import com.itsluminous.clearmovie.ui.layout.AdaptiveScreen
import com.itsluminous.clearmovie.ui.layout.HeightClass
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Test tags. */
const val EDITOR_TIMELINE_TAG = "editor_timeline"

/** How the parts of the editor out of use while a voice is recorded are dimmed (Material's disabled level, and lighter for the timeline). */
private const val LOCKED_ALPHA = 0.38f
private const val LOCKED_TIMELINE_ALPHA = 0.6f
const val EDITOR_EXPORT_TAG = "editor_export"
const val EDITOR_PREVIEW_BOX_TAG = "editor_preview_box"
const val EDITOR_POPUP_TAG = "editor_preview_popup"
const val EDITOR_POPUP_CLOSE_TAG = "editor_preview_popup_close"
const val EDITOR_POPUP_MOVE_TAG = "editor_preview_popup_move"
const val ZOOM_IN_TAG = "bar_action_ZOOM_IN"
const val ZOOM_OUT_TAG = "bar_action_ZOOM_OUT"
const val ZOOM_FIT_TAG = "bar_action_FIT_ALL"
const val TRANSPORT_TAG = "editor_transport"
const val PLAY_TAG = "editor_play"
const val TIME_TAG = "editor_time"
const val EDITOR_TOP_BAR_TAG = "editor_top_bar"
const val UNDO_TAG = "editor_undo"
const val REDO_TAG = "editor_redo"
const val EXACT_TAG = "editor_exact"
const val EMPTY_ADD_TAG = "editor_empty_add"
const val EDITOR_MORE_TAG = "editor_more"
const val EDITOR_MENU_SHAPE_TAG = "editor_menu_shape"
const val EDITOR_MENU_RENAME_TAG = "editor_menu_rename"
const val EDITOR_MENU_SETTINGS_TAG = "editor_menu_settings"

/** The playhead and play state the controls show. */
data class PlayheadUi(
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val playing: Boolean = false,
)

/** Everything the editor screen asks of its owner. */
interface EditorActions : EditorCallbacks {
    fun back()

    fun export()

    fun togglePlay()

    /** Stops the preview where it is (nothing happens when it is not playing). */
    fun pause() = Unit

    fun exact()

    fun fixPreset()

    /** "Video shape" from More options (cm-aspect-ratio). */
    fun openShape() = Unit

    /** "Settings" from More options: the app's settings screen; Back comes back here. */
    fun openSettings() = Unit
}

/**
 * The editor (requirement 4, ARCHITECTURE sections 11.1 to 11.7): the top
 * bar with Back, the title (a tap renames the video), Undo, Redo, Save video
 * and More options;
 * the preview with Play, the time and the zoom buttons; the timeline; and the
 * contextual bar, or the panel of the action being used. On a phone held
 * upright the preview is on top and shrinks as the rows need room
 * ([PreviewLayout]); when all rows are asked for and they don't fit, it
 * floats over them in a corner. In two panes the preview fills the left pane.
 * Stateless apart from [state], which is saved with the screen.
 */
@Composable
fun EditorScreen(
    ui: EditorUi,
    title: String,
    playhead: PlayheadUi,
    preview: @Composable (Modifier) -> Unit,
    thumbnail: ThumbSource,
    actions: EditorActions,
    state: EditorScreenState = rememberEditorScreenState(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    recording: (@Composable (Modifier) -> Unit)? = null,
) {
    val project = ui.project
    // While a voice is recorded (cm-record-voice) its panel takes the bar's place, and the rest stays in sight but takes no touches.
    val lock = if (recording != null) Modifier.lockedForRecording(LOCKED_ALPHA) else Modifier
    // The timeline less so: the playhead running along the rows stays easy to follow.
    val lockTimeline = if (recording != null) Modifier.lockedForRecording(LOCKED_TIMELINE_ALPHA) else Modifier
    val slots = ui.slots
    // The preview's shape follows the video's (cm-aspect-ratio).
    val canvasAspect = (project?.shape ?: CanvasShape.LANDSCAPE).aspect.toFloat()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // A selection whose item or row went away (an undo, a delete) is dropped.
    val selectedSlot = slots.firstOrNull { it.clip.id == state.selected }
    val selectedClip = state.selected?.let { project?.clip(it) }
    val selectedTrack = state.selectedRow?.let { project?.track(it) }
    LaunchedEffect(selectedClip == null, state.selected) { if (selectedClip == null && state.selected != null) state.selected = null }
    LaunchedEffect(selectedTrack == null, state.selectedRow) {
        if (selectedTrack == null && state.selectedRow != null) state.selectedRow = null
    }
    // The selected item's row, or the selected row: kept on screen first after MAIN when rows fold away.
    val focusRow = selectedClip?.let { project?.trackOf(it.id)?.id } ?: selectedTrack?.id
    val playheadMs = playhead.positionUs / 1000
    val fontScale = LocalDensity.current.fontScale
    // One scroll for the rows in either layout, so turning the phone keeps it (cm-timeline-scroll).
    val rowScroll = rememberScrollState()
    // Worked out once per project, not per playhead tick (it holds every item's handle limits).
    val rows = remember(project) { project?.let { timelineRows(it) }.orEmpty() }
    val missing = ui.missing.filterValues { it != MediaStatus.AVAILABLE }.keys

    // A delete's "Undo" belongs to that delete only: any later change to the video closes it.
    // [undoArmed] is set by a delete; the next project seen is the one the delete made, and any
    // change after that closes the snackbar.
    var undoArmed by remember { mutableStateOf(false) }
    var deletedAt by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(project) {
        if (undoArmed) {
            undoArmed = false
            deletedAt = project
        } else if (deletedAt != null && project != deletedAt) {
            snackbar.currentSnackbarData?.dismiss()
            deletedAt = null
        }
    }

    fun deleted(clip: Clip) {
        // One delete's Undo at a time: an earlier one still showing goes, so it can't undo this one.
        snackbar.currentSnackbarData?.dismiss()
        deletedAt = null
        undoArmed = true
        scope.launch {
            val message =
                if (clip is IntroClip || clip is EndingClip) {
                    resources.getString(R.string.editor_part_skipped, resources.getString(itemName(clip)))
                } else {
                    resources.getString(R.string.editor_deleted)
                }
            // Long, not the indefinite default for a snackbar with an action: it must not sit over the bar.
            val result =
                snackbar.showSnackbar(
                    message,
                    actionLabel = resources.getString(R.string.action_undo),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            // Undo here undoes the delete: any later change closes the snackbar first (above).
            if (result == SnackbarResult.ActionPerformed && deletedAt != null) actions.undo()
        }
    }

    // The rows the timeline shows now (measured below): Move row up and down step among these.
    var shownRows by remember { mutableStateOf(emptyList<String>()) }

    // The caption editor (cm-captions): the caption it is open on, its box on the preview, and,
    // however it closes, the end of its visit (a caption left without words goes).
    val editingCaption =
        state.panel
            ?.takeIf { it.panel == Panel.TEXT }
            ?.clipId
            ?.let { project?.clip(it) as? TextClip }
    val captionWindow = project?.let { frameWindow(it, ui.preset) } ?: NormRect.FULL
    val openCaptionId = state.panel?.takeIf { it.panel == Panel.TEXT }?.clipId
    val latestProject by rememberUpdatedState(project)
    var captionVisit by remember { mutableStateOf(openCaptionId) }
    val latestPlayheadMs by rememberUpdatedState(playheadMs)
    LaunchedEffect(openCaptionId) {
        val was = captionVisit
        captionVisit = openCaptionId
        if (was != null && was != openCaptionId) finishCaption(was, latestProject, actions)
        // A visit starting: the preview stops on the caption, so every change shows there at once.
        val caption = openCaptionId?.let { latestProject?.clip(it) }
        if (caption != null && was != openCaptionId) {
            // The caption as the visit found it, saved with the screen, for Cancel (see CaptionPanel).
            state.startCaptionVisit(caption as TextClip)
            actions.pause()
            if (latestPlayheadMs !in caption.startMs until caption.endMs) actions.seek(caption.startMs + caption.durationMs / 2)
        }
    }

    // Back from the top bar leaves the editor with the caption editor still open: its visit ends first,
    // or a new caption left without words would stay behind.
    val topActions: EditorActions =
        remember(actions, state) {
            object : EditorActions by actions {
                override fun back() {
                    val open = state.panel?.takeIf { it.panel == Panel.TEXT }?.clipId
                    if (open != null) {
                        finishCaption(open, latestProject, actions)
                        state.panel = null
                    }
                    actions.back()
                }
            }
        }
    // Rename video, from a tap on the title or More options; kept open across a rotation. Not while a
    // caption is being written: a stray touch on the title must not end that visit or split its undo step.
    var renaming by rememberSaveable { mutableStateOf(false) }
    val canRename = project != null && editingCaption == null
    val openRename = { if (canRename) renaming = true }
    if (renaming && project != null) {
        RenameDialog(
            current = project.name ?: title,
            onRename = { name, edited ->
                renaming = false
                // One undo step, like any other edit. The same name changes nothing, and neither does
                // Rename on an unnamed video's stand-in title ("New video", the preset's name) left untouched:
                // that title is not a name, and it follows the app's language while the video has none.
                val current = latestProject
                val unchanged = name == current?.name || (current?.name == null && !edited && name == title)
                if (current != null && !unchanged) actions.edit { rename(name) }
            },
            onDismiss = { renaming = false },
        )
    }
    // A caption selected (its editor closed) and showing at the playhead, while paused: its box can be moved
    // and resized on the preview too (cm-caption-box), each drag one undo step.
    val selectedCaption =
        (selectedClip as? TextClip)?.takeIf {
            editingCaption == null &&
                !playhead.playing &&
                playheadMs in it.startMs until it.endMs &&
                project?.trackOf(it.id)?.enabled != false
        }
    val previewWithBox: @Composable (Modifier) -> Unit = { m ->
        Box(m.clipToBounds()) {
            preview(Modifier.fillMaxSize())
            if (project != null) {
                if (editingCaption != null) {
                    CaptionBoxOverlay(editingCaption, project, captionWindow, actions)
                } else if (selectedCaption != null) {
                    CaptionBoxOverlay(selectedCaption, project, captionWindow, actions, editing = false)
                }
            }
        }
    }
    // In the small floating preview the box only shows: dragging there moves the window, as everywhere else.
    val floatingPreview: @Composable (Modifier) -> Unit = { m ->
        Box(m.clipToBounds()) {
            preview(Modifier.fillMaxSize())
            if (project != null) {
                val shown = editingCaption ?: selectedCaption
                if (shown != null) CaptionBoxOverlay(shown, project, captionWindow, actions, draggable = false)
            }
        }
    }

    /** A stacking move, with the one-time note when it puts a row behind the photos and videos (section 6.2). */
    fun restack(
        trackId: TrackId,
        move: StackMove,
    ) {
        val before = project ?: return
        var behind = emptyList<TrackId>()
        if (actions.edit { stack(trackId, move).also { behind = newlyBehindMain(before, it) } } &&
            behind.isNotEmpty() &&
            !state.behindNoteShown
        ) {
            state.behindNoteShown = true
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.stack_behind_note), duration = SnackbarDuration.Long) }
        }
    }

    fun askRemoveRow(trackId: TrackId) {
        val track = project?.track(trackId) ?: return
        if (track.clips.isEmpty()) {
            actions.edit { removeRow(trackId) }
            state.panel = null
        } else {
            state.panel = OpenPanel(Panel.REMOVE_ROW, trackId = trackId)
        }
    }

    // Read when a "+" is tapped: the actions object below outlives recompositions.
    val mainKey = rememberUpdatedState(project?.main?.id?.value)
    val timelineActions =
        remember(actions, state) {
            object : TimelineActions {
                override fun select(id: ClipId?) {
                    state.selected = id
                    state.selectedRow = null
                    if (state.panel?.clipId != id || state.panel?.trackId != null) state.panel = null
                }

                override fun selectRow(key: String) {
                    state.selected = null
                    state.selectedRow = TrackId(key)
                    state.panel = null
                }

                override fun addHere(key: String) {
                    // MAIN's one "+" (nothing on it yet) opens the gallery, as Add photos does.
                    if (key == mainKey.value) return actions.pickToAdd()
                    state.selected = null
                    state.selectedRow = TrackId(key)
                    state.panel = OpenPanel(Panel.INSERT, trackId = TrackId(key))
                }

                override fun rowMenu(key: String) {
                    state.selected = null
                    state.selectedRow = TrackId(key)
                    state.panel = OpenPanel(Panel.ROW_MENU, trackId = TrackId(key))
                }

                override fun moveRow(
                    key: String,
                    toIndex: Int,
                ) {
                    // The row just moved is the one being worked on: selected, so it stays shown when rows fold away.
                    if (actions.edit { moveRow(TrackId(key), toIndex) }) {
                        state.selected = null
                        state.selectedRow = TrackId(key)
                        // A panel about an item goes with that item's selection.
                        if (state.panel?.clipId != null) state.panel = null
                    }
                }

                override fun moveTimed(
                    id: ClipId,
                    startMs: Long,
                    toRow: String,
                ) {
                    actions.edit { moveClip(id, startMs, TrackId(toRow)) }
                }

                override fun resize(
                    id: ClipId,
                    edge: TrimEdge,
                    toMs: Long,
                ) {
                    actions.edit { resizeClip(id, edge.model(), toMs) }
                }

                override fun seek(ms: Long) = actions.seek(ms)

                override fun trim(
                    id: ClipId,
                    edge: TrimEdge,
                    deltaMs: Double,
                ) {
                    actions.edit {
                        val slot = mainLayoutClip(this, id) ?: return@edit this
                        replaceTrimmed(this, slot, TrimMath.dragged(slot, edge, deltaMs))
                    }
                }

                override fun move(
                    id: ClipId,
                    toContentIndex: Int,
                ) {
                    actions.edit { moveMainClip(id, toContentIndex) }
                }

                override fun insert(
                    anchor: ClipId,
                    side: InsertSide,
                ) {
                    state.panel = OpenPanel(Panel.INSERT, anchor, side)
                }

                override fun menu(id: ClipId) {
                    state.selected = id
                    state.selectedRow = null
                    state.panel = OpenPanel(Panel.ITEM_MENU, id)
                }

                override fun transition(lead: ClipId) {
                    state.selected = lead
                    state.panel = OpenPanel(Panel.TRANSITION, lead)
                }

                override fun showAllRows() = state.layoutEvent(LayoutEvent.ShowAllRows)

                override fun showFewerRows() = state.layoutEvent(LayoutEvent.ShowFewerRows)
            }
        }

    fun onBar(action: EditAction) {
        val slot = selectedSlot
        val id = slot?.clip?.id
        val row = selectedTrack
        when {
            action == EditAction.ADD_MEDIA -> {
                actions.pickToAdd()
            }

            action == EditAction.FIT_ALL -> {
                fitAll(state, slots)
            }

            action == EditAction.ZOOM_IN || action == EditAction.ZOOM_OUT -> {
                zoomButton(state, slots, playheadMs, zoomIn = action == EditAction.ZOOM_IN)
            }

            action == EditAction.ADD_ROW -> {
                state.panel = OpenPanel(Panel.ADD_ROW, trackId = project?.main?.id)
            }

            row != null && project != null -> {
                onRowAction(action, row, state, actions, ::restack, ::askRemoveRow, shownRows)
            }

            selectedClip != null && slot == null && project != null -> {
                val clip = selectedClip
                if (action == EditAction.DELETE) {
                    if (actions.edit { deleteClip(clip.id) }) deleted(clip)
                } else {
                    onTimedAction(action, clip, project, state, actions)
                }
            }

            else -> {
                if (id == null || project == null) return
                when (action) {
                    EditAction.TRIM -> {
                        state.panel = OpenPanel(Panel.TRIM, id)
                    }

                    EditAction.DURATION -> {
                        state.panel = OpenPanel(Panel.DURATION, id)
                    }

                    EditAction.SPLIT -> {
                        if ((slot.clip as? VideoClip)?.loops?.let { it > 1 } == true) {
                            // Said rather than greyed out: the user learns what to change.
                            scope.launch { snackbar.showSnackbar(resources.getString(R.string.split_loop_first)) }
                        } else {
                            state.panel = OpenPanel(Panel.SPLIT, id)
                        }
                    }

                    EditAction.SPEED -> {
                        state.panel = OpenPanel(Panel.SPEED, id)
                    }

                    EditAction.LOOP -> {
                        state.panel = OpenPanel(Panel.LOOP, id)
                    }

                    EditAction.VOLUME -> {
                        state.panel = OpenPanel(Panel.VOLUME, id)
                    }

                    EditAction.FIT -> {
                        state.panel = OpenPanel(Panel.FIT, id)
                    }

                    EditAction.EDIT_TEXT -> {
                        state.panel = OpenPanel(Panel.CARD_TEXT, id)
                    }

                    EditAction.ADD_CAPTION -> {
                        startCaption(id, project, actions, state)
                    }

                    EditAction.COLOUR -> {
                        state.panel = OpenPanel(Panel.CARD_COLOUR, id)
                    }

                    EditAction.TRANSITION -> {
                        state.panel = OpenPanel(Panel.TRANSITION, id)
                    }

                    EditAction.ANIMATION -> {
                        state.panel = OpenPanel(Panel.ANIMATION, id)
                    }

                    EditAction.ROTATE -> {
                        actions.edit { rotate(id) }
                    }

                    EditAction.REPLACE -> {
                        actions.pickForReplace(id)
                    }

                    EditAction.DUPLICATE -> {
                        actions.edit { duplicateClip(id) }
                    }

                    EditAction.MOVE_LEFT -> {
                        contentIndex(slots, slot).let { i -> if (i > 0) actions.edit { moveMainClip(id, i - 1) } }
                    }

                    EditAction.MOVE_RIGHT -> {
                        contentIndex(slots, slot).let { i -> actions.edit { moveMainClip(id, i + 1) } }
                    }

                    EditAction.JOIN -> {
                        joinTarget(project, slots, slot)?.let { (a, b) -> actions.edit { joinClips(a, b) } }
                    }

                    EditAction.PLAY -> {
                        actions.playPart(id)
                    }

                    EditAction.DELETE, EditAction.SKIP -> {
                        if (actions.edit { deleteClip(id) }) deleted(slot.clip)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    // The bar's (or panel's) measured height: snackbars sit above it, never over its buttons.
    var bottomDp by remember { mutableFloatStateOf(0f) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomDp.dp)) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        AdaptiveScreen(Modifier.fillMaxSize().padding(padding)) { window ->
            val windowHeight = maxHeight.value.toDouble()
            val windowWidth = maxWidth.value.toDouble()
            // A phone held sideways (under 480 dp tall): slimmer bars and rows, so at least four rows show.
            val compact = window.height == HeightClass.COMPACT
            val metrics = RowMetrics(fontScale, compact)
            val topBarHeight = if (compact) TOP_BAR_HEIGHT_COMPACT else TOP_BAR_HEIGHT
            var topDp by remember { mutableFloatStateOf(0f) }
            var controlsDp by remember { mutableFloatStateOf(0f) }
            val density = LocalDensity.current
            // In two panes the playback controls sit under the preview, so only the right pane's bars count.
            val chrome =
                if (window.twoPane) {
                    (topDp + bottomDp).toDouble() + insetsDp()
                } else {
                    (topDp + controlsDp + bottomDp).toDouble() +
                        insetsDp()
                }
            val input =
                LayoutInput(
                    twoPane = window.twoPane,
                    windowWidthDp = windowWidth,
                    windowHeightDp = windowHeight,
                    chromeHeightDp = chrome,
                    rulerDp = metrics.rulerDp,
                    rows = rows.map { RowSpec(it.key, metrics.heightOf(it.type), it.type == RowType.MAIN) },
                    toggleDp = metrics.toggleDp,
                    selectedRow = focusRow?.value,
                    canvasAspect = canvasAspect.toDouble(),
                    rowGapDp = metrics.gapDp,
                )
            LaunchedEffect(input) { state.layoutEvent(LayoutEvent.Measured(input)) }
            // Worked out now from this frame's measurements (the reducer is pure), so a frame never shows the
            // layout of the rows as they were before (the effect above only stores it for the next event).
            val layout = PreviewLayoutReducer.reduce(state.layout, LayoutEvent.Measured(input)).layout
            val visible = layout?.visibleRows ?: rows.map { it.key }
            SideEffect { if (shownRows != visible) shownRows = visible }
            val hidden = rows.filter { it.key in layout?.hiddenRows.orEmpty() }

            val timeline: @Composable (Modifier) -> Unit = { m ->
                Column(m.testTag(EDITOR_TIMELINE_TAG).then(lockTimeline)) {
                    Banners(ui, actions::fixPreset)
                    if (!ui.hasContent) EmptyHint(actions::pickToAdd)
                    TimelineView(
                        slots = slots,
                        rows = rows,
                        visibleRows = visible,
                        hiddenRows = hidden,
                        // "Show fewer rows" only while the preview floats; two panes have no Show all rows.
                        allRowsRequested = layout is PreviewLayout.Floating,
                        selected = selectedClip?.id,
                        selectedRow = selectedTrack?.id?.value,
                        missing = missing,
                        playheadMs = playheadMs,
                        playing = playhead.playing,
                        zoom = state.zoom,
                        onZoom = { state.zoom = it },
                        onSpanWidth = { state.spanWidth = it },
                        thumbnail = thumbnail,
                        actions = timelineActions,
                        rowScroll = rowScroll,
                        metrics = metrics,
                    )
                }
            }
            val bottom: @Composable (Modifier) -> Unit = { m ->
                Box(m.onSizeChanged { bottomDp = with(density) { it.height.toDp().value } }) {
                    CompositionLocalProvider(LocalShownRows provides visible) {
                        val open = state.panel
                        if (recording != null) {
                            recording(Modifier)
                        } else if (open != null && project != null) {
                            EditPanel(
                                open,
                                project,
                                playheadMs,
                                thumbnail,
                                actions,
                                onPanel = { state.panel = it },
                                onDeleted = { clip -> deleted(clip) },
                                onSelect = { selected ->
                                    state.selectedRow = null
                                    state.selected = selected
                                },
                                rows = rows,
                                onStack = ::restack,
                                onRemoveRow = ::askRemoveRow,
                                onSelectRow = { id ->
                                    state.selected = null
                                    state.selectedRow = id
                                },
                                // A panel leaves room for the rows (the one it is about first): it scrolls instead.
                                // The caption editor may take more: its preview stays in sight above or beside it.
                                maxHeight =
                                    when {
                                        open.panel == Panel.TEXT && window.twoPane -> {
                                            (windowHeight * CAPTION_PANEL_SHARE_TWO_PANE).dp.coerceIn(160.dp, 560.dp)
                                        }

                                        open.panel == Panel.TEXT -> {
                                            (windowHeight * CAPTION_PANEL_SHARE).dp.coerceIn(200.dp, 480.dp)
                                        }

                                        window.twoPane -> {
                                            (windowHeight * PANEL_SHARE_TWO_PANE).dp.coerceIn(160.dp, 420.dp)
                                        }

                                        else -> {
                                            (windowHeight * PANEL_SHARE).dp.coerceIn(200.dp, 420.dp)
                                        }
                                    },
                                captionWindow = captionWindow,
                                captionVisit = state,
                            )
                        } else {
                            val timedClip = selectedClip?.takeIf { selectedSlot == null }
                            ContextualBar(
                                actions =
                                    when {
                                        project != null && selectedTrack != null -> rowBar(project, selectedTrack, shownRows)
                                        project != null && timedClip != null -> timedBar(project, timedClip)
                                        else -> barActions(project, slots, selectedSlot, missing, state.zoom)
                                    },
                                onAction = ::onBar,
                                description =
                                    when {
                                        selectedTrack != null -> {
                                            val shownRow = rows.firstOrNull { it.key == selectedTrack.id.value }
                                            stringResource(R.string.bar_description, shownRow?.let { rowName(it) }.orEmpty())
                                        }

                                        selectedSlot != null -> {
                                            stringResource(R.string.bar_description, stringResource(itemName(selectedSlot.clip)))
                                        }

                                        timedClip != null -> {
                                            stringResource(R.string.bar_description, stringResource(timedItemName(project, timedClip)))
                                        }

                                        else -> {
                                            stringResource(R.string.bar_description_nothing)
                                        }
                                    },
                            )
                        }
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { m ->
                Controls(
                    playhead = playhead,
                    enabled = ui.schedule?.main?.isNotEmpty() == true,
                    actions = actions,
                    modifier = m.onSizeChanged { controlsDp = with(density) { it.height.toDp().value } }.then(lock),
                )
            }

            if (window.twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .background(LocalMediaColors.current.previewBackground)
                            .windowInsetsPadding(WindowInsets.safeDrawing.pane(left = true)),
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth().testTag(EDITOR_PREVIEW_BOX_TAG), contentAlignment = Alignment.Center) {
                            previewWithBox(Modifier.aspectRatio(canvasAspect))
                        }
                        Surface(Modifier.fillMaxWidth()) { controls(Modifier) }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.pane(left = false)),
                    ) {
                        TopBar(
                            title,
                            ui,
                            topActions,
                            openRename,
                            canRename,
                            topBarHeight,
                            Modifier.onSizeChanged { topDp = with(density) { it.height.toDp().value } }.then(lock),
                        )
                        timeline(Modifier.weight(1f).fillMaxWidth())
                        bottom(Modifier)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            title,
                            ui,
                            topActions,
                            openRename,
                            canRename,
                            topBarHeight,
                            Modifier.onSizeChanged { topDp = with(density) { it.height.toDp().value } }.then(lock),
                        )
                        val previewDp =
                            when (layout) {
                                is PreviewLayout.Full -> layout.previewDp
                                is PreviewLayout.Shrunk -> layout.previewDp
                                is PreviewLayout.Partial -> layout.previewDp
                                is PreviewLayout.Floating -> 0.0
                                else -> PreviewLayoutReducer.fullPreviewDp(windowWidth, canvasAspect.toDouble())
                            }
                        if (layout !is PreviewLayout.Floating) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(previewDp.dp)
                                    .background(LocalMediaColors.current.previewBackground)
                                    .testTag(EDITOR_PREVIEW_BOX_TAG),
                                contentAlignment = Alignment.Center,
                            ) {
                                previewWithBox(Modifier.fillMaxHeight().aspectRatio(canvasAspect, matchHeightConstraintsFirst = true))
                            }
                        }
                        controls(Modifier)
                        timeline(Modifier.weight(1f).fillMaxWidth())
                        bottom(Modifier)
                    }
                    if (layout is PreviewLayout.Floating) {
                        FloatingPreview(
                            corner = layout.corner,
                            // Below the top bar and the playback controls, which stay usable.
                            topDp = topDp + controlsDp,
                            bottomDp = bottomDp,
                            preview = floatingPreview,
                            onMoved = { state.layoutEvent(LayoutEvent.PopupMoved(it)) },
                            onClose = { state.layoutEvent(LayoutEvent.ShowFewerRows) },
                            aspect = canvasAspect,
                        )
                    }
                }
            }
        }
    }
}

/** The vertical insets the stacked layout pads by (status and navigation bars), in dp. */
@Composable
private fun insetsDp(): Double {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    return with(density) { (insets.getTop(this) + insets.getBottom(this)).toDp().value.toDouble() }
}

private fun zoomButton(
    state: EditorScreenState,
    slots: List<MainSlot>,
    playheadMs: Long,
    zoomIn: Boolean,
) {
    val z = state.zoom ?: return
    val total = slots.lastOrNull()?.endMs ?: 0L
    val width = state.spanWidth ?: return
    state.zoom = TimelineZoom.zoomButton(z, zoomIn, playheadMs.toDouble(), total, width)
}

/** Fit all: the whole video across the timeline. */
private fun fitAll(
    state: EditorScreenState,
    slots: List<MainSlot>,
) {
    val width = state.spanWidth ?: return
    state.zoom = TimelineZoom.fitAll(slots.lastOrNull()?.endMs ?: 0L, width)
}

/** Insets for one of the two panes: the left pane takes the start edge, the right pane the end edge; both take the top and bottom. */
private fun WindowInsets.pane(left: Boolean): WindowInsets =
    only(if (left) WindowInsetsSides.Start + WindowInsetsSides.Vertical else WindowInsetsSides.End + WindowInsetsSides.Vertical)

private fun selectionKind(clip: MainClip?): SelectionKind =
    when (clip) {
        null -> SelectionKind.NOTHING
        is PhotoClip -> SelectionKind.PHOTO
        is VideoClip -> SelectionKind.VIDEO
        is CardClip -> if (clip.text.isNullOrBlank()) SelectionKind.COLOUR_CARD else SelectionKind.TEXT_CARD
        is IntroClip -> SelectionKind.INTRO
        is EndingClip -> SelectionKind.ENDING
    }

private fun contentIndex(
    slots: List<MainSlot>,
    slot: MainSlot,
): Int = slots.take(slot.index).count { it.clip.isContentClip() }

private fun MainClip.isContentClip() = this is PhotoClip || this is VideoClip || this is CardClip

/** The split partner next to [slot] that Join joins it with: the next one first. */
private fun joinTarget(
    project: Project,
    slots: List<MainSlot>,
    slot: MainSlot,
): Pair<ClipId, ClipId>? {
    val next = slots.getOrNull(slot.index + 1)?.clip?.id
    val prev = slots.getOrNull(slot.index - 1)?.clip?.id
    return when {
        next != null && project.canJoin(slot.clip.id, next) -> slot.clip.id to next
        prev != null && project.canJoin(prev, slot.clip.id) -> prev to slot.clip.id
        else -> null
    }
}

private fun barActions(
    project: Project?,
    slots: List<MainSlot>,
    selected: MainSlot?,
    missing: Set<ClipId>,
    zoom: ZoomState?,
): List<BarAction> {
    val clip = selected?.clip
    val kind = selectionKind(clip)
    if (selected == null || project == null) {
        val actions =
            ClipActions.forSelection(
                kind,
                canZoomIn = zoom?.let { it.msPerDp > TimelineZoom.closeUpMsPerDp * 1.0001 } ?: true,
                canZoomOut = zoom?.fitAll != true,
            )
        // A video with no photos or videos yet shows one big "Add photos and videos" over the timeline:
        // the bar leaves out its own, so there is one control for it, not two.
        val empty = slots.none { it.clip.isContentClip() }
        return if (empty) actions.filter { it.action != EditAction.ADD_MEDIA } else actions
    }
    val index = contentIndex(slots, selected)
    val count = slots.count { it.clip.isContentClip() }
    return ClipActions.forSelection(
        kind = kind,
        missing = selected.clip.id in missing,
        canMoveLeft = index > 0,
        canMoveRight = index < count - 1,
        canJoin = joinTarget(project, slots, selected) != null,
        hasNext = selected.index < slots.lastIndex,
    )
}

private fun mainLayoutClip(
    project: Project,
    id: ClipId,
): MainClip? = project.mainClips.firstOrNull { it.id == id }

/** The project with MAIN item [old] at its trimmed form, through the model's own operations. */
private fun replaceTrimmed(
    project: Project,
    old: MainClip,
    trimmed: MainClip,
): Project =
    when (trimmed) {
        is VideoClip -> project.trimVideo(old.id, trimmed.trimStartMs, trimmed.trimEndMs)
        is PhotoClip -> project.setPhotoDuration(old.id, trimmed.durationMs)
        is CardClip -> project.setPhotoDuration(old.id, trimmed.durationMs)
        else -> project
    }

/**
 * The editor's top bar (kupraki's request): Back, the title (a tap renames
 * the video), Undo above Redo, Save video (the download arrow, which opens
 * the export sheet) and More options. Material's small top app bar, 64 dp
 * tall whatever the title ([barHeight]: 56 dp on a phone held sideways,
 * cm-timeline-scroll); the window insets are already applied around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    ui: EditorUi,
    actions: EditorActions,
    onRename: () -> Unit,
    canRename: Boolean,
    barHeight: Dp,
    modifier: Modifier,
) {
    // One height in every language and at every text size: the title fits itself to the room it has
    // (EditorTitle) rather than making the bar taller.
    TopAppBar(
        title = {
            EditorTitle(
                title,
                barHeight,
                onClick = onRename,
                clickLabel = stringResource(R.string.editor_menu_rename),
                enabled = canRename,
            )
        },
        navigationIcon = {
            IconButton(onClick = actions::back, modifier = Modifier.size(TOUCH)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(ICON),
                )
            }
        },
        actions = {
            UndoRedo(ui, actions, barHeight / 2)
            IconButton(onClick = actions::export, modifier = Modifier.size(TOUCH).testTag(EDITOR_EXPORT_TAG)) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = stringResource(R.string.editor_export),
                    modifier = Modifier.size(ICON),
                )
            }
            MoreOptions(ui, actions, onRename, canRename)
        },
        expandedHeight = barHeight,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().testTag(EDITOR_TOP_BAR_TAG),
    )
}

/** The editor's top bar is Material's small top app bar height, whatever the title. */
private val TOP_BAR_HEIGHT = 64.dp

/** Sideways on a phone the top bar is as tall as the bottom bar and the Play bar beside it. */
private val TOP_BAR_HEIGHT_COMPACT = 56.dp

/** The floating preview's width, as a share of the window's, for a landscape video. */
private const val POPUP_WIDTH_SHARE = 0.45f

/** The floating preview is never narrower than its Move and Close buttons. */
private val POPUP_MIN_WIDTH = 112.dp

/**
 * More options: what the editor needs now and then, out of the way of the
 * everyday controls. Rename video (also a tap on the title), Video shape
 * (cm-aspect-ratio) and Settings, each with its icon.
 */
@Composable
private fun MoreOptions(
    ui: EditorUi,
    actions: EditorActions,
    onRename: () -> Unit,
    canRename: Boolean,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val itemStyle = MaterialTheme.typography.bodyLarge
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        IconButton(
            onClick = { open = true },
            enabled = ui.project != null,
            modifier = Modifier.size(TOUCH).testTag(EDITOR_MORE_TAG),
        ) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.editor_more),
                modifier = Modifier.size(ICON),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_menu_rename), style = itemStyle) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(ICON)) },
                onClick = {
                    open = false
                    onRename()
                },
                enabled = canRename,
                modifier = Modifier.heightIn(min = TOUCH).testTag(EDITOR_MENU_RENAME_TAG),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_menu_shape), style = itemStyle) },
                leadingIcon = { ShapeOutline(ui.project?.shape ?: CanvasShape.LANDSCAPE, iconTint) },
                onClick = {
                    open = false
                    actions.openShape()
                },
                modifier = Modifier.heightIn(min = TOUCH).testTag(EDITOR_MENU_SHAPE_TAG),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_settings), style = itemStyle) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(ICON)) },
                onClick = {
                    open = false
                    actions.openSettings()
                },
                modifier = Modifier.heightIn(min = TOUCH).testTag(EDITOR_MENU_SETTINGS_TAG),
            )
        }
    }
}

/**
 * Undo above Redo, one icon each, stacked with no gap so the title has their
 * width (kupraki's call). Two 48 dp buttons cannot stack in the 64 dp bar, so
 * each is 48 dp wide and half the bar tall ([height]: 32 dp, 28 dp sideways):
 * the one place in the editor a touch target is under 48 dp tall. Each is
 * named for TalkBack.
 */
@Composable
private fun UndoRedo(
    ui: EditorUi,
    actions: EditorActions,
    height: Dp,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(Modifier.width(TOUCH)) {
            StackedIcon(R.drawable.ic_undo, R.string.action_undo, ui.canUndo, UNDO_TAG, height, actions::undo)
            StackedIcon(R.drawable.ic_redo, R.string.action_redo, ui.canRedo, REDO_TAG, height, actions::redo)
        }
    }
}

@Composable
private fun StackedIcon(
    icon: Int,
    label: Int,
    enabled: Boolean,
    tag: String,
    height: Dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(TOUCH, height).testTag(tag)) {
        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(ICON))
    }
}

/**
 * The playback strip under the preview: Play, the time, and "As exported"
 * at the far end, spread across one row that never wraps. At very large text
 * (more than 1.5x) As exported keeps only its icon, still named for TalkBack;
 * Play keeps its word whenever the row fits with it. Zooming is in the bar below (with Fit all) and by pinching.
 */
@Composable
private fun Controls(
    playhead: PlayheadUi,
    enabled: Boolean,
    actions: EditorActions,
    modifier: Modifier,
) {
    val position = durationText(playhead.positionUs)
    val total = durationText(playhead.durationUs)
    val timeLabel = stringResource(R.string.editor_time, position, total)
    val seekLabel = stringResource(R.string.editor_seek)
    val duration = playhead.durationUs.coerceAtLeast(1)
    val playLabel = stringResource(if (playhead.playing) R.string.action_pause else R.string.action_play)
    val playIcon = if (playhead.playing) R.drawable.ic_pause else R.drawable.ic_play
    val exactLabel = stringResource(R.string.editor_exact)
    val exactShort = stringResource(R.string.editor_exact_short)
    val labels = MaterialTheme.typography.labelLarge
    val timeStyle = MaterialTheme.typography.titleSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Play keeps one width whether it says Play or Pause, so the time never shifts when it is pressed.
    val playWords = listOf(stringResource(R.string.action_play), stringResource(R.string.action_pause))
    val playTextDp = with(density) { playWords.maxOf { measurer.measure(it, labels).size.width }.toDp() }
    val exactTextDp =
        with(density) {
            measurer
                .measure(exactShort, labels)
                .size.width
                .toDp()
        }
    // The time is measured at its widest ("total of total"), so the choice does not flip as it plays.
    val timeDp =
        with(density) {
            measurer
                .measure(stringResource(R.string.editor_time, total, total), timeStyle)
                .size.width
                .toDp()
        }
    val playMinWidth = 12.dp + ICON + 8.dp + playTextDp + 16.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Words on Play and As exported only when the whole row fits with them (any language, any text size).
        // The strip's own padding (8 + 8), Play, the time with its padding (8 + 8), As exported.
        val needed = 16.dp + playMinWidth + 16.dp + timeDp + (12.dp + ICON + 8.dp + exactTextDp + 12.dp)
        // As exported gives up its word first; Play keeps its own while the row still fits (the main action).
        val exactIconOnly = density.fontScale > ICON_ONLY_FONT_SCALE || needed > maxWidth
        // Play keeps its word whenever the row still fits with it, at any text size: it is the one he looks for.
        val playIconOnly = 16.dp + playMinWidth + 16.dp + timeDp + TOUCH > maxWidth
        TransportRow(
            playIconOnly,
            exactIconOnly,
            enabled,
            actions,
            playLabel,
            playIcon,
            playMinWidth,
            timeLabel,
            seekLabel,
            playhead,
            duration,
            exactLabel,
            exactShort,
        )
    }
}

@Composable
private fun TransportRow(
    playIconOnly: Boolean,
    exactIconOnly: Boolean,
    enabled: Boolean,
    actions: EditorActions,
    playLabel: String,
    playIcon: Int,
    playMinWidth: Dp,
    timeLabel: String,
    seekLabel: String,
    playhead: PlayheadUi,
    duration: Long,
    exactLabel: String,
    exactShort: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp)
            .testTag(TRANSPORT_TAG),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playIconOnly) {
            FilledTonalIconButton(onClick = actions::togglePlay, enabled = enabled, modifier = Modifier.size(TOUCH).testTag(PLAY_TAG)) {
                Icon(painterResource(playIcon), contentDescription = playLabel, modifier = Modifier.size(ICON))
            }
        } else {
            FilledTonalButton(
                onClick = actions::togglePlay,
                enabled = enabled,
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                modifier = Modifier.height(TOUCH).widthIn(min = playMinWidth).testTag(PLAY_TAG),
            ) {
                Icon(painterResource(playIcon), contentDescription = null, modifier = Modifier.size(ICON))
                Spacer(Modifier.width(8.dp))
                AutoSizeLabel(playLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
        // The time, which TalkBack can also move (the timeline's ruler is the gesture for it).
        Text(
            timeLabel,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier.padding(horizontal = 8.dp).testTag(TIME_TAG).semantics {
                    contentDescription = seekLabel
                    stateDescription = timeLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(playhead.positionUs.toFloat() / duration, 0f..1f)
                    setProgress { f ->
                        actions.seek((f.coerceIn(0f, 1f) * duration / 1000).toLong())
                        true
                    }
                },
        )
        if (exactIconOnly) {
            IconButton(onClick = actions::exact, enabled = enabled, modifier = Modifier.size(TOUCH).testTag(EXACT_TAG)) {
                Icon(painterResource(R.drawable.ic_visibility), contentDescription = exactLabel, modifier = Modifier.size(ICON))
            }
        } else {
            TextButton(
                onClick = actions::exact,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(TOUCH).testTag(EXACT_TAG).semantics { contentDescription = exactLabel },
            ) {
                Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, modifier = Modifier.size(ICON))
                Spacer(Modifier.width(8.dp))
                AutoSizeLabel(exactShort, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Icons are one size everywhere in the editor; touch targets are 48 dp. */
private val ICON = 24.dp
private val TOUCH = 48.dp

/** Above this text size the strip's buttons drop their words (keeping them for TalkBack) so it stays one row. */
private const val ICON_ONLY_FONT_SCALE = 1.5f

/** The most of an upright window an open panel takes. */
private const val PANEL_SHARE = 0.3

/** The same in two panes, where the panel shares the right pane with the rows only. */
private const val PANEL_SHARE_TWO_PANE = 0.38

/** The caption editor's share of an upright window, and of the right pane in two panes. */
private const val CAPTION_PANEL_SHARE = 0.42
private const val CAPTION_PANEL_SHARE_TWO_PANE = 0.72

@Composable
private fun Banners(
    ui: EditorUi,
    onFixPreset: () -> Unit,
) {
    if (!ui.presetNeedsRepair) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.preset_repair_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.editor_preset_repair_body), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onFixPreset, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.editor_fix_preset)) }
        }
    }
}

@Composable
private fun EmptyHint(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.editor_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp).testTag(EMPTY_ADD_TAG)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editor_add_media))
        }
    }
}

/**
 * The preview as a small window over the rows (ARCHITECTURE section 11.4,
 * Floating): about 45% of the width, dragged anywhere and snapped to the
 * nearest corner on release, with a close button (back to fewer rows). A
 * Move button, and TalkBack actions, move it without dragging.
 */
@Composable
private fun FloatingPreview(
    corner: PopupCorner,
    topDp: Float,
    bottomDp: Float,
    preview: @Composable (Modifier) -> Unit,
    onMoved: (PopupCorner) -> Unit,
    onClose: () -> Unit,
    aspect: Float = 16f / 9f,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // A landscape picture takes 45% of the width; an upright or square one is narrower, so it
        // covers about as much of the rows, and never narrower than its two buttons.
        val w = (maxWidth * POPUP_WIDTH_SHARE * minOf(1f, sqrt(aspect / (16f / 9f)))).coerceAtLeast(POPUP_MIN_WIDTH)
        val h = w / aspect + 48.dp
        val margin = 12.dp

        fun place(c: PopupCorner): IntOffset =
            with(density) {
                val start = c == PopupCorner.TOP_START || c == PopupCorner.BOTTOM_START
                val top = c == PopupCorner.TOP_START || c == PopupCorner.TOP_END
                val x = if (start) margin else maxWidth - w - margin
                val y = if (top) topDp.dp + margin else maxHeight - h - bottomDp.dp - margin
                IntOffset(x.roundToPx(), y.roundToPx())
            }
        var drag by remember { mutableStateOf<IntOffset?>(null) }
        val target = drag ?: place(corner)
        val shown by animateIntOffsetAsState(target)
        val moveLabel = stringResource(R.string.preview_popup_move)
        val names = PopupCorner.entries.associateWith { stringResource(cornerName(it)) }
        val description = stringResource(R.string.preview_popup)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = LocalMediaColors.current.previewBackground,
            modifier =
                Modifier
                    .offset { if (drag != null) target else shown }
                    .width(w)
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .testTag(EDITOR_POPUP_TAG)
                    .semantics {
                        contentDescription = description
                        customActions =
                            PopupCorner.entries.filter { it != corner }.map { c ->
                                CustomAccessibilityAction(names.getValue(c)) {
                                    onMoved(c)
                                    true
                                }
                            }
                    }.pointerInputDrag(
                        onDrag = { delta ->
                            drag =
                                (drag ?: place(corner)).let { IntOffset(it.x + delta.x.roundToInt(), it.y + delta.y.roundToInt()) }
                        },
                        onEnd = {
                            val at = drag
                            drag = null
                            if (at != null) {
                                with(density) {
                                    val centreX = at.x + (w / 2).toPx()
                                    val centreY = at.y + (h / 2).toPx()
                                    val right = centreX > (maxWidth / 2).toPx()
                                    val bottom = centreY > (maxHeight / 2).toPx()
                                    onMoved(
                                        when {
                                            !right && !bottom -> PopupCorner.TOP_START
                                            right && !bottom -> PopupCorner.TOP_END
                                            !right -> PopupCorner.BOTTOM_START
                                            else -> PopupCorner.BOTTOM_END
                                        },
                                    )
                                }
                            }
                        },
                    ),
        ) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(aspect)) { preview(Modifier.fillMaxSize()) }
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onMoved(nextCorner(corner)) },
                        modifier = Modifier.size(48.dp).testTag(EDITOR_POPUP_MOVE_TAG).semantics { contentDescription = moveLabel },
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_picture_in_picture),
                            contentDescription = null,
                            tint = LocalMediaColors.current.onPreviewBackground,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag(EDITOR_POPUP_CLOSE_TAG)) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.preview_popup_close),
                            tint = LocalMediaColors.current.onPreviewBackground,
                        )
                    }
                }
            }
        }
    }
}

private fun nextCorner(c: PopupCorner): PopupCorner =
    when (c) {
        PopupCorner.TOP_START -> PopupCorner.TOP_END
        PopupCorner.TOP_END -> PopupCorner.BOTTOM_END
        PopupCorner.BOTTOM_END -> PopupCorner.BOTTOM_START
        PopupCorner.BOTTOM_START -> PopupCorner.TOP_START
    }

private fun cornerName(c: PopupCorner): Int =
    when (c) {
        PopupCorner.TOP_START -> R.string.corner_top_start
        PopupCorner.TOP_END -> R.string.corner_top_end
        PopupCorner.BOTTOM_START -> R.string.corner_bottom_start
        PopupCorner.BOTTOM_END -> R.string.corner_bottom_end
    }

private fun Modifier.pointerInputDrag(
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onEnd: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
            change.consume()
            onDrag(amount)
        }
    }
