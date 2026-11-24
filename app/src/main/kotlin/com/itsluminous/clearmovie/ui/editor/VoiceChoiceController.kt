package com.itsluminous.clearmovie.ui.editor

import com.itsluminous.clearmovie.data.voice.PackAddResult
import com.itsluminous.clearmovie.data.voice.VoiceFailure
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
}

/** The voice pack dialog: the files to download, and whether the steps to add them show yet. */
data class PackAsk(
    val files: List<VoicePackFile>,
    val version: Int,
    val steps: Boolean = false,
)

/**
 * The voice choice of the editor (cm-voice-choice): the voice and language
 * picked in a voice row's sheet, the voice pack dialog, and the jobs that
 * find a recording's words and speak them in another voice. Each job runs off
 * the main thread and puts its result in as one undo step, only if the item
 * is still as it was (otherwise its file is left for the sweep). Lives in the
 * editor's view model, so a rotation keeps it.
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
) {
    private val choiceState = MutableStateFlow(VoiceChoice.NONE)
    val choice: StateFlow<VoiceChoice> = choiceState.asStateFlow()

    private val languageState = MutableStateFlow(SpokenLanguage.ENGLISH)
    val language: StateFlow<SpokenLanguage> = languageState.asStateFlow()

    private val workState = MutableStateFlow<VoiceWork?>(null)
    val work: StateFlow<VoiceWork?> = workState.asStateFlow()

    private val askState = MutableStateFlow<PackAsk?>(null)
    val packAsk: StateFlow<PackAsk?> = askState.asStateFlow()

    /** False on a phone the voice pack has no engine for: only "No change" is offered there. */
    val available: Boolean get() = tools.supported

    /** The choice for the next recording or typed words. Choosing a voice without the pack asks for it first. */
    fun choose(voice: VoiceChoice) {
        val v = if (voice.speaks(languageState.value)) voice else VoiceChoice.NONE
        choiceState.value = v
        if (v != VoiceChoice.NONE) askForPack(languageState.value)
    }

    fun speakIn(language: SpokenLanguage) {
        languageState.value = language
        if (!choiceState.value.speaks(language)) choiceState.value = VoiceChoice.NONE
    }

    /** Restores the choice saved with the screen. */
    fun restore(
        voice: VoiceChoice?,
        language: SpokenLanguage?,
    ) {
        language?.let { languageState.value = it }
        voice?.takeIf { it.speaks(languageState.value) }?.let { choiceState.value = it }
    }

    /** Shows the pack dialog when files are missing to voice words in [language]; true when nothing is missing. */
    fun askForPack(language: SpokenLanguage?): Boolean {
        val manifest = tools.manifest
        if (!tools.supported || manifest == null) return false
        val missing = tools.missing(language)
        if (missing.isEmpty()) return true
        askState.value = PackAsk(missing, manifest.version)
        return false
    }

    fun showSteps() {
        askState.value = askState.value?.copy(steps = true)
    }

    fun dismissPack() {
        askState.value = null
    }

    /** Takes in the files the user chose after downloading them. */
    fun addPackFiles(sources: List<() -> InputStream?>) {
        if (sources.isEmpty() || workState.value != null) return
        val want = askState.value
        workState.value = VoiceWork.AddingPack(0f)
        scope.launch {
            val result: PackAddResult =
                withContext(Dispatchers.IO) {
                    runCatching { tools.addPackFiles(sources) { workState.value = VoiceWork.AddingPack(it) } }
                        .getOrElse { PackAddResult(emptyList(), sources.size) }
                }
            workState.value = null
            val language = languageState.value
            val missing = tools.missing(language)
            when {
                result.outOfSpace -> {
                    onNote(VoiceNote.PACK_NO_SPACE)
                }

                missing.isEmpty() -> {
                    askState.value = null
                    onNote(VoiceNote.PACK_ADDED)
                }

                result.added.isEmpty() && result.rejected > 0 -> {
                    onNote(VoiceNote.PACK_WRONG_FILE)
                }

                else -> {
                    onNote(VoiceNote.PACK_STILL_MISSING)
                }
            }
            if (missing.isNotEmpty() && want != null) askState.value = want.copy(files = missing, steps = true)
        }
    }

    /** A recording just went on the timeline: with a voice chosen, its words are found and shown to check. */
    fun afterRecording(clipId: ClipId) {
        val voice = choiceState.value
        if (voice == VoiceChoice.NONE) return
        val language = languageState.value
        edit { setVoiceOver(clipId, VoiceOver(voice, language, WordsSource.SPOKEN, emptyList())) }
        recognize(clipId, language)
    }

    /** Finds the words of recording [clipId] in [language]; they replace its words (one undo step) and open to check. */
    fun recognize(
        clipId: ClipId,
        language: SpokenLanguage,
    ) {
        val clip = audio(clipId) ?: return
        val media = clip.media as? MediaRef.Copied ?: return
        if (workState.value != null || !askForPack(language)) return
        workState.value = VoiceWork.Recognizing(clipId)
        scope.launch {
            val phrases =
                withContext(Dispatchers.Default) {
                    runCatching { tools.recognize(projectId, media, language) }
                }
            workState.value = null
            phrases
                .onSuccess { found ->
                    val now = audio(clipId)
                    if (now == null || now.media != media) return@onSuccess onNote(VoiceNote.CHANGED_MEANWHILE)
                    if (found.isEmpty()) onNote(VoiceNote.NO_WORDS)
                    val over =
                        now.voiceOver
                            ?: VoiceOver(
                                choiceState.value.takeIf { it.speaks(language) } ?: VoiceChoice.NONE,
                                language,
                                WordsSource.SPOKEN,
                                emptyList(),
                            )
                    val voice = if (over.voice.speaks(language)) over.voice else VoiceChoice.NONE
                    edit { setVoiceOver(clipId, over.copy(voice = voice, language = language, phrases = found, generated = null)) }
                    onCheckWords(clipId)
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
                edit {
                    val kept = clip.voiceOver?.copy(phrases = words, language = language)
                    (if (kept != null) setVoiceOver(clipId, kept) else this).useOwnVoice(clipId)
                }
            }
            return
        }
        if (workState.value != null || !askForPack(null)) return
        if (words.isEmpty()) return onNote(VoiceNote.NO_WORDS)
        workState.value = VoiceWork.Voicing(clipId, 0f)
        scope.launch {
            val made =
                withContext(Dispatchers.Default) {
                    runCatching {
                        tools.speak(projectId, words, voice, language, if (typed) null else clip.sourceDurationMs) {
                            workState.value = VoiceWork.Voicing(clipId, it)
                        }
                    }
                }
            workState.value = null
            made
                .onSuccess { v ->
                    val now = audio(clipId)
                    if (now == null || now.media != clip.media ||
                        now.sourceDurationMs != clip.sourceDurationMs
                    ) {
                        return@onSuccess onNote(VoiceNote.CHANGED_MEANWHILE)
                    }
                    if (typed) {
                        edit { setTypedVoice(clipId, v.media, v.lengthMs, VoiceOver(voice, language, WordsSource.TYPED, v.phrases)) }
                    } else {
                        edit { setVoiceOver(clipId, VoiceOver(voice, language, WordsSource.SPOKEN, v.phrases, v.media)) }
                    }
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
        if (workState.value != null || !askForPack(null)) return false
        workState.value = VoiceWork.Voicing(null, 0f)
        scope.launch {
            val made =
                withContext(Dispatchers.Default) {
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

    private fun failed(e: Throwable) {
        onNote(
            when ((e as? VoiceFailure)?.reason) {
                VoiceFailure.Reason.NO_WORDS -> VoiceNote.NO_WORDS
                VoiceFailure.Reason.NO_ENGINE -> VoiceNote.NOT_ON_THIS_PHONE
                VoiceFailure.Reason.NO_PACK -> VoiceNote.PACK_STILL_MISSING
                else -> VoiceNote.FAILED
            },
        )
    }

    private fun audio(id: ClipId): AudioClip? = project()?.clip(id) as? AudioClip
}
