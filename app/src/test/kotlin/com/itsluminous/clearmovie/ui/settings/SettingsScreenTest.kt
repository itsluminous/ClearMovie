package com.itsluminous.clearmovie.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

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
        onOpenProjectPage: () -> Unit = {},
        crashAtMs: Long? = null,
        onShareCrash: () -> Unit = {},
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
            onOpenProjectPage = onOpenProjectPage,
            crashAtMs = crashAtMs,
            onShareCrash = onShareCrash,
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
                string(R.string.settings_version_link),
            )
        for (label in labels) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        // Presets are made and edited from Home only (cm-home-presets).
        compose.onNodeWithText(string(R.string.preset_edit)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.home_preset_ready)).assertDoesNotExist()
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
    fun `the version opens ClearMovie's GitHub page in the browser`() {
        compose.setContent {
            val activity = LocalContext.current
            AtSize(ScreenSize.COMPACT) {
                ClearMovieTheme(dynamicColor = false) { Screen(onOpenProjectPage = { openProjectPage(activity) }) }
            }
        }
        compose
            .onNodeWithTag(SETTINGS_VERSION_TAG)
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.data).isEqualTo(Uri.parse("https://github.com/itsluminous/ClearMovie"))
        assertThat(started.categories).containsExactly(Intent.CATEGORY_BROWSABLE)
        // Only the address: nothing of the app's own goes with it.
        assertThat(started.extras).isNull()
        assertThat(started.component).isNull()
        assertThat(started.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)).isEqualTo(0)
    }

    @Test
    fun `with no browser the version says so instead of failing`() {
        val app = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(app)
        shadow.checkActivities(true)
        compose.setContent {
            val activity = LocalContext.current
            AtSize(ScreenSize.COMPACT) {
                ClearMovieTheme(dynamicColor = false) { Screen(onOpenProjectPage = { openProjectPage(activity) }) }
            }
        }
        compose.onNodeWithTag(SETTINGS_VERSION_TAG).performScrollTo().performClick()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(string(R.string.settings_no_browser))
    }

    @Test
    fun `About offers to share the crash only after one, and shares its text through the share sheet`() {
        var crashAt by mutableStateOf<Long?>(null)
        compose.setContent {
            val activity = LocalContext.current
            AtSize(ScreenSize.COMPACT) {
                ClearMovieTheme(dynamicColor = false) {
                    Screen(crashAtMs = crashAt, onShareCrash = { shareCrash(activity, "the trace") })
                }
            }
        }
        compose.onNodeWithTag(SETTINGS_CRASH_TAG).assertDoesNotExist()
        crashAt = 1_790_000_000_000L
        compose
            .onNodeWithTag(SETTINGS_CRASH_TAG)
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("the trace")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo(string(R.string.settings_crash_subject))
        // Text only: no file or link of the app's own goes with it.
        assertThat(send.data).isNull()
        assertThat(send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)).isEqualTo(0)
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
