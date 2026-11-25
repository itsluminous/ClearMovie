package com.itsluminous.clearmovie.ui.editor

import com.itsluminous.clearmovie.data.voice.PackAddResult
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.VoiceFailure
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoiceOver
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.WordsSource
import com.itsluminous.clearmovie.domain.model.addRecording
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.model.setTypedVoice
import com.itsluminous.clearmovie.domain.model.setVoiceOver
import com.itsluminous.clearmovie.domain.model.useOwnVoice
import com.itsluminous.clearmovie.domain.voice.Phrases
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.roomToDownload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** What the voice engine is doing (cm-voice-choice). */
sealed interface VoiceWork {
    /** Finding the words of recording [clipId]. */
    data class Recognizing(
        val clipId: ClipId,
    ) : VoiceWork

    /** Speaking the words of [clipId] (null: typed words for a new item), [done] from 0 to 1. */
    data class Voicing(
        val clipId: ClipId?,
        val done: Float,
    ) : VoiceWork

    /** Taking in voice pack files, [done] from 0 to 1. */
    data class AddingPack(
        val done: Float,
    ) : VoiceWork
}

/** Something the editor says once about voices. */
enum class VoiceNote {
    NO_WORDS,
    FAILED,
    NOT_ON_THIS_PHONE,
    CHANGED_MEANWHILE,
    PACK_ADDED,
    PACK_STILL_MISSING,
    PACK_WRONG_FILE,
    PACK_NO_SPACE,
    BUSY,
    SOME_CUT,
    TOO_LONG,
}

/**
 * The voice pack dialog: the files to download, whether the browser steps to
 * add them show instead of the app's own download, and what they are for:
 * voicing words only ([speech] null), or also finding the words of speech in
 * [speech]. [room] is how many more bytes the phone must free first (0: it
 * has room).
 */
data class PackAsk(
    val files: List<VoicePackFile>,
    val version: Int,
    val steps: Boolean = false,
    val speech: SpokenLanguage? = null,
    val room: Long = 0,
)

/**
 * The voice choice of the editor (cm-voice-choice): the voice and language
 * picked in a voice row's sheet, the voice pack dialog, and the jobs that
 * find a recording's words and speak them in another voice. Each job runs off
 * the main thread and puts its result in as one undo step, only if what the
 * result depends on (the item's file, its length and its words) is still as
 * it was; otherwise its file is left for the sweep. Lives in the editor's
 * view model, so a rotation keeps it.
 */
