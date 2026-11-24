package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MediaClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.Side
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.Track
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.insertAt
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.domain.model.moveClip
import com.itsluminous.clearmovie.domain.model.moveClipToNewRow
import com.itsluminous.clearmovie.domain.model.removeRow
import com.itsluminous.clearmovie.domain.model.setAudioFades
import com.itsluminous.clearmovie.domain.model.setPip
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.setTrackMuted
import com.itsluminous.clearmovie.domain.model.trimAudio
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.ui.components.preciseDurationText
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.RowEndPlus
import com.itsluminous.clearmovie.ui.editor.timeline.RowType
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineRow
import com.itsluminous.clearmovie.ui.editor.timeline.rowLook
import com.itsluminous.clearmovie.ui.editor.timeline.rowName
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// The panels of rows and of items on rows other than MAIN (ROADMAP cm-tracks).

fun rowMenuTag(option: String) = "row_menu_$option"

fun addRowTag(type: RowType) = "add_row_${type.name}"

fun moveTargetTag(key: String) = "move_target_$key"

const val MOVE_NEW_ROW_TAG = "move_target_new"
const val TEXT_FIELD_TAG = "text_field"
const val REMOVE_ROW_CONFIRM_TAG = "remove_row_confirm"

/** The "Record voice" option's name in [insertOptionTag]. */
const val RECORD_OPTION = "record"

fun pipCornerTag(corner: Corner) = "pip_corner_${corner.name}"

private fun InsertSide.model(): Side = if (this == InsertSide.BEFORE) Side.BEFORE else Side.AFTER

/** The row's name as the timeline names it ("Text 2"). */
@Composable
private fun nameOf(
    track: Track,
    rows: List<TimelineRow>,
): String = rows.firstOrNull { it.key == track.id.value }?.let { rowName(it) } ?: stringResource(rowLook(rowTypeOf(track)).second)

/**
 * The "+" chooser on a row other than MAIN (section 11.6), or "Add here" on
 * any row but MAIN (no anchor: at the playhead). Each row offers what it can
 * hold: picture in picture a photo or video from the gallery, text a new
 * caption, music and voice a sound from Files, a frame row an overlay video.
 */
