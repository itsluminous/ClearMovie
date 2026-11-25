package com.itsluminous.clearmovie.voice

import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.voice.PackAddResult
import com.itsluminous.clearmovie.data.voice.SpokenVoice
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A voice engine for device tests (cm-voice-progress), put in with
 * `RecordModule.testVoiceTools`: it waits in each job until the test tells
 * it to report progress, finish or fail, so the screen can be checked (and
 * photographed) in every state. It finds [words] in any recording, and its
 * voice is a copy of the recording, so the editor plays a real file.
 */
class HeldVoiceTools(
    private val files: AppFiles,
    private val words: List<VoicePhrase>,
) : VoiceTools {
    /** One step of a job: report [done], finish, or fail. */
    sealed interface Step {
        data class Progress(
            val done: Float,
        ) : Step

        data object Finish : Step

        data class Fail(
            val e: Exception,
        ) : Step
    }

    val steps = LinkedBlockingQueue<Step>()

    /** Told after each step is taken ("progress") and when a job ends ("ended"). */
    val took = LinkedBlockingQueue<String>()

    override val manifest =
        VoicePackManifest(VoicePackManifest.NAME, 1, listOf(VoicePackFile("voices.zip", VoicePackFile.VOICES, 1, 1, 1, "a".repeat(64))))
    override val supported = true
    override val engineWorks: Boolean = true

    override fun checkEngine() = true

    override fun missing(speech: SpokenLanguage?) = emptyList<VoicePackFile>()

    override fun addPackFiles(
        sources: List<() -> InputStream?>,
        progress: (Float) -> Unit,
    ) = PackAddResult(emptyList(), 0)

    override fun packBytes() = 0L

    override fun removePack() = Unit

    /** Lets the job take [step] and waits until it has. */
    fun step(step: Step) {
        steps += step
        checkNotNull(took.poll(STEP_TIMEOUT_S, TimeUnit.SECONDS)) { "the job did not take $step" }
    }

    private fun run(progress: (Float) -> Unit) {
        try {
            while (true) {
                when (val step = checkNotNull(steps.poll(STEP_TIMEOUT_S, TimeUnit.SECONDS)) { "the test gave no step" }) {
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
        // The "voice" is the newest recording, copied: a real file the editor can play.
        val folder = files.copiedFile(projectId, MediaRef.Copied(VoiceRecordingFiles.nameFor("aaaaaaaa"))).parentFile!!
        val source =
            folder
                .listFiles()
                .orEmpty()
                .filter { VoiceRecordingFiles.isRecording(it.name) }
                .maxBy { it.lastModified() }
        val made =
            MediaRef.Copied(
                VoiceRecordingFiles.nameFor(
                    RandomIds
                        .next()
                        .filter {
                            it.isLetterOrDigit()
                        }.lowercase()
                        .take(16)
                        .padEnd(8, 'a'),
                ),
            )
        source.copyTo(files.copiedFile(projectId, made), overwrite = true)
        return SpokenVoice(made, phrases, lengthMs ?: 2_000, emptyList())
    }

    private companion object {
        const val STEP_TIMEOUT_S = 60L
    }
}
