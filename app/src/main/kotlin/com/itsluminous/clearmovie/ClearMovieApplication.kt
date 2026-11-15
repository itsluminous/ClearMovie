package com.itsluminous.clearmovie

import android.app.Application
import com.itsluminous.clearmovie.data.settings.SettingsRepository
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
        // settings file is a few bytes, so this read takes milliseconds.
        applyNightMode(runBlocking { settingsRepository.settings.first() }.themeMode)
    }
}