@Composable
internal fun TimedInsertPanel(
    open: OpenPanel,
    track: Track,
    anchor: Clip?,
    rows: List<TimelineRow>,
    playheadMs: Long,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
    onSelect: (ClipId) -> Unit,
) {
    val side = open.side ?: InsertSide.AFTER
    // "Add here" from a row's "+": the cursor as it was when the "+" was tapped (it may have moved on since).
    val hereMs = open.atMs ?: playheadMs
    val title =
        if (anchor != null) {
            val where = stringResource(timedItemName(null, anchor))
            stringResource(if (side == InsertSide.BEFORE) R.string.insert_title_before else R.string.insert_title_after, where)
        } else if (open.frameFrom && open.atMs != null) {
            val at = durationText(open.atMs * 1000)
            if (track.presetRole != null || !track.enabled) {
                stringResource(R.string.timeline_add_frame_other_row, at)
            } else {
                stringResource(R.string.timeline_add_frame_from, at)
            }
        } else if (open.atMs != null) {
            // From a row's "+": the time it adds at, fixed when the "+" was tapped; "another row" when the "+" said the
            // row is full there, as the model then puts it on another row of its kind.
            val time = durationText(open.atMs * 1000)
            if (open.otherRow) {
                stringResource(R.string.timeline_add_other_row, stringResource(rowLook(rowTypeOf(track)).second), time)
            } else {
                stringResource(R.string.timeline_add_at, nameOf(track, rows), time)
            }
        } else {
            stringResource(R.string.add_here_title, nameOf(track, rows))
        }
    PanelTitle(title, onDone = null, onCancel = { onPanel(null) })

    fun pick() {
        onPanel(null)
        when {
            anchor != null -> callbacks.pickForInsert(anchor.id, side)
            open.frameFrom -> callbacks.pickFrameFrom(track.id, hereMs, open.takeOver)
            else -> callbacks.pickForRow(track.id, hereMs)
        }
    }
    when (track.type) {
        TrackType.TEXT -> {
            Choice(
                stringResource(R.string.insert_new_text),
                stringResource(R.string.insert_new_text_body),
                null,
                R.drawable.ic_text_fields,
                insertOptionTag("text"),
            ) {
                // The caption is made now, with the preset's words or the app's own, and the editor opens on it (it goes
                // again if the user leaves it empty).
                val id = RandomIds.clipId()
                val ok =
                    callbacks.editMerged(captionMergeKey(id)) {
                        // Near the end of the video the new caption ends with it (where it starts from a known time: after
                        // an item, or Add here; before an item it ends where that item starts).
                        val from =
                            when {
                                anchor == null -> hereMs
                                side == InsertSide.AFTER -> anchor.endMs
                                else -> null
                            }
                        val left = from?.let { (mainLayout().lastOrNull()?.endMs ?: 0L) - it }
                        val length =
                            if (left != null &&
                                left >= RowEndPlus.MIN_SEEN_MS
                            ) {
                                minOf(Limits.DEFAULT_TEXT_MS, left)
                            } else {
                                Limits.DEFAULT_TEXT_MS
                            }
                        val made = TextClip(id, 0, length, newCaptionWords())
                        val placed =
                            if (anchor != null) {
                                insertAt(trackOf(anchor.id)!!.id, anchor.id, side.model(), made)
                            } else {
                                addToTrack(track.id, made, hereMs)
                            }
                        placed.withEffectDefaultsFor(listOf(id))
                    }
                if (ok) {
                    onSelect(id)
                    onPanel(OpenPanel(Panel.TEXT, id))
                }
            }
        }

        TrackType.VISUAL -> {
            Choice(
                stringResource(R.string.insert_gallery),
                stringResource(R.string.insert_pip_body),
                null,
                R.drawable.ic_photo_library,
                insertOptionTag("gallery"),
            ) {
                pick()
            }
        }

        TrackType.AUDIO -> {
            Choice(
                stringResource(R.string.insert_sound),
                stringResource(R.string.insert_sound_body),
                null,
                R.drawable.ic_music_note,
                insertOptionTag("sound"),
            ) {
                pick()
            }
            // A voice row can also record one (cm-record-voice): from the playhead, or, at a "+" after an item, from
            // that item's end, as the other things put there go; the video plays from there while it records.
            // On the preset's closing-message row too: the recording then goes on another voice row (addRecording).
            if (track.audioRole == AudioRole.VOICE) {
                val from = if (anchor != null && side == InsertSide.AFTER) anchor.endMs else hereMs
                // cm-voice-choice: the voice the recording, or typed words, are said in.
                val voices = callbacks.voices
                val voice = voices?.choice?.collectAsState()?.value ?: VoiceChoice.NONE
                if (voices != null) VoiceSheetFields(voices)
                Choice(
                    stringResource(R.string.record_voice),
                    stringResource(R.string.record_voice_body, durationText(from * 1000)),
                    null,
                    R.drawable.ic_record_voice,
                    insertOptionTag(RECORD_OPTION),
                ) {
                    onPanel(null)
                    callbacks.recordVoice(track.id, from)
                }
                if (voices != null && voice != VoiceChoice.NONE) {
                    Choice(
                        stringResource(R.string.voice_type_words),
                        stringResource(R.string.voice_type_words_body, durationText(from * 1000)),
                        null,
                        R.drawable.ic_text_fields,
                        insertOptionTag(TYPE_WORDS_OPTION),
                    ) {
                        onPanel(OpenPanel(Panel.VOICE_TYPE, trackId = track.id, atMs = from))
                    }
                }
            }
        }

        TrackType.OVERLAY -> {
            Choice(
                stringResource(R.string.insert_overlay),
                stringResource(R.string.insert_overlay_body),
                null,
                R.drawable.ic_frame,
                insertOptionTag("overlay"),
            ) {
                pick()
            }
        }

        TrackType.MAIN -> {
            Unit
        }
    }
}

/** Trim for a sound: which part of the file plays. */
@Composable
internal fun SoundTrimPanel(
    clip: AudioClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    var start by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.trimStartMs.toFloat()) }
    var end by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.trimEndMs.toFloat()) }
    val source = clip.sourceDurationMs.toFloat()
    val minGap = minOf(Limits.MIN_CLIP_MS.toFloat() * 5, source)
    PanelTitle(
        stringResource(R.string.trim_title),
        onDone = {
            callbacks.edit { trimAudio(clip.id, start.roundToLong(), end.roundToLong()) }
            close()
        },
        onCancel = close,
    )
    Text(stringResource(R.string.trim_from, preciseDurationText(start.roundToLong())), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.trim_to, preciseDurationText(end.roundToLong())), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.trim_length, preciseDurationText((end - start).roundToLong())),
        style = MaterialTheme.typography.bodyMedium,
    )
    val label = stringResource(R.string.trim_title)
    val shown = stringResource(R.string.trim_range, preciseDurationText(start.roundToLong()), preciseDurationText(end.roundToLong()))
    RangeSlider(
        value = start..end,
        onValueChange = { r ->
            val movedEnd = r.endInclusive != end
            var s = r.start
            var e = r.endInclusive
            if (e - s < minGap) {
                if (movedEnd) {
                    e = s + minGap
                } else {
                    s = e - minGap
                }
            }
            start = s.coerceIn(0f, (source - minGap).coerceAtLeast(0f))
            end = e.coerceIn((start + minGap).coerceAtMost(source), source)
        },
        valueRange = 0f..source,
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = shown
            },
    )
}

