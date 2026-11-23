package com.itsluminous.clearmovie.di

import android.content.Context
import android.os.StatFs
import androidx.room.Room
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.files.StorageUsage
import com.itsluminous.clearmovie.data.media.AndroidMediaFactsProbe
import com.itsluminous.clearmovie.data.media.AndroidUriGrants
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.MediaResolver
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.media.UriGrants
import com.itsluminous.clearmovie.data.preset.AndroidFrameKeyCheck
import com.itsluminous.clearmovie.data.preset.AndroidFrameStills
import com.itsluminous.clearmovie.data.preset.AndroidMediaProbe
import com.itsluminous.clearmovie.data.preset.AndroidSoundLoudness
import com.itsluminous.clearmovie.data.preset.FrameKeyCheck
import com.itsluminous.clearmovie.data.preset.FrameStills
import com.itsluminous.clearmovie.data.preset.MediaProbe
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SoundLoudness
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.render.media3.AndroidSoundTracks
import com.itsluminous.clearmovie.render.media3.SoundTracks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app-wide coroutine scope: work that must outlive any screen, such as autosave. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Free bytes in app storage, where copies go. */
fun interface FreeSpace {
    fun bytes(): Long
}

/** Projects, presets and media access (ARCHITECTURE sections 8 to 10). */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        // Taken first, so it is read before the database is made (cm-home-presets).
        @Suppress("UNUSED_PARAMETER") history: InstallHistory,
    ): ClearMovieDatabase =
        Room
            .databaseBuilder(context, ClearMovieDatabase::class.java, ClearMovieDatabase.FILE_NAME)
            .addMigrations(*ClearMovieDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun provideAppFiles(
        @ApplicationContext context: Context,
    ): AppFiles = AppFiles(context.filesDir, context.cacheDir)

    @Provides
    fun provideFreeSpace(
        @ApplicationContext context: Context,
    ): FreeSpace = FreeSpace { runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L) }

    @Provides
    @Singleton
    fun provideGateway(
        @ApplicationContext context: Context,
    ): MediaStoreGateway = MediaStoreGateway(context)

    @Provides
    @Singleton
    fun provideGrants(
        @ApplicationContext context: Context,
    ): UriGrants = AndroidUriGrants(context.contentResolver)

    @Provides
    fun provideCopier(): MediaCopier = MediaCopier()

    @Provides
    @Singleton
    fun provideProbe(
        @ApplicationContext context: Context,
    ): MediaProbe = AndroidMediaProbe(context = context)

    @Provides
    @Singleton
    fun provideProjectRepository(
        db: ClearMovieDatabase,
        files: AppFiles,
        grants: UriGrants,
        @ApplicationScope scope: CoroutineScope,
    ): ProjectRepository = ProjectRepository(db, files, scope, grants = grants)

    @Provides
    @Singleton
    fun provideMediaAccess(
        @ApplicationContext context: Context,
        gateway: MediaStoreGateway,
        grants: UriGrants,
        copier: MediaCopier,
        files: AppFiles,
        db: ClearMovieDatabase,
        freeSpace: FreeSpace,
    ): MediaAccess =
        MediaAccess(
            resolver = context.contentResolver,
            mediaResolver = MediaResolver(gateway, grants, ownAuthorityPrefix = context.packageName),
            gateway = gateway,
            grants = grants,
            copier = copier,
            files = files,
            db = db,
            freeBytes = freeSpace::bytes,
        )

    @Provides
    @Singleton
    fun providePresetStore(
        @ApplicationContext context: Context,
        gateway: MediaStoreGateway,
        db: ClearMovieDatabase,
        files: AppFiles,
        copier: MediaCopier,
        probe: MediaProbe,
        freeSpace: FreeSpace,
        history: InstallHistory,
        grants: UriGrants,
    ): PresetStore =
        PresetStore(
            context.assets,
            context.contentResolver,
            gateway,
            db,
            files,
            copier,
            probe,
            freeSpace::bytes,
            installedBefore = { history.openedBefore },
            grants = grants,
        )

    /**
     * Whether the app ran on this phone before this launch: its database is
     * made the first time it runs, so it is there only after an earlier
     * launch, of this version or an older one. Read before the database is
     * opened ([provideDatabase] takes it), never from the install dates, which
     * call an install updated before it was ever opened an old one. On the
     * first launch ever there is no preset to keep, and that is recorded at
     * once, before the database exists, so a launch cut short never makes a
     * new install look like an old one.
     */
    @Provides
    @Singleton
    fun provideInstallHistory(
        @ApplicationContext context: Context,
        files: AppFiles,
    ): InstallHistory {
        val openedBefore = context.getDatabasePath(ClearMovieDatabase.FILE_NAME).exists()
        if (!openedBefore) {
            runCatching {
                files.presetSeedMarker.parentFile?.mkdirs()
                files.presetSeedMarker.createNewFile()
            }
        }
        return InstallHistory(openedBefore)
    }

    @Provides
    fun provideFrameStills(
        @ApplicationContext context: Context,
    ): FrameStills = AndroidFrameStills(context)

    @Provides
    fun provideSoundTracks(
        @ApplicationContext context: Context,
    ): SoundTracks = AndroidSoundTracks(context)

    @Provides
    fun provideSoundLoudness(
        @ApplicationContext context: Context,
    ): SoundLoudness = AndroidSoundLoudness(context)

    @Provides
    fun provideMediaFactsProbe(
        @ApplicationContext context: Context,
        files: AppFiles,
    ): MediaFactsProbe = AndroidMediaFactsProbe(context, files)

    @Provides
    fun provideFrameKeyCheck(
        @ApplicationContext context: Context,
        files: AppFiles,
    ): FrameKeyCheck = AndroidFrameKeyCheck(context, files)

    @Provides
    fun provideStorageUsage(
        db: ClearMovieDatabase,
        files: AppFiles,
    ): StorageUsage = StorageUsage(db, files)
}

/** What the app knew about this phone when it started (cm-home-presets). */
class InstallHistory(
    /** The app had run here before this launch. */
    val openedBefore: Boolean,
)
