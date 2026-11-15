package com.itsluminous.clearmovie

import android.app.Application
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.ui.theme.applyNightMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Application entry point; the Hilt component lives here. */
@HiltAndroidApp
class ClearMovieApplication : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // The Light/Dark override must be in place before the first activity
        // inflates, or it would start in the phone's mode and recreate. The
        // settings file is a few bytes, so this read takes milliseconds. Any
        // failure falls back to following the phone rather than crashing start-up.
        val settings = runCatching { runBlocking { settingsRepository.settings.first() } }.getOrDefault(AppSettings())
        applyNightMode(settings.themeMode)
    }
}
