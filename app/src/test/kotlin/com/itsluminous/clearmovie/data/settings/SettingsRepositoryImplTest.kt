package com.itsluminous.clearmovie.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File by lazy { File(tmp.root, "settings.preferences_pb") }

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    @Test
    fun `defaults are follow the phone, with wallpaper colours on`() =
        runTest {
            val repo = SettingsRepositoryImpl(dataStore(backgroundScope))
            assertThat(repo.settings.first()).isEqualTo(AppSettings(ThemeMode.SYSTEM, dynamicColor = true))
        }

    @Test
    fun `choices persist across a new DataStore on the same file`() =
        runTest {
            val job = Job()
            val first = SettingsRepositoryImpl(dataStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)))
            first.setThemeMode(ThemeMode.DARK)
            first.setDynamicColor(false)
            job.cancelAndJoin()

            val second = SettingsRepositoryImpl(dataStore(backgroundScope))
            assertThat(second.settings.first()).isEqualTo(AppSettings(ThemeMode.DARK, dynamicColor = false))
        }

    @Test
    fun `an unknown stored theme falls back to following the phone`() =
        runTest {
            val store = dataStore(backgroundScope)
            store.edit { it[stringPreferencesKey("theme_mode")] = "PURPLE" }
            assertThat(SettingsRepositoryImpl(store).settings.first().themeMode).isEqualTo(ThemeMode.SYSTEM)
        }
}
