package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.voice.PackAddResult
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.PackProgress
import com.itsluminous.clearmovie.data.voice.SpokenVoice
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File
import java.io.InputStream

/** The voice pack dialog's own download (cm-voice-pack-download). */
class VoicePackDownloadControllerTest {
    private fun f(
        name: String,
        part: String,
        bytes: Long,
        abi: String? = null,
    ) = VoicePackFile(name, part, bytes, bytes * 2, 1, "a".repeat(64), abi)

    private val voices = f("voices.zip", VoicePackFile.VOICES, 90)
    private val engine = f("engine-x86_64.zip", VoicePackFile.ENGINE, 10, "x86_64")
    private val english = f("speech-english.zip", VoicePackFile.SPEECH_EN, 40)
    private val hindi = f("speech-hindi.zip", VoicePackFile.SPEECH_HI, 44)

    private inner class Tools : VoiceTools {
        val added = mutableSetOf<VoicePackFile>()
        var free = Long.MAX_VALUE
        var engineChecks = 0
        override val manifest = VoicePackManifest(VoicePackManifest.NAME, 1, listOf(voices, english, hindi, engine))
        override val supported = true
        override val engineWorks: Boolean? = null

        override fun missing(speech: SpokenLanguage?): List<VoicePackFile> =
            listOfNotNull(
                voices,
                english.takeIf { speech == SpokenLanguage.ENGLISH },
                hindi.takeIf { speech == SpokenLanguage.HINDI },
                engine,
            ).filterNot { it in added }

        override fun checkEngine(): Boolean {
            engineChecks++
            return true
        }

        override fun downloadFile(f: VoicePackFile): File? = null

        override fun freeBytes() = free

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
        ): List<VoicePhrase> = emptyList()

