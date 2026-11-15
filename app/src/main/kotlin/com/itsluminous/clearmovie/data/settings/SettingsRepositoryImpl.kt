package com.itsluminous.clearmovie.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** [SettingsRepository] on DataStore Preferences. */
@Singleton
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override val settings: Flow<AppSettings> =
            dataStore.data
                // An unreadable file must not keep the splash up forever: fall back to defaults.
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map { prefs ->
                    AppSettings(
                        themeMode = prefs[THEME_MODE].toThemeMode(),
                        dynamicColor = prefs[DYNAMIC_COLOR] ?: AppSettings().dynamicColor,
                    )
                }.distinctUntilChanged()

        override suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.edit { it[THEME_MODE] = mode.name }
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            dataStore.edit { it[DYNAMIC_COLOR] = enabled }
        }

        private fun String?.toThemeMode(): ThemeMode = ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.SYSTEM

        private companion object {
            // Stored by enum NAME, so reordering ThemeMode never changes a saved choice.
            val THEME_MODE = stringPreferencesKey("theme_mode")
            val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        }
    }
