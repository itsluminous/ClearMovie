package com.itsluminous.clearmovie

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The in-app language picker switches the UI between English, Hindi and
 * Hinglish, and the choice is still applied when the app is opened again.
 * Runs on API 29 (AppCompat stores the choice) and on 36 (LocaleManager does).
 */
@RunWith(AndroidJUnit4::class)
class LanguagePickerTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resetLanguage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Before
    fun startInPhoneLanguage() = resetLanguage()

    @After
    fun backToPhoneLanguage() = resetLanguage()

    private fun stringsFor(tag: String): Context =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        )

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(TIMEOUT_MS) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openSettings(home: Context) {
        compose.waitForText(home.getString(R.string.home_empty_title))
        compose.onNodeWithContentDescription(home.getString(R.string.action_settings)).performClick()
        compose.waitForText(home.getString(R.string.settings_section_language))
    }

    @Test
    fun pickerSwitchesBetweenAllThreeLanguagesAndTheChoiceSurvivesAReopen() {
        val english = stringsFor("en")
        val hindi = stringsFor("hi")
        val hinglish = stringsFor("hi-Latn")

        ActivityScenario.launch(MainActivity::class.java).use {
            openSettings(english)

            compose.onNodeWithText("हिन्दी").performScrollTo().performClick()
            compose.waitForText(hindi.getString(R.string.settings_section_language))
            assertThat(AppCompatDelegate.getApplicationLocales().toLanguageTags()).isEqualTo("hi")

            compose.onNodeWithText("Hinglish").performScrollTo().performClick()
            compose.waitForText(hinglish.getString(R.string.settings_section_language))
            assertThat(AppCompatDelegate.getApplicationLocales().toLanguageTags()).isEqualTo("hi-Latn")
        }

        // Opened again: the app starts in Hinglish without touching the picker.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText(hinglish.getString(R.string.home_empty_title))
            compose.onNodeWithContentDescription(hinglish.getString(R.string.action_settings)).performClick()
            compose.waitForText(hinglish.getString(R.string.settings_section_language))

            compose.onNodeWithText(hinglish.getString(R.string.language_system)).performScrollTo().performClick()
            compose.waitForText(english.getString(R.string.settings_section_language))
            assertThat(AppCompatDelegate.getApplicationLocales().isEmpty).isTrue()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
