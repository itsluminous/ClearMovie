package com.itsluminous.clearmovie.di

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.record.AudioRecordMic
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.data.voice.PackDownloader
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.PackVoiceTools
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.work.ExportWorker
import com.itsluminous.clearmovie.work.WorkPackDownloads
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
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
        val manifest =
            runCatching {
                context.assets
                    .open(
                        VoicePackStore.MANIFEST_ASSET,
                    ).bufferedReader()
                    .use { VoicePackManifest.parse(it.readText()) }
            }.getOrNull() ?: return VoiceTools.NONE
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val abi = abis.firstOrNull { manifest.supports(it) } ?: abis.firstOrNull().orEmpty()
        return PackVoiceTools(VoicePackStore(context.filesDir, manifest, abi, freeSpace::bytes), files, recordings)
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
