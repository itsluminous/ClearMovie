package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * cm-preset-manager: a project keeps the caption style and words its preset
 * gave it. A plain caption (no outline, no band) must read back plain, though
 * project JSON leaves null values out.
 */
class CaptionDefaultsTest {
    private val codec = ProjectCodec()

    private fun roundTrip(p: Project): Project = (codec.decode(codec.encode(p)) as ProjectDecodeResult.Ok).project

    @Test
    fun `a plain caption style and the default words survive saving and reading`() {
        val plain = CaptionStyle(box = NormRect(0.15, 0.7981, 0.83, 0.1222), outline = null, background = null)
        val p = Fixtures.richProject().copy(captionDefaults = plain, captionDefaultText = LocalizedText(hi = "नमस्ते"))
        val back = roundTrip(p)
        assertThat(back.captionDefaults).isEqualTo(plain)
        assertThat(back.captionDefaultText).isEqualTo(LocalizedText(hi = "नमस्ते"))
        assertThat(codec.encode(p)).contains("\"hi\": \"नमस्ते\"")
    }

    @Test
    fun `a project from a preset copies its caption style and words`() {
        val preset =
            Fixtures.preset.copy(
                caption = CaptionStyle(sizePx = 60, outline = null, background = null),
                defaultText = LocalizedText(hi = "नमस्ते"),
            )
        val p = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds())
        assertThat(p.captionDefaults).isEqualTo(preset.caption)
        assertThat(p.captionDefaultText).isEqualTo(preset.defaultText)
        assertThat(p.validate()).isEmpty()
        // A blank project has the standard style and no words.
        val blank = ProjectFactory.blank(Fixtures.PROJECT_ID, SequentialIds())
        assertThat(blank.captionDefaults).isEqualTo(CaptionStyle.STANDARD)
        assertThat(blank.captionDefaultText).isNull()
    }

    @Test
    fun `default words with control characters or too long make the project invalid`() {
        val p = ProjectFactory.blank(Fixtures.PROJECT_ID, SequentialIds())
        assertThat(p.copy(captionDefaultText = LocalizedText(en = "ok\u202Eevil")).validate()).isNotEmpty()
        assertThat(p.copy(captionDefaultText = LocalizedText(en = "x".repeat(Limits.MAX_TEXT_LENGTH + 1))).validate()).isNotEmpty()
        assertThat(p.copy(captionDefaultText = LocalizedText(en = "two\nlines")).validate()).isEmpty()
    }

    @Test
    fun `the standard style keeps its outline and band`() {
        assertThat(CaptionStyle.STANDARD.outline).isEqualTo(TextOutline(ArgbColor.BLACK, 4))
        assertThat(CaptionStyle.STANDARD.background).isEqualTo(TextBackground(ArgbColor(0x99000000L), 12))
        assertThat(CaptionStyle.STANDARD.box).isEqualTo(CaptionStyle.DEFAULT_BOX)
    }
}
