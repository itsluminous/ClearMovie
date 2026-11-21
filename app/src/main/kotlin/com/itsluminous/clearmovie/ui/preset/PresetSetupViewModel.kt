package com.itsluminous.clearmovie.ui.preset

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.preset.DefinitionBindResult
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.data.project.NewVideoResult
import com.itsluminous.clearmovie.data.project.NewVideos
import com.itsluminous.clearmovie.data.project.ShareChoice
import com.itsluminous.clearmovie.data.project.ShareDrafts
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.navigation.EntryMode
import com.itsluminous.clearmovie.ui.navigation.FlowStep
import com.itsluminous.clearmovie.ui.navigation.NewVideoFlow
import com.itsluminous.clearmovie.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the setup screen shows while a file is being taken in. */
sealed interface SetupWork {
    /** [copied] of [total] bytes (null while the size is unknown). */
    data class Copying(
        val copied: Long,
        val total: Long?,
    ) : SetupWork

    /** Copied; its length, loudness and (for the frame) green are being measured. */
    data object Checking : SetupWork
}

/** A message after choosing a file. */
sealed interface SetupMessage {
    data object NotAVideo : SetupMessage

    data object NotASound : SetupMessage

    data object Unreadable : SetupMessage

    data class NoSpace(
        val neededBytes: Long,
        val freeBytes: Long,
    ) : SetupMessage

    data object DefinitionInvalid : SetupMessage

    data object DefinitionAdded : SetupMessage

    /** The preset file was for another preset (it was added there). */
    data object DefinitionOtherPreset : SetupMessage

    /** The new video could not be started (the phone is full). */
    data object NotSaved : SetupMessage

    /** The frame chosen is not the preset's shape (cm-aspect-ratio): it is kept, and will look stretched. */
    data object FrameShapeDiffers : SetupMessage
}

data class SetupUi(
    val preset: Preset? = null,
    val status: PresetStatus? = null,
    val work: SetupWork? = null,
    val message: SetupMessage? = null,
) {
    /** Setting up (a part not chosen yet, nothing to repair): the short guide shows until every part is chosen. */
    val firstTime: Boolean
        get() = status != null && status.needsRepair.isEmpty() && status.needsSetup.isNotEmpty()
}

sealed interface SetupEvent {
    data class Go(
        val step: FlowStep,
    ) : SetupEvent

    data object Back : SetupEvent
}

/**
 * Preset setup and repair (ARCHITECTURE section 11.1, R9): choose each file
 * once; it is copied into app storage with progress and checked. Opened for
 * a new video (Done then starts it) or from Settings (Done goes back).
 */
