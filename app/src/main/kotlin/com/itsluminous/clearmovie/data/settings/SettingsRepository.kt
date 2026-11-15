package com.itsluminous.clearmovie.data.settings

import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** The user's appearance settings, persisted across launches. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)
}
