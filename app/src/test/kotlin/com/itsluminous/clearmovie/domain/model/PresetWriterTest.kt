package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.testing.SourceTree
import org.junit.Test
import java.io.File

/** cm-preset-manager: a preset the app writes reads back as the same preset. */
class PresetWriterTest {
    private val bundled =
        (
            PresetParser.parse(
                File(SourceTree.mainKotlinDir.parentFile, "assets/presets/event-frame/preset.json").readText(),
            ) as PresetParseResult.Ok
        ).preset

    private fun roundTrip(p: Preset): Preset {
        val r = PresetParser.parse(PresetWriter.write(p))
        assertThat(r).isInstanceOf(PresetParseResult.Ok::class.java)
        assertThat((r as PresetParseResult.Ok).warnings).isEmpty()
        return r.preset
    }

    @Test
    fun `the bundled preset and the code-built one read back unchanged`() {
        assertThat(roundTrip(bundled)).isEqualTo(bundled)
        assertThat(roundTrip(Fixtures.preset)).isEqualTo(Fixtures.preset)
    }

    @Test
    fun `every kind of part, caption and effect reads back unchanged`() {
        val frame = requireNotNull(bundled.frame)
        val variants =
            listOf(
                bundled.copy(intro = null, frame = null, ending = null),
                bundled.copy(ending = EndingSpec(EndingKind.VIDEO)),
                bundled.copy(frame = frame.copy(coverEnding = CoverEnding.YES, repeat = false, startAfterIntro = false)),
                bundled.copy(
                    frame = bundled.frame!!.copy(coverEnding = CoverEnding.NO, music = FrameMusic(enabled = false, gainDb = -3.5)),
                ),
                bundled.copy(
                    displayNameRes = null,
                    displayName = LocalizedText.same("स्पष्ट \"धारा\""),
                    defaultText = LocalizedText("Hello\nthere", null, "Namaste"),
                ),
                bundled.copy(caption = CaptionStyle.STANDARD.copy(align = TextAlign.END, font = CaptionFont.SERIF.id, sizePx = 120)),
                bundled.copy(caption = CaptionStyle(outline = TextOutline(ArgbColor(0x80FF0000L), 0), background = null)),
                bundled.copy(effects = EffectDefaults()),
                bundled.copy(effects = EffectDefaults(mainTransition = Transition(TransitionKind.FADE_BLACK, 1500))),
                bundled.copy(photoDurationMs = 5000, defaultFit = Fit.FIT_BLUR, ducking = DuckingSpec(-6.0, -10.0, -30.0, 0, 5000)),
                bundled.copy(export = ExportSpec(height = 720, fps = 25, audioSampleRate = 44100, audioBitrate = 128_000)),
            )
        for (p in variants) assertThat(roundTrip(p)).isEqualTo(p)
    }

    @Test
    fun `a plain caption is written with explicit nulls, so it never reads back with a band`() {
        val text = PresetWriter.write(bundled.copy(caption = CaptionStyle(outline = null, background = null)))
        assertThat(text).contains("\"outline\": null")
        assertThat(text).contains("\"background\": null")
    }
}
