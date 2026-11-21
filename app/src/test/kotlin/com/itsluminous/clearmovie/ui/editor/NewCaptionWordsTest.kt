package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.CaptionWords
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import org.junit.Test
import java.util.Locale

/** cm-preset-manager: a new caption starts with the preset's words, in the app's language or the nearest one. */
class NewCaptionWordsTest {
    private val blank = ProjectFactory.blank(ProjectId("p"))
    private val languages = listOf("en", "hi", "hi-Latn")

    @Test
    fun `words in only one language show in every language`() {
        val p = blank.copy(captionDefaultText = LocalizedText(hi = "नमस्ते"))
        for (tag in languages) assertThat(p.newCaptionWords(Locale.forLanguageTag(tag))).isEqualTo("नमस्ते")
    }

    @Test
    fun `each language gets its own words when the preset has them`() {
        val p = blank.copy(captionDefaultText = LocalizedText("Sample words", "नमस्ते", "Namaste"))
        assertThat(p.newCaptionWords(Locale.ENGLISH)).isEqualTo("Sample words")
        assertThat(p.newCaptionWords(Locale.forLanguageTag("hi-IN"))).isEqualTo("नमस्ते")
        assertThat(p.newCaptionWords(Locale.forLanguageTag("hi-Latn"))).isEqualTo("Namaste")
    }

    @Test
    fun `a video without words of its own starts a new caption with The image caption, in every language`() {
        assertThat(CaptionWords.DEFAULT).isEqualTo("The image caption")
        for (tag in languages) assertThat(blank.newCaptionWords(Locale.forLanguageTag(tag))).isEqualTo("The image caption")
        val empty = blank.copy(captionDefaultText = LocalizedText.same(" "))
        assertThat(empty.newCaptionWords(Locale.ENGLISH)).isEqualTo("The image caption")
    }

    @Test
    fun `a video made from the 0_0_2 Event frame starts a new caption with The image caption`() {
        val retired = CaptionWords.RETIRED
        val old = blank.copy(captionDefaultText = retired)
        for (tag in languages) assertThat(old.newCaptionWords(Locale.forLanguageTag(tag))).isEqualTo("The image caption")
        // The same words typed by the user in every language are theirs, and stay.
        val typed = blank.copy(captionDefaultText = LocalizedText.same(retired.hi!!))
        assertThat(typed.newCaptionWords(Locale.ENGLISH)).isEqualTo(retired.hi)
    }
}
