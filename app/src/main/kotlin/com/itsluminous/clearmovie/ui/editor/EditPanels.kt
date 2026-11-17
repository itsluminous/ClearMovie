package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MediaClip
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TextRules
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.duplicateClip
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.setCardColor
import com.itsluminous.clearmovie.domain.model.setFit
import com.itsluminous.clearmovie.domain.model.setLoops
import com.itsluminous.clearmovie.domain.model.setPhotoDuration
import com.itsluminous.clearmovie.domain.model.setSpeed
import com.itsluminous.clearmovie.domain.model.setText
import com.itsluminous.clearmovie.domain.model.setVolume
import com.itsluminous.clearmovie.domain.model.splitClip
import com.itsluminous.clearmovie.domain.model.trimVideo
import com.itsluminous.clearmovie.ui.components.preciseDurationText
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbSource
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineRow
import com.itsluminous.clearmovie.ui.editor.timeline.TrimMath
import com.itsluminous.clearmovie.ui.editor.timeline.itemName
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Test tags. */
const val PANEL_TAG = "edit_panel"
const val PANEL_DONE_TAG = "edit_panel_done"
const val PANEL_CANCEL_TAG = "edit_panel_cancel"

/** A panel at least this tall keeps every caption editor tab the same height. */
private val ROOMY_PANEL = 360.dp

fun insertOptionTag(option: String) = "insert_option_$option"

fun swatchTag(index: Int) = "card_swatch_$index"

/** The card colours offered (opaque, so text on them is readable with the caption style's outline). */
val CARD_COLOURS: List<Pair<ArgbColor, Int>> =
    listOf(
        ArgbColor(0xFF202020) to R.string.colour_black,
        ArgbColor(0xFFFFFFFF) to R.string.colour_white,
        ArgbColor(0xFFB71C1C) to R.string.colour_red,
        ArgbColor(0xFFE65100) to R.string.colour_orange,
        ArgbColor(0xFFF9A825) to R.string.colour_yellow,
        ArgbColor(0xFF1B5E20) to R.string.colour_green,
        ArgbColor(0xFF0D47A1) to R.string.colour_blue,
        ArgbColor(0xFF4A148C) to R.string.colour_purple,
    )

/** What the editor's panels and bar ask their owner to do. */
interface EditorCallbacks {
    /** Applies a model edit as one undo step; false when it was refused. */
    fun edit(change: Project.() -> Project): Boolean

    /**
     * Applies a model edit as part of the undo step that edits with the same
     * [mergeKey] make one after another (one visit to the caption editor).
     */
    fun editMerged(
        mergeKey: String,
        change: Project.() -> Project,
    ): Boolean = edit(change)

    /** Ends a run of merged edits: the next edit is a step of its own. */
    fun endMerge() = Unit

    /**
     * Takes back the run of edits made with [mergeKey], leaving nothing to
     * redo (the caption editor's Cancel). False when there was no such run
     * (it ended, or the app was closed by the system since).
     */
    fun revertMerge(mergeKey: String): Boolean = false

    fun undo()

    fun redo()

    fun seek(ms: Long)

    /**
     * Shows a change just made in the preview: plays from [ms] once the
     * preview has the edited video (a transition or animation just chosen).
     */
    fun previewFrom(ms: Long) = seek(ms)

    /** Opens the gallery to put photos and videos at a "+". */
    fun pickForInsert(
        anchor: ClipId,
        side: InsertSide,
    )

    /** Opens the gallery to replace an item, or the intro or ending. */
    fun pickForReplace(clipId: ClipId)

    /** Opens the gallery to add at the end. */
    fun pickToAdd()

    fun insertCard(
        anchor: ClipId,
        side: InsertSide,
        text: String?,
        color: ArgbColor,
    )

    /** Plays the intro or the ending on its own. */
    fun playPart(clipId: ClipId)

    /**
     * "Add here" on a picture-in-picture, music, voice or frame row: opens
     * the gallery or Files, and what is picked goes on row [trackId] at
     * [atMs] (or, where that is taken, on a new row of its kind).
     */
    fun pickForRow(
        trackId: TrackId,
        atMs: Long,
    )
}

