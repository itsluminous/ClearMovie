package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.data.media.IntakeItem
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.data.media.newMainItems
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.OpenResult
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.data.project.ProjectMediaSet
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.FrameSpec
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.MediaClip
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.Side
import com.itsluminous.clearmovie.domain.model.Track
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.domain.model.insertAt
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.domain.model.replaceMainItem
import com.itsluminous.clearmovie.domain.model.replacePresetPart
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.Source
import com.itsluminous.clearmovie.render.preview.ExactDraft
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import com.itsluminous.clearmovie.render.preview.StillLoader
import com.itsluminous.clearmovie.ui.media.IntakeState
import com.itsluminous.clearmovie.ui.media.MediaIntake
import com.itsluminous.clearmovie.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

/** What the editor shell shows. */
data class EditorUi(
    val project: Project? = null,
    val preset: Preset? = null,
    /** A preset file was moved or deleted: the editor offers the repair screen. */
    val presetNeedsRepair: Boolean = false,
    /** Clips whose photo or video can't be opened now. */
    val missing: Map<ClipId, MediaStatus> = emptyMap(),
    /** The project could not be opened (gone, or unreadable). */
    val failed: Boolean = false,
    val schedule: PreviewSchedule? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    /** MAIN in play order: intro, the user's items, ending. */
    val slots: List<MainSlot> get() = project?.mainLayout().orEmpty()

    val hasContent: Boolean get() = slots.any { it.clip is PhotoClip || it.clip is VideoClip || it.clip is CardClip }
}

/** "Play exactly as exported". */
sealed interface DraftState {
    data object Idle : DraftState

    data class Preparing(
        val percent: Int,
    ) : DraftState

    data class Ready(
        val file: File,
    ) : DraftState

    data object Failed : DraftState
}

sealed interface EditorEvent {
    data object Closed : EditorEvent

    /** Leaving failed because the latest edits could not be written (the phone is full). */
    data object NotSaved : EditorEvent

    /** New items went in; the first one is selected. */
    data class Inserted(
        val first: ClipId,
    ) : EditorEvent

    /** Some picked items could not be used. */
    data class Skipped(
        val count: Int,
    ) : EditorEvent

    /** The chosen file can't be used for what it was chosen for. */
    data class WrongFile(
        val wanted: WantedFile,
    ) : EditorEvent

    /** The copies did not fit on the phone. */
    data object OutOfSpace : EditorEvent

    /** An edit could not be made (an item too short for that speed, say). */
    data object CantDoThat : EditorEvent
}

/** What a chosen file had to be. */
enum class WantedFile { PHOTO_OR_VIDEO, VIDEO, SOUND, MUSIC, OVERLAY }

/**
 * Why photos or videos are being taken in from the gallery. Kept in the
 * saved state with the links, so a process death during the copy takes them
 * in again for the same purpose.
 */
sealed interface IntakePurpose {
    /** "Add photos and videos": at the end, before the ending. */
    data object Add : IntakePurpose

    /** A "+": next to [anchor]. */
    data class Insert(
        val anchor: ClipId,
        val side: Side,
    ) : IntakePurpose

    /** "Replace" on a photo, video or card. */
    data class Replace(
        val clipId: ClipId,
    ) : IntakePurpose

    /** "Replace (this video only)" on the intro or the ending. */
    data class ReplacePart(
        val clipId: ClipId,
    ) : IntakePurpose

    /** "Add here" on a picture-in-picture, music, voice or frame row: at [atMs] on row [trackId]. */
    data class AddToRow(
        val trackId: TrackId,
        val atMs: Long,
    ) : IntakePurpose

    fun encode(): String =
        when (this) {
            Add -> "add"
            is Insert -> "insert:${side.name}:${anchor.value}"
            is Replace -> "replace:${clipId.value}"
            is ReplacePart -> "part:${clipId.value}"
            is AddToRow -> "row:$atMs:${trackId.value}"
        }