        override fun speak(
            projectId: ProjectId,
            phrases: List<VoicePhrase>,
            voice: VoiceChoice,
            language: SpokenLanguage,
            lengthMs: Long?,
            progress: (Float) -> Unit,
        ): SpokenVoice = error("unused")
    }

    private class Downloads : PackDownloads {
        override val state = MutableStateFlow<PackDownloadState>(PackDownloadState.Idle)
        val started = mutableListOf<List<VoicePackFile>>()
        var cancelled = 0

        override fun start(files: List<VoicePackFile>) {
            started += files
        }

        override fun cancel() {
            cancelled++
        }

        override suspend fun cancelAndWait() = cancel()
    }

    private val notes = mutableListOf<VoiceNote>()

    private fun controller(
        tools: VoiceTools,
        downloads: PackDownloads,
    ) = VoiceChoiceController(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        tools,
        ProjectId("p"),
        project = { ProjectFactory.blank(ProjectId("p")) },
        edit = { true },
        onNote = { notes += it },
        onCheckWords = {},
        onInserted = {},
        downloads = downloads,
        worker = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `choosing a voice offers one download of the voices, this phone's engine and the words of the language in use`() {
        val tools = Tools()
        val downloads = Downloads()
        val c = controller(tools, downloads)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        assertThat(c.packAsk.value!!.files).containsExactly(voices, english, engine).inOrder()
        assertThat(c.packAsk.value!!.room).isEqualTo(0)
        c.downloadPack()
        assertThat(downloads.started.single()).containsExactly(voices, english, engine).inOrder()
        assertThat(c.download.value).isInstanceOf(PackDownloadState.Waiting::class.java)

        // Hindi in use: its words file instead of the English one.
        val c2 = controller(Tools(), Downloads())
        c2.speakIn(SpokenLanguage.HINDI)
        c2.choose(VoiceChoice.INDIAN_MALE)
        assertThat(c2.packAsk.value!!.files).containsExactly(voices, hindi, engine).inOrder()
    }

    @Test
    fun `when the download is done the engine is checked, the dialog closes and the pack is said to be added`() {
        val tools = Tools()
        val downloads = Downloads()
        val c = controller(tools, downloads)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.downloadPack()
        downloads.state.value = PackDownloadState.Running("w1", PackProgress(30, 140))
        assertThat((c.download.value as PackDownloadState.Running).progress.done).isEqualTo(30)
        // Hidden, it goes on; the note still comes when it is done.
        c.hidePack()
        assertThat(c.packAsk.value).isNull()
        tools.added += listOf(voices, english, engine)
        downloads.state.value = PackDownloadState.Done("w1")
        assertThat(notes).containsExactly(VoiceNote.PACK_ADDED)
        assertThat(tools.engineChecks).isEqualTo(1)
        assertThat(c.download.value).isEqualTo(PackDownloadState.Idle)
        assertThat(c.choice.value).isEqualTo(VoiceChoice.BRITISH_FEMALE)
    }

    @Test
    fun `a failed download shows why with Try again, and an older download's end is not shown`() {
        val tools = Tools()
        val downloads = Downloads()
        downloads.state.value = PackDownloadState.Failed("old", PackDownloadError.NETWORK)
        val c = controller(tools, downloads)
        assertThat(c.download.value).isEqualTo(PackDownloadState.Idle)
        downloads.state.value = PackDownloadState.Done("old2")
        assertThat(notes).isEmpty()
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.downloadPack()
        downloads.state.value = PackDownloadState.Waiting("w2")
        downloads.state.value = PackDownloadState.Failed("w2", PackDownloadError.SERVER)
        assertThat(c.download.value).isEqualTo(PackDownloadState.Failed("w2", PackDownloadError.SERVER))
        c.downloadPack()
        assertThat(downloads.started).hasSize(2)
        // Not now clears it, so the next dialog starts at Download.
        downloads.state.value = PackDownloadState.Failed("w3", PackDownloadError.NETWORK)
        c.dismissPack()
        assertThat(c.download.value).isEqualTo(PackDownloadState.Idle)
    }

    @Test
    fun `a download that ends while the dialog asks for more keeps asking for what is still missing`() {
        val tools = Tools()
        val downloads = Downloads()
        val c = controller(tools, downloads)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.downloadPack()
        downloads.state.value = PackDownloadState.Running("w1", PackProgress(30, 140))
        c.hidePack()
        // Meanwhile Hindi words are to be found: the dialog shows again, for the Hindi file too.
        c.askForPack(SpokenLanguage.HINDI)
        tools.added += listOf(voices, english, engine)
        downloads.state.value = PackDownloadState.Done("w1")
        assertThat(notes).containsExactly(VoiceNote.PACK_STILL_MISSING)
        assertThat(c.packAsk.value!!.files).containsExactly(hindi)
    }

    @Test
    fun `leaving a no-room error removes the kept file, while a network error keeps its part`() {
        val downloads = Downloads()
        val c = controller(Tools(), downloads)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.downloadPack()
        downloads.state.value = PackDownloadState.Waiting("w1")
        downloads.state.value = PackDownloadState.Failed("w1", PackDownloadError.NETWORK)
        c.dismissPack()
        assertThat(downloads.cancelled).isEqualTo(0)
        c.choose(VoiceChoice.BRITISH_MALE)
        c.downloadPack()
        downloads.state.value = PackDownloadState.Waiting("w2")
        downloads.state.value = PackDownloadState.Failed("w2", PackDownloadError.NO_SPACE, 10)
        assertThat(c.download.value).isInstanceOf(PackDownloadState.Failed::class.java)
        c.useBrowser()
        assertThat(downloads.cancelled).isEqualTo(1)
        assertThat(c.download.value).isEqualTo(PackDownloadState.Idle)
    }

    @Test
    fun `Cancel stops the download and closes the dialog`() {
        val downloads = Downloads()
        val c = controller(Tools(), downloads)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.downloadPack()
        downloads.state.value = PackDownloadState.Running("w1", PackProgress(1, 140))
        c.cancelDownload()
        assertThat(downloads.cancelled).isEqualTo(1)
        assertThat(c.packAsk.value).isNull()
        assertThat(c.download.value).isEqualTo(PackDownloadState.Idle)
    }

    @Test
    fun `without room the download does not start and the dialog says how much to free`() {
        val tools = Tools().apply { free = 100 }
        val downloads = Downloads()
        val c = controller(tools, downloads)
        c.choose(VoiceChoice.BRITISH_FEMALE)
        val need = (90 + 40 + 10) * 2 + 90 + VoicePackStore.SPARE_BYTES - 100
        assertThat(c.packAsk.value!!.room).isEqualTo(need)
        c.downloadPack()
        assertThat(downloads.started).isEmpty()
        tools.free = Long.MAX_VALUE
        c.downloadPack()
        assertThat(downloads.started).hasSize(1)
    }

    @Test
    fun `the browser fallback keeps the steps and Add the downloaded files`() {
        val c = controller(Tools(), Downloads())
        c.choose(VoiceChoice.BRITISH_FEMALE)
        c.useBrowser()
        assertThat(c.packAsk.value!!.steps).isTrue()
    }
}
