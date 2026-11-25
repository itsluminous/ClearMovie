package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.voice.PackAddResult
import com.itsluminous.clearmovie.data.voice.SpokenVoice
import com.itsluminous.clearmovie.data.voice.VoiceFailure
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
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.model.addRecording
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The voice jobs' states (cm-voice-progress): what the editor shows while words are found or a
 * voice is made, Stop, and Try again after a failure. The engine is scripted step by step, so each
 * state can be looked at while the job waits in it.
 */
class VoiceWorkStatesTest {
    private val recording = MediaRef.Copied(VoiceRecordingFiles.nameFor("abcd1234efgh5678"))
    private val made = MediaRef.Copied(VoiceRecordingFiles.nameFor("made1234made5678"))
    private val words = listOf(VoicePhrase(100, "welcome everyone"), VoicePhrase(2_000, "to the party"))

    /** One step of the scripted engine: report progress, finish, or fail. */
    private sealed interface Step {
        data class Progress(
            val done: Float,
        ) : Step

        data object Finish : Step

        data class Fail(
            val e: Exception,
        ) : Step
    }

    /** An engine that does what the test tells it, one step at a time, and says where it waits. */
    private inner class ScriptedTools : VoiceTools {
        val steps = LinkedBlockingQueue<Step>()

        /** Each time the engine has taken a step (or ended), so the test can look at the state after it. */
        val took = LinkedBlockingQueue<String>()
        var missing: List<VoicePackFile> = emptyList()
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

        private fun run(progress: (Float) -> Unit) {
            try {
                while (true) {
                    when (val step = checkNotNull(steps.poll(10, TimeUnit.SECONDS)) { "the test gave no step" }) {
                        is Step.Progress -> progress(step.done)
                        Step.Finish -> return
                        is Step.Fail -> throw step.e
                    }
                    took += "progress"
                }
            } finally {
                took += "ended"
            }
        }

        override fun recognize(
            projectId: ProjectId,
            media: MediaRef.Copied,
            language: SpokenLanguage,
            progress: (Float) -> Unit,
        ): List<VoicePhrase> {
            run(progress)
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
            run(progress)
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
    private val inserted = mutableListOf<ClipId>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tools = ScriptedTools()

    private val c =
        VoiceChoiceController(
            scope,
            tools,
            ProjectId("p"),
            project = { synchronized(this) { project } },
            edit = { change ->
                synchronized(this) { project = project.change() }
                true
            },
            onNote = { synchronized(this) { notes += it } },
            onCheckWords = { synchronized(this) { checked += it } },
            onInserted = { synchronized(this) { inserted += it } },
            worker = Dispatchers.IO,
            io = Dispatchers.Unconfined,
        )

    @After
    fun tearDown() = scope.cancel()

    private fun rec() = synchronized(this) { project.clip(ClipId("rec")) as AudioClip }

    /** Lets the engine take [step], and waits until it has (and the state it reported is in). */
    private fun step(step: Step) {
        tools.steps += step
        assertThat(tools.took.poll(10, TimeUnit.SECONDS)).isNotNull()
    }

    /** Waits until the job's end has gone through (its work cleared and its result put in). */
    private fun settle(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!synchronized(this) { done() }) {
            check(System.currentTimeMillis() < end) { "the job did not end" }
            Thread.sleep(5)
        }
    }

    private fun voicePicked() = c.choose(VoiceChoice.INDIAN_FEMALE)

    @Test
    fun `words being found show from the end of the recording, first without progress, then with it, and clear when they are in`() {
        voicePicked()
        assertThat(c.afterRecording(ClipId("rec"))).isTrue()
        // Before the engine reports anything (loading it, reading the recording): no progress yet.
        assertThat(c.work.value).isEqualTo(VoiceWork.Recognizing(ClipId("rec"), null))
        step(Step.Progress(0f))
        assertThat(c.work.value).isEqualTo(VoiceWork.Recognizing(ClipId("rec"), 0f))
        step(Step.Progress(0.5f))
        assertThat(c.work.value).isEqualTo(VoiceWork.Recognizing(ClipId("rec"), 0.5f))
        tools.steps += Step.Finish
        settle { ClipId("rec") in checked }
        assertThat(c.work.value).isNull()
        assertThat(rec().voiceOver?.phrases).isEqualTo(words)
        assertThat(c.trouble.value).isNull()
    }

    @Test
    fun `no voice chosen, or no pack, starts nothing to show`() {
        assertThat(c.afterRecording(ClipId("rec"))).isFalse()
        assertThat(c.work.value).isNull()
        voicePicked()
        tools.missing = tools.manifest.files
        assertThat(c.afterRecording(ClipId("rec"))).isFalse()
        assertThat(c.work.value).isNull()
        assertThat(c.packAsk.value).isNotNull()
    }

    @Test
    fun `Stop shows Stopping until the engine's next step, then nothing is put in and nothing is said`() {
        voicePicked()
        c.afterRecording(ClipId("rec"))
        step(Step.Progress(0f))
        c.stop()
        assertThat(c.work.value).isEqualTo(VoiceWork.Stopping(VoiceWork.Recognizing(ClipId("rec"), 0f)))
        // The engine's next report is where it stops.
        tools.steps += Step.Progress(0.3f)
        settle { c.work.value == null }
        assertThat(tools.took.poll(10, TimeUnit.SECONDS)).isEqualTo("ended")
        assertThat(rec().voiceOver).isNull()
        assertThat(checked).isEmpty()
        assertThat(notes).isEmpty()
        assertThat(c.trouble.value).isNull()
        // Free for the next job at once.
        c.recognize(ClipId("rec"), SpokenLanguage.ENGLISH)
        assertThat(c.work.value).isInstanceOf(VoiceWork.Recognizing::class.java)
        tools.steps += Step.Finish
        settle { ClipId("rec") in checked }
    }

    @Test
    fun `a voice being made shows its phrases done, and Stop leaves the item as it was`() {
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        assertThat(c.work.value).isEqualTo(VoiceWork.Voicing(ClipId("rec"), null))
        step(Step.Progress(0f))
        step(Step.Progress(0.5f))
        assertThat(c.work.value).isEqualTo(VoiceWork.Voicing(ClipId("rec"), 0.5f))
        // Stop is ignored for nothing else, and a second Stop changes nothing.
        c.stop()
        c.stop()
        assertThat(c.work.value).isEqualTo(VoiceWork.Stopping(VoiceWork.Voicing(ClipId("rec"), 0.5f)))
        assertThat(c.work.value?.clipId).isEqualTo(ClipId("rec"))
        tools.steps += Step.Progress(1f)
        settle { c.work.value == null }
        assertThat(rec().voiceOver?.generated).isNull()
        assertThat(notes).isEmpty()
    }

    @Test
    fun `a failure where trying again may work keeps Try again, which runs the job again`() {
        voicePicked()
        c.afterRecording(ClipId("rec"))
        tools.steps += Step.Fail(IllegalStateException("decoder"))
        settle { c.trouble.value != null }
        assertThat(c.work.value).isNull()
        assertThat(c.trouble.value).isEqualTo(VoiceTrouble(ClipId("rec"), finding = true))
        // Said by the error line, not once more as a note.
        assertThat(notes).doesNotContain(VoiceNote.FAILED)
        c.tryAgain()
        assertThat(c.trouble.value).isNull()
        assertThat(c.work.value).isEqualTo(VoiceWork.Recognizing(ClipId("rec"), null))
        tools.steps += Step.Finish
        settle { ClipId("rec") in checked }
        assertThat(c.trouble.value).isNull()
    }

    @Test
    fun `a voice that could not be made keeps Try again until it is closed`() {
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(VoiceFailure(VoiceFailure.Reason.FAILED))
        settle { c.trouble.value != null }
        assertThat(c.trouble.value).isEqualTo(VoiceTrouble(ClipId("rec"), finding = false))
        c.dismissTrouble()
        assertThat(c.trouble.value).isNull()
        // Nothing left to try again.
        c.tryAgain()
        assertThat(c.work.value).isNull()
    }

    @Test
    fun `typed words that could not be said are tried again with the same words and place`() {
        assertThat(c.makeTyped(TrackId("voice"), 12_000, "hello there", VoiceChoice.INDIAN_FEMALE, SpokenLanguage.ENGLISH)).isTrue()
        assertThat(c.work.value).isEqualTo(VoiceWork.Voicing(null, null))
        tools.steps += Step.Fail(RuntimeException("encoder"))
        settle { c.trouble.value != null }
        assertThat(c.trouble.value).isEqualTo(VoiceTrouble(null, finding = false))
        c.tryAgain()
        tools.steps += Step.Finish
        settle { inserted.isNotEmpty() }
        assertThat((synchronized(this) { project }.clip(inserted.single()) as AudioClip).startMs).isEqualTo(12_000)
    }

    @Test
    fun `failures that trying again cannot fix are said once, with no Try again`() {
        voicePicked()
        c.afterRecording(ClipId("rec"))
        tools.steps += Step.Fail(VoiceFailure(VoiceFailure.Reason.TOO_LONG))
        settle { VoiceNote.TOO_LONG in notes }
        assertThat(c.trouble.value).isNull()
        assertThat(c.work.value).isNull()
    }

    @Test
    fun `the next job clears an earlier failure, and Try again waits while a job runs`() {
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(RuntimeException("boom"))
        settle { c.trouble.value != null }
        c.recognize(ClipId("rec"), SpokenLanguage.ENGLISH)
        assertThat(c.trouble.value).isNull()
        c.tryAgain()
        // Nothing to try again now; the running job is untouched.
        assertThat(c.work.value).isEqualTo(VoiceWork.Recognizing(ClipId("rec"), null))
        tools.steps += Step.Finish
        settle { ClipId("rec") in checked }
    }

    @Test
    fun `a job whose item an undo took away stops at the engine's next step, and is not tried again`() {
        voicePicked()
        c.afterRecording(ClipId("rec"))
        step(Step.Progress(0f))
        val before = synchronized(this) { project }
        synchronized(this) { project = project.deleteClip(ClipId("rec")) }
        tools.steps += Step.Progress(0.4f)
        settle { c.work.value == null }
        assertThat(checked).isEmpty()
        assertThat(c.trouble.value).isNull()
        // A failure for an item that then went away: Try again says it changed, and runs nothing.
        synchronized(this) { project = before }
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(RuntimeException("boom"))
        settle { c.trouble.value != null }
        synchronized(this) { project = project.deleteClip(ClipId("rec")) }
        c.tryAgain()
        assertThat(c.work.value).isNull()
        assertThat(notes).contains(VoiceNote.CHANGED_MEANWHILE)
    }

    @Test
    fun `a failure is forgotten once its item is gone`() {
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(RuntimeException("boom"))
        settle { c.trouble.value != null }
        c.forgetGone()
        assertThat(c.trouble.value).isNotNull()
        synchronized(this) { project = project.deleteClip(ClipId("rec")) }
        c.forgetGone()
        assertThat(c.trouble.value).isNull()
    }

    @Test
    fun `each end is told once - found, stopped, failed - and an end said by a note, or an item gone, tells none`() {
        val told = java.util.Collections.synchronizedList(mutableListOf<VoiceOutcome>())
        scope.launch { c.outcomes.collect { told += it } }
        voicePicked()
        c.afterRecording(ClipId("rec"))
        tools.steps += Step.Finish
        settle { told.size == 1 }
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        step(Step.Progress(0f))
        c.stop()
        tools.steps += Step.Progress(0.5f)
        settle { told.size == 2 }
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(RuntimeException("boom"))
        settle { told.size == 3 }
        c.recognize(ClipId("rec"), SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(VoiceFailure(VoiceFailure.Reason.TOO_LONG))
        settle { VoiceNote.TOO_LONG in notes }
        c.recognize(ClipId("rec"), SpokenLanguage.ENGLISH)
        step(Step.Progress(0f))
        synchronized(this) { project = project.deleteClip(ClipId("rec")) }
        tools.steps += Step.Progress(0.2f)
        settle { c.work.value == null && VoiceNote.CHANGED_MEANWHILE in notes }
        assertThat(told).containsExactly(VoiceOutcome.WORDS_FOUND, VoiceOutcome.STOPPED, VoiceOutcome.FAILED).inOrder()
    }

    @Test
    fun `no words found is said by its note alone, and an end is kept until a screen listens`() {
        val silent =
            VoiceChoiceController(
                scope,
                object : VoiceTools by tools {
                    override fun recognize(
                        projectId: ProjectId,
                        media: MediaRef.Copied,
                        language: SpokenLanguage,
                        progress: (Float) -> Unit,
                    ) = emptyList<VoicePhrase>()
                },
                ProjectId("p"),
                project = { synchronized(this) { project } },
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
        silent.recognize(ClipId("rec"), SpokenLanguage.ENGLISH)
        settle { VoiceNote.NO_WORDS in notes && silent.work.value == null && checked.isNotEmpty() }
        // Nobody listened while it ended: the next listener is told what was kept, and only that.
        c.makeVoice(ClipId("rec"), words, VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH)
        tools.steps += Step.Fail(RuntimeException("boom"))
        settle { c.trouble.value != null }
        val told = java.util.Collections.synchronizedList(mutableListOf<VoiceOutcome>())
        scope.launch { silent.outcomes.collect { told += it } }
        scope.launch { c.outcomes.collect { told += it } }
        settle { told.isNotEmpty() }
        Thread.sleep(50)
        assertThat(told).containsExactly(VoiceOutcome.FAILED)
    }

    @Test
    fun `Stop does nothing while nothing runs, and pack work has no item`() {
        c.stop()
        assertThat(c.work.value).isNull()
        assertThat(VoiceWork.AddingPack(0.5f).clipId).isNull()
        assertThat(VoiceWork.Stopping(VoiceWork.Voicing(null)).clipId).isNull()
    }
}
