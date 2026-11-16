package com.itsluminous.clearmovie.ui.share

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.media.CaptureOrder
import com.itsluminous.clearmovie.data.media.CaptureTimeReader
import com.itsluminous.clearmovie.data.media.IntakeItem
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.PendingShare
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.data.media.ShareProbe
import com.itsluminous.clearmovie.data.media.ShareTypes
import com.itsluminous.clearmovie.data.media.SharedLinks
import com.itsluminous.clearmovie.data.media.newMainItems
import com.itsluminous.clearmovie.data.project.LaunchDecision
import com.itsluminous.clearmovie.data.project.OpenResult
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.project.ShareChoice
import com.itsluminous.clearmovie.data.project.ShareDrafts
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.ui.media.IntakeState
import com.itsluminous.clearmovie.ui.media.MediaIntake
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Named

/** What could not be added from a share, said once in plain words. */
data class ShareNotes(
    /** Not photos or videos (a PDF, text, a sound). */
    val notMedia: Int = 0,
    /** Links that could not be opened: the grant is gone, the file was deleted, or it is not a link the app reads. */
    val unreadable: Int = 0,
    /** Left out past [ShareLinks.MAX_ITEMS]. */
    val overLimit: Int = 0,
) {
    val any: Boolean get() = notMedia + unreadable + overLimit > 0
}

/** Where the share flow is (ARCHITECTURE R3, section 11.12). */
sealed interface ShareState {
    data object Idle : ShareState

    /**
     * A share arrived while a video is open: add the [count] items to it, or
     * start a new video. [toWaitingShare]: the video is an earlier share still
     * on its preset pick, not one open in the editor.
     */
    data class Asking(
        val count: Int,
        val target: ProjectId,
        val toWaitingShare: Boolean = false,
    ) : ShareState

    /** Waiting for the go-ahead: the one-time photo access explainer, for a new video. [target] null means a new video. */
    data class Waiting(
        val count: Int,
        val target: ProjectId?,
    ) : ShareState

    /** Checking, resolving and copying; the copy dialogs follow [ShareViewModel.intake]. */
    data class Preparing(
        val count: Int,
        val target: ProjectId?,
    ) : ShareState

    /** More than [ShareViewModel.MANY_ITEMS] items: how long the video gets, with Continue and Cancel. */
    data class ManyItems(
        val count: Int,
        val minutes: Int,
    ) : ShareState

    /** The new video's draft holds [items] photos and videos; the preset pick shows. */
    data class Ready(
        val draft: ProjectId,
        val items: Int,
        val notes: ShareNotes,
        val choosing: Boolean = false,
    ) : ShareState

    /** [added] items went onto the open video; [notes] say what could not. */
    data class Added(
        val added: Int,
        val notes: ShareNotes,
    ) : ShareState

    /** Nothing in the share could be used. */
    data class Nothing(
        val notes: ShareNotes,
    ) : ShareState

    /** It stopped, for [reason]. Nothing was added (except [FailReason.CHOICE_NOT_SAVED], where the items wait on the pick). */
    data class Failed(
        val reason: FailReason,
    ) : ShareState
}

/** Why a share stopped; each has its own message. */
enum class FailReason {
    /** The phone filled up during a copy. */
    OUT_OF_SPACE,

    /** The video could not be written: the phone is full. */
    PHONE_FULL,

    /** The copy question's Choose fewer: a share cannot choose, so it says how. */
    CHOOSE_FEWER,

    /** The chosen preset could not be saved onto the draft (phone full); the items stay on the pick. */
    CHOICE_NOT_SAVED,

    /** Anything else (the open video could not be read, for example). */
    OTHER,
}

/** Where the app must go for the share. */
sealed interface ShareNav {
    /** The share's preset pick. */
    data object ToPick : ShareNav

    data class ToEditor(
        val projectId: ProjectId,
    ) : ShareNav

    /** A preset file must be set up or repaired first; then the editor. */
    data class ToSetup(
        val presetId: PresetId,
        val draft: ProjectId,
    ) : ShareNav

    /** The new video was dropped: back Home. */
    data object Home : ShareNav

    /** Under partial access the user chose Allow all photos: ask, then call [ShareViewModel.retry]. */
    data object AskForAccess : ShareNav
}

