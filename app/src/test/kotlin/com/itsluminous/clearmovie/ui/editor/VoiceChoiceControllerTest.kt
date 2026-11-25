package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.voice.PackAddResult
import com.itsluminous.clearmovie.data.voice.SpokenVoice
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoiceOver
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.model.WordsSource
import com.itsluminous.clearmovie.domain.model.addRecording
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.playedMedia
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.model.setVoiceOver
import com.itsluminous.clearmovie.domain.model.setVolume
import com.itsluminous.clearmovie.domain.model.validate
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The voice jobs of the editor (cm-voice-choice): a result goes in only onto the item as it was. */
class VoiceChoiceControllerTest {
    private val recording = MediaRef.Copied(VoiceRecordingFiles.nameFor("abcd1234efgh5678"))
    private val made = MediaRef.Copied(VoiceRecordingFiles.nameFor("made1234made5678"))
    private val words = listOf(VoicePhrase(100, "welcome everyone"))

    private class FakeTools(
        private val words: List<VoicePhrase>,
        private val made: MediaRef.Copied,
    ) : VoiceTools {
        val release = CountDownLatch(1)
        var missing: List<VoicePackFile> = emptyList()
        var speakFails: Exception? = null
        override val manifest =
            VoicePackManifest(VoicePackManifest.NAME, 1, listOf(VoicePackFile("voices.zip", VoicePackFile.VOICES, 1, 1, 1, "a".repeat(64))))
        override val supported = true
        override var engineWorks: Boolean? = true

        override fun missing(
            speech: SpokenLanguage?,
            voice: VoiceChoice?,
        ) = missing

        override fun addPackFiles(
            sources: List<() -> InputStream?>,
            progress: (Float) -> Unit,
        ) = PackAddResult(emptyList(), 0)

        override fun packBytes() = 0L

        override fun removePack() = Unit

        override fun recognize(
            projectId: ProjectId,
            media: MediaRef.Copied,
            language: SpokenLanguage,
            progress: (Float) -> Unit,
        ): List<VoicePhrase> {
            release.await(10, TimeUnit.SECONDS)
            return words
        }

        override fun speak(
            projectId: ProjectId,
            phrases: List<VoicePhrase>,
            voice: VoiceChoice,
            language: SpokenLanguage,
            lengthMs: Long?,
            progress: (Float) -> Unit,
        ): SpokenVoice {
            release.await(10, TimeUnit.SECONDS)
            speakFails?.let { throw it }
            return SpokenVoice(made, phrases, lengthMs ?: 1000, emptyList())
        }
    }

