package com.itsluminous.clearmovie

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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
 * Hinglish, and the choice is stored where it survives a process restart:
 * on API 33+ in the system's LocaleManager, on API 29-32 in the file AppCompat
 * writes for `autoStoreLocales` and reads back on the next cold start. (A
 * second launch in the same process alone would not prove that, because
 * AppCompat also keeps the choice in memory.) The cold restart itself was
 * checked on the API 36 emulator by force-stopping and relaunching the app.
 */
@RunWith(AndroidJUnit4::class)
class LanguagePickerTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Back to the phone's language. On API 33+ this goes straight to LocaleManager,
     * because AppCompat's setter is a no-op there until an AppCompatActivity exists.
     */
    private fun resetLanguage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    /** The language tags as stored for the next cold start, not as cached in memory. */
    private fun storedLanguageTags(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            // AppCompat's record for autoStoreLocales (AppLocalesStorageHelper); it
            // is written on a background thread, so wait for it.
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var stored = ""
            while (System.currentTimeMillis() < deadline) {
                stored = runCatching { context.openFileInput(APPCOMPAT_LOCALES_FILE).bufferedReader().readText() }.getOrDefault("")
                if ("hi-Latn" in stored) break
                Thread.sleep(100)
            }
            stored
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
            assertThat(storedLanguageTags()).contains("hi-Latn")
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
        const val APPCOMPAT_LOCALES_FILE = "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file"
    }
}
