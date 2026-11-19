package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import org.junit.Test
import java.util.Locale

/** cm-preset-manager: a new caption starts with the preset's words, in the app's language or the nearest one. */
class NewCaptionWordsTest {
    private val blank = ProjectFactory.blank(ProjectId("p"))

    @Test
    fun `words in only one language show in every language`() {
        val p = blank.copy(captionDefaultText = LocalizedText(hi = "सुंदर प्रकृति"))
        for (tag in listOf("en", "hi", "hi-Latn")) assertThat(p.newCaptionWords(Locale.forLanguageTag(tag))).isEqualTo("सुंदर प्रकृति")
    }

    @Test
    fun `each language gets its own words when the preset has them`() {
        val p = blank.copy(captionDefaultText = LocalizedText("Beautiful nature", "सुंदर प्रकृति", "Sundar prakriti"))
        assertThat(p.newCaptionWords(Locale.ENGLISH)).isEqualTo("Beautiful nature")
        assertThat(p.newCaptionWords(Locale.forLanguageTag("hi-IN"))).isEqualTo("सुंदर प्रकृति")
        assertThat(p.newCaptionWords(Locale.forLanguageTag("hi-Latn"))).isEqualTo("Sundar prakriti")
    }

    @Test
    fun `no words leave a new caption empty`() {
        assertThat(blank.newCaptionWords(Locale.ENGLISH)).isEmpty()
    }
}