    private var project: Project =
        ProjectFactory
            .blank(ProjectId("p"))
            .addToMain(listOf(PhotoClip(ClipId("a"), MediaRef.Store("content://media/external/images/media/1"), 20_000)))
            .addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("voice"))
            .addRecording(requireNotNull(recordingClip(ClipId("rec"), recording, 1_000, 8_000, 20_000)), TrackId("voice"))
    private val notes = mutableListOf<VoiceNote>()
    private val checked = mutableListOf<ClipId>()

    private fun controller(tools: VoiceTools) =
        VoiceChoiceController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            tools,
            ProjectId("p"),
            project = { project },
            edit = { change ->
                synchronized(this) { project = project.change() }
                true
            },
            onNote = { synchronized(this) { notes += it } },
            onCheckWords = { synchronized(this) { checked += it } },
            onInserted = {},
            worker = Dispatchers.IO,
            io = Dispatchers.Unconfined,
        )

    private fun rec() = project.clip(ClipId("rec")) as AudioClip

    /** Waits until [done] holds (the job's result lands after its work state clears). */
    private fun waitFor(done: () -> Boolean) =
        runBlocking { withTimeout(10_000) { while (!synchronized(this@VoiceChoiceControllerTest) { done() }) Thread.sleep(10) } }

    @Test
    fun `a voice made while the item changed is not put in`() {
        val tools = FakeTools(words, made)
        val c = controller(tools)
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        assertThat(c.work.value).isInstanceOf(VoiceWork.Voicing::class.java)
        // An edit (or an undo) meanwhile.
        project = project.setVoiceOver(ClipId("rec"), VoiceOver(VoiceChoice.NONE, SpokenLanguage.ENGLISH, WordsSource.SPOKEN, emptyList()))
        tools.release.countDown()
        waitFor { VoiceNote.CHANGED_MEANWHILE in notes }
        assertThat(rec().voiceOver?.generated).isNull()
    }

    @Test
    fun `a voice made onto the unchanged item plays, and No change plays the recording again`() {
        val tools = FakeTools(words, made)
        val c = controller(tools)
        // A volume change meanwhile does not matter: the voice depends on the file and the words.
        c.makeVoice(ClipId("rec"), words, VoiceChoice.INDIAN_FEMALE, SpokenLanguage.ENGLISH)
        synchronized(this) { project = project.setVolume(ClipId("rec"), -6.0) }
        // The voice is made only now, so the volume change is really made while it is being made.
        tools.release.countDown()
        waitFor { rec().voiceOver?.generated != null }
        assertThat(rec().playedMedia).isEqualTo(made)
        assertThat(rec().volumeDb).isEqualTo(-6.0)
        assertThat(rec().media).isEqualTo(recording)
        c.makeVoice(ClipId("rec"), words, VoiceChoice.NONE, SpokenLanguage.ENGLISH)
        assertThat(rec().playedMedia).isEqualTo(recording)
        assertThat(rec().voiceOver?.phrases).isEqualTo(words)
    }

    @Test
    fun `found words keep the item playing what it played until a voice is made, and open to check`() {
        val tools = FakeTools(words, made)
        tools.release.countDown()
        val c = controller(tools)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.afterRecording(ClipId("rec"))
        waitFor { checked.isNotEmpty() }
        assertThat(rec().voiceOver?.phrases).isEqualTo(words)
        assertThat(c.pendingVoice(ClipId("rec"))).isEqualTo(VoiceChoice.BRITISH_FEMALE)
        assertThat(rec().voiceOver?.voice).isEqualTo(VoiceChoice.NONE)
        assertThat(rec().playedMedia).isEqualTo(recording)
        assertThat(checked).containsExactly(ClipId("rec"))
        assertThat(project.validate()).isEmpty()
    }

    @Test
    fun `without the pack the dialog asks for it, and nothing runs`() {
        val tools = FakeTools(words, made)
        tools.missing = tools.manifest.files
        val c = controller(tools)
        c.choose(VoiceChoice.BRITISH_MALE)
        assertThat(c.packAsk.value?.files).isEqualTo(tools.manifest.files)
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        assertThat(c.work.value).isNull()
        c.dismissPack()
        assertThat(c.packAsk.value).isNull()
    }

    @Test
    fun `Hindi keeps the voice's gender in an Indian voice, and an engine that does not load turns the choice off`() {
        val tools = FakeTools(words, made)
        val c = controller(tools)
        c.choose(VoiceChoice.BRITISH_MALE)
        c.speakIn(SpokenLanguage.HINDI)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.INDIAN_MALE)
        assertThat(c.available).isTrue()
    }

    @Test
    fun `an engine that does not load turns the choice off and says so`() {
        val tools = FakeTools(words, made)
        tools.speakFails =
            com.itsluminous.clearmovie.data.voice
                .VoiceFailure(com.itsluminous.clearmovie.data.voice.VoiceFailure.Reason.NO_ENGINE)
        tools.release.countDown()
        val c = controller(tools)
        c.choose(VoiceChoice.BRITISH_MALE)
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        waitFor { VoiceNote.NOT_ON_THIS_PHONE in notes }
        assertThat(c.available).isFalse()
        assertThat(c.choice.value).isEqualTo(VoiceChoice.NONE)
        assertThat(c.askForPack(null)).isFalse()
        c.choose(VoiceChoice.BRITISH_MALE)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.NONE)
    }

    @Test
    fun `the preset's default voice is preselected without asking for the pack, and asked for once the recording's words are wanted`() {
        val tools = FakeTools(words, made)
        tools.missing = tools.manifest.files
        val c = controller(tools)
        c.preselect(VoiceChoice.BRITISH_FEMALE)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.BRITISH_FEMALE)
        // Preselecting never shows the pack dialog: recording is not blocked.
        assertThat(c.packAsk.value).isNull()
        val before = rec()
        c.afterRecording(ClipId("rec"))
        // The recording stays as it was made, and the dialog asks for what finding its words needs.
        assertThat(rec()).isEqualTo(before)
        assertThat(rec().playedMedia).isEqualTo(recording)
        assertThat(c.packAsk.value?.speech).isEqualTo(SpokenLanguage.ENGLISH)
        assertThat(c.work.value).isNull()
        // Its panel offers the default voice once the pack is there.
        assertThat(c.pendingVoice(ClipId("rec"))).isEqualTo(VoiceChoice.BRITISH_FEMALE)
        assertThat(notes).isEmpty()
        // Closed without the pack: the next recording plays as recorded, and is not asked about again.
        c.dismissPack()
        assertThat(c.choice.value).isEqualTo(VoiceChoice.NONE)
        c.afterRecording(ClipId("rec"))
        assertThat(c.packAsk.value).isNull()
        assertThat(rec()).isEqualTo(before)
    }

    @Test
    fun `a voice the user picked stays after the pack dialog is closed, as before`() {
        val tools = FakeTools(words, made)
        tools.missing = tools.manifest.files
        val c = controller(tools)
        c.preselect(VoiceChoice.BRITISH_FEMALE)
        c.choose(VoiceChoice.INDIAN_MALE)
        assertThat(c.packAsk.value).isNotNull()
        c.dismissPack()
        assertThat(c.choice.value).isEqualTo(VoiceChoice.INDIAN_MALE)
    }

    @Test
    fun `with the pack a preselected voice finds the recording's words, as a chosen one does`() {
        val tools = FakeTools(words, made)
        tools.release.countDown()
        val c = controller(tools)
        c.preselect(VoiceChoice.AMERICAN_MALE)
        c.afterRecording(ClipId("rec"))
        waitFor { checked.isNotEmpty() }
        assertThat(rec().voiceOver?.phrases).isEqualTo(words)
        assertThat(c.pendingVoice(ClipId("rec"))).isEqualTo(VoiceChoice.AMERICAN_MALE)
        // The user can still change the voice for the next recording.
        c.choose(VoiceChoice.NONE)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.NONE)
    }

    @Test
    fun `no default, No change, or a phone where the voices cannot run leaves the choice at No change`() {
        val tools = FakeTools(words, made)
        val c = controller(tools)
        c.preselect(null)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.NONE)
        c.preselect(VoiceChoice.NONE)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.NONE)
        tools.engineWorks = false
        val off = controller(tools)
        off.preselect(VoiceChoice.INDIAN_MALE)
        assertThat(off.choice.value).isEqualTo(VoiceChoice.NONE)
        assertThat(off.packAsk.value).isNull()
        val none = controller(VoiceTools.NONE)
        none.preselect(VoiceChoice.INDIAN_MALE)
        assertThat(none.choice.value).isEqualTo(VoiceChoice.NONE)
    }

    @Test
    fun `a default voice is said in the language chosen, so an English-only voice becomes the Indian one for Hindi`() {
        val c = controller(FakeTools(words, made))
        c.speakIn(SpokenLanguage.HINDI)
        c.preselect(VoiceChoice.BRITISH_FEMALE)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.INDIAN_FEMALE)
    }
}
