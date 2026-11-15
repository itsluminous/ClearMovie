package com.itsluminous.clearmovie.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/**
 * The language tags the picker sets resolve to the right resource folders:
 * `hi-Latn` (a script, not a region) must reach values-b+hi+Latn, and must not
 * fall back to Devanagari values-hi or to English.
 */
@RunWith(RobolectricTestRunner::class)
class LocaleResolutionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun localized(tag: String): Context =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        )

    @Test
    fun `each picker tag resolves to its own folder`() {
        assertThat(localized("en").getString(R.string.home_empty_title)).isEqualTo("Your videos will appear here")
        assertThat(localized("hi").getString(R.string.home_empty_title)).isEqualTo("आपके वीडियो यहाँ दिखेंगे")
        assertThat(localized("hi-Latn").getString(R.string.home_empty_title)).isEqualTo("Aapke videos yahan dikhenge")
    }

    @Test
    fun `the b+hi+Latn qualifier selects Hinglish, with plurals and format arguments`() {
        RuntimeEnvironment.setQualifiers("b+hi+Latn")
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertThat(resources.getString(R.string.settings_section_language)).isEqualTo("Bhasha")
        assertThat(resources.getString(R.string.settings_version, "0.1.0")).isEqualTo("Version 0.1.0")
        assertThat(resources.getQuantityString(R.plurals.home_saved_videos, 3, 3)).isEqualTo("3 videos saved hain")
        // Hindi plural rules: 0 takes the "one" form.
        assertThat(resources.getQuantityString(R.plurals.home_saved_videos, 0, 0)).isEqualTo("0 video saved hai")
    }

    @Test
    fun `Hindi plurals and format arguments`() {
        val hindi = localized("hi").resources
        assertThat(hindi.getQuantityString(R.plurals.home_saved_videos, 3, 3)).isEqualTo("3 वीडियो सेव हैं")
        assertThat(hindi.getString(R.string.settings_version, "0.1.0")).isEqualTo("वर्शन 0.1.0")
    }

    @Test
    fun `the picker lists every language in its own script, whatever the UI language`() {
        val expected =
            listOf(
                LanguageOption("en", "English"),
                LanguageOption("hi", "हिन्दी"),
                LanguageOption("hi-Latn", "Hinglish"),
            )
        assertThat(AppLanguage.options(context)).isEqualTo(expected)
        assertThat(AppLanguage.options(localized("hi"))).isEqualTo(expected)
        assertThat(AppLanguage.options(localized("hi-Latn"))).isEqualTo(expected)
    }
}