class VoiceChoiceController(
    private val scope: CoroutineScope,
    private val tools: VoiceTools,
    private val projectId: ProjectId,
    private val project: () -> Project?,
    private val edit: (Project.() -> Project) -> Boolean,
    private val onNote: (VoiceNote) -> Unit,
    private val onCheckWords: (ClipId) -> Unit,
    private val onInserted: (ClipId) -> Unit,
    /** The app's own download of the voice pack (cm-voice-pack-download). */
    private val downloads: PackDownloads = PackDownloads.NONE,
    /** Where the engine runs (tests use their own). */
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val choiceState = MutableStateFlow(VoiceChoice.NONE)
    val choice: StateFlow<VoiceChoice> = choiceState.asStateFlow()

    private val languageState = MutableStateFlow(SpokenLanguage.ENGLISH)
    val language: StateFlow<SpokenLanguage> = languageState.asStateFlow()

    private val workState = MutableStateFlow<VoiceWork?>(null)
    val work: StateFlow<VoiceWork?> = workState.asStateFlow()

    private val askState = MutableStateFlow<PackAsk?>(null)
    val packAsk: StateFlow<PackAsk?> = askState.asStateFlow()

    private val availableState = MutableStateFlow(tools.supported && tools.engineWorks != false)

    /** False where the voice pack cannot run (its processor has no engine, or the engine did not load): only "No change" is offered. */
    val availability: StateFlow<Boolean> = availableState.asStateFlow()
    val available: Boolean get() = availableState.value

    /** The voice a recording's words were found for, until a voice is made from them (kept only while the editor is open). */
    private val pending = mutableMapOf<ClipId, VoiceChoice>()

    /** The voice to offer for item [clipId] whose words wait for a voice: the one they were found for. */
    fun pendingVoice(clipId: ClipId): VoiceChoice? = pending[clipId]

    /** True while the choice is the preset's default voice ([preselect]), not one the user picked. */
    private var preselected = false

    private val downloadState = MutableStateFlow<PackDownloadState>(PackDownloadState.Idle)

    /**
     * The voice pack download as the dialog shows it (cm-voice-pack-download): one running or waiting
     * now, or the end of one this editor started or saw running. An older download's end is not shown.
     */
    val download: StateFlow<PackDownloadState> = downloadState.asStateFlow()

    /** The download this editor follows, and the dialog it answers. */
    private var watched: String? = null
    private var downloadFor: PackAsk? = null

    init {
        scope.launch { downloads.state.collect(::onDownload) }
    }

    /** The editor closed: a "not enough room" file kept for Try again goes, since no one can try again now. */
    fun close() {
        leaveFailure()
    }

    /**
     * Leaving a "not enough room" error: the verified file kept for Try again is removed, so a phone short of
     * room does not keep it. A part kept after a dropped connection stays, so Download carries on from it.
     */
    private fun leaveFailure() {
        val failed = downloadState.value as? PackDownloadState.Failed ?: return
        watched = null
        downloadState.value = PackDownloadState.Idle
        if (failed.error == PackDownloadError.NO_SPACE) downloads.cancel()
    }

    private fun onDownload(state: PackDownloadState) {
        when (state) {
            is PackDownloadState.Waiting, is PackDownloadState.Running -> {
                watched = (state as? PackDownloadState.Waiting)?.id ?: (state as PackDownloadState.Running).id
                downloadState.value = state
            }

            is PackDownloadState.Failed -> {
                downloadState.value = if (state.id == watched) state else PackDownloadState.Idle
            }

            is PackDownloadState.Done -> {
                val mine = state.id == watched
                watched = null
                downloadState.value = PackDownloadState.Idle
                if (mine) {
                    // The dialog showing now (it may ask for more, such as the other language's words), else the one it answered.
                    val want = askState.value ?: downloadFor
                    downloadFor = null
                    scope.launch { settle(want, null) }
                }
            }

            PackDownloadState.Idle -> {
                watched = null
                downloadState.value = PackDownloadState.Idle
            }
        }
    }

    /**
     * Download: the app downloads the dialog's files itself, in the background, and adds each one once
     * it matches the app's own manifest. The dialog then shows its progress.
     */
    fun downloadPack() {
        val current = askState.value ?: return
        if (!available || current.files.isEmpty()) return
        // Room is checked again on each tap: the user may have freed some since the dialog opened.
        val ask = current.copy(steps = false, room = roomFor(current.files))
        if (ask.room > 0) {
            leaveFailure()
            watched = null
            downloadState.value = PackDownloadState.Idle
            askState.value = ask
            return
        }
        downloadFor = ask
        askState.value = downloadFor
        // Shown at once; the worker's own state follows.
        downloadState.value = PackDownloadState.Waiting(PENDING)
        watched = PENDING
        downloads.start(ask.files)
    }

    /** Cancel: the download stops, what it downloaded is removed, and the dialog closes. */
    fun cancelDownload() {
        downloads.cancel()
        watched = null
        downloadFor = null
        downloadState.value = PackDownloadState.Idle
        dismissPack()
    }

    /** Hide: the dialog closes and the download goes on; "Voice pack added" shows when it is done. */
    fun hidePack() {
        askState.value = null
    }

    /** The browser fallback: the steps to download the files from GitHub's page and add them. */
    fun useBrowser() {
        leaveFailure()
        showSteps()
    }

    /**
     * The files the dialog offers: what is missing for [speech] (null: voicing only), together with the
     * words file of the language in use, so the first download is the only one most people need.
     */
    private fun askFiles(speech: SpokenLanguage?): List<VoicePackFile> {
        val manifest = tools.manifest ?: return emptyList()
        val wanted = (tools.missing(speech) + tools.missing(speech ?: languageState.value)).toSet()
        return manifest.files.filter { it in wanted }
    }

    /** How many more bytes the phone must free before [files] can be downloaded and added (0: none). */
    private fun roomFor(files: List<VoicePackFile>): Long {
        val partial = files.sumOf { f -> tools.downloadFile(f)?.takeIf { it.isFile }?.length() ?: 0L }
        return (roomToDownload(files, VoicePackStore.SPARE_BYTES) - partial - tools.freeBytes()).coerceAtLeast(0)
    }

    /** The choice for the next recording or typed words. Choosing a voice without the pack asks for it first. */
    fun choose(voice: VoiceChoice) {
        if (!available) return
        preselected = false
        val v = voice.forLanguage(languageState.value)
        choiceState.value = v
        // The voices and engine; the speech file for the language is asked for when a recording's words are found.
        if (v != VoiceChoice.NONE) askForPack(null)
    }

    fun speakIn(language: SpokenLanguage) {
        languageState.value = language
        choiceState.value = choiceState.value.forLanguage(language)
    }

    /**
     * Starts the choice at the preset's default voice (cm-preset-voice-default), when the editor opens
     * a video with no choice saved with the screen. Unlike [choose] it never asks for the voice pack:
     * a recording made without the pack is kept as it is, and the pack dialog shows once its words
     * are to be found ([afterRecording]). Closing that dialog without the pack sets the choice back to
     * No change, so later recordings are not asked about a voice the user never picked. Ignored where
     * the voice pack cannot run (only No change is offered there), and for No change.
     */
    fun preselect(voice: VoiceChoice?) {
        if (!available || voice == null || voice == VoiceChoice.NONE) return
        choiceState.value = voice.forLanguage(languageState.value)
        preselected = true
    }

    /** Restores the choice saved with the screen. */
    fun restore(
        voice: VoiceChoice?,
        language: SpokenLanguage?,
    ) {
        language?.let { languageState.value = it }
        if (available) voice?.let { choiceState.value = it.forLanguage(languageState.value) }
    }

    /** Shows the pack dialog when files are missing to voice words (and, with [speech], to find them); true when nothing is. */
    fun askForPack(speech: SpokenLanguage?): Boolean {
        val manifest = tools.manifest
        if (!available || manifest == null) {
            if (tools.supported && manifest != null) onNote(VoiceNote.NOT_ON_THIS_PHONE)
            return false
        }
        if (tools.missing(speech).isEmpty()) return true
        val files = askFiles(speech)
        askState.value = PackAsk(files, manifest.version, speech = speech, room = roomFor(files))
        return false
    }

    /** Brings back the pack dialog saved with the screen (the app may be stopped while the user downloads). */
    fun restorePackAsk(
        steps: Boolean,
        speech: SpokenLanguage?,
    ) {
        val manifest = tools.manifest ?: return
        if (!available) return
        scope.launch {
            val files = withContext(io) { if (tools.missing(speech).isEmpty()) emptyList() else askFiles(speech) }
            if (files.isNotEmpty()) askState.value = PackAsk(files, manifest.version, steps, speech, withContext(io) { roomFor(files) })
        }
    }

    fun showSteps() {
        askState.value = askState.value?.copy(steps = true)
    }

    fun dismissPack() {
        askState.value = null
        leaveFailure()
        // The preset's voice, declined with its pack: the next recordings play as recorded, without asking again.
        if (preselected) {
            preselected = false
            choiceState.value = VoiceChoice.NONE
        }
    }

    /** Takes in the files the user chose after downloading them, then checks the engine runs on this phone. */
    fun addPackFiles(sources: List<() -> InputStream?>) {
        if (sources.isEmpty()) return
        if (workState.value != null) return onNote(VoiceNote.BUSY)
        val want = askState.value
        workState.value = VoiceWork.AddingPack(0f)
        scope.launch {
            val result: PackAddResult =
                withContext(io) {
                    runCatching { tools.addPackFiles(sources) { workState.value = VoiceWork.AddingPack(it) } }
                        .getOrElse { PackAddResult(emptyList(), sources.size) }
                }
            settle(want, result)
        }
    }

    /**
     * After pack files went in, from the user's files ([result]) or the app's own download (null): checks
     * the engine runs on this phone, says what happened, and keeps the dialog for what is still missing.
     */
    private suspend fun settle(
        want: PackAsk?,
        result: PackAddResult?,
    ) {
        val missing = withContext(io) { tools.missing(want?.speech) }
        // Told now, not after the first voice, when the engine cannot run here.
        val works = if (missing.isEmpty() || tools.missing(null).isEmpty()) withContext(io) { tools.checkEngine() } else true
        workState.value = null
        if (!works) return engineFailed()
        when {
            result?.outOfSpace == true -> {
                onNote(VoiceNote.PACK_NO_SPACE)
            }

            missing.isEmpty() -> {
                askState.value = null
                onNote(VoiceNote.PACK_ADDED)
            }

            result != null && result.added.isEmpty() && result.rejected > 0 -> {
                onNote(VoiceNote.PACK_WRONG_FILE)
            }

            else -> {
                onNote(VoiceNote.PACK_STILL_MISSING)
            }
        }
        if (missing.isNotEmpty() && want != null) {
            val files = withContext(io) { askFiles(want.speech) }
            askState.value = want.copy(files = files, steps = result != null, room = withContext(io) { roomFor(files) })
        }
    }

    /** A recording just went on the timeline: with a voice chosen, its words are found and shown to check. */
    fun afterRecording(clipId: ClipId) {
        val voice = choiceState.value
        if (voice == VoiceChoice.NONE) return
        pending[clipId] = voice
        recognize(clipId, languageState.value)
    }

    /**
     * Finds the words of recording [clipId] in [language]. They go in (one undo
     * step) as words waiting for a voice: the item plays its recording until
     * Make voice, so it never plays an old voice that says other words. Its
     * panel then opens to check them.
     */
    fun recognize(
        clipId: ClipId,
        language: SpokenLanguage,
    ) {
        val clip = audio(clipId) ?: return
        val media = clip.media as? MediaRef.Copied ?: return
        if (workState.value != null) return onNote(VoiceNote.BUSY)
        if (!askForPack(language)) return
        clip.voiceOver
            ?.voice
            ?.takeIf { it != VoiceChoice.NONE }
            ?.let { pending[clipId] = it }
        workState.value = VoiceWork.Recognizing(clipId)
        scope.launch {
            val phrases =
                withContext(worker) {
                    runCatching { tools.recognize(projectId, media, language) }
                }
            workState.value = null
            phrases
                .onSuccess { found ->
                    val now = audio(clipId)
                    if (now == null || now.media != media || now.voiceOver != clip.voiceOver) {
                        return@onSuccess onNote(VoiceNote.CHANGED_MEANWHILE)
                    }
                    if (found.isEmpty()) onNote(VoiceNote.NO_WORDS)
                    val ok = edit { setVoiceOver(clipId, VoiceOver(VoiceChoice.NONE, language, WordsSource.SPOKEN, found)) }
                    if (ok) onCheckWords(clipId) else onNote(VoiceNote.FAILED)
                }.onFailure(::failed)
        }
    }

    /** Speaks [phrases] of item [clipId] in [voice] (No change: the recording plays again). One undo step. */
    fun makeVoice(
        clipId: ClipId,
        phrases: List<VoicePhrase>,
        voice: VoiceChoice,
        language: SpokenLanguage,
    ) {
        val clip = audio(clipId) ?: return
        val words = phrases.map { it.copy(text = Phrases.clip(it.text)) }.filter { it.text.isNotEmpty() }
        val typed = clip.voiceOver?.source == WordsSource.TYPED
        if (voice == VoiceChoice.NONE) {
            if (!typed) {
                pending.remove(clipId)
                edit {
                    val kept = clip.voiceOver?.copy(phrases = words, language = language, voice = VoiceChoice.NONE, generated = null)
                    (if (kept != null) setVoiceOver(clipId, kept) else this).useOwnVoice(clipId)
                }
            }
            return
        }
        if (workState.value != null) return onNote(VoiceNote.BUSY)
        if (!askForPack(null)) return
        if (words.isEmpty()) return onNote(VoiceNote.NO_WORDS)
        workState.value = VoiceWork.Voicing(clipId, 0f)
        scope.launch {
            val made =
                withContext(worker) {
                    runCatching {
                        tools.speak(projectId, words, voice, language, if (typed) null else clip.sourceDurationMs) {
                            workState.value = VoiceWork.Voicing(clipId, it)
                        }
                    }
                }
            workState.value = null
            made
                .onSuccess { v ->
                    // Only if what the voice was made from is still there; a move, a volume or a fade change does not matter.
                    val now = audio(clipId)
                    if (now == null || now.media != clip.media || now.sourceDurationMs != clip.sourceDurationMs ||
                        now.voiceOver != clip.voiceOver
                    ) {
                        return@onSuccess onNote(VoiceNote.CHANGED_MEANWHILE)
                    }
                    if (v.cut.isNotEmpty()) onNote(VoiceNote.SOME_CUT)
                    val ok =
                        if (typed) {
                            edit { setTypedVoice(clipId, v.media, v.lengthMs, VoiceOver(voice, language, WordsSource.TYPED, v.phrases)) }
                        } else {
                            edit { setVoiceOver(clipId, VoiceOver(voice, language, WordsSource.SPOKEN, v.phrases, v.media)) }
                        }
                    if (ok) pending.remove(clipId) else onNote(VoiceNote.FAILED)
                }.onFailure(::failed)
        }
    }

    /** Typed words spoken in [voice] as a new item on voice row [trackId] (or the first free one) at [atMs]. */
    fun makeTyped(
        trackId: TrackId?,
        atMs: Long,
        text: String,
        voice: VoiceChoice,
        language: SpokenLanguage,
    ): Boolean {
        if (voice == VoiceChoice.NONE || !voice.speaks(language)) return false
        val phrases = Phrases.fromTyped(text)
        if (phrases.isEmpty()) {
            onNote(VoiceNote.NO_WORDS)
            return false
        }
        if (workState.value != null) {
            onNote(VoiceNote.BUSY)
            return false
        }
        if (!askForPack(null)) return false
        workState.value = VoiceWork.Voicing(null, 0f)
        scope.launch {
            val made =
                withContext(worker) {
                    runCatching { tools.speak(projectId, phrases, voice, language, null) { workState.value = VoiceWork.Voicing(null, it) } }
                }
            workState.value = null
            made
                .onSuccess { v ->
                    val end = project()?.mainLayout()?.lastOrNull()?.endMs ?: 0L
                    val id = RandomIds.clipId()
                    val clip =
                        recordingClip(id, v.media, atMs, v.lengthMs, end)
                            ?.copy(voiceOver = VoiceOver(voice, language, WordsSource.TYPED, v.phrases))
                            ?: return@onSuccess onNote(VoiceNote.FAILED)
                    if (edit { addRecording(clip, trackId) }) onInserted(id) else onNote(VoiceNote.FAILED)
                }.onFailure(::failed)
        }
        return true
    }

    /** The engine cannot run on this phone: the choice goes off, and only No change stays. */
    private fun engineFailed() {
        askState.value = null
        availableState.value = false
        choiceState.value = VoiceChoice.NONE
        onNote(VoiceNote.NOT_ON_THIS_PHONE)
    }

    private fun failed(e: Throwable) {
        when ((e as? VoiceFailure)?.reason) {
            VoiceFailure.Reason.NO_ENGINE -> engineFailed()
            VoiceFailure.Reason.NO_WORDS -> onNote(VoiceNote.NO_WORDS)
            VoiceFailure.Reason.TOO_LONG -> onNote(VoiceNote.TOO_LONG)
            VoiceFailure.Reason.NO_PACK -> onNote(VoiceNote.PACK_STILL_MISSING)
            else -> onNote(VoiceNote.FAILED)
        }
    }

    private fun audio(id: ClipId): AudioClip? = project()?.clip(id) as? AudioClip

    private companion object {
        /** The download just asked for, before WorkManager names it. */
        const val PENDING = "pending"
    }
}
