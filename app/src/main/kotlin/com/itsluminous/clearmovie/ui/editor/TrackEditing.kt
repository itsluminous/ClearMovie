package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.Track
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.bringForward
import com.itsluminous.clearmovie.domain.model.bringToTop
import com.itsluminous.clearmovie.domain.model.moveRow
import com.itsluminous.clearmovie.domain.model.nudgeClip
import com.itsluminous.clearmovie.domain.model.nudgedStart
import com.itsluminous.clearmovie.domain.model.rotate
import com.itsluminous.clearmovie.domain.model.sendBackward
import com.itsluminous.clearmovie.domain.model.sendToBottom
import com.itsluminous.clearmovie.domain.model.setLowerWhenTalking
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.setTrackMuted
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.ClipActions
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.RowClip
import com.itsluminous.clearmovie.ui.editor.timeline.RowClipKind
import com.itsluminous.clearmovie.ui.editor.timeline.RowFacts
import com.itsluminous.clearmovie.ui.editor.timeline.RowOrder
import com.itsluminous.clearmovie.ui.editor.timeline.RowType
import com.itsluminous.clearmovie.ui.editor.timeline.SelectionKind
import com.itsluminous.clearmovie.ui.editor.timeline.StackPlace
import com.itsluminous.clearmovie.ui.editor.timeline.TimedFacts
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineRow
import com.itsluminous.clearmovie.ui.editor.timeline.rowLook

// Rows and the items on rows other than MAIN, as the editor screen sees them (ROADMAP cm-tracks).

/** How far Earlier and Later move an item. */
const val NUDGE_MS = 500L

/** The kind of row [track] is, for its icon and name. */
fun rowTypeOf(track: Track): RowType =
    when (track.type) {
        TrackType.MAIN -> {
            RowType.MAIN
        }

        TrackType.TEXT -> {
            RowType.TEXT
        }

        TrackType.OVERLAY -> {
            RowType.FRAME
        }

        TrackType.VISUAL -> {
            RowType.PIP
        }

        TrackType.AUDIO -> {
            when (track.audioRole) {
                AudioRole.MUSIC -> RowType.MUSIC
                AudioRole.VOICE -> RowType.VOICE
                else -> RowType.SOUND
            }
        }
    }

/** The rows the timeline shows, in screen order, each with its items, its number among rows of its kind, and its place in the stack. */
fun timelineRows(project: Project): List<TimelineRow> {
    val types = project.tracks.map(::rowTypeOf)
    return project.tracks.mapIndexed { i, t ->
        val type = types[i]
        val number = if (types.count { it == type } > 1) types.take(i + 1).count { it == type } else null
        val stack = project.stackIndex(t.id).takeIf { it >= 0 }?.let { StackPlace(it, project.stacking.size) }
        TimelineRow(
            key = t.id.value,
            type = type,
            clips = if (t.type == TrackType.MAIN) emptyList() else t.clips.map(::rowClip),
            enabled = t.enabled,
            muted = t.muted,
            number = number,
            stack = stack,
            fixed = t.presetRole != null,
        )
    }
}

private fun rowClip(c: Clip): RowClip =
    RowClip(
        c.id,
        c.startMs,
        c.endMs,
        when (c) {
            is TextClip -> RowClipKind.TEXT
            is VideoClip -> RowClipKind.VIDEO
            is OverlayClip -> RowClipKind.OVERLAY
            is AudioClip -> RowClipKind.SOUND
            else -> RowClipKind.PHOTO
        },
        (c as? TextClip)?.text,
    )

/** What the bar treats a clip on a row other than MAIN as. */
fun timedKind(
    project: Project,
    clip: Clip,
): SelectionKind =
    when (clip) {
        is TextClip -> SelectionKind.TEXT
        is PhotoClip -> SelectionKind.PIP_PHOTO
        is VideoClip -> SelectionKind.PIP_VIDEO
        is AudioClip -> SelectionKind.SOUND
        is OverlayClip -> if (project.trackOf(clip.id)?.presetRole == PresetRole.FRAME) SelectionKind.FRAME else SelectionKind.OVERLAY
        else -> SelectionKind.NOTHING
    }