/** Fade in and out for a sound: each up to half its length, and never more than [MAX_FADE_MS]. */
@Composable
internal fun FadePanel(
    clip: AudioClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    val top = minOf(MAX_FADE_MS, clip.durationMs / 2).toFloat().coerceAtLeast(0f)
    var fadeIn by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.fadeInMs.toFloat().coerceAtMost(top)) }
    var fadeOut by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.fadeOutMs.toFloat().coerceAtMost(top)) }
    PanelTitle(
        stringResource(R.string.fade_title),
        onDone = {
            callbacks.edit { setAudioFades(clip.id, fadeIn.roundToLong(), fadeOut.roundToLong()) }
            close()
        },
        onCancel = close,
    )
    for ((value, label, set) in listOf(
        Triple(fadeIn, R.string.fade_in, { v: Float -> fadeIn = v }),
        Triple(fadeOut, R.string.fade_out, { v: Float -> fadeOut = v }),
    )) {
        val shown = stringResource(label, preciseDurationText(value.roundToLong()))
        Text(shown, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { set((it / FADE_STEP_MS).roundToInt() * FADE_STEP_MS) },
            valueRange = 0f..top,
            enabled = top > 0f,
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = shown
                    stateDescription = shown
                },
        )
    }
}

private const val MAX_FADE_MS = 10_000L
private const val FADE_STEP_MS = 100f

/** Where a picture-in-picture item shows (a corner of the frame window) and how big it is. */
@Composable
internal fun PipPlacePanel(
    clip: MediaClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    val pip = clip.pip ?: PipPlacement.DEFAULT
    var corner by rememberSaveable(clip.id.value) { mutableStateOf(pip.corner) }
    var percent by rememberSaveable(clip.id.value) { mutableFloatStateOf((pip.scale * 100).toFloat()) }
    PanelTitle(
        stringResource(R.string.pip_title),
        onDone = {
            callbacks.edit {
                setPip(
                    clip.id,
                    PipPlacement(corner, (percent / 100.0).coerceIn(PipPlacement.MIN_SCALE, PipPlacement.MAX_SCALE)),
                )
            }
            close()
        },
        onCancel = close,
    )
    for ((c, label) in listOf(
        Corner.TOP_LEFT to R.string.pip_top_left,
        Corner.TOP_RIGHT to R.string.pip_top_right,
        Corner.BOTTOM_LEFT to R.string.pip_bottom_left,
        Corner.BOTTOM_RIGHT to R.string.pip_bottom_right,
    )) {
        // The icon's small picture sits bottom right: mirrored, it shows each corner.
        val flip =
            Modifier.graphicsLayer {
                scaleX = if (c == Corner.TOP_LEFT || c == Corner.BOTTOM_LEFT) -1f else 1f
                scaleY = if (c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT) -1f else 1f
            }
        Choice(stringResource(label), null, corner == c, R.drawable.ic_picture_in_picture, pipCornerTag(c), iconModifier = flip) {
            corner =
                c
        }
    }
    val shown = stringResource(R.string.pip_size, percent.roundToInt())
    Text(shown, style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = percent,
        onValueChange = { percent = (it / 5).roundToInt() * 5f },
        valueRange = (PipPlacement.MIN_SCALE * 100).toFloat()..(PipPlacement.MAX_SCALE * 100).toFloat(),
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = shown
                stateDescription = shown
            },
    )
}

