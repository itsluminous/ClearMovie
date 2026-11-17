package com.itsluminous.clearmovie.ui.pick

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.newMainItems
import com.itsluminous.clearmovie.data.project.OpenResult
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import com.itsluminous.clearmovie.ui.media.IntakeState
import com.itsluminous.clearmovie.ui.media.MediaIntake
import com.itsluminous.clearmovie.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PickEvent {
    /** The items are on MAIN: open the editor. [skipped] could not be added. */
    data class Added(
        val projectId: ProjectId,
        val skipped: Int,
    ) : PickEvent

    /** Nothing was chosen, or the user backed out: go back. */
    data object Back : PickEvent

    /** Under partial access the user chose Allow all photos: ask, then take the same items in again. */
    data object AskForAccess : PickEvent

    /** Choose fewer: the picker opens again, by the user's own choice. */
    data object PickAgain : PickEvent

    /** The phone filled up during the copy; nothing was added. */
    data object OutOfSpace : PickEvent

    /** The items were taken in, but the video could not be written (the phone is full). */
    data object NotSaved : PickEvent
}

/**
 * The photo and video step (requirement 3): the one-time access explainer,
 * then the system Photo Picker, then the intake into the project (links kept
 * or copied within the copy budget), then the editor. The picked links are
 * kept in the saved state from the moment they arrive, so a rotation or a
 * process death during the intake takes them in again rather than losing
 * them, and never opens the picker a second time.
 */
@HiltViewModel
class MediaPickViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val repository: ProjectRepository,
        mediaAccess: MediaAccess,
        private val probe: MediaFactsProbe,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        val projectId: ProjectId = ProjectId(requireNotNull(savedState.get<String>(Routes.ARG_PROJECT)))

        /** Whether the app has asked for access before; null until read. */
        val askedBefore: StateFlow<Boolean?> =
            settings.mediaAccessAsked
                .map<Boolean, Boolean?> {
                    it
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val intake = MediaIntake(mediaAccess, viewModelScope)

        private val addingState = MutableStateFlow(false)

        /** True from the picker's answer until the items are on MAIN. */
        val adding: StateFlow<Boolean> = addingState.asStateFlow()

        private val eventChannel = Channel<PickEvent>(Channel.BUFFERED)
        val events: Flow<PickEvent> = eventChannel.receiveAsFlow()

        /** Links picked but not yet on MAIN: set when the picker answers, cleared once they are added. */
        val pending: List<Uri>? get() = savedState.get<ArrayList<String>>(KEY_PENDING)?.map { it.toUri() }

        init {
            viewModelScope.launch {
                intake.state.collect { state ->
                    when (state) {
                        is IntakeState.Done -> {
                            intake.consumed()
                            add(state)
                        }

                        IntakeState.Cancelled, IntakeState.OutOfSpace -> {
                            intake.consumed()
                            savedState.remove<ArrayList<String>>(KEY_PENDING)
                            addingState.value = false
                            when {
                                state == IntakeState.OutOfSpace -> eventChannel.send(PickEvent.OutOfSpace)
                                choosingFewer -> eventChannel.send(PickEvent.PickAgain)
                                else -> leave()
                            }
                            choosingFewer = false
                        }

                        IntakeState.AllowAllRequested -> {
                            intake.consumed()
                            eventChannel.send(PickEvent.AskForAccess)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
        }

        private var choosingFewer = false

        /** Choose fewer, from the copy question: the picker opens again. */
        fun chooseFewer() {
            choosingFewer = true
            intake.chooseFewer()
        }

        fun markAsked() {
            viewModelScope.launch { settings.setMediaAccessAsked() }
        }

        /** The picker's answer. Nothing chosen goes back to where the user came from. */
        fun picked(
            uris: List<Uri>,
            access: MediaAccessState,
        ) {
            if (uris.isEmpty()) {
                leave()
                return
            }
            savedState[KEY_PENDING] = ArrayList(uris.map { it.toString() })
            start(access)
        }

        /**
         * Takes the pending links in (again): after a process death during an
         * intake, or after Allow all photos.
         */
        fun start(access: MediaAccessState) {
            val uris = pending ?: return
            addingState.value = true
            viewModelScope.launch {
                // After a process death nothing is open yet; the intake needs the project's row.
                if (repository.open.value?.id != projectId && repository.open(projectId, inEditor = false) !is OpenResult.Opened) {
                    addingState.value = false
                    eventChannel.send(PickEvent.Back)
                    return@launch
                }
                intake.start(projectId, uris, access)
            }
        }

        /**
         * Back from this step. A new blank video with nothing in it is not kept.
         * While the items are being put on MAIN (after the copies are done) Back
         * waits: leaving then would let them land in a video the user just left.
         */
        fun leave() {
            if (finishing) return
            intake.cancel()
            viewModelScope.launch {
                val project = repository.open.value?.takeIf { it.id == projectId }
                if (project != null && project.presetId == null && project.isEmpty()) repository.delete(projectId)
                eventChannel.send(PickEvent.Back)
            }
        }

        private var finishing = false

        private suspend fun add(done: IntakeState.Done) {
            finishing = true
            try {
                addItems(done)
            } finally {
                finishing = false
            }
        }

        private suspend fun addItems(done: IntakeState.Done) {
            val project = repository.open.value?.takeIf { it.id == projectId }
            if (project == null) {
                addingState.value = false
                eventChannel.send(PickEvent.Back)
                return
            }
            val items = newMainItems(projectId, done.items, project.photoDurationMs, project.defaultFit, probe)
            val added =
                items.clips.isEmpty() ||
                    repository.update { it.addToMain(items.clips).withEffectDefaultsFor(items.clips.map { c -> c.id }) }
            val written = repository.flush()
            savedState.remove<ArrayList<String>>(KEY_PENDING)
            addingState.value = false
            eventChannel.send(if (added && written) PickEvent.Added(projectId, items.skipped) else PickEvent.NotSaved)
        }

        private fun Project.isEmpty() = mainClips.isEmpty()

        companion object {
            const val KEY_PENDING = "pick_pending"
        }
    }