/** The bar for an item on a row other than MAIN. */
fun timedBar(
    project: Project,
    clip: Clip,
): List<BarAction> {
    val track = requireNotNull(project.trackOf(clip.id))
    val facts =
        TimedFacts(
            canMoveEarlier = project.nudgedStart(clip.id, -NUDGE_MS) != null,
            canMoveLater = project.nudgedStart(clip.id, NUDGE_MS) != null,
            fixed = track.presetRole != null,
            isMusic = track.audioRole == AudioRole.MUSIC,
            lowerWhenTalking = track.lowerWhenTalking,
            frameOn = track.enabled,
        )
    return ClipActions.forTimed(timedKind(project, clip), facts)
}

/** The bar for a selected row. */
fun rowBar(
    project: Project,
    track: Track,
    shown: List<String> = emptyList(),
): List<BarAction> {
    val visible = shown.filter { k -> project.tracks.any { it.id.value == k } }
    val screen = visible.indexOf(track.id.value).takeIf { it >= 0 } ?: project.tracks.indexOfFirst { it.id == track.id }
    val last = if (track.id.value in visible) visible.lastIndex else project.tracks.lastIndex
    val stack = project.stackIndex(track.id)
    return ClipActions.forRow(
        RowFacts(
            isMain = track.type == TrackType.MAIN,
            isVisual = track.type.isVisual,
            isSound = track.type == TrackType.AUDIO,
            isMusic = track.audioRole == AudioRole.MUSIC,
            isPresetFrame = track.presetRole == PresetRole.FRAME,
            removable = track.isRemovable,
            canMoveUp = screen > 0,
            canMoveDown = screen in 0 until last,
            canGoForward = stack in 0 until project.stacking.lastIndex,
            canGoBackward = stack > 0,
            muted = track.muted,
            enabled = track.enabled,
            lowerWhenTalking = track.lowerWhenTalking,
        ),
    )
}

/** The four stacking moves (ARCHITECTURE section 6.2): they change the video, never the screen order. */
enum class StackMove { TO_TOP, FORWARD, BACKWARD, TO_BOTTOM }

fun Project.stack(
    trackId: TrackId,
    move: StackMove,
): Project =
    when (move) {
        StackMove.TO_TOP -> bringToTop(trackId)
        StackMove.FORWARD -> bringForward(trackId)
        StackMove.BACKWARD -> sendBackward(trackId)
        StackMove.TO_BOTTOM -> sendToBottom(trackId)
    }

fun stackMoveOf(action: EditAction): StackMove? =
    when (action) {
        EditAction.SHOW_ON_TOP -> StackMove.TO_TOP
        EditAction.BRING_FORWARD -> StackMove.FORWARD
        EditAction.SEND_BACKWARD -> StackMove.BACKWARD
        EditAction.SHOW_AT_BOTTOM -> StackMove.TO_BOTTOM
        else -> null
    }

/** Rows that [after] draws under the photos and videos and [before] did not: they are hidden while MAIN plays. */
fun newlyBehindMain(
    before: Project,
    after: Project,
): List<TrackId> = after.stacking.filter { after.isBelowMain(it) && before.track(it) != null && !before.isBelowMain(it) }

/**
 * One step up or down among the rows on screen ([shown]; rows folded away
 * keep their places), or of the whole screen order when [shown] does not
 * hold the row.
 */
fun Project.moveRowBy(
    trackId: TrackId,
    step: Int,
    shown: List<String> = emptyList(),
): Project {
    val all = tracks.map { it.id.value }
    val visible = shown.filter { it in all }
    val i = visible.indexOf(trackId.value)
    if (i < 0) {
        val from = tracks.indexOfFirst { it.id == trackId }
        return moveRow(trackId, (from + step).coerceIn(0, tracks.lastIndex))
    }
    return moveRow(trackId, RowOrder.fullIndex(all, visible, trackId.value, (i + step).coerceIn(0, visible.lastIndex)))
}

