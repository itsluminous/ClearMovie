package com.itsluminous.clearmovie.di

import android.content.Context
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.data.export.ExportStore
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.work.ExportPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File

/** The export (ARCHITECTURE section 12). */
@Module
@InstallIn(SingletonComponent::class)
object ExportModule {
    @Provides
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    fun provideExportStore(
        @ApplicationContext context: Context,
    ): ExportStore = ExportStore(context.contentResolver)

    @UnstableApi
    @Provides
    fun provideExportPipeline(
        @ApplicationContext context: Context,
        repository: ProjectRepository,
        projectMedia: ProjectMedia,
        presets: PresetStore,
        mediaAccess: MediaAccess,
        store: ExportStore,
    ): ExportPipeline =
        ExportPipeline(context, repository, projectMedia, presets, mediaAccess, store, freeBytes = { exportFreeBytes(context) })

    /**
     * Free bytes where an export writes: the temporary file goes to the
     * app's cache, the video to shared storage. They are usually the same
     * disk; the smaller answer counts.
     */
    fun exportFreeBytes(context: Context): Long =
        listOfNotNull(context.cacheDir, context.getExternalFilesDir(null))
            .minOfOrNull { dir -> runCatching { StatFs(File(dir.path).path).availableBytes }.getOrDefault(0L) } ?: 0L
}
