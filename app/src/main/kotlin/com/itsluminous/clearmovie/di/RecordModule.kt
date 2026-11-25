package com.itsluminous.clearmovie.di

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.BuildConfig
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.record.AudioRecordMic
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.data.voice.PackDownloader
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.PackVoiceTools
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.work.ExportWorker
import com.itsluminous.clearmovie.work.WorkPackDownloads
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.InputStream
import javax.inject.Singleton

/** Voice recordings made in the editor (cm-record-voice). */
@Module
@InstallIn(SingletonComponent::class)
object RecordModule {
    /**
     * A test may put its own sound in place of the microphone (the
     * emulator has none to speak into); null in the app.
     */
    @Volatile
    var testMics: MicSources? = null

    /**
     * A test may put its own voice engine in place of the voice pack's
     * (cm-voice-progress), to hold a job in one state; null in the app, and
     * never read in a release build.
     */
    @Volatile
    var testVoiceTools: VoiceTools? = null

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideVoiceRecordings(
        @ApplicationContext context: Context,
        files: AppFiles,
        workManager: WorkManager,
    ): VoiceRecordings =
        VoiceRecordings(
            files,
            mics = { (testMics ?: MicSources { AudioRecordMic(context) }).create() },
            exportBusy = {
                runCatching { workManager.getWorkInfosForUniqueWork(ExportWorker.UNIQUE_NAME).get().any { !it.state.isFinished } }
                    .getOrDefault(true)
            },
        )

    /**
     * The voice choice's tools (cm-voice-choice): the voice pack this app
     * takes (its manifest is in the app's assets), on the processor type the
     * app runs as. Without a readable manifest there are no other voices.
     */
    @Provides
    @Singleton
    fun provideVoiceTools(
        @ApplicationContext context: Context,
        files: AppFiles,
        recordings: VoiceRecordings,
        freeSpace: FreeSpace,
    ): VoiceTools {
        fun asset(name: String) =
            runCatching {
                context.assets
                    .open(name)
                    .bufferedReader()
                    .use { VoicePackManifest.parse(it.readText()) }
            }.getOrNull()
        val base =
            asset(VoicePackStore.MANIFEST_ASSET) ?: return if (BuildConfig.DEBUG) TestableVoiceTools(VoiceTools.NONE) else VoiceTools.NONE
        // Hindi HD (cm-hindi-voices) is an add-on: without its manifest the pack works as before.
        val manifest = asset(VoicePackStore.HINDI_HD_MANIFEST_ASSET)?.let { runCatching { base + it }.getOrNull() } ?: base
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val abi = abis.firstOrNull { manifest.supports(it) } ?: abis.firstOrNull().orEmpty()
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }.totalMem
        // A 32-bit app cannot hold the full model's 3.5 GB: at most the light one.
        val model = HindiModel.of(memory).let { if (Process.is64Bit() || it != HindiModel.FULL) it else HindiModel.LIGHT }
        val store = VoicePackStore(context.filesDir, manifest, abi, model, freeSpace::bytes)
        val app = PackVoiceTools(store, files, recordings)
        // The test seam only in a debug build (device tests); a release build uses the pack's engine directly.
        return if (BuildConfig.DEBUG) TestableVoiceTools(app) else app
    }

    /** [app]'s tools, or [testVoiceTools] while a test has put its own in (read on every call, as [testMics] is). */
    private class TestableVoiceTools(
        private val app: VoiceTools,
    ) : VoiceTools {
        private val tools: VoiceTools get() = testVoiceTools ?: app
        override val manifest get() = tools.manifest
        override val supported get() = tools.supported
        override val engineWorks get() = tools.engineWorks

        override fun checkEngine() = tools.checkEngine()

        override fun missing(
            speech: SpokenLanguage?,
            voice: VoiceChoice?,
        ) = tools.missing(speech, voice)

        override val hindiModel get() = tools.hindiModel

        override fun hindiHdMissing() = tools.hindiHdMissing()

        override fun notAdded() = tools.notAdded()

        override fun pieceFile(name: String) = tools.pieceFile(name)

        override fun piecePart(name: String) = tools.piecePart(name)

        override fun checkPiece(
            f: VoicePackFile,
            index: Int,
            file: File,
        ) = tools.checkPiece(f, index, file)

        override fun addPackFiles(
            sources: List<() -> InputStream?>,
            progress: (Float) -> Unit,
        ) = tools.addPackFiles(sources, progress)

        override fun packBytes() = tools.packBytes()

        override fun removePack() = tools.removePack()

        override fun downloadFile(f: VoicePackFile) = tools.downloadFile(f)

        override fun addDownloaded(
            f: VoicePackFile,
            files: List<File>,
            stopped: () -> Boolean,
        ) = tools.addDownloaded(f, files, stopped)

        override fun clearDownloads() = tools.clearDownloads()

        override fun freeBytes() = tools.freeBytes()

        override fun recognize(
            projectId: ProjectId,
            media: MediaRef.Copied,
            language: SpokenLanguage,
            progress: (Float) -> Unit,
        ) = tools.recognize(projectId, media, language, progress)

        override fun speak(
            projectId: ProjectId,
            phrases: List<VoicePhrase>,
            voice: VoiceChoice,
            language: SpokenLanguage,
            lengthMs: Long?,
            progress: (Float) -> Unit,
        ) = tools.speak(projectId, phrases, voice, language, lengthMs, progress)
    }

    /** The one class that uses the internet: it downloads voice pack files (cm-voice-pack-download). */
    @Provides
    @Singleton
    fun providePackDownloader(): PackDownloader = PackDownloader()

    /** The voice pack download, in a background worker. */
    @Provides
    @Singleton
    fun providePackDownloads(
        workManager: WorkManager,
        tools: VoiceTools,
        @ApplicationScope scope: CoroutineScope,
    ): PackDownloads = WorkPackDownloads(workManager, tools, scope)
}
