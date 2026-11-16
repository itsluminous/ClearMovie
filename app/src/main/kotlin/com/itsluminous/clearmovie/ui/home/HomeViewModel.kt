package com.itsluminous.clearmovie.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.NewVideoResult
import com.itsluminous.clearmovie.data.project.NewVideos
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.project.ProjectSummary
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.ui.navigation.EntryMode
import com.itsluminous.clearmovie.ui.navigation.FlowStep
import com.itsluminous.clearmovie.ui.navigation.NewVideoFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One preset on the home screen. */
data class PresetCard(
    val preset: Preset,
    val state: State,
    /** A picture from the preset's intro (or frame), when it is set up. */
    val thumbnail: Bitmap? = null,
) {
    sealed interface State {
        data object Ready : State

        /** Never set up: the setup screen comes first. */
        data object NeedsSetup : State

        /** [count] files were moved, deleted or changed: "tap to fix". */
        data class Missing(
            val count: Int,
        ) : State
    }

    companion object {
        fun stateOf(status: PresetStatus): State =
            when {
                status.needsRepair.isNotEmpty() -> State.Missing(status.needsRepair.size)
                !status.isReady -> State.NeedsSetup
                else -> State.Ready
            }
    }
}

sealed interface HomeEvent {
    data class Go(
        val step: FlowStep,
    ) : HomeEvent

    /** The new draft could not be written: the phone is full. */
    data object NotSaved : HomeEvent
}

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val presetStore: PresetStore,
        private val newVideos: NewVideos,
        repository: ProjectRepository,
    ) : ViewModel() {
        private val presetsState = MutableStateFlow<List<PresetCard>?>(null)

        /** Null until the presets are read. */
        val presets: StateFlow<List<PresetCard>?> = presetsState.asStateFlow()

        val videos: StateFlow<List<ProjectSummary>> =
            repository.projects.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        private val busyState = MutableStateFlow(false)

        /** True while a new video is being made, so a second tap does nothing. */
        val busy: StateFlow<Boolean> = busyState.asStateFlow()

        private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
        val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

        /** Reads the presets again: called whenever the screen shows, as a file may have been set up or lost. */
        fun refresh() {
            viewModelScope.launch {
                presetsState.value =
                    presetStore.definitions().map { def ->
                        val status = presetStore.status(def.preset.id)
                        val old = presetsState.value?.firstOrNull { it.preset.id == def.preset.id }
                        // The intro shows what the preset is (a channel's logo, say); the frame's own picture is mostly green.
                        val picture =
                            listOf(
                                PresetSlot.INTRO,
                                PresetSlot.FRAME,
                            ).firstNotNullOfOrNull { (status.slots[it] as? SlotStatus.Ready)?.binding }
                        val thumb = old?.thumbnail ?: picture?.let { thumbnail(it.file.path) }
                        PresetCard(def.preset, PresetCard.stateOf(status), if (picture != null) thumb else null)
                    }
            }
        }

        fun startWithPreset(
            id: PresetId,
            mode: EntryMode = EntryMode.LAUNCHER,
        ) = start {
            when (val r = newVideos.fromPreset(id)) {
                is NewVideoResult.Created -> {
                    HomeEvent.Go(
                        NewVideoFlow.afterPresetChosen(mode, id, presetReady = true, project = r.projectId),
                    )
                }

                NewVideoResult.NeedsSetup -> {
                    HomeEvent.Go(NewVideoFlow.afterPresetChosen(mode, id, presetReady = false, project = null))
                }

                NewVideoResult.NotSaved -> {
                    HomeEvent.NotSaved
                }
            }
        }

        fun startBlank(mode: EntryMode = EntryMode.LAUNCHER) =
            start {
                when (val r = newVideos.blank()) {
                    is NewVideoResult.Created -> HomeEvent.Go(NewVideoFlow.afterBlankChosen(mode, r.projectId))
                    else -> HomeEvent.NotSaved
                }
            }

        private fun start(block: suspend () -> HomeEvent) {
            if (busyState.value) return
            busyState.value = true
            viewModelScope.launch {
                try {
                    eventChannel.send(block())
                } finally {
                    busyState.value = false
                }
            }
        }

        private suspend fun thumbnail(path: String): Bitmap? =
            withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(path)
                    r.getScaledFrameAtTime(THUMB_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIZE, THUMB_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { r.release() }
                }
            }

        private companion object {
            const val THUMB_AT_US = 3_000_000L
            const val THUMB_SIZE = 480
        }
    }