/**
 * The open panel, in place of the contextual bar. It is about an item
 * ([OpenPanel.clipId], on any row) or a row ([OpenPanel.trackId]); when that
 * goes away (an undo) the panel closes.
 */
@Composable
fun EditPanel(
    open: OpenPanel,
    project: Project,
    playheadMs: Long,
    thumbnail: ThumbSource,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
    onDeleted: (Clip) -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (ClipId) -> Unit = {},
    rows: List<TimelineRow> = emptyList(),
    onStack: (TrackId, StackMove) -> Unit = { _, _ -> },
    onRemoveRow: (TrackId) -> Unit = {},
    onSelectRow: (TrackId) -> Unit = {},
    maxHeight: Dp = 420.dp,
    captionWindow: NormRect = NormRect.FULL,
) {
    val close = { onPanel(null) }
    val clip = open.clipId?.let { project.clip(it) }
    val track = open.trackId?.let { project.track(it) } ?: clip?.let { project.trackOf(it.id) }
    val gone = (open.clipId != null && clip == null) || track == null
    // The item or row went away (an undo): the panel closes, after this frame rather than during it.
    LaunchedEffect(gone) { if (gone) close() }
    if (gone || track == null) return
    val onMain = track.type == TrackType.MAIN
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth().testTag(PANEL_TAG)) {
        // The caption editor scrolls only its tab's content, so its title, Cancel and Done stay in sight
        // (after typing, a scrolled panel could hide them).
        val scrolls = open.panel != Panel.TEXT
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(
                    max = maxHeight,
                ).then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (open.panel) {
                Panel.TRIM -> {
                    (clip as? VideoClip)?.let { TrimPanel(it, thumbnail, callbacks, close) }
                }

                Panel.DURATION -> {
                    (clip as? MainClip)?.let { DurationPanel(it, callbacks, close) }
                }

                Panel.SPLIT -> {
                    (clip as? VideoClip)?.let { SplitPanel(it, project, playheadMs, thumbnail, callbacks, close) }
                }

                Panel.SPEED -> {
                    (clip as? VideoClip)?.let { SpeedPanel(it, callbacks, close) }
                }

                Panel.LOOP -> {
                    (clip as? VideoClip)?.let { LoopPanel(it, callbacks, close) }
                }

                Panel.VOLUME -> {
                    volumeOf(clip)?.let { VolumePanel(clip!!.id, it, callbacks, close) }
                }

                Panel.FIT -> {
                    (clip as? MediaClip)?.let { FitPanel(it, callbacks, close) }
                }

                Panel.INSERT -> {
                    if (onMain && clip is MainClip) {
                        InsertPanel(open, clip, callbacks, onPanel)
                    } else {
                        TimedInsertPanel(open, track, clip, rows, playheadMs, callbacks, onPanel, onSelect)
                    }
                }

                Panel.CARD_TEXT -> {
                    (clip as? MainClip)?.let { CardTextPanel(open, it, callbacks, close) }
                }

                Panel.CARD_COLOUR -> {
                    (clip as? MainClip)?.let { CardColourPanel(open, it, callbacks, close) }
                }

                Panel.ITEM_MENU -> {
                    if (onMain && clip is MainClip) {
                        ItemMenuPanel(clip, callbacks, onPanel, onDeleted)
                        RowSection(track, project, callbacks, onPanel, onStack, onRemoveRow, withAddHere = false)
                    } else if (clip != null) {
                        TimedItemMenuPanel(clip, track, project, callbacks, onPanel, onDeleted, onStack, onRemoveRow)
                    }
                }

                Panel.ROW_MENU -> {
                    RowMenuPanel(track, project, rows, callbacks, onPanel, onStack, onRemoveRow)
                }

                Panel.ADD_ROW -> {
                    AddRowPanel(callbacks, onSelectRow = onSelectRow, close = close)
                }

                Panel.TEXT -> {
                    if (clip is TextClip) {
                        CaptionPanel(clip, project, captionWindow, callbacks, onDone = close, roomy = maxHeight >= ROOMY_PANEL)
                    } else {
                        // Only a caption has this editor (a panel saved by an older version may name another item).
                        LaunchedEffect(open) { close() }
                    }
                }

                Panel.SOUND_TRIM -> {
                    (clip as? AudioClip)?.let { SoundTrimPanel(it, callbacks, close) }
                }

                Panel.FADE -> {
                    (clip as? AudioClip)?.let { FadePanel(it, callbacks, close) }
                }

                Panel.PIP_PLACE -> {
                    (clip as? MediaClip)?.let { PipPlacePanel(it, callbacks, close) }
                }

                Panel.MOVE_TO_ROW -> {
                    clip?.let { MoveToRowPanel(it, project, rows, callbacks, close) }
                }

                Panel.REMOVE_ROW -> {
                    RemoveRowPanel(track, rows, callbacks, close)
                }

                Panel.TRANSITION -> {
                    TransitionPanel(open, project, thumbnail, callbacks, close, onPanel)
                }

                Panel.ANIMATION -> {
                    AnimationPanel(open, project, thumbnail, callbacks, close)
                }
            }
        }
    }
}

