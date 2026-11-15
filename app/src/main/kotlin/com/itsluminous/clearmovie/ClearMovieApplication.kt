package com.itsluminous.clearmovie

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.project.AutosaveGuard
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.di.ApplicationScope
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.ui.theme.applyNightMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Application entry point; the Hilt component lives here. */
@HiltAndroidApp
class ClearMovieApplication : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var projectRepository: ProjectRepository

    @Inject
    lateinit var mediaAccess: MediaAccess

    @Inject
    lateinit var appFiles: AppFiles

    @Inject
    lateinit var presetStore: PresetStore

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // The Light/Dark override must be in place before the first activity
        // inflates, or it would start in the phone's mode and recreate. The
        // settings file is a few bytes, so this read takes milliseconds. Any
        // failure falls back to following the phone rather than crashing start-up.
        val settings = runCatching { runBlocking { settingsRepository.settings.first() } }.getOrDefault(AppSettings())
        applyNightMode(settings.themeMode)

        // Autosave: write pending edits when the app goes to the background
        // (before any low-memory kill) and when it crashes.
        val guard = AutosaveGuard(projectRepository, appScope)
        guard.installCrashHandler()
        ProcessLifecycleOwner.get().lifecycle.addObserver(guard)

        // Tidy up after an earlier process that was killed mid-copy, or between
        // resolving links and adding them to a project.
        appScope.launch {
            runCatching { appFiles.sweep(System.currentTimeMillis()) }
            runCatching { presetStore.sweepOrphans() }
            runCatching { mediaAccess.reconcileGrants(contentResolver.persistedUriPermissions.map { it.uri.toString() }) }
        }
    }
}
