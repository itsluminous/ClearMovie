package com.itsluminous.clearmovie.ui.preset

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.IntakeItem
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.preset.FrameKeyCheck
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.OpenResult
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PartChoice
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetChoices
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.TextRules
import com.itsluminous.clearmovie.domain.model.withPresetChoices
import com.itsluminous.clearmovie.ui.media.IntakeState
import com.itsluminous.clearmovie.ui.media.MediaIntake
import com.itsluminous.clearmovie.ui.navigation.FlowStep
import com.itsluminous.clearmovie.ui.navigation.NewVideoFlow
import com.itsluminous.clearmovie.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class ReviewUi(
    val preset: Preset? = null,
    val bound: PresetMedia = PresetMedia(),
    val choices: PresetChoices = PresetChoices(),
    /** The part a file is being taken in for, while it is. */
    val replacing: PresetSlot? = null,
    val message: ReviewMessage? = null,
) {
    /** The parts this preset has, in the order a video plays them. */
    val parts: List<PresetSlot>
        get() =
            listOfNotNull(
                PresetSlot.INTRO.takeIf { preset?.intro != null },
                PresetSlot.FRAME.takeIf { preset?.frame != null },
                PresetSlot.ENDING.takeIf { preset?.ending != null },
            )

    val spokenEnding: Boolean get() = preset?.ending?.kind == EndingKind.AUDIO
}

sealed interface ReviewMessage {
    /** The chosen file is not what the part needs (a video, or a sound for a spoken ending). */
    data class WrongKind(
        val slot: PresetSlot,
    ) : ReviewMessage

    /** It could not be opened or kept. */
    data object Unreadable : ReviewMessage

    /** A frame of the user's own with no flat green where the preset's window is. */
    data object NoGreenWindow : ReviewMessage
}

sealed interface ReviewEvent {
    data class Go(
        val step: FlowStep,
    ) : ReviewEvent

    data object Back : ReviewEvent
}

/**
 * Preset review (ARCHITECTURE section 11.1): the intro, frame and ending of
 * the new video, each with Play, Replace and Skip for this video, and Next.
 * The draft already exists (made when the preset was picked); every choice
 * rebuilds it from the preset's track template, so a choice can be undone,
 * and is kept in the saved state as well, so a rotation or a process death
 * shows the same choices. Back leaves the flow and deletes the draft, which
 * holds nothing of the user's yet.
 */