    companion object {
        fun decode(text: String): IntakePurpose? =
            runCatching {
                val parts = text.split(':', limit = 3)
                when (parts[0]) {
                    "add" -> Add
                    "insert" -> Insert(ClipId(parts[2]), Side.valueOf(parts[1]))
                    "replace" -> Replace(ClipId(parts[1]))
                    "part" -> ReplacePart(ClipId(parts[1]))
                    "row" -> AddToRow(TrackId(parts[2]), parts[1].toLong().coerceAtLeast(0))
                    else -> null
                }
            }.getOrNull()
    }
}

/**
 * The editor: the open project, its layered preview, the edits made on the
 * timeline (each one an undo step, see [EditSession]) and the photos and
 * videos taken in from the gallery for "+", Replace and Add. It owns the
 * preview's players, so a rotation keeps playing and only the surfaces are
 * reattached; the playhead and whether it was playing are kept in the saved
 * state for a process death, as are links picked but not yet taken in.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        @param:ApplicationContext private val context: Context,
        private val repository: ProjectRepository,
        private val projectMedia: ProjectMedia,
        private val mediaAccess: MediaAccess,
        private val probe: MediaFactsProbe,
    ) : ViewModel() {
        val projectId: ProjectId = ProjectId(requireNotNull(savedState.get<String>(Routes.ARG_PROJECT)))

        private val uiState = MutableStateFlow(EditorUi())
        val ui: StateFlow<EditorUi> = uiState.asStateFlow()

        val engine = LayeredPreviewEngine(context, viewModelScope)
        val stills = StillLoader(context)

        /** The timeline's thumbnail strips: many small pictures. */
        private val strips = StillLoader(context, maxSide = STRIP_SIDE, cacheSize = STRIP_CACHE)

        private val session = EditSession()

        /** Photos and videos being taken in for "+", Replace or Add. */
        val intake = MediaIntake(mediaAccess, viewModelScope)

        private val draftState = MutableStateFlow<DraftState>(DraftState.Idle)
        val draft: StateFlow<DraftState> = draftState.asStateFlow()
        private var draftJob: Job? = null

        private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)
        val events: Flow<EditorEvent> = eventChannel.receiveAsFlow()

        /** The notes the editor shows once on arrival: items that could not be added, "We brought back your last video". */
        var arrivalNotes: ArrivalNotes? =
            if (savedState.get<Boolean>(KEY_NOTES_SHOWN) == true) {
                null
            } else {
                ArrivalNotes(
                    skipped = savedState.get<Int>(Routes.ARG_SKIPPED) ?: 0,
                    broughtBack = savedState.get<Boolean>(Routes.ARG_BROUGHT_BACK) ?: false,
                )
            }
            private set

        /** The notes are shown once: not again after a rotation (this view model lives on) or a process death. */
        fun notesShown() {
            arrivalNotes = null
            savedState[KEY_NOTES_SHOWN] = true
        }

        var resolver: MediaResolver? = null
            private set
        private var media: ProjectMediaSet? = null
        private var firstLoad = true

        /** The project the preview was last worked out for. */
        private var lastLoaded: Project? = null
        private var loadedFiles: Map<PresetSlot, File?>? = null
        private var access: MediaAccessState? = null

        init {
            viewModelScope.launch {
                val opened = repository.open(projectId)
                if (opened !is OpenResult.Opened) {
                    uiState.update { it.copy(failed = true) }
                    return@launch
                }
                repository.open
                    .filterNotNull()
                    .filter { it.id == projectId }
                    .distinctUntilChanged()
                    .collect {
                        session.adopt(it)
                        load(it)
                    }
            }
            // The playhead goes into the saved state, for a process death.
            viewModelScope.launch {
                while (isActive) {
                    delay(SAVE_POSITION_MS)
                    if (engine.schedule != null) {
                        savedState[KEY_POSITION] = engine.positionUs
                        savedState[KEY_PLAYING] = engine.isPlaying
                    }
                }
            }
        }

        /** Re-reads the preset files and the media: after the repair screen, or when access changed. */
        fun refresh(access: MediaAccessState) {
            this.access = access
            // Links picked before a process death are taken in again, once.
            if (!resumed) {
                resumed = true
                val purpose = savedState.get<String>(KEY_INTAKE_PURPOSE)?.let(IntakePurpose::decode)
                val uris = savedState.get<ArrayList<String>>(KEY_INTAKE_URIS)
                if (purpose != null && uris != null && intake.state.value == IntakeState.Idle) {
                    intakePurpose = purpose
                    intake.start(projectId, uris.map { it.toUri() }, access)
                }
            }
            val project = uiState.value.project ?: return
            viewModelScope.launch { load(project) }
        }

        private var resumed = false
        private var intakePurpose: IntakePurpose? = null

        init {
            viewModelScope.launch {
                intake.state.collect { state ->
                    when (state) {
                        is IntakeState.Done -> {
                            intake.consumed()
                            finishIntake(state.items)
                        }

                        IntakeState.Cancelled, IntakeState.AllowAllRequested -> {
                            intake.consumed()
                            clearIntake()
                        }

                        IntakeState.OutOfSpace -> {
                            intake.consumed()
                            clearIntake()
                            eventChannel.send(EditorEvent.OutOfSpace)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
        }

        /**
         * Applies [change] to the project as one undo step, or, with a
         * [mergeKey], as part of the step the last edit with that key made.
         * False when it was refused (nothing changes).
         */
        fun edit(
            mergeKey: String? = null,
            change: Project.() -> Project,
        ): Boolean {
            val result = session.edit(mergeKey, change)
            val applied = result == EditResult.Applied
            if (applied) session.current?.let(::write)
            // The bar only offers edits that apply; one that is refused all the same is said, never silent.
            if (result is EditResult.Refused) viewModelScope.launch { eventChannel.send(EditorEvent.CantDoThat) }
            publishHistory()
            return applied
        }

        fun undo() {
            session.undo()?.let(::write)
            publishHistory()
        }

        /** Ends a run of merged edits (the caption editor closed with Done). */
        fun endMerge() = session.endMerge()

        /** Takes back the run of edits made with [mergeKey] (the caption editor's Cancel). */
        fun revertMerge(mergeKey: String): Boolean {
            val before = session.revertMerge(mergeKey)
            before?.let(::write)
            publishHistory()
            return before != null
        }

        fun redo() {
            session.redo()?.let(::write)
            publishHistory()
        }

        /**
         * Writes [next] and shows it at once: the timeline must not wait for the
         * autosave round trip (an item just put in is selected straight away,
         * and a selection is dropped only when its item is really gone). The
         * preview's schedule follows when the repository hands the project back.
         */
        private fun write(next: Project) {
            uiState.update { it.copy(project = next) }
            // Refused only when the video is no longer open (Back in flight): say it was not kept.
            if (!repository.update { next }) viewModelScope.launch { eventChannel.send(EditorEvent.NotSaved) }
        }

        private fun publishHistory() = uiState.update { it.copy(canUndo = session.canUndo, canRedo = session.canRedo) }

        /** A text or colour card at a "+" (section 11.6), 3 s long. */
        fun insertCard(
            anchor: ClipId,
            side: Side,
            text: String?,
            color: ArgbColor,
        ) {
            val card = CardClip(RandomIds.clipId(), Limits.DEFAULT_CARD_MS, color, text)
            if (edit { insertAt(main.id, anchor, side, card).withEffectDefaultsFor(listOf(card.id)) }) {
                viewModelScope.launch { eventChannel.send(EditorEvent.Inserted(card.id)) }
            }
        }

        /** Takes in [uris] picked in the gallery for [purpose]. Nothing picked does nothing. */
        fun takeIn(
            purpose: IntakePurpose,
            uris: List<Uri>,
            access: MediaAccessState,
        ) {
            if (uris.isEmpty()) return
            intakePurpose = purpose
            savedState[KEY_INTAKE_PURPOSE] = purpose.encode()
            savedState[KEY_INTAKE_URIS] = ArrayList(uris.map { it.toString() })
            intake.start(projectId, uris, access)
        }

        private fun clearIntake() {
            intakePurpose = null
            savedState.remove<String>(KEY_INTAKE_PURPOSE)
            savedState.remove<ArrayList<String>>(KEY_INTAKE_URIS)
        }

        private suspend fun finishIntake(items: List<IntakeItem>) {
            val purpose = intakePurpose
            val project = session.current
            clearIntake()
            if (purpose == null || project == null) return
            val timedRow =
                when (purpose) {
                    is IntakePurpose.Insert -> project.trackOf(purpose.anchor)?.takeIf { it.type.isTimed }
                    is IntakePurpose.AddToRow -> project.track(purpose.trackId)
                    else -> null
                }
            when {
                purpose is IntakePurpose.ReplacePart -> {
                    replacePart(purpose.clipId, items.firstOrNull(), project)
                }

                timedRow != null -> {
                    placeOnRow(purpose, timedRow, items, project)
                }

                purpose is IntakePurpose.AddToRow -> {
                    // The row went away while the files were being taken in.
                    Unit
                }

                else -> {
                    val made = newMainItems(projectId, items, project.photoDurationMs, project.defaultFit, probe)
                    if (made.skipped > 0) eventChannel.send(EditorEvent.Skipped(made.skipped))
                    val clips = made.clips
                    if (clips.isEmpty()) return
                    val applied =
                        when (purpose) {
                            IntakePurpose.Add -> {
                                edit { addToMain(clips).withEffectDefaultsFor(clips.map { it.id }) }
                            }

                            is IntakePurpose.Insert -> {
                                edit {
                                    var p = this
                                    var anchor = purpose.anchor
                                    var side = purpose.side
                                    for (c in clips) {
                                        p = p.insertAt(p.main.id, anchor, side, c)
                                        anchor = c.id
                                        side = Side.AFTER
                                    }
                                    p.withEffectDefaultsFor(clips.map { it.id })
                                }
                            }

                            is IntakePurpose.Replace -> {
                                edit { replaceMainItem(purpose.clipId, clips.first()) }
                            }

                            else -> {
                                false
                            }
                        }
                    if (applied) {
                        val first = if (purpose is IntakePurpose.Replace) purpose.clipId else clips.first().id
                        eventChannel.send(EditorEvent.Inserted(first))
                    }
                }
            }
        }

        /**
         * Picked files going on a row other than MAIN (section 11.6): at a "+"
         * (before or after its item, in order) or by "Add here" (at the
         * playhead, then one after another). Where that is taken, the model
         * puts an item on the highest free row of its kind, or a new one. A
         * file that is not what the row holds is left out and said.
         */
        private suspend fun placeOnRow(
            purpose: IntakePurpose,
            row: Track,
            items: List<IntakeItem>,
            project: Project,
        ) {
            val (clips, skipped) = timedClips(row, items, project)
            if (clips.isEmpty()) {
                eventChannel.send(
                    if (row.type == TrackType.VISUAL) EditorEvent.Skipped(skipped) else EditorEvent.WrongFile(wantedFor(row)),
                )
                return
            }
            if (skipped > 0) eventChannel.send(EditorEvent.Skipped(skipped))
            val applied =
                edit {
                    var p = this
                    when (purpose) {
                        is IntakePurpose.Insert -> {
                            // Before an item they go in backwards, each just before the last one placed, so they keep their order.
                            val ordered = if (purpose.side == Side.BEFORE) clips.reversed() else clips
                            var anchor = purpose.anchor
                            for (c in ordered) {
                                p = p.insertAt(requireNotNull(p.trackOf(anchor)).id, anchor, purpose.side, c)
                                anchor = c.id
                            }
                        }

                        is IntakePurpose.AddToRow -> {
                            p = p.addToTrack(row.id, clips.first(), purpose.atMs)
                            var anchor = clips.first().id
                            for (c in clips.drop(1)) {
                                p = p.insertAt(requireNotNull(p.trackOf(anchor)).id, anchor, Side.AFTER, c)
                                anchor = c.id
                            }
                        }

                        else -> {
                            Unit
                        }
                    }
                    p
                }
            if (applied) {
                val first = if (purpose is IntakePurpose.Insert && purpose.side == Side.BEFORE) clips.last() else clips.first()
                eventChannel.send(EditorEvent.Inserted(first.id))
            }
        }

        private fun wantedFor(row: Track): WantedFile =
            when (row.type) {
                TrackType.OVERLAY -> WantedFile.OVERLAY
                TrackType.AUDIO -> WantedFile.MUSIC
                else -> WantedFile.PHOTO_OR_VIDEO
            }

        /** The items a row of [row]'s kind can hold from [items], and how many were left out. */
        private suspend fun timedClips(
            row: Track,
            items: List<IntakeItem>,
            project: Project,
        ): Pair<List<Clip>, Int> {
            if (row.type == TrackType.VISUAL) {
                val made = newMainItems(projectId, items, project.photoDurationMs, project.defaultFit, probe)
                val pips = made.clips.filterIsInstance<MediaClip>().map { it.withPip(PipPlacement.DEFAULT) }
                return pips to made.skipped + (made.clips.size - pips.size)
            }
            val out = mutableListOf<Clip>()
            for (item in items) {
                val ref = item.ref ?: continue
                val facts = probe.probe(projectId, ref, item.info?.mime) ?: continue
                val ms = facts.durationMs?.takeIf { it in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS } ?: continue
                out +=
                    when {
                        row.type == TrackType.AUDIO && facts.hasAudio -> {
                            AudioClip(RandomIds.clipId(), ref, startMs = 0, durationMs = ms, sourceDurationMs = ms)
                        }

                        row.type == TrackType.OVERLAY && facts.hasVideo && !facts.isImage -> {
                            OverlayClip(RandomIds.clipId(), ref, 0, ms, ms, key = overlayKey(), repeat = false)
                        }

                        else -> {
                            continue
                        }
                    }
            }
            return out to items.size - out.size
        }

        /** An extra overlay is keyed like the preset's frame, or by the usual green without a preset. */
        private fun overlayKey(): KeySpec =
            uiState.value.preset
                ?.frame
                ?.key ?: KeySpec(DEFAULT_KEY_GREEN, FrameSpec.DEFAULT_TOLERANCE, FrameSpec.DEFAULT_SOFTNESS)

        private suspend fun replacePart(
            clipId: ClipId,
            item: IntakeItem?,
            project: Project,
        ) {
            val part = project.mainClips.firstOrNull { it.id == clipId } ?: return
            val wantSound = part is EndingClip && part.kind == EndingKind.AUDIO
            val ref = item?.ref
            val facts = ref?.let { probe.probe(projectId, it, item.info?.mime) }
            val ms = facts?.durationMs
            val fits = facts != null && (if (wantSound) facts.hasAudio else facts.hasVideo)
            if (ref == null || !fits || ms == null || ms !in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) {
                eventChannel.send(EditorEvent.WrongFile(if (wantSound) WantedFile.SOUND else WantedFile.VIDEO))
                return
            }
            edit { replacePresetPart(clipId, ref, ms) }
        }

        /** What Play plays for the intro or the ending: its file, or null when it can't be opened. */
        fun playable(clipId: ClipId): Uri? {
            val clip = session.current?.mainClips?.firstOrNull { it.id == clipId } ?: return null
            val ref =
                when (clip) {
                    is IntroClip -> clip.media
                    is EndingClip -> clip.media
                    else -> return null
                }
            return runCatching { resolver?.resolve(ref) }.getOrNull()
        }

        /** Whether Play for [clipId] plays sound only (a spoken ending). */
        fun isSoundPart(clipId: ClipId): Boolean =
            (session.current?.mainClips?.firstOrNull { it.id == clipId } as? EndingClip)?.kind == EndingKind.AUDIO

        /** A picture for a thumbnail strip: [clip] at [sourceMs] of its file. */
        suspend fun stripFrame(
            clip: MainClip,
            sourceMs: Long,
        ): Bitmap? {
            val r = resolver ?: return null
            val source =
                when (clip) {
                    is PhotoClip -> Source.Photo(clip.media)
                    is VideoClip -> Source.VideoFrame(clip.media, sourceMs * 1000)
                    is IntroClip -> Source.VideoFrame(clip.media, sourceMs * 1000)
                    is EndingClip -> if (clip.kind == EndingKind.VIDEO) Source.VideoFrame(clip.media, sourceMs * 1000) else return null
                    else -> return null
                }
            return strips.load(source, r)
        }

        /** One load at a time: refresh() and the project's own updates never overlap. */
        private val loading = Mutex()

        private suspend fun load(project: Project) = loading.withLock { loadNow(project) }

        private suspend fun loadNow(project: Project) {
            // Only captions changed (a word typed, a colour picked, the box dragged): the files and the
            // media's state are as they were, so the new schedule is worked out straight away, without
            // looking anything up, and the players carry on.
            val cached = media
            val last = lastLoaded
            // (The same project again is a refresh, which must look the files up: never this way.)
            if (!firstLoad && cached != null && last != null && project != last && project.withoutCaptions() == last.withoutCaptions()) {
                val quick =
                    runCatching {
                        PreviewSchedule.of(
                            RenderPlanBuilder(cached.preset, cached.loudness).build(project, height = PREVIEW_HEIGHT),
                        )
                    }.getOrNull()
                if (quick != null && engine.updateCaptions(quick)) {
                    lastLoaded = project
                    uiState.update { it.copy(project = session.current ?: project, schedule = quick) }
                    // A picker asked to play the change (a caption's animation): play it now.
                    pendingPreviewUs?.let { at ->
                        pendingPreviewUs = null
                        previewFallback?.cancel()
                        engine.seekTo(at)
                        engine.play()
                    }
                    return
                }
            }
            lastLoaded = project
            val set = projectMedia.forProject(project)
            media = set
            resolver = set.resolver
            val schedule =
                runCatching { PreviewSchedule.of(RenderPlanBuilder(set.preset, set.loudness).build(project, height = PREVIEW_HEIGHT)) }
                    .getOrNull()
            val missing = access?.let { mediaAccess.missing(project, it) }.orEmpty()
            uiState.update {
                it.copy(
                    // An edit made while the files were being looked up is newer than [project]: keep it.
                    project = session.current ?: project,
                    preset = set.preset,
                    presetNeedsRepair = set.presetStatus?.needsRepair?.isNotEmpty() == true,
                    missing = missing,
                    schedule = schedule,
                )
            }
            if (schedule == null) return
            // The same preview of the same files again (the screen came back): nothing to reload.
            // A repaired preset file is a change even though the plan is the same.
            val files = set.presetStatus?.slots?.mapValues { (_, s) -> (s as? SlotStatus.Ready)?.binding?.file }
            if (!firstLoad && schedule == engine.schedule && files == loadedFiles) return
            // Only captions changed (typing, styling, placing one): the players carry on as they are.
            if (!firstLoad && files == loadedFiles && engine.updateCaptions(schedule)) return
            loadedFiles = files
            val start: Long
            val play: Boolean
            val asked = pendingPreviewUs
            if (asked != null && !firstLoad) {
                pendingPreviewUs = null
                previewFallback?.cancel()
                start = asked
                play = true
            } else if (firstLoad) {
                firstLoad = false
                start = savedState.get<Long>(KEY_POSITION) ?: 0L
                play = savedState.get<Boolean>(KEY_PLAYING) ?: false
            } else {
                start = engine.positionUs
                play = engine.isPlaying
            }
            engine.load(schedule, set.resolver, start, play)
        }

        fun togglePlay() = engine.togglePlay()

        /** Where the preview plays from once it has the video just edited ([previewFrom]), or null. */
        private var pendingPreviewUs: Long? = null
        private var previewFallback: Job? = null

        /**
         * Plays the preview from [us] as soon as it has the edit just made (a
         * transition or animation chosen in a picker), so the change shows
         * straight away. If the edit changed nothing the preview reloads, it
         * plays from there a moment later all the same.
         */
        fun previewFrom(us: Long) {
            pendingPreviewUs = us
            previewFallback?.cancel()
            previewFallback =
                viewModelScope.launch {
                    delay(PREVIEW_FALLBACK_MS)
                    pendingPreviewUs?.let { at ->
                        pendingPreviewUs = null
                        engine.seekTo(at)
                        engine.play()
                    }
                }
        }

        fun pause() = engine.pause()

        fun seekTo(us: Long) = engine.seekTo(us)

        /** Drops any intake in flight and its saved links (the screen is leaving). */
        override fun onCleared() {
            intake.cancel()
            draftJob?.cancel()
            engine.release()
        }

        /** Renders the exact draft around the playhead. */
        fun requestDraft() {
            if (draftJob?.isActive == true) return
            val project = uiState.value.project ?: return
            val set = media ?: return
            engine.pause()
            val at = engine.positionUs
            draftState.value = DraftState.Preparing(0)
            draftJob =
                viewModelScope.launch {
                    val plan =
                        runCatching { RenderPlanBuilder(set.preset, set.loudness).build(project, height = ExactDraft.HEIGHT) }.getOrNull()
                    if (plan == null || plan.durationUs <= 0) {
                        draftState.value = DraftState.Failed
                        return@launch
                    }
                    draftState.value =
                        when (
                            val r =
                                ExactDraft(
                                    context,
                                ).render(plan, at, set.resolver) { p -> draftState.value = DraftState.Preparing(p) }
                        ) {
                            is ExactDraft.Result.Done -> DraftState.Ready(r.file)
                            ExactDraft.Result.Failed -> DraftState.Failed
                        }
                }
        }

        fun cancelDraft() {
            draftJob?.cancel()
            draftState.value = DraftState.Idle
        }

        fun closeDraft() {
            draftState.value = DraftState.Idle
        }

        /** Back: writes the project and leaves the editor. */
        fun close() {
            engine.pause()
            viewModelScope.launch {
                eventChannel.send(if (repository.close()) EditorEvent.Closed else EditorEvent.NotSaved)
            }
        }

        data class ArrivalNotes(
            val skipped: Int,
            val broughtBack: Boolean,
        )

        companion object {
            /** The preview's plan height; only bitmaps depend on it, the players play the files as they are. */
            const val PREVIEW_HEIGHT = 720

            /** How long [previewFrom] waits for the edited video before it plays what it has. */
            const val PREVIEW_FALLBACK_MS = 1_500L
            const val STRIP_SIDE = 160
            const val STRIP_CACHE = 96
            const val KEY_INTAKE_PURPOSE = "editor_intake_purpose"
            const val KEY_INTAKE_URIS = "editor_intake_uris"
            const val SAVE_POSITION_MS = 500L
            const val KEY_POSITION = "editor_position_us"
            const val KEY_PLAYING = "editor_playing"
            const val KEY_NOTES_SHOWN = "editor_notes_shown"

            /** The green an extra overlay's window is keyed by when there is no preset frame to copy. */
            val DEFAULT_KEY_GREEN = ArgbColor(0xFF00D922)
        }
    }

/** The project without its caption rows: what the preview's players depend on. */
internal fun Project.withoutCaptions(): Project {
    val text = tracks.filter { it.type == TrackType.TEXT }.map { it.id }.toSet()
    return copy(tracks = tracks.filterNot { it.id in text }, stacking = stacking.filterNot { it in text })
}