/**
 * The share flow, one per activity, so it lives across screens and
 * rotations (ARCHITECTURE R3, section 11.12).
 *
 * A share is read from its intent ([receive]) and routed: when a video is
 * open in the editor, or an earlier share is waiting on its preset pick, the
 * user is asked whether to add to it or start a new video. The items are
 * taken in at once with [MediaIntake] (resolve, the copy budget, copy, while
 * the share's grants last), ordered by capture time ([CaptureOrder]), and put
 * on the MAIN row of a new draft ([ShareDrafts]) or of the open video. The
 * draft is written before the preset pick shows. Where the flow is, the
 * draft's id and the notes are kept in the saved state, and the links in
 * [PendingShare], so a process death resumes the share instead of losing it.
 */
@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val repository: ProjectRepository,
        mediaAccess: MediaAccess,
        private val probe: MediaFactsProbe,
        private val captureTimes: CaptureTimeReader,
        private val shareProbe: ShareProbe,
        private val drafts: ShareDrafts,
        private val pending: PendingShare,
        private val settings: SettingsRepository,
        @param:Named(IO_DISPATCHER) private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val stateFlow = MutableStateFlow<ShareState>(ShareState.Idle)
        val state: StateFlow<ShareState> = stateFlow.asStateFlow()

        val intake = MediaIntake(mediaAccess, viewModelScope)

        private val navChannel = Channel<ShareNav>(Channel.BUFFERED)
        val nav: Flow<ShareNav> = navChannel.receiveAsFlow()

        // The share being handled. Kept in the saved state by [show], except the links ([pending]).
        private var links: List<Uri> = emptyList()
        private var notes = ShareNotes()

        /** The open video the items go to; null for a new video. */
        private var target: ProjectId? = null

        /** The new video's draft (or, when adding to a waiting share, that share's draft, which is also [target]). */
        private var draft: ProjectId? = null

        /** The earlier share waiting on its preset pick when this one arrived: shown again if this one is dropped. */
        private var waitingShare: ShareState.Ready? = null

        /** The share's preset pick is on screen (a new video), so dropping it goes Home. */
        private var onPick = false

        private var manyConfirmed = false
        private var usable: List<Uri>? = null
        private var lastAccess: MediaAccessState = MediaAccessState.NotAsked
        private var job: Job? = null

        /** Shares in the order they arrived; each is routed once the one before has settled. */
        private val incoming = Channel<SharedLinks>(Channel.UNLIMITED)

        /** The copy question's Choose fewer was tapped: the cancel that follows says how to share fewer. */
        private var choseFewer = false

        /** MIME types read while classifying, reused by the length estimate. */
        private val types = HashMap<Uri, String?>()

        /**
         * Calls into the sharing app's provider. It can be any app, and one
         * that hangs must never hang the share: each call has a time limit,
         * and at most [PROVIDER_THREADS] threads can be stuck in such calls.
         */
        private val providerScope = CoroutineScope(SupervisorJob() + io.limitedParallelism(PROVIDER_THREADS))

        /** The links file is written and read in order, off the main thread. */
        private val fileIo = io.limitedParallelism(1)

        private val restoredState = MutableStateFlow(false)

        /** False until a share from before a process death is back in place: until then Idle means nothing yet. */
        val restoredFlow: StateFlow<Boolean> = restoredState.asStateFlow()

        /** Whether the app has asked for photo access before; null until read. */
        val askedBefore: StateFlow<Boolean?> =
            settings.mediaAccessAsked
                .map<Boolean, Boolean?> { it }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val restored: Job =
            viewModelScope.launch {
                try {
                    restore()
                } finally {
                    restoredState.value = true
                }
            }

        init {
            viewModelScope.launch {
                intake.state.collect { s ->
                    when (s) {
                        is IntakeState.Done -> {
                            intake.consumed()
                            commit(s.items)
                        }

                        // Cancel on the copy dialog, or Choose fewer; a cancel of our own has already moved on.
                        IntakeState.Cancelled -> {
                            intake.consumed()
                            if (stateFlow.value is ShareState.Preparing) {
                                if (choseFewer) fail(FailReason.CHOOSE_FEWER) else cancel()
                            }
                            choseFewer = false
                        }

                        IntakeState.OutOfSpace -> {
                            intake.consumed()
                            fail(FailReason.OUT_OF_SPACE)
                        }

                        IntakeState.AllowAllRequested -> {
                            intake.consumed()
                            navChannel.send(ShareNav.AskForAccess)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
            // One share at a time, in the order they came: the next waits until this one is settled.
            viewModelScope.launch {
                restored.join()
                for (shared in incoming) {
                    stateFlow.first { !busy(it) }
                    route(shared)
                }
            }
        }

        override fun onCleared() {
            providerScope.cancel()
        }

        /**
         * A share from [intent] (`onCreate` with no saved state, or
         * `onNewIntent`). Anything that is not a share, and a share Android
         * replays from Recents (its grants are long gone), is ignored.
         */
        fun receive(intent: Intent?) {
            if (intent == null || !ShareLinks.isShare(intent)) return
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
            val shared = ShareLinks.readSafely(intent) ?: return
            incoming.trySend(shared)
        }

        /** A share is being handled; a waiting share's pick (not while a preset is being applied) is not. */
        private fun busy(state: ShareState): Boolean = !(state is ShareState.Idle || (state is ShareState.Ready && !state.choosing))

        private suspend fun route(shared: SharedLinks) {
            val first = ShareNotes(unreadable = shared.refused, overLimit = shared.overLimit)
            val waiting = stateFlow.value as? ShareState.Ready
            if (shared.isEmpty) {
                // Nothing to take in: say so where the user is, and change nothing.
                waitingShare = waiting
                notes = first
                show(ShareState.Nothing(first))
                return
            }
            // The editor's video is the one named in the session (cm-flow D5); a review or pick draft is not.
            val inEditor = (runCatching { repository.launchDecision() }.getOrNull() as? LaunchDecision.ReopenEditor)?.id
            val open: ProjectId? = waiting?.draft ?: inEditor
            links = shared.uris
            notes = first
            withContext(fileIo) { pending.save(links) }
            manyConfirmed = false
            usable = null
            types.clear()
            waitingShare = waiting
            if (open != null) {
                target = open
                draft = waiting?.draft
                show(ShareState.Asking(links.size, open, toWaitingShare = waiting != null))
            } else {
                dropEmptyLauncherDraft()
                startNew()
            }
        }

        /**
         * A share arriving on the review or photo screen of a video started
         * from the launcher takes over; that video, with none of his photos
         * or videos in it yet, is not kept (as Back from the photo screen
         * does not keep an empty blank video).
         */
        private suspend fun dropEmptyLauncherDraft() {
            val left = repository.open.value ?: return
            if (left.name == null && left.userItems() == 0) withContext(io) { repository.delete(left.id) }
        }

        /** "Add to this video". */
        fun addToOpen() {
            val asking = stateFlow.value as? ShareState.Asking ?: return
            target = asking.target
            show(ShareState.Waiting(links.size, asking.target))
        }

        /**
         * "Start a new video": the open video stays as it is, saved in My
         * videos. The editor's video is closed first (written, and out of the
         * session), so a later share or a restart never takes it for open.
         * A waiting share's draft also stays in My videos with its items.
         */
        fun startNewVideo() {
            val asking = stateFlow.value as? ShareState.Asking ?: return
            viewModelScope.launch {
                if (!asking.toWaitingShare && !repository.close()) {
                    target = null
                    show(ShareState.Failed(FailReason.PHONE_FULL))
                    return@launch
                }
                startNew()
            }
        }

        private suspend fun startNew() {
            target = null
            draft = null
            waitingShare = null
            onPick = true
            show(ShareState.Waiting(links.size, target = null))
            navChannel.send(ShareNav.ToPick)
        }

        /** The photo access question is settled (explained and asked, or Not now): take the items in with [access]. */
        fun begin(access: MediaAccessState) {
            val waiting = stateFlow.value as? ShareState.Waiting ?: return
            lastAccess = access
            show(ShareState.Preparing(waiting.count, waiting.target))
            job =
                viewModelScope.launch {
                    val kept = usable ?: classify().also { usable = it }
                    if (kept.isEmpty()) {
                        finishWithNothing()
                        return@launch
                    }
                    if (kept.size > MANY_ITEMS && !manyConfirmed) {
                        show(ShareState.ManyItems(kept.size, minutes(kept)))
                        return@launch
                    }
                    val project = projectForIntake() ?: return@launch
                    intake.start(project, kept, access)
                }
        }

        /** Continue, after the many-items notice. */
        fun continueMany() {
            val many = stateFlow.value as? ShareState.ManyItems ?: return
            manyConfirmed = true
            show(ShareState.Waiting(many.count, target))
            begin(lastAccess)
        }

        /** The copy question's Choose fewer (or the no-space message's only button). */
        fun chooseFewer() {
            choseFewer = true
            intake.chooseFewer()
        }

        /** Takes the items in again with the new [access], after Allow all photos. */
        fun retry(access: MediaAccessState) {
            if (stateFlow.value !is ShareState.Preparing) return
            show(ShareState.Waiting(links.size, target))
            begin(access)
        }

        fun markAsked() {
            viewModelScope.launch { settings.setMediaAccessAsked() }
        }

        /**
         * Cancel, Back, Choose fewer, or the Asking question dismissed: the
         * share is dropped. A new video's draft goes with its copies; an
         * open video keeps everything it had (an intake's own copies are
         * removed by [MediaIntake]); an earlier share's pick shows again.
         */
        fun cancel() {
            // While the items go onto the timeline (the copies are done) Cancel waits: they would land in a video just dropped.
            if (stateFlow.value is ShareState.Idle || committing) return
            job?.cancel()
            intake.cancel()
            val drop = if (target == null) draft else null
            val goHome = onPick && target == null && waitingShare == null
            settle()
            viewModelScope.launch {
                drop?.let { withContext(io) { drafts.discard(it) } }
                if (goHome) navChannel.send(ShareNav.Home)
            }
        }

        /** A message that ends the flow (added, nothing usable, failed) was read. */
        fun dismiss() {
            val s = stateFlow.value
            if (s !is ShareState.Added && s !is ShareState.Nothing && s !is ShareState.Failed) return
            val goHome = onPick && target == null && waitingShare == null
            settle()
            if (goHome) viewModelScope.launch { navChannel.send(ShareNav.Home) }
        }

        /** A preset, or null for Start without a preset, was chosen on the share's preset pick. */
        fun choose(preset: PresetId?) {
            val ready = stateFlow.value as? ShareState.Ready ?: return
            if (ready.choosing) return
            show(ready.copy(choosing = true))
            viewModelScope.launch {
                val result = drafts.choose(ready.draft, preset)
                (stateFlow.value as? ShareState.Ready)?.takeIf { it.draft == ready.draft }?.let { show(it.copy(choosing = false)) }
                when (result) {
                    is ShareChoice.Ready -> {
                        navChannel.send(ShareNav.ToEditor(result.projectId))
                    }

                    ShareChoice.NeedsSetup -> {
                        if (preset != null) navChannel.send(ShareNav.ToSetup(preset, ready.draft))
                    }

                    ShareChoice.Gone -> {
                        draft = null
                        show(ShareState.Nothing(ShareNotes(unreadable = ready.items)))
                    }

                    ShareChoice.NotSaved -> {
                        // The draft and its items stay: the same choice can be tried again once there is space.
                        waitingShare = ready
                        show(ShareState.Failed(FailReason.CHOICE_NOT_SAVED))
                    }
                }
            }
        }

        /** The editor shows [projectId]: a share whose video it is, is done. */
        fun editorShown(projectId: ProjectId) {
            val s = stateFlow.value
            if (s is ShareState.Ready && s.draft == projectId) {
                waitingShare = null
                settle()
            }
        }

        /**
         * The links worth taking in: not clearly another kind of file (counted
         * as not media), and from a provider that answers at all (one that
         * does not answer in time is counted as could not be opened).
         */
        private suspend fun classify(): List<Uri> {
            val kept = mutableListOf<Uri>()
            var other = 0
            var silent = 0
            for (uri in links) {
                currentCoroutineContext().ensureActive()
                val answer = ask { shareProbe.typeOf(uri) }
                when {
                    answer === NoAnswer -> {
                        silent++
                    }

                    ShareTypes.kindOf(answer as String?) == ShareTypes.Kind.OTHER -> {
                        other++
                    }

                    else -> {
                        types[uri] = answer
                        kept += uri
                    }
                }
            }
            notes = notes.copy(notMedia = notes.notMedia + other, unreadable = notes.unreadable + silent)
            saveNotes()
            return kept
        }

        /**
         * [block] on a provider thread, or [NoAnswer] when it does not return
         * within [PROVIDER_TIMEOUT_MS]. A call that hangs is left behind on
         * its thread (a call into another process cannot be stopped); the
         * share goes on without it.
         */
        private suspend fun ask(block: () -> Any?): Any? {
            val call = providerScope.async { block() }
            return withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { call.await() }
                ?: if (call.isCompleted) null else NoAnswer.also { call.cancel() }
        }

        private object NoAnswer

        /**
         * About how many minutes [items] add: photos at the video's photo
         * length, videos at the length their provider gives (MediaStore does)
         * without opening the files, else a photo's length.
         */
        private suspend fun minutes(items: List<Uri>): Int {
            val photoMs =
                target?.let { id ->
                    repository.open.value
                        ?.takeIf { it.id == id }
                        ?.photoDurationMs
                } ?: Project.DEFAULT_PHOTO_MS
            var total = 0L
            for (uri in items) {
                currentCoroutineContext().ensureActive()
                val video = types[uri].orEmpty().startsWith("video/")
                total += if (video) (ask { shareProbe.durationMs(uri) } as? Long ?: photoMs) else photoMs
            }
            return ((total + MINUTE_MS / 2) / MINUTE_MS).toInt().coerceAtLeast(1)
        }

        /** The project the intake copies into: the open video, or a new draft written now. */
        private suspend fun projectForIntake(): ProjectId? {
            val into = target
            if (into != null) {
                if (repository.open.value?.id != into) {
                    when (repository.open(into, inEditor = into != draft)) {
                        is OpenResult.Opened -> Unit
                        OpenResult.NotSaved -> return null.also { fail(FailReason.PHONE_FULL) }
                        else -> return null.also { fail(FailReason.OTHER) }
                    }
                }
                return into
            }
            draft?.let { stale ->
                // A draft from before a process death that never got its items: started again, without its half-made copies.
                withContext(io) { drafts.discard(stale) }
                draft = null
            }
            val made = drafts.create()
            if (made == null) {
                fail(FailReason.PHONE_FULL)
                return null
            }
            draft = made
            savedState[KEY_DRAFT] = made.value
            return made
        }

        private var committing = false

        private suspend fun commit(items: List<IntakeItem>) {
            if (stateFlow.value !is ShareState.Preparing) return
            committing = true
            try {
                putOnTimeline(items)
            } finally {
                committing = false
            }
        }

        private suspend fun putOnTimeline(items: List<IntakeItem>) {
            val project = target ?: draft ?: return
            val times = items.map { captureTimes.takenAt(project, it) }
            val ordered = CaptureOrder.order(items.indices.toList()) { times[it] }.map { items[it] }
            val open =
                repository.open.value?.takeIf { it.id == project }
                    ?: (repository.open(project, inEditor = false) as? OpenResult.Opened)?.project
            if (open == null) {
                fail(FailReason.OTHER)
                return
            }
            val made = newMainItems(project, ordered, open.photoDurationMs, open.defaultFit, probe)
            val added = made.clips.isEmpty() || repository.update { it.addToMain(made.clips) }
            val written = repository.flush()
            notes = notes.copy(unreadable = notes.unreadable + made.skipped)
            if (!added || !written) {
                fail(if (added) FailReason.PHONE_FULL else FailReason.OTHER)
                return
            }
            withContext(fileIo) { pending.clear() }
            val total =
                repository.open.value
                    ?.takeIf { it.id == project }
                    ?.userItems() ?: made.clips.size
            when {
                made.clips.isEmpty() && target == null -> {
                    finishWithNothing()
                }

                target == null || target == draft -> {
                    // A new video, or more items for the share still on its preset pick.
                    waitingShare = null
                    show(ShareState.Ready(project, total, notes))
                    saveNotes()
                }

                else -> {
                    show(ShareState.Added(made.clips.size, notes))
                    navChannel.send(ShareNav.ToEditor(project))
                }
            }
        }

        private suspend fun finishWithNothing() {
            withContext(fileIo) { pending.clear() }
            if (target == null) draft?.let { withContext(io) { drafts.discard(it) } }
            if (target == null) draft = null
            show(ShareState.Nothing(notes))
        }

        private suspend fun fail(reason: FailReason) {
            withContext(fileIo) { pending.clear() }
            if (target == null) draft?.let { withContext(io) { drafts.discard(it) } }
            if (target == null) draft = null
            show(ShareState.Failed(reason))
        }

        /** Ends this share: the earlier share's pick shows again if there was one, else nothing is going on. */
        private fun settle() {
            val back = waitingShare
            viewModelScope.launch(fileIo) { pending.clear() }
            links = emptyList()
            notes = back?.notes ?: ShareNotes()
            target = null
            draft = back?.draft
            waitingShare = null
            usable = null
            manyConfirmed = false
            if (back == null) onPick = false
            job = null
            show(back?.copy(choosing = false) ?: ShareState.Idle)
            if (back != null) saveNotes()
        }

        private fun show(state: ShareState) {
            stateFlow.value = state
            savedState[KEY_PHASE] =
                when (state) {
                    ShareState.Idle, is ShareState.Added, is ShareState.Nothing, is ShareState.Failed -> null
                    is ShareState.Asking -> PHASE_ASKING
                    is ShareState.Waiting, is ShareState.Preparing, is ShareState.ManyItems -> PHASE_TAKING_IN
                    is ShareState.Ready -> PHASE_READY
                }
            savedState[KEY_TARGET] = target?.value
            savedState[KEY_DRAFT] = (state as? ShareState.Ready)?.draft?.value ?: draft?.value
            savedState[KEY_WAITING_DRAFT] = waitingShare?.draft?.value
            savedState[KEY_ON_PICK] = onPick
        }

        private fun saveNotes() {
            savedState[KEY_NOTES] = intArrayOf(notes.notMedia, notes.unreadable, notes.overLimit)
        }

        /** After a process death: back to where the share was. A share half taken in is taken in again from its links. */
        private suspend fun restore() {
            val phase = savedState.get<String>(KEY_PHASE)
            if (phase == null) {
                // A fresh start: links left by an earlier process that never got anywhere are dropped.
                withContext(fileIo) { pending.clear() }
                return
            }
            target = savedState.get<String>(KEY_TARGET)?.let(::idOrNull)
            draft = savedState.get<String>(KEY_DRAFT)?.let(::idOrNull)
            onPick = savedState.get<Boolean>(KEY_ON_PICK) ?: false
            notes =
                savedState.get<IntArray>(KEY_NOTES)?.takeIf { it.size == NOTE_FIELDS }?.let { ShareNotes(it[0], it[1], it[2]) }
                    ?: ShareNotes()
            waitingShare = savedState.get<String>(KEY_WAITING_DRAFT)?.let(::idOrNull)?.let { readyOf(it, ShareNotes()) }
            links = withContext(fileIo) { pending.load() }
            when (phase) {
                PHASE_READY -> {
                    val ready = draft?.let { readyOf(it, notes) }
                    if (ready != null) show(ready) else settle()
                }

                PHASE_ASKING -> {
                    val into = target
                    if (into != null &&
                        links.isNotEmpty()
                    ) {
                        show(ShareState.Asking(links.size, into, toWaitingShare = into == draft))
                    } else {
                        settle()
                    }
                }

                else -> {
                    if (links.isEmpty()) settle() else show(ShareState.Waiting(links.size, target))
                }
            }
        }

        private suspend fun readyOf(
            id: ProjectId,
            notes: ShareNotes,
        ): ShareState.Ready? {
            val project = (repository.open(id, inEditor = false) as? OpenResult.Opened)?.project ?: return null
            return ShareState.Ready(id, project.userItems(), notes)
        }

        private fun idOrNull(value: String): ProjectId? = runCatching { ProjectId(value) }.getOrNull()

        private fun Project.userItems(): Int = mainClips.count { it is PhotoClip || it is VideoClip }

        companion object {
            /** More items than the Photo Picker allows at once (review F25): the length notice shows first. */
            const val MANY_ITEMS = 100

            const val IO_DISPATCHER = "share_io"
            private const val MINUTE_MS = 60_000L
            private const val NOTE_FIELDS = 3

            /** How long a sharing app's provider has to answer one question. */
            const val PROVIDER_TIMEOUT_MS = 5_000L
            private const val PROVIDER_THREADS = 4

            const val KEY_PHASE = "share_phase"
            const val KEY_TARGET = "share_target"
            const val KEY_DRAFT = "share_draft"
            const val KEY_WAITING_DRAFT = "share_waiting_draft"
            const val KEY_ON_PICK = "share_on_pick"
            const val KEY_NOTES = "share_notes"
            const val PHASE_ASKING = "asking"
            const val PHASE_TAKING_IN = "taking_in"
            const val PHASE_READY = "ready"
        }
    }
