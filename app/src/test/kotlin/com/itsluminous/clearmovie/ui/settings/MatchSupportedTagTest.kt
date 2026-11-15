package com.itsluminous.clearmovie.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class MatchSupportedTagTest {
    private val supported = listOf("en", "hi", "hi-Latn")

    @Test
    fun `an exact tag wins`() {
        assertThat(matchSupportedTag(Locale.forLanguageTag("hi-Latn"), supported)).isEqualTo("hi-Latn")
        assertThat(matchSupportedTag(Locale.forLanguageTag("hi"), supported)).isEqualTo("hi")
    }

    @Test
    fun `a region falls back to the same language and script`() {
        assertThat(matchSupportedTag(Locale.forLanguageTag("hi-IN"), supported)).isEqualTo("hi")
        assertThat(matchSupportedTag(Locale.forLanguageTag("hi-Latn-IN"), supported)).isEqualTo("hi-Latn")
        assertThat(matchSupportedTag(Locale.forLanguageTag("en-GB"), supported)).isEqualTo("en")
    }

    @Test
    fun `Latin-script Hindi never selects Devanagari Hindi, and unknown languages select nothing`() {
        assertThat(matchSupportedTag(Locale.forLanguageTag("hi-Latn"), listOf("en", "hi"))).isNull()
        assertThat(matchSupportedTag(Locale.forLanguageTag("fr"), supported)).isNull()
        assertThat(matchSupportedTag(null, supported)).isNull()
    }
}