/** The volume a video or a sound has now (dB), or null for an item without sound. */
private fun volumeOf(clip: Clip?): Double? =
    when (clip) {
        is VideoClip -> clip.volumeDb
        is AudioClip -> clip.volumeDb
        else -> null
    }

@Composable
internal fun PanelTitle(
    title: String,
    onDone: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    doneLabel: Int = R.string.action_done,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(PANEL_CANCEL_TAG)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        if (onDone != null) {
            Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag(PANEL_DONE_TAG)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(doneLabel))
            }
        }
    }
}

@Composable
private fun FramePreview(
    clip: MainClip,
    sourceMs: Long,
    thumbnail: ThumbSource,
) {
    val bitmap by produceState<Bitmap?>(null, clip, sourceMs / 100) { value = thumbnail(clip, sourceMs) }
    Box(
        Modifier
            .widthIn(max = 200.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TrimPanel(
    clip: VideoClip,
    thumbnail: ThumbSource,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    var start by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.trimStartMs.toFloat()) }
    var end by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.trimEndMs.toFloat()) }
    var showingEnd by rememberSaveable(clip.id.value) { mutableStateOf(false) }
    // Never more than the whole file: a 1 s video at 4x is already shorter than the usual minimum.
    val minGap = minOf(maxOf(Limits.MIN_CLIP_MS, clip.speed.sourceMs(TrimMath.MIN_VIDEO_MS)), clip.sourceDurationMs).toFloat()
    PanelTitle(
        stringResource(R.string.trim_title),
        onDone = {
            callbacks.edit { trimVideo(clip.id, start.roundToLong(), end.roundToLong()) }
            close()
        },
        onCancel = close,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FramePreview(clip, (if (showingEnd) end - 1 else start).roundToLong().coerceAtLeast(0), thumbnail)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.trim_from, preciseDurationText(start.roundToLong())), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.trim_to, preciseDurationText(end.roundToLong())), style = MaterialTheme.typography.bodyLarge)
            val length = clip.copy(trimStartMs = start.roundToLong(), trimEndMs = end.roundToLong()).durationMs
            Text(stringResource(R.string.trim_length, preciseDurationText(length)), style = MaterialTheme.typography.bodyMedium)
        }
    }
    val label = stringResource(R.string.trim_title)
    val shownRange = stringResource(R.string.trim_range, preciseDurationText(start.roundToLong()), preciseDurationText(end.roundToLong()))
    RangeSlider(
        value = start..end,
        onValueChange = { r ->
            showingEnd = r.endInclusive != end
            var s = r.start
            var e = r.endInclusive
            if (e - s < minGap) {
                if (showingEnd) {
                    e = s + minGap
                } else {
                    s = e - minGap
                }
            }
            start = s.coerceIn(0f, (clip.sourceDurationMs - minGap).coerceAtLeast(0f))
            end = e.coerceIn((start + minGap).coerceAtMost(clip.sourceDurationMs.toFloat()), clip.sourceDurationMs.toFloat())
        },
        valueRange = 0f..clip.sourceDurationMs.toFloat(),
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = shownRange
            },
    )
}

