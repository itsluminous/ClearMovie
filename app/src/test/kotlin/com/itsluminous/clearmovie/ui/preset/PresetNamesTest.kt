package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PresetNamesTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val hinglish = Locale.forLanguageTag("hi-Latn")
    private val hindi = Locale.forLanguageTag("hi")

    @Test
    fun `the user's own name is shown in the UI language, or the nearest one`() {
        val text = LocalizedText(en = "Clear Stream", hi = "स्पष्ट", hiLatn = "Spasht")
        val preset = Preset(PresetId("event-frame"), displayName = text)
        assertThat(PresetNames.name(preset, resources, Locale.ENGLISH)).isEqualTo("Clear Stream")
        assertThat(PresetNames.name(preset, resources, hindi)).isEqualTo("स्पष्ट")
        assertThat(PresetNames.name(preset, resources, hinglish)).isEqualTo("Spasht")
        val onlyLatin = Preset(PresetId("event-frame"), displayName = LocalizedText(hiLatn = "Spasht"))
        assertThat(PresetNames.name(onlyLatin, resources, hindi)).isEqualTo("Spasht")
        assertThat(PresetNames.name(onlyLatin, resources, Locale.ENGLISH)).isEqualTo("Spasht")
    }

    @Test
    fun `a bundled preset names a known string, and an unknown one falls back`() {
        val bundled = Preset(PresetId("event-frame"), displayNameRes = "preset_event_frame_name")
        assertThat(PresetNames.name(bundled, resources, Locale.ENGLISH)).isEqualTo(resources.getString(R.string.preset_event_frame_name))
        val unknown = Preset(PresetId("x"), displayNameRes = "app_name")
        assertThat(PresetNames.name(unknown, resources, Locale.ENGLISH)).isEqualTo(resources.getString(R.string.preset_unnamed))
    }
}
