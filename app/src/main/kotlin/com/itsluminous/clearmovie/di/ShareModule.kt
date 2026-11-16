package com.itsluminous.clearmovie.di

import android.content.Context
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.AndroidCaptureTimeReader
import com.itsluminous.clearmovie.data.media.CaptureTimeReader
import com.itsluminous.clearmovie.data.media.MimeLookup
import com.itsluminous.clearmovie.data.media.PendingShare
import com.itsluminous.clearmovie.data.media.ResolverMimeLookup
import com.itsluminous.clearmovie.ui.share.ShareViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Named

/** Share from the gallery (cm-share). */
@Module
@InstallIn(SingletonComponent::class)
object ShareModule {
    @Provides
    fun provideMimeLookup(
        @ApplicationContext context: Context,
    ): MimeLookup = ResolverMimeLookup(context.contentResolver)

    @Provides
    fun provideCaptureTimeReader(
        @ApplicationContext context: Context,
        files: AppFiles,
    ): CaptureTimeReader = AndroidCaptureTimeReader(context, files)

    @Provides
    fun providePendingShare(
        @ApplicationContext context: Context,
    ): PendingShare = PendingShare(File(File(context.noBackupFilesDir, "share"), "pending.links"))

    @Provides
    @Named(ShareViewModel.IO_DISPATCHER)
    fun provideShareDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