@Composable
private fun DurationPanel(
    clip: MainClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    var ms by rememberSaveable(clip.id.value) { mutableFloatStateOf(clip.durationMs.toFloat()) }
    // A fixed range for this item: up to a minute, or further if a handle already made it longer.
    val top = maxOf(MAX_DURATION_SLIDER_MS, clip.durationMs.toFloat())
    PanelTitle(
        stringResource(R.string.duration_title),
        onDone = {
            callbacks.edit { setPhotoDuration(clip.id, ms.roundToLong()) }
            close()
        },
        onCancel = close,
    )
    val shown = preciseDurationText(ms.roundToLong())
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(R.drawable.ic_remove, R.string.duration_shorter) {
            ms = (ms - STEP_MS).coerceAtLeast(TrimMath.MIN_STILL_MS.toFloat())
        }
        Text(
            shown,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton(R.drawable.ic_add, R.string.duration_longer) {
            ms = (ms + STEP_MS).coerceAtMost(top)
        }
    }
    val label = stringResource(R.string.duration_title)
    Slider(
        value = ms.coerceIn(TrimMath.MIN_STILL_MS.toFloat(), top),
        onValueChange = { ms = ((it / STEP_MS).roundToInt() * STEP_MS).coerceAtLeast(TrimMath.MIN_STILL_MS.toFloat()) },
        valueRange = TrimMath.MIN_STILL_MS.toFloat()..top,
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = shown
            },
    )
}

@Composable
private fun StepButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit,
) {
    val text = stringResource(label)
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(56.dp).semantics { contentDescription = text },
    ) {
        Icon(painterResource(icon), contentDescription = null)
    }
}

