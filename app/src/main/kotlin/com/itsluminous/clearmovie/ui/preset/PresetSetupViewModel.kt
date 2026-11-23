package com.itsluminous.clearmovie.ui.preset

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.preset.CopyReason
import com.itsluminous.clearmovie.data.preset.DefinitionBindResult
import com.itsluminous.clearmovie.data.preset.FrameSample
import com.itsluminous.clearmovie.data.preset.FrameStills
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetSaveResult
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.NewVideoResult
import com.itsluminous.clearmovie.data.project.NewVideos
import com.itsluminous.clearmovie.data.project.ShareChoice
import com.itsluminous.clearmovie.data.project.ShareDrafts
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.frame.FRAME_LOOK_STILL_WIDTH
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
    /**
     * [copied] of [total] bytes (null while the size is unknown). [reason]
     * is set when the preset uses its files where they are but this one
     * could not be, so the screen says why it is copied.
     */
    data class Copying(
        val copied: Long,
        val total: Long?,
        val reason: CopyReason? = null,
    ) : SetupWork

    /**
     * Its length, loudness and (for the frame) green are being measured:
     * a file used where it is from the start (nothing is copied), a copy
     * once it is in.
     */
    data object Checking : SetupWork

    companion object {
        /** The work shown for a copy's progress: [Checking] once every byte is in. */
        fun copying(
            copied: Long,
            total: Long?,
            reason: CopyReason?,
        ): SetupWork = if (total != null && copied >= total) Checking else Copying(copied, total, reason)
    }
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

    /** The video chosen is not the preset's shape (cm-aspect-ratio): it is kept, and will look stretched. */
    data object ShapeDiffers : SetupMessage

    /** The preset uses its files where they are, but this one was copied, for [reason] (cm-preset-link-copy). */
    data class KeptCopy(
        val reason: CopyReason,
    ) : SetupMessage

    companion object {
        /**
         * The message after a file is bound: a shape that differs matters
         * more than a copy (the copy was explained while it ran).
         */
        fun afterBound(
            result: SlotBindResult.Bound,
            copyReason: CopyReason?,
        ): SetupMessage? =
            when {
                result.fileShape != null -> ShapeDiffers
                copyReason != null && !result.binding.isLinked -> KeptCopy(copyReason)
                else -> null
            }
    }
}

data class SetupUi(
    val preset: Preset? = null,
    val status: PresetStatus? = null,
    val work: SetupWork? = null,
    val message: SetupMessage? = null,
    /** The frame's look is being set (cm-frame-area): asked when the frame is chosen, and from its row. */
    val frameLook: Boolean = false,
    /** The look was opened because the frame file was just chosen: its area then starts from the picked colour. */
    val frameJustChosen: Boolean = false,
    /** Whether the files chosen are copied into the app (else used where they are). */
    val copiesFiles: Boolean = true,
) {
    /** The frame file, when it is chosen and ready. */
    val frameBinding: PresetBinding? get() = (status?.slots?.get(PresetSlot.FRAME) as? SlotStatus.Ready)?.binding

    /** The colour the frame keys out now: the one it was set to, or the green measured in its file. */
    val frameKey: KeySpec?
        get() {
            val key = preset?.frame?.key ?: return null
            return frameBinding?.keyColor?.let { key.copy(color = it) } ?: key
        }

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
 * once; it is used where it is or copied into app storage (with progress),
 * as the preset says, and checked. Opened for
 * a new video (Done then starts it) or from Settings (Done goes back).
 */
@HiltViewModel
class PresetSetupViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val presetStore: PresetStore,
        private val newVideos: NewVideos,
        private val shareDrafts: ShareDrafts,
        private val frameStills: FrameStills,
    ) : ViewModel() {
        val presetId: PresetId = PresetId(requireNotNull(savedState.get<String>(Routes.ARG_PRESET)))
        val forNewVideo: Boolean = savedState.get<Boolean>(Routes.ARG_NEW_VIDEO) ?: false

        /** Set up from a share's preset pick: Done gives the share's draft this preset, then the editor (cm-share). */
        private val shareDraft: ProjectId? =
            savedState
                .get<String>(
                    Routes.ARG_SHARE_DRAFT,
                )?.let { runCatching { ProjectId(it) }.getOrNull() }

        private val uiState =
            MutableStateFlow(
                SetupUi(frameLook = savedState[KEY_FRAME_LOOK] ?: false, frameJustChosen = savedState[KEY_FRAME_NEW] ?: false),
            )
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
                val copies = presetStore.copiesFiles(presetId)
                uiState.update { it.copy(preset = preset, status = presetStore.status(presetId), copiesFiles = copies) }
            }
        }

        /** Opens the frame's look (its colour and picture area), once its file is ready. */
        fun openFrameLook() = setFrameLook(true, justChosen = false)

        fun closeFrameLook() = setFrameLook(false)

        private fun setFrameLook(
            open: Boolean,
            justChosen: Boolean = false,
        ) {
            savedState[KEY_FRAME_LOOK] = open
            savedState[KEY_FRAME_NEW] = open && justChosen
            uiState.update { it.copy(frameLook = open, frameJustChosen = open && justChosen) }
        }

        /** A picture of the frame file at [atUs], for its look. */
        suspend fun frameStill(atUs: Long): FrameSample? {
            val binding = uiState.value.frameBinding ?: return null
            return frameStills.still(binding.uri, atUs.coerceIn(0, binding.durationUs), FRAME_LOOK_STILL_WIDTH)
        }

        /** Keeps the frame's look for this preset: every new video from it starts with it. */
        fun saveFrameLook(
            key: KeySpec,
            area: NormRect,
        ) {
            if (job?.isActive == true) return
            closeFrameLook()
            run {
                when (presetStore.setFrameLook(presetId, key, area)) {
                    is PresetSaveResult.Saved -> null
                    PresetSaveResult.Invalid, PresetSaveResult.NotSaved -> SetupMessage.NotSaved
                }
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
            // "Checking" until the store says a copy starts: a file used where it is is never copied (cm-preset-link-copy).
            uiState.update { it.copy(work = SetupWork.Checking, message = null) }
            var copyReason: CopyReason? = null
            val result =
                presetStore.bindSlot(
                    presetId,
                    slot,
                    uri,
                    onCopyStart = { reason ->
                        copyReason = reason
                        uiState.update { it.copy(work = SetupWork.Copying(0, null, reason)) }
                    },
                ) { copied, total ->
                    uiState.update { it.copy(work = SetupWork.copying(copied, total, copyReason)) }
                }
            when (result) {
                is SlotBindResult.Bound -> {
                    // A frame just chosen asks once which colour to remove and where photos go (cm-frame-area).
                    if (slot == PresetSlot.FRAME) setFrameLook(true, justChosen = true)
                    SetupMessage.afterBound(result, copyReason)
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
            private const val KEY_FRAME_LOOK = "frameLook"
            private const val KEY_FRAME_NEW = "frameJustChosen"
            val VIDEO = arrayOf("video/*")
            val AUDIO = arrayOf("audio/*")

            /** A preset.json may come as JSON, plain text or an unknown type, depending on the file manager. */
            val DEFINITION = arrayOf("application/json", "text/plain", "application/octet-stream")
        }
    }
