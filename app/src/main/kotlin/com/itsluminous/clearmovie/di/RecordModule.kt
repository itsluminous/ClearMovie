package com.itsluminous.clearmovie.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.record.AudioRecordMic
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.work.ExportWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
}
