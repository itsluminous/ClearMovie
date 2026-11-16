package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.OpenResult
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.data.project.ProjectMediaSet
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.Source
import com.itsluminous.clearmovie.render.preview.ExactDraft
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import com.itsluminous.clearmovie.render.preview.StillLoader
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
}

/**
 * The editor shell (cm-flow): the open project, its layered preview and the
 * read-only MAIN row. It owns the preview's players, so a rotation keeps
 * playing and only the surfaces are reattached; the playhead and whether it
 * was playing are kept in the saved state for a process death.
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
    ) : ViewModel() {
        val projectId: ProjectId = ProjectId(requireNotNull(savedState.get<String>(Routes.ARG_PROJECT)))

        private val uiState = MutableStateFlow(EditorUi())
        val ui: StateFlow<EditorUi> = uiState.asStateFlow()

        val engine = LayeredPreviewEngine(context, viewModelScope)
        val stills = StillLoader(context)
        private val thumbs = StillLoader(context, maxSide = THUMB_SIDE)

        private val draftState = MutableStateFlow<DraftState>(DraftState.Idle)
        val draft: StateFlow<DraftState> = draftState.asStateFlow()
        private var draftJob: Job? = null

        private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)
        val events: Flow<EditorEvent> = eventChannel.receiveAsFlow()

        /** The notes the editor shows once on arrival: items that could not be added, "We brought back your last video". */
        val arrivalNotes: ArrivalNotes? =
            if (savedState.get<Boolean>(KEY_NOTES_SHOWN) == true) {
                null
            } else {
                ArrivalNotes(
                    skipped = savedState.get<Int>(Routes.ARG_SKIPPED) ?: 0,
                    broughtBack = savedState.get<Boolean>(Routes.ARG_BROUGHT_BACK) ?: false,
                )
            }

        fun notesShown() {
            savedState[KEY_NOTES_SHOWN] = true
        }

        var resolver: MediaResolver? = null
            private set
        private var media: ProjectMediaSet? = null
        private var firstLoad = true
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
                    .collect { load(it) }
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
            val project = uiState.value.project ?: return
            viewModelScope.launch { load(project) }
        }

        private suspend fun load(project: Project) {
            val set = projectMedia.forProject(project)
            media = set
            resolver = set.resolver
            val schedule =
                runCatching { PreviewSchedule.of(RenderPlanBuilder(set.preset, set.loudness).build(project, height = PREVIEW_HEIGHT)) }
                    .getOrNull()
            val missing = access?.let { mediaAccess.missing(project, it) }.orEmpty()
            uiState.update {
                it.copy(
                    project = project,
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
            loadedFiles = files
            val start: Long
            val play: Boolean
            if (firstLoad) {
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

        fun pause() = engine.pause()

        fun seekTo(us: Long) = engine.seekTo(us)

        /** A picture for a MAIN item's tile, or null. */
        suspend fun thumbnail(slot: MainSlot): Bitmap? {
            val r = resolver ?: return null
            val source =
                when (val c = slot.clip) {
                    is PhotoClip -> {
                        Source.Photo(c.media)
                    }

                    is VideoClip -> {
                        Source.VideoFrame(
                            c.media,
                            (c.trimStartMs + THUMB_AT_MS.coerceAtMost(c.trimEndMs - c.trimStartMs - 1)) * 1000,
                        )
                    }

                    is IntroClip -> {
                        Source.VideoFrame(c.media, THUMB_AT_MS * 1000)
                    }

                    is EndingClip -> {
                        if (c.kind == EndingKind.VIDEO) Source.VideoFrame(c.media, 0) else return null
                    }

                    else -> {
                        return null
                    }
                }
            return thumbs.load(source, r)
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

        override fun onCleared() {
            draftJob?.cancel()
            engine.release()
        }

        data class ArrivalNotes(
            val skipped: Int,
            val broughtBack: Boolean,
        )

        companion object {
            /** The preview's plan height; only bitmaps depend on it, the players play the files as they are. */
            const val PREVIEW_HEIGHT = 720
            const val THUMB_SIDE = 320
            const val THUMB_AT_MS = 1000L
            const val SAVE_POSITION_MS = 500L
            const val KEY_POSITION = "editor_position_us"
            const val KEY_PLAYING = "editor_playing"
            const val KEY_NOTES_SHOWN = "editor_notes_shown"
        }
    }