/** The rows the timeline shows now, for "Move row up" and "Move row down" in the bar and the menus. */
val LocalShownRows = staticCompositionLocalOf { emptyList<String>() }

/** Other rows an item can move to: the same kind (for sound, the same role), not managed by the preset. */
fun Project.moveTargets(clipId: ClipId): List<Track> {
    val from = trackOf(clipId) ?: return emptyList()
    return tracks.filter {
        it.id != from.id && it.type == from.type && it.audioRole == from.audioRole && it.presetRole == null && it.enabled
    }
}

/** A bar action on the selected row. */
internal fun onRowAction(
    action: EditAction,
    track: Track,
    state: EditorScreenState,
    actions: EditorActions,
    restack: (TrackId, StackMove) -> Unit,
    removeRow: (TrackId) -> Unit,
    shown: List<String> = emptyList(),
) {
    stackMoveOf(action)?.let { return restack(track.id, it) }
    when (action) {
        EditAction.ADD_HERE -> state.panel = OpenPanel(Panel.INSERT, trackId = track.id)
        EditAction.MUTE_ROW -> actions.edit { setTrackMuted(track.id, !track.muted) }
        EditAction.LOWER_MUSIC -> actions.edit { setLowerWhenTalking(track.id, !track.lowerWhenTalking) }
        EditAction.FRAME_ON -> actions.edit { setTrackEnabled(track.id, !track.enabled) }
        EditAction.MOVE_ROW_UP -> actions.edit { moveRowBy(track.id, -1, shown) }
        EditAction.MOVE_ROW_DOWN -> actions.edit { moveRowBy(track.id, 1, shown) }
        EditAction.REMOVE_ROW -> removeRow(track.id)
        else -> Unit
    }
}

/** A bar action on the selected item of a row other than MAIN (Delete is handled by the screen, for its Undo). */
internal fun onTimedAction(
    action: EditAction,
    clip: Clip,
    project: Project,
    state: EditorScreenState,
    actions: EditorActions,
) {
    val id = clip.id
    val track = project.trackOf(id) ?: return
    when (action) {
        EditAction.EDIT_TEXT -> state.panel = OpenPanel(Panel.TEXT, id)
        EditAction.ADD_CAPTION -> startCaption(id, project, actions, state)
        EditAction.ANIMATION -> state.panel = OpenPanel(Panel.ANIMATION, id)
        EditAction.DURATION -> state.panel = OpenPanel(Panel.DURATION, id)
        EditAction.POSITION -> state.panel = OpenPanel(Panel.PIP_PLACE, id)
        EditAction.ROTATE -> actions.edit { rotate(id) }
        EditAction.TRIM -> state.panel = OpenPanel(if (clip is AudioClip) Panel.SOUND_TRIM else Panel.TRIM, id)
        EditAction.VOLUME -> state.panel = OpenPanel(Panel.VOLUME, id)
        EditAction.FADE -> state.panel = OpenPanel(Panel.FADE, id)
        EditAction.LOWER_MUSIC -> actions.edit { setLowerWhenTalking(track.id, !track.lowerWhenTalking) }
        EditAction.FRAME_ON -> actions.edit { setTrackEnabled(track.id, !track.enabled) }
        EditAction.MOVE_EARLIER -> actions.edit { nudgeClip(id, -NUDGE_MS) }
        EditAction.MOVE_LATER -> actions.edit { nudgeClip(id, NUDGE_MS) }
        EditAction.MOVE_TO_ROW -> state.panel = OpenPanel(Panel.MOVE_TO_ROW, id)
        else -> Unit
    }
}

/** The name of an item on a row other than MAIN ("Text", "Photo", "Music"). */
fun timedItemName(
    project: Project?,
    clip: Clip,
): Int =
    when (clip) {
        is TextClip -> R.string.timeline_row_text
        is PhotoClip -> R.string.editor_item_photo
        is VideoClip -> R.string.editor_item_video
        is OverlayClip -> R.string.timeline_row_frame
        else -> project?.trackOf(clip.id)?.let { rowLook(rowTypeOf(it)).second } ?: R.string.timeline_row_sound
    }