/** "Move to another row": the other rows of its kind, or a new one. The item keeps its time. */
@Composable
internal fun MoveToRowPanel(
    clip: Clip,
    project: Project,
    rows: List<TimelineRow>,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.action_move_to_row), onDone = null, onCancel = close)
    for (target in project.moveTargets(clip.id)) {
        val (icon, _) = rowLook(rowTypeOf(target))
        val free = target.isFree(clip.startMs, clip.endMs)
        Choice(
            nameOf(target, rows),
            if (free) null else stringResource(R.string.move_row_busy),
            null,
            icon,
            moveTargetTag(target.id.value),
        ) {
            callbacks.edit { moveClip(clip.id, clip.startMs, target.id) }
            close()
        }
    }
    Choice(
        stringResource(R.string.move_new_row),
        stringResource(R.string.move_new_row_body),
        null,
        R.drawable.ic_playlist_add,
        MOVE_NEW_ROW_TAG,
    ) {
        callbacks.edit { moveClipToNewRow(clip.id) }
        close()
    }
}

/** "Add row": an empty row of a chosen kind, at that kind's usual place. */
@Composable
internal fun AddRowPanel(
    callbacks: EditorCallbacks,
    onSelectRow: (TrackId) -> Unit,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.add_row_title), onDone = null, onCancel = close)
    for ((type, body) in listOf(
        RowType.TEXT to R.string.add_row_text_body,
        RowType.PIP to R.string.add_row_pip_body,
        RowType.FRAME to R.string.add_row_frame_body,
        RowType.MUSIC to R.string.add_row_music_body,
        RowType.VOICE to R.string.add_row_voice_body,
    )) {
        val (icon, name) = rowLook(type)
        Choice(stringResource(name), stringResource(body), null, icon, addRowTag(type)) {
            val id = RandomIds.trackId()
            val made =
                callbacks.edit {
                    when (type) {
                        RowType.TEXT -> addRow(TrackType.TEXT, id = id)
                        RowType.PIP -> addRow(TrackType.VISUAL, id = id)
                        RowType.FRAME -> addRow(TrackType.OVERLAY, id = id)
                        RowType.MUSIC -> addRow(TrackType.AUDIO, AudioRole.MUSIC, id)
                        else -> addRow(TrackType.AUDIO, AudioRole.VOICE, id)
                    }
                }
            close()
            if (made) onSelectRow(id)
        }
    }
}

/**
 * The menu of a long-press without moving, on a row's icon or its empty
 * space: Add here, its sound, what shows on top in the video, where it is on
 * screen, and Remove row.
 */
@Composable
internal fun RowMenuPanel(
    track: Track,
    project: Project,
    rows: List<TimelineRow>,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
    onStack: (TrackId, StackMove) -> Unit,
    onRemoveRow: (TrackId) -> Unit,
) {
    PanelTitle(nameOf(track, rows), onDone = null, onCancel = { onPanel(null) })
    RowSection(track, project, callbacks, onPanel, onStack, onRemoveRow, withAddHere = true)
}

/**
 * A row's part of a menu. [withAddHere] is off in an item's menu, which has
 * its own Add before and Add after.
 */