@Composable
private fun SplitPanel(
    clip: VideoClip,
    project: Project,
    playheadMs: Long,
    thumbnail: ThumbSource,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    val slot = project.mainSlot(clip.id) ?: return
    val min = Limits.MIN_CLIP_MS.toFloat() * clip.speed.denominator
    val max = (clip.durationMs - min).coerceAtLeast(min)
    val inside = (playheadMs - slot.startMs).toFloat()
    var at by rememberSaveable(clip.id.value) {
        mutableFloatStateOf(if (inside in min..max) inside else clip.durationMs / 2f)
    }
    PanelTitle(
        stringResource(R.string.split_title),
        onDone = {
            if (callbacks.edit { splitClip(clip.id, at.roundToLong()) }) callbacks.seek(slot.startMs + at.roundToLong())
            close()
        },
        onCancel = close,
        doneLabel = R.string.split_here,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FramePreview(clip, clip.trimStartMs + clip.speed.sourceMs(at.roundToLong()) % (clip.trimEndMs - clip.trimStartMs), thumbnail)
        Text(stringResource(R.string.split_at, preciseDurationText(at.roundToLong())), style = MaterialTheme.typography.bodyLarge)
    }
    val label = stringResource(R.string.split_title)
    val cutAt = stringResource(R.string.split_at, preciseDurationText(at.roundToLong()))
    Slider(
        value = at.coerceIn(min, max),
        onValueChange = { at = it },
        onValueChangeFinished = { callbacks.seek(slot.startMs + at.roundToLong()) },
        valueRange = min..max,
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = cutAt
            },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPanel(
    clip: VideoClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.speed_title), onDone = close, onCancel = null)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (speed in Speed.entries) {
            val text = stringResource(R.string.speed_value, speedNumber(speed))
            FilterChip(
                selected = clip.speed == speed,
                onClick = { callbacks.edit { setSpeed(clip.id, speed) } },
                label = { Text(text, style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 0.25, 0.5, 1, 1.5, 2, 4 in the phone's own digits. */
@Composable
private fun speedNumber(speed: Speed): String {
    val value = speed.numerator.toDouble() / speed.denominator
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return java.text.NumberFormat
        .getNumberInstance(locale)
        .apply { maximumFractionDigits = 2 }
        .format(value)
}

@Composable
private fun LoopPanel(
    clip: VideoClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.loop_title), onDone = close, onCancel = null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(R.drawable.ic_remove, R.string.loop_fewer) { if (clip.loops > 1) callbacks.edit { setLoops(clip.id, clip.loops - 1) } }
        Text(
            pluralStringResource(R.plurals.loop_times, clip.loops, clip.loops),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton(R.drawable.ic_add, R.string.loop_more) {
            if (clip.loops < Limits.MAX_LOOPS) callbacks.edit { setLoops(clip.id, clip.loops + 1) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun VolumePanel(
    clipId: ClipId,
    volumeDb: Double,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    var percent by rememberSaveable(clipId.value) { mutableFloatStateOf(dbToPercent(volumeDb)) }
    PanelTitle(
        stringResource(R.string.volume_title),
        onDone = {
            callbacks.edit { setVolume(clipId, percentToDb(percent)) }
            close()
        },
        onCancel = close,
    )
    val shown = stringResource(R.string.volume_value, percent.roundToInt())
    Row(verticalAlignment = Alignment.CenterVertically) {
        val mute = stringResource(R.string.volume_mute)
        OutlinedButton(onClick = { percent = 0f }, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = mute }) {
            Icon(painterResource(R.drawable.ic_volume_off), contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(mute)
        }
        Text(shown, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
    val label = stringResource(R.string.volume_title)
    Slider(
        value = percent,
        onValueChange = { percent = (it / 5).roundToInt() * 5f },
        valueRange = 0f..MAX_VOLUME_PERCENT,
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = shown
            },
    )
}

/** Volume as the user sees it (100% is as recorded) and as the model keeps it (dB). */
fun dbToPercent(db: Double): Float =
    if (db <=
        Limits.MIN_VOLUME_DB
    ) {
        0f
    } else {
        (100 * 10.0.pow(db / 20)).toFloat().coerceIn(0f, MAX_VOLUME_PERCENT)
    }

fun percentToDb(percent: Float): Double =
    if (percent <=
        0f
    ) {
        Limits.MIN_VOLUME_DB
    } else {
        (20 * log10(percent / 100.0)).coerceIn(Limits.MIN_VOLUME_DB, Limits.MAX_VOLUME_DB)
    }

@Composable
private fun FitPanel(
    clip: MediaClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.fit_title), onDone = close, onCancel = null)
    for ((fit, label, body) in listOf(
        Triple(Fit.FILL_CROP, R.string.fit_fill, R.string.fit_fill_body),
        Triple(Fit.FIT_BLUR, R.string.fit_blur, R.string.fit_blur_body),
        Triple(Fit.FIT_BARS, R.string.fit_bars, R.string.fit_bars_body),
    )) {
        Choice(
            title = stringResource(label),
            body = stringResource(body),
            selected = clip.fit == fit,
            icon = R.drawable.ic_aspect_ratio,
            tag = "fit_${fit.name}",
        ) { callbacks.edit { setFit(clip.id, fit) } }
    }
}

/**
 * One large option in a panel or menu: an icon, a title and an optional
 * line under it. [selected] marks the chosen one (null: not a choice of
 * one); [enabled] false greys it out where it can't apply (Show on top for
 * the top row), so the options never move about.
 */
@Composable
internal fun Choice(
    title: String,
    body: String?,
    selected: Boolean?,
    icon: Int,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected == true) Modifier.border(BorderStroke(2.dp, primary), RoundedCornerShape(12.dp)) else Modifier,
            ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics(mergeDescendants = true) { if (selected != null) this.selected = selected }
            .padding(12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InsertPanel(
    open: OpenPanel,
    anchor: MainClip,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
) {
    val side = open.side ?: InsertSide.AFTER
    val where = stringResource(itemName(anchor))
    PanelTitle(
        stringResource(if (side == InsertSide.BEFORE) R.string.insert_title_before else R.string.insert_title_after, where),
        onDone = null,
        onCancel = { onPanel(null) },
    )
    Choice(
        stringResource(R.string.insert_gallery),
        stringResource(R.string.insert_gallery_body),
        null,
        R.drawable.ic_photo_library,
        insertOptionTag("gallery"),
    ) {
        onPanel(null)
        callbacks.pickForInsert(anchor.id, side)
    }
    Choice(
        stringResource(R.string.insert_text_card),
        stringResource(R.string.insert_text_card_body),
        null,
        R.drawable.ic_text_fields,
        insertOptionTag("text"),
    ) {
        onPanel(OpenPanel(Panel.CARD_TEXT, anchor.id, side))
    }
    Choice(
        stringResource(R.string.insert_colour_card),
        stringResource(R.string.insert_colour_card_body),
        null,
        R.drawable.ic_palette,
        insertOptionTag("colour"),
    ) {
        onPanel(OpenPanel(Panel.CARD_COLOUR, anchor.id, side))
    }
}

@Composable
private fun CardTextPanel(
    open: OpenPanel,
    clip: MainClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    val editing = open.side == null
    var text by rememberSaveable(clip.id.value, editing) { mutableStateOf(if (editing) (clip as? CardClip)?.text.orEmpty() else "") }
    val usable = text.isNotBlank() && TextRules.isValidText(text)
    val submit = {
        if (usable) {
            if (editing) {
                callbacks.edit { setText(clip.id, text.trim()) }
            } else {
                callbacks.insertCard(clip.id, open.side!!, text.trim(), CARD_COLOURS.first().first)
            }
            close()
        }
    }
    PanelTitle(
        stringResource(R.string.card_text_title),
        onDone = if (usable) submit else null,
        onCancel = close,
        doneLabel = if (editing) R.string.action_done else R.string.card_add,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= Limits.MAX_TEXT_LENGTH) text = it },
        label = { Text(stringResource(R.string.card_text_hint)) },
        minLines = 2,
        // The keyboard's own Done key adds the card too: held sideways, the keyboard leaves no room for the panel's button.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().testTag("card_text_field"),
    )
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardColourPanel(
    open: OpenPanel,
    clip: MainClip,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    val editing = open.side == null
    PanelTitle(stringResource(R.string.card_colour_title), onDone = null, onCancel = close)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CARD_COLOURS.forEachIndexed { i, (colour, name) ->
            val label = stringResource(name)
            val chosen = editing && (clip as? CardClip)?.color == colour
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(colour.argb.toInt()))
                    .border(BorderStroke(if (chosen) 4.dp else 1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                    .clickable(role = Role.Button) {
                        if (editing) {
                            callbacks.edit { setCardColor(clip.id, colour) }
                        } else {
                            callbacks.insertCard(clip.id, open.side!!, null, colour)
                        }
                        close()
                    }.semantics {
                        contentDescription = label
                        selected = chosen
                    }.testTag(swatchTag(i)),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ItemMenuPanel(
    clip: MainClip,
    callbacks: EditorCallbacks,
    onPanel: (OpenPanel?) -> Unit,
    onDeleted: (Clip) -> Unit,
) {
    PanelTitle(stringResource(itemName(clip)), onDone = null, onCancel = { onPanel(null) })
    val content = clip is PhotoClip || clip is VideoClip || clip is CardClip
    // Nothing goes before the intro or after the ending.
    if (clip !is IntroClip) {
        Choice(stringResource(R.string.menu_add_before), null, null, R.drawable.ic_add, "menu_add_before") {
            onPanel(OpenPanel(Panel.INSERT, clip.id, InsertSide.BEFORE))
        }
    }
    if (clip !is EndingClip) {
        Choice(stringResource(R.string.menu_add_after), null, null, R.drawable.ic_add, "menu_add_after") {
            onPanel(OpenPanel(Panel.INSERT, clip.id, InsertSide.AFTER))
        }
    }
    if (content) {
        Choice(stringResource(R.string.action_duplicate), null, null, R.drawable.ic_content_copy, "menu_duplicate") {
            callbacks.edit { duplicateClip(clip.id) }
            onPanel(null)
        }
        Choice(stringResource(R.string.action_delete), null, null, R.drawable.ic_delete, "menu_delete") {
            if (callbacks.edit { deleteClip(clip.id) }) onDeleted(clip)
            onPanel(null)
        }
    }
}

private const val STEP_MS = 500f
private const val MAX_DURATION_SLIDER_MS = 60_000f
const val MAX_VOLUME_PERCENT = 200f
