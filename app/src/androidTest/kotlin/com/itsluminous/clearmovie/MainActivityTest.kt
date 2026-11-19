package com.itsluminous.clearmovie

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The debug app launches, opens Settings, and keeps its screen across recreation. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every test starts and ends following the phone, even if one fails midway. */
    @Before
    @After
    fun followThePhone() {
        val app = ApplicationProvider.getApplicationContext<ClearMovieApplication>()
        runBlocking { app.settingsRepository.setThemeMode(ThemeMode.SYSTEM) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTextExists(text) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextExists(text: String) =
        onAllNodes(
            androidx.compose.ui.test
                .hasText(text),
        ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun launchesToHomeAndOpensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText(context.getString(R.string.home_new_video))
            compose.onNodeWithContentDescription(context.getString(R.string.action_settings)).performClick()
            waitForText(context.getString(R.string.settings_section_theme))
            assertThat(scenario.state.isAtLeast(Lifecycle.State.RESUMED)).isTrue()
        }
    }

    @Test
    fun recreateKeepsTheSettingsScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText(context.getString(R.string.home_new_video))
            compose.onNodeWithContentDescription(context.getString(R.string.action_settings)).performClick()
            waitForText(context.getString(R.string.settings_section_theme))

            scenario.recreate()

            waitForText(context.getString(R.string.settings_section_theme))
            compose.onNodeWithText(context.getString(R.string.settings_section_language)).assertExists()
        }
    }

    @Test
    fun darkOverrideReachesTheWholeActivityAndSameAsPhoneRestoresIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText(context.getString(R.string.home_new_video))
            compose.onNodeWithContentDescription(context.getString(R.string.action_settings)).performClick()
            waitForText(context.getString(R.string.settings_section_theme))

            compose.onNodeWithText(context.getString(R.string.theme_dark)).performScrollTo().performClick()
            // Wait on the recreated activity's own configuration: it turns night, so
            // the window, values-night resources and dialogs follow the override,
            // not only Compose.
            compose.waitUntil(TIMEOUT_MS) {
                var night = 0
                scenario.onActivity { night = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
                night == Configuration.UI_MODE_NIGHT_YES
            }
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)
            waitForText(context.getString(R.string.settings_section_theme))

            compose.onNodeWithText(context.getString(R.string.theme_system)).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) {
                AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