@Composable
internal fun RowSection(
    track: Track,
    project: Project,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
    onStack: (TrackId, StackMove) -> Unit,
    onRemoveRow: (TrackId) -> Unit,
    withAddHere: Boolean,
) {
    val close = { onPanel(null) }
    if (withAddHere && track.type != TrackType.MAIN) {
        Choice(stringResource(R.string.action_add_here), null, null, R.drawable.ic_add, rowMenuTag("add_here")) {
            onPanel(OpenPanel(Panel.INSERT, trackId = track.id))
        }
    }
    val frameSound = track.hasFrameSound(LocalFramesWithSound.current)
    val mutedFrameRow = track.type == TrackType.OVERLAY && track.presetRole != PresetRole.FRAME && track.muted
    if (track.type == TrackType.AUDIO || frameSound || mutedFrameRow) {
        Choice(stringResource(R.string.action_mute_row), null, track.muted, R.drawable.ic_volume_off, rowMenuTag("mute")) {
            callbacks.edit { setTrackMuted(track.id, !track.muted) }
            close()
        }
    }
    if (lowersForTalk(track, LocalFramesWithSound.current)) {
        // The same popup as the bar's Lower for talk: on or off, and how low; on a frame row for its frames' sound,
        // on a photo-and-video row for its videos' sound.
        Choice(stringResource(talkSwitchLabel(track)), null, track.lowerWhenTalking, R.drawable.ic_volume_down, rowMenuTag("lower")) {
            onPanel(OpenPanel(Panel.TALK_LOWER, trackId = track.id))
        }
    }
    if (track.presetRole == PresetRole.FRAME) {
        Choice(stringResource(R.string.action_frame_on), null, track.enabled, R.drawable.ic_frame, rowMenuTag("frame_on")) {
            callbacks.edit { setTrackEnabled(track.id, !track.enabled) }
            close()
        }
    }
    val stackAt = project.stackIndex(track.id)
    if (stackAt >= 0) {
        Text(
            stringResource(R.string.stack_heading),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        val up = stackAt < project.stacking.lastIndex
        val down = stackAt > 0
        for ((move, label, icon, enabled) in listOf(
            StackOption(StackMove.TO_TOP, R.string.stack_show_on_top, R.drawable.ic_flip_to_front, up),
            StackOption(StackMove.FORWARD, R.string.stack_bring_forward, R.drawable.ic_keyboard_arrow_up, up),
            StackOption(StackMove.BACKWARD, R.string.stack_send_backward, R.drawable.ic_keyboard_arrow_down, down),
            StackOption(StackMove.TO_BOTTOM, R.string.stack_show_at_bottom, R.drawable.ic_flip_to_back, down),
        )) {
            Choice(stringResource(label), null, null, icon, rowMenuTag(move.name), enabled = enabled) {
                onStack(track.id, move)
                close()
            }
        }
    }
    val shown = LocalShownRows.current.filter { k -> project.tracks.any { it.id.value == k } }
    val onScreen = track.id.value in shown
    val screen = if (onScreen) shown.indexOf(track.id.value) else project.tracks.indexOfFirst { it.id == track.id }
    val last = if (onScreen) shown.lastIndex else project.tracks.lastIndex
    Text(
        stringResource(R.string.row_heading),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    Choice(stringResource(R.string.row_move_up), null, null, R.drawable.ic_arrow_upward, rowMenuTag("up"), enabled = screen > 0) {
        callbacks.edit { moveRowBy(track.id, -1, shown) }
        close()
    }
    Choice(
        stringResource(R.string.row_move_down),
        null,
        null,
        R.drawable.ic_arrow_downward,
        rowMenuTag("down"),
        enabled = screen in 0 until last,
    ) {
        callbacks.edit { moveRowBy(track.id, 1, shown) }
        close()
    }
    if (track.isRemovable) {
        Choice(stringResource(R.string.action_remove_row), null, null, R.drawable.ic_delete, rowMenuTag("remove")) {
            onRemoveRow(track.id)
        }
    }
}

private data class StackOption(
    val move: StackMove,
    val label: Int,
    val icon: Int,
    val enabled: Boolean,
)

/** The menu of a long-press without moving on an item of a row other than MAIN: its own actions, then its row's. */
@Composable
internal fun TimedItemMenuPanel(
    clip: Clip,
    track: Track,
    project: Project,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
    onDeleted: (Clip) -> Unit,
    onStack: (TrackId, StackMove) -> Unit,
    onRemoveRow: (TrackId) -> Unit,
) {
    PanelTitle(stringResource(timedItemName(project, clip)), onDone = null, onCancel = { onPanel(null) })
    Choice(stringResource(R.string.menu_add_before), null, null, R.drawable.ic_add, "menu_add_before") {
        onPanel(OpenPanel(Panel.INSERT, clip.id, InsertSide.BEFORE))
    }
    Choice(stringResource(R.string.menu_add_after), null, null, R.drawable.ic_add, "menu_add_after") {
        onPanel(OpenPanel(Panel.INSERT, clip.id, InsertSide.AFTER))
    }
    if (track.presetRole != PresetRole.FRAME) {
        Choice(stringResource(R.string.action_delete), null, null, R.drawable.ic_delete, "menu_delete") {
            if (callbacks.edit { deleteClip(clip.id) }) onDeleted(clip)
            onPanel(null)
        }
    }
    RowSection(track, project, callbacks, onPanel, onStack, onRemoveRow, withAddHere = false)
}

/** "Remove row" on a row that still has items: says how many go with it. */
@Composable
internal fun RemoveRowPanel(
    track: Track,
    rows: List<TimelineRow>,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.remove_row_title, nameOf(track, rows)), onDone = null, onCancel = null)
    val n = track.clips.size
    Text(pluralStringResource(R.plurals.remove_row_body, n, n), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
        OutlinedButton(onClick = close, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = {
                callbacks.edit { removeRow(track.id) }
                close()
            },
            modifier = Modifier.heightIn(min = 56.dp).testTag(REMOVE_ROW_CONFIRM_TAG),
        ) { Text(stringResource(R.string.action_remove_row)) }
    }
    Spacer(Modifier.height(8.dp))
}
