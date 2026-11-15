package com.itsluminous.clearmovie.data.settings

import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** The user's appearance settings, and small app facts, persisted across launches. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /**
     * Whether the app has asked for photo and video access before. Android does
     * not say whether a refused permission was ever requested, so the app keeps
     * this to tell "never asked" (show the explanation) from "refused".
     */
    val mediaAccessAsked: Flow<Boolean>

    suspend fun setMediaAccessAsked()

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)
}
