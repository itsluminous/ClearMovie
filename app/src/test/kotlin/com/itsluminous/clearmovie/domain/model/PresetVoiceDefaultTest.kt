package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * cm-preset-voice-default: a preset's default voice is read, written and
 * copied into the projects made from it; a preset or project without one is
 * read as No change and keeps its bytes.
 */
class PresetVoiceDefaultTest {
    private val codec = ProjectCodec()

    private fun parse(text: String): PresetParseResult.Ok {
        val r = PresetParser.parse(text)
        assertThat(r).isInstanceOf(PresetParseResult.Ok::class.java)
        return r as PresetParseResult.Ok
    }

    /** [Fixtures.preset] as `preset.json`, with `defaultVoice` set to the JSON [value] (left out when null). */
    private fun withVoice(value: String?): String {
        val base = Json.parseToJsonElement(PresetWriter.write(Fixtures.preset)).jsonObject - PresetParser.DEFAULT_VOICE
        return JsonObject(if (value == null) base else base + (PresetParser.DEFAULT_VOICE to Json.parseToJsonElement(value))).toString()
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/project/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `a preset without a default voice, from before it, reads as No change with no warning`() {
        val r = parse(withVoice(null))
        assertThat(r.preset.voice).isEqualTo(VoiceChoice.NONE)
        assertThat(r.warnings).isEmpty()
        assertThat(parse(withVoice("null")).preset.voice).isEqualTo(VoiceChoice.NONE)
    }

    @Test
    fun `every voice id reads as its voice`() {
        for (voice in VoiceChoice.entries) {
            val r = parse(withVoice("\"${voice.id}\""))
            assertThat(r.preset.voice).isEqualTo(voice)
            assertThat(r.warnings).isEmpty()
        }
    }

    @Test
    fun `an id this version does not know reads as No change with a warning, and the preset still opens`() {
        for (id in listOf("scottish_male", "", "BRITISH_MALE", "x".repeat(500))) {
            val r = parse(withVoice(JsonPrimitive(id).toString()))
            assertThat(r.preset.voice).isEqualTo(VoiceChoice.NONE)
            assertThat(r.warnings).containsExactly("defaultVoice: unknown voice, read as No change")
        }
    }

    @Test
    fun `a default voice that is not text is an error, as any other wrong field`() {
        for (value in listOf("1", "true", "{}", "[\"british_male\"]")) {
            val r = PresetParser.parse(withVoice(value))
            assertThat(r).isInstanceOf(PresetParseResult.Invalid::class.java)
            assertThat((r as PresetParseResult.Invalid).errors).containsExactly("defaultVoice: must be text")
        }
    }

    @Test
    fun `the writer writes a voice by its id, leaves No change out, and every voice reads back`() {
        assertThat(PresetWriter.write(Fixtures.preset)).doesNotContain("defaultVoice")
        assertThat(PresetWriter.write(Fixtures.preset.copy(voice = VoiceChoice.INDIAN_MALE))).contains("\"defaultVoice\": \"indian_male\"")
        for (voice in VoiceChoice.entries) {
            val p = Fixtures.preset.copy(voice = voice)
            val r = parse(PresetWriter.write(p))
            assertThat(r.preset).isEqualTo(p)
            assertThat(r.warnings).isEmpty()
        }
    }

    @Test
    fun `a project made from the preset copies its default voice, and No change copies nothing`() {
        val voiced =
            ProjectFactory.fromPreset(
                Fixtures.PROJECT_ID,
                Fixtures.preset.copy(voice = VoiceChoice.BRITISH_FEMALE),
                Fixtures.presetMedia,
            )
        assertThat(voiced.defaultVoice).isEqualTo(VoiceChoice.BRITISH_FEMALE)
        val plain = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("x"))
        assertThat(plain.defaultVoice).isNull()
        assertThat(plain).isEqualTo(Fixtures.presetProject())
        assertThat(ProjectFactory.blank(Fixtures.PROJECT_ID).defaultVoice).isNull()
    }

    @Test
    fun `the project keeps its copy when the preset changes later, and existing items are untouched`() {
        val preset = Fixtures.preset.copy(voice = VoiceChoice.AMERICAN_MALE)
        val project = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds("x"))
        // Only the default is new: every row and item is the one a preset without a voice makes.
        assertThat(project.tracks).isEqualTo(Fixtures.presetProject().tracks)
        val stored = codec.encode(project)
        assertThat(stored).contains("\"defaultVoice\": \"american_male\"")
        val read = (codec.decode(stored) as ProjectDecodeResult.Ok).project
        assertThat(read).isEqualTo(project)
        assertThat(read.defaultVoice).isEqualTo(VoiceChoice.AMERICAN_MALE)
    }

    @Test
    fun `a project from before the default voice reads as No change and is written back byte for byte`() {
        for (name in listOf("rich-v8.json", "blank-v8.json")) {
            val text = fixture(name)
            val result = codec.decode(text) as ProjectDecodeResult.Ok
            assertThat(result.project.defaultVoice).isNull()
            assertThat(result.repaired).isFalse()
            assertThat(codec.encode(result.project)).isEqualTo(text)
            assertThat(text).doesNotContain("defaultVoice")
        }
    }
}
