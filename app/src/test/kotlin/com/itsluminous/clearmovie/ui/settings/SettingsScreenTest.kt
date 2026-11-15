package com.itsluminous.clearmovie.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val languages =
        listOf(LanguageOption("en", "English"), LanguageOption("hi", "हिन्दी"), LanguageOption("hi-Latn", "Hinglish"))

    private val selectedThemes = mutableListOf<ThemeMode>()
    private val dynamicChanges = mutableListOf<Boolean>()
    private val selectedLanguages = mutableListOf<String?>()

    @Composable
    private fun Screen(
        settings: AppSettings = AppSettings(),
        dynamicColorSupported: Boolean = true,
        selectedLanguageTag: String? = null,
    ) {
        SettingsScreen(
            settings = settings,
            dynamicColorSupported = dynamicColorSupported,
            languages = languages,
            selectedLanguageTag = selectedLanguageTag,
            versionName = "0.1.0",
            onBack = {},
            onThemeModeSelected = { selectedThemes += it },
            onDynamicColorChanged = { dynamicChanges += it },
            onLanguageSelected = { selectedLanguages += it },
        )
    }

    private fun showAt(
        size: ScreenSize,
        fontScale: Float = 1f,
    ) {
        compose.setContent { AtSize(size, fontScale) { ClearMovieTheme(dynamicColor = false) { Screen() } } }
    }

    private fun string(id: Int) = context.getString(id)

    private fun assertAllShown() {
        compose.onNodeWithText(string(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
        val labels =
            listOf(
                string(R.string.settings_section_theme),
                string(R.string.theme_system),
                string(R.string.theme_light),
                string(R.string.theme_dark),
                string(R.string.settings_dynamic_color),
                string(R.string.settings_dynamic_color_summary),
                string(R.string.settings_section_language),
                string(R.string.language_system),
                "English",
                "हिन्दी",
                "Hinglish",
                string(R.string.settings_section_about),
                context.getString(R.string.settings_version, "0.1.0"),
            )
        for (label in labels) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun compact() = showAt(ScreenSize.COMPACT).also { assertAllShown() }

    @Test
    fun medium() = showAt(ScreenSize.MEDIUM).also { assertAllShown() }

    @Test
    fun expanded() = showAt(ScreenSize.EXPANDED).also { assertAllShown() }

    @Test
    fun `compact at font scale 2`() = showAt(ScreenSize.COMPACT, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `medium at font scale 2`() = showAt(ScreenSize.MEDIUM, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `expanded at font scale 2`() = showAt(ScreenSize.EXPANDED, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `tapping a row reports the choice`() {
        showAt(ScreenSize.COMPACT)
        compose.onNodeWithText(string(R.string.theme_dark)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.settings_dynamic_color)).performScrollTo().performClick()
        compose.onNodeWithText("हिन्दी").performScrollTo().performClick()
        compose.onNodeWithText("Hinglish").performScrollTo().performClick()
        assertThat(selectedThemes).containsExactly(ThemeMode.DARK)
        assertThat(dynamicChanges).containsExactly(false)
        assertThat(selectedLanguages).containsExactly("hi", "hi-Latn").inOrder()
    }

    @Test
    fun `the current choices are shown selected`() {
        compose.setContent {
            AtSize(ScreenSize.COMPACT) {
                ClearMovieTheme(dynamicColor = false) {
                    Screen(settings = AppSettings(ThemeMode.LIGHT, dynamicColor = true), selectedLanguageTag = "hi-Latn")
                }
            }
        }
        compose.onNodeWithText(string(R.string.theme_light)).performScrollTo().assertIsSelected()
        compose.onNodeWithText("Hinglish").performScrollTo().assertIsSelected()
    }

    @Test
    fun `on Android 10 and 11 wallpaper colours are off, disabled and explained`() {
        compose.setContent {
            AtSize(ScreenSize.COMPACT) {
                ClearMovieTheme(dynamicColor = false) { Screen(dynamicColorSupported = false) }
            }
        }
        compose.onNodeWithText(string(R.string.settings_dynamic_color_unavailable)).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(string(R.string.settings_dynamic_color))
            .performScrollTo()
            .assertIsNotEnabled()
            .assertIsOff()
            .performClick()
        assertThat(dynamicChanges).isEmpty()
    }

    @Test
    fun `the scroll position survives saved-state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AtSize(ScreenSize.COMPACT, fontScale = 2f) { ClearMovieTheme(dynamicColor = false) { Screen() } }
        }
        val version = context.getString(R.string.settings_version, "0.1.0")
        compose.onNodeWithTag(SETTINGS_LIST_TAG).performScrollToNode(hasText(version))
        compose.onNodeWithText(string(R.string.settings_section_theme)).assertIsNotDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(version).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_section_theme)).assertIsNotDisplayed()
    }
}