@HiltViewModel
class PresetSetupViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val presetStore: PresetStore,
        private val newVideos: NewVideos,
        private val shareDrafts: ShareDrafts,
    ) : ViewModel() {
        val presetId: PresetId = PresetId(requireNotNull(savedState.get<String>(Routes.ARG_PRESET)))
        val forNewVideo: Boolean = savedState.get<Boolean>(Routes.ARG_NEW_VIDEO) ?: false

        /** Set up from a share's preset pick: Done gives the share's draft this preset, then the editor (cm-share). */
        private val shareDraft: ProjectId? =
            savedState
                .get<String>(
                    Routes.ARG_SHARE_DRAFT,
                )?.let { runCatching { ProjectId(it) }.getOrNull() }

        private val uiState = MutableStateFlow(SetupUi())
        val ui: StateFlow<SetupUi> = uiState.asStateFlow()

        private val eventChannel = Channel<SetupEvent>(Channel.BUFFERED)
        val events: Flow<SetupEvent> = eventChannel.receiveAsFlow()

        private var job: Job? = null

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                val preset = presetStore.definition(presetId)?.preset
                uiState.update { it.copy(preset = preset, status = presetStore.status(presetId)) }
            }
        }

        /** The kinds of file the picker offers for [slot]. */
        fun mimeTypes(slot: PresetSlot): Array<String> =
            if (slot == PresetSlot.ENDING && uiState.value.preset
                    ?.ending
                    ?.kind == EndingKind.AUDIO
            ) {
                AUDIO
            } else {
                VIDEO
            }

        fun choose(
            slot: PresetSlot,
            uri: Uri,
        ) = run {
            uiState.update { it.copy(work = SetupWork.Copying(0, null), message = null) }
            val result =
                presetStore.bindSlot(presetId, slot, uri) { copied, total ->
                    uiState.update { s ->
                        s.copy(work = if (total != null && copied >= total) SetupWork.Checking else SetupWork.Copying(copied, total))
                    }
                }
            when (result) {
                is SlotBindResult.Bound -> {
                    if (result.frameShape != null) SetupMessage.FrameShapeDiffers else null
                }

                SlotBindResult.WrongKind -> {
                    if (mimeTypes(slot) === AUDIO) SetupMessage.NotASound else SetupMessage.NotAVideo
                }

                is SlotBindResult.NotEnoughSpace -> {
                    SetupMessage.NoSpace(result.neededBytes, result.freeBytes)
                }

                SlotBindResult.Unreadable, SlotBindResult.UnknownPreset, SlotBindResult.NotNeeded -> {
                    SetupMessage.Unreadable
                }
            }
        }

        fun chooseDefinition(uri: Uri) =
            run {
                uiState.update { it.copy(work = SetupWork.Checking, message = null) }
                when (val r = presetStore.bindDefinition(uri)) {
                    // A file for another preset is added to that one; say it is not this preset's.
                    is DefinitionBindResult.Bound -> {
                        if (r.preset.id ==
                            presetId
                        ) {
                            SetupMessage.DefinitionAdded
                        } else {
                            SetupMessage.DefinitionOtherPreset
                        }
                    }

                    is DefinitionBindResult.Invalid -> {
                        SetupMessage.DefinitionInvalid
                    }

                    DefinitionBindResult.Unreadable -> {
                        SetupMessage.Unreadable
                    }
                }
            }

        /** Stops a copy; the earlier file (if any) stays and no partial copy is left. */
        fun cancel() {
            job?.cancel()
        }

        fun dismissMessage() = uiState.update { it.copy(message = null) }

        fun done() {
            if (job?.isActive == true) return
            if (!forNewVideo) {
                eventChannel.trySend(SetupEvent.Back)
                return
            }
            val share = shareDraft
            if (share != null) {
                job = viewModelScope.launch { doneForShare(share) }
                return
            }
            job =
                viewModelScope.launch {
                    when (val r = newVideos.fromPreset(presetId)) {
                        is NewVideoResult.Created -> {
                            eventChannel.send(
                                SetupEvent.Go(NewVideoFlow.afterSetup(EntryMode.LAUNCHER, r.projectId)),
                            )
                        }

                        NewVideoResult.NeedsSetup -> {
                            refresh()
                        }

                        NewVideoResult.NotSaved -> {
                            uiState.update { it.copy(message = SetupMessage.NotSaved) }
                        }
                    }
                }
        }

        private suspend fun doneForShare(draft: ProjectId) {
            when (val r = shareDrafts.choose(draft, presetId)) {
                is ShareChoice.Ready -> eventChannel.send(SetupEvent.Go(NewVideoFlow.afterSetup(EntryMode.MEDIA_CHOSEN, r.projectId)))

                ShareChoice.NeedsSetup -> refresh()

                ShareChoice.NotSaved -> uiState.update { it.copy(message = SetupMessage.NotSaved) }

                // The shared items' video was deleted elsewhere: nothing to finish.
                ShareChoice.Gone -> eventChannel.send(SetupEvent.Back)
            }
        }

        private fun run(block: suspend () -> SetupMessage?) {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    try {
                        val message = block()
                        uiState.update { it.copy(message = message) }
                    } finally {
                        withContext(NonCancellable) {
                            val preset = presetStore.definition(presetId)?.preset
                            val status = presetStore.status(presetId)
                            uiState.update { it.copy(work = null, preset = preset ?: it.preset, status = status) }
                        }
                    }
                }
        }

        companion object {
            val VIDEO = arrayOf("video/*")
            val AUDIO = arrayOf("audio/*")

            /** A preset.json may come as JSON, plain text or an unknown type, depending on the file manager. */
            val DEFINITION = arrayOf("application/json", "text/plain", "application/octet-stream")
        }
    }