@HiltViewModel
class PresetReviewViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val repository: ProjectRepository,
        private val presetStore: PresetStore,
        private val mediaAccess: MediaAccess,
        private val probe: MediaFactsProbe,
        private val files: AppFiles,
        private val frameKey: FrameKeyCheck,
    ) : ViewModel() {
        val projectId: ProjectId = ProjectId(requireNotNull(savedState.get<String>(Routes.ARG_PROJECT)))

        private val uiState =
            MutableStateFlow(ReviewUi(choices = savedState.get<String>(KEY_CHOICES)?.let(::decodeChoices) ?: PresetChoices()))
        val ui: StateFlow<ReviewUi> = uiState.asStateFlow()

        private val eventChannel = Channel<ReviewEvent>(Channel.BUFFERED)
        val events: Flow<ReviewEvent> = eventChannel.receiveAsFlow()

        val intake = MediaIntake(mediaAccess, viewModelScope)

        init {
            viewModelScope.launch {
                val opened = repository.open(projectId, inEditor = false) as? OpenResult.Opened
                val presetId = opened?.project?.presetId
                val preset = presetId?.let { presetStore.effectivePreset(it) }
                if (preset == null) {
                    eventChannel.send(ReviewEvent.Back)
                    return@launch
                }
                uiState.update { it.copy(preset = preset, bound = presetStore.presetMedia(presetId)) }
                apply()
            }
            viewModelScope.launch {
                intake.state.collect { state ->
                    val slot = uiState.value.replacing ?: return@collect
                    when (state) {
                        is IntakeState.Done -> {
                            intake.consumed()
                            finishReplace(slot, state.items.firstOrNull())
                        }

                        IntakeState.Cancelled, IntakeState.OutOfSpace, IntakeState.AllowAllRequested -> {
                            intake.consumed()
                            uiState.update { it.copy(replacing = null) }
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
        }

        /** Takes in [uri] as the user's own file for [slot]. */
        fun replace(
            slot: PresetSlot,
            uri: Uri,
            access: MediaAccessState,
        ) {
            if (uiState.value.replacing != null) return
            uiState.update { it.copy(replacing = slot, message = null) }
            intake.start(projectId, listOf(uri), access)
        }

        fun skip(slot: PresetSlot) = choose(slot, PartChoice.Skip)

        fun usePreset(slot: PresetSlot) = choose(slot, PartChoice.UsePreset)

        fun dismissMessage() = uiState.update { it.copy(message = null) }

        /** What Play plays for [slot]: the user's file, or the preset's copy. */
        suspend fun playable(slot: PresetSlot): Uri? =
            when (val c = uiState.value.choices[slot]) {
                is PartChoice.Replace -> {
                    when (val m = c.media) {
                        is MediaRef.Store -> m.uri.toUri()
                        is MediaRef.Copied -> runCatching { files.copiedFile(projectId, m) }.getOrNull()?.takeIf { it.isFile }?.toUri()
                        is MediaRef.Preset -> null
                    }
                }

                PartChoice.Skip -> {
                    null
                }

                PartChoice.UsePreset -> {
                    val preset = uiState.value.preset ?: return null
                    val status = presetStore.status(preset.id)
                    (status.slots[slot] as? SlotStatus.Ready)?.binding?.file?.toUri()
                }
            }

        /** Next: the picker. The project is written first, so nothing is lost if the app is closed there. */
        fun next() {
            viewModelScope.launch {
                repository.flush()
                eventChannel.send(ReviewEvent.Go(NewVideoFlow.afterReview(projectId)))
            }
        }

        /** Back out of a new video before anything of the user's is in it. */
        fun discard() {
            intake.cancel()
            viewModelScope.launch {
                repository.delete(projectId)
                eventChannel.send(ReviewEvent.Back)
            }
        }

        private fun choose(
            slot: PresetSlot,
            choice: PartChoice,
        ) {
            uiState.update { it.copy(choices = it.choices.with(slot, choice), message = null) }
            apply()
        }

        private suspend fun finishReplace(
            slot: PresetSlot,
            item: IntakeItem?,
        ) {
            val ref = item?.ref
            val facts = ref?.let { probe.probe(projectId, it, item.info?.mime) }
            val wantSound = slot == PresetSlot.ENDING && uiState.value.spokenEnding
            val ok = facts != null && (if (wantSound) facts.hasAudio else facts.hasVideo)
            val ms = facts?.durationMs
            when {
                ref == null || facts == null -> {
                    uiState.update { it.copy(replacing = null, message = ReviewMessage.Unreadable) }
                }

                !ok || ms == null || ms !in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS -> {
                    uiState.update { it.copy(replacing = null, message = ReviewMessage.WrongKind(slot)) }
                }

                else -> {
                    val name =
                        item.info
                            ?.displayName
                            ?.takeIf {
                                TextRules.isAllowed(
                                    it,
                                    allowNewlines = false,
                                )
                            }?.take(PartChoice.Replace.MAX_FILE_NAME)
                    // A frame of the user's own needs a flat green where the preset's window is.
                    val frame = uiState.value.preset?.frame
                    val green = if (slot == PresetSlot.FRAME && frame != null) frameKey.green(projectId, ref, ms, frame) else null
                    if (slot == PresetSlot.FRAME && frame != null && green == null) {
                        uiState.update { it.copy(replacing = null, message = ReviewMessage.NoGreenWindow) }
                    } else {
                        uiState.update { it.copy(replacing = null) }
                        choose(slot, PartChoice.Replace(ref, ms, name, keyColor = green))
                    }
                }
            }
        }

        /** Rebuilds the draft from the current choices, and saves them. */
        private fun apply() {
            val ui = uiState.value
            savedState[KEY_CHOICES] = JSON.encodeToString(PresetChoices.serializer(), ui.choices)
            val preset = ui.preset ?: return
            repository.update { it.withPresetChoices(preset, ui.bound, ui.choices) }
        }

        companion object {
            const val KEY_CHOICES = "review_choices"
            private val JSON = Json { ignoreUnknownKeys = true }

            private fun decodeChoices(text: String): PresetChoices? =
                runCatching {
                    JSON.decodeFromString(PresetChoices.serializer(), text)
                }.getOrNull()
        }
    }
