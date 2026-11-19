package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.testing.SourceTree
import com.itsluminous.clearmovie.testing.StringXml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/** Preset schema 1 (section 8): the bundled preset, defaults, and every kind of bad input. */
class PresetParserTest {
    private val bundledFile = File(SourceTree.mainKotlinDir.parentFile, "assets/presets/event-frame/preset.json")
    private val bundled: String get() = bundledFile.readText()

    private fun ok(text: String): PresetParseResult.Ok {
        val r = PresetParser.parse(text)
        assertThat(r).isInstanceOf(PresetParseResult.Ok::class.java)
        return r as PresetParseResult.Ok
    }

    private fun errors(text: String): List<String> {
        val r = PresetParser.parse(text)
        assertThat(r).isInstanceOf(PresetParseResult.Invalid::class.java)
        return (r as PresetParseResult.Invalid).errors
    }

    /** The bundled preset with [path] (dot-separated) set to [value], or removed when [value] is null. */
    private fun bundledWith(
        path: String,
        value: String?,
    ): String {
        fun edit(
            obj: JsonObject,
            keys: List<String>,
        ): JsonObject {
            val key = keys.first()
            if (keys.size == 1) {
                return if (value == null) JsonObject(obj - key) else JsonObject(obj + (key to Json.parseToJsonElement(value)))
            }
            // A part the bundled file leaves out or switches off (null) is edited as an empty object.
            return JsonObject(obj + (key to edit((obj[key] as? JsonObject) ?: JsonObject(emptyMap()), keys.drop(1))))
        }
        return edit(Json.parseToJsonElement(bundled).jsonObject, path.split('.')).toString()
    }

    @Test
    fun `the bundled generic preset parses with no warnings`() {
        val r = ok(bundled)
        assertThat(r.warnings).isEmpty()
        val p = r.preset
        assertThat(p.id).isEqualTo(PresetId("event-frame"))
        assertThat(p.intro).isEqualTo(IntroSpec)
        assertThat(p.ending).isEqualTo(EndingSpec(EndingKind.AUDIO))
        val frame = p.frame!!
        assertThat(frame.key).isEqualTo(KeySpec(ArgbColor.parse("#00D922")!!, 0.18, 0.08))
        assertThat(frame.contentRect).isEqualTo(NormRect(0.0094, 0.0222, 0.9813, 0.7741))
        assertThat(frame.coverEnding).isEqualTo(CoverEnding.AUTO)
        assertThat(frame.music).isEqualTo(FrameMusic(true, -8.0))
        // cm-preset-manager: the caption sits on the reference frame's blue strip (rows 862-993 and columns
        // 272-1901 of Frame-White.mp4 at 1920 x 1080), clear of the logo, as plain white text with no band.
        assertThat(p.caption).isEqualTo(
            CaptionStyle(
                box = NormRect(0.155, 0.7981, 0.825, 0.1222),
                sizePx = 60,
                color = ArgbColor.WHITE,
                outline = null,
                background = null,
            ),
        )
        assertThat(p.caption.box.y * 1080).isWithin(1.0).of(862.0)
        assertThat((p.caption.box.y + p.caption.box.h) * 1080).isWithin(1.0).of(994.0)
        assertThat(p.caption.box.x * 1920).isGreaterThan(272.0)
        assertThat((p.caption.box.x + p.caption.box.w) * 1920).isLessThan(1902.0)
        assertThat(p.effects).isEqualTo(EffectDefaults(AnimKind.KEN_BURNS_IN, Transition(TransitionKind.CROSSFADE, 600), AnimKind.FADE_IN))
        assertThat(p.ducking).isEqualTo(DuckingSpec())
        assertThat(p.export).isEqualTo(ExportSpec())
        // The project built from it is valid and, its caption aside, matches the code-built fixture preset.
        assertThat(p.copy(caption = CaptionStyle.STANDARD, defaultText = null)).isEqualTo(Fixtures.preset)
        assertThat(ProjectFactory.fromPreset(Fixtures.PROJECT_ID, p, Fixtures.presetMedia, SequentialIds()).validate()).isEmpty()
    }

    @Test
    fun `the bundled preset contains no personal text`() {
        val p = ok(bundled).preset
        // Its name is a string resource, present in all three languages; it has no literal name. Its only words
        // are its default caption (cm-preset-manager): "beautiful nature", nothing personal.
        assertThat(p.displayName).isNull()
        assertThat(p.defaultText).isEqualTo(LocalizedText(hi = "सुंदर प्रकृति"))
        assertThat(p.displayNameRes).isEqualTo("preset_event_frame_name")
        for (dir in SourceTree.shippedValuesDirs) {
            val names = StringXml.entries(File(dir, "strings.xml")).map { it.name }
            assertThat(names).contains("preset_event_frame_name")
        }
        // Every string value in the file is an identifier, a colour or a fixed keyword: no words for a person to read.
        val strings =
            Regex("\"([^\"]*)\"\\s*[,}\\]\\n]").findAll(bundled).map { it.groupValues[1] }.toList() - "सुंदर प्रकृति"
        assertThat(strings).isNotEmpty()
        for (s in strings) assertThat(s).matches("[A-Za-z0-9_#-]+")
    }

    @Test
    fun `a minimal preset takes every default`() {
        val r = ok("""{ "schemaVersion": 1, "id": "plain", "displayNameRes": "preset_plain" }""")
        val p = r.preset
        assertThat(p.intro).isNull()
        assertThat(p.ending).isNull()
        assertThat(p.frame).isNull()
        assertThat(p.caption).isEqualTo(CaptionStyle.STANDARD)
        assertThat(p.photoDurationMs).isEqualTo(3000)
        assertThat(p.defaultFit).isEqualTo(Fit.FILL_CROP)
        assertThat(p.effects).isEqualTo(EffectDefaults())
        // A project from it is MAIN and an empty text row.
        val project = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, p, PresetMedia(), SequentialIds())
        assertThat(project.tracks.map { it.type }).containsExactly(TrackType.TEXT, TrackType.MAIN).inOrder()
    }

    @Test
    fun `a user preset may carry its own name and caption text`() {
        val p =
            ok(
                bundledWith("displayNameRes", null)
                    .let { Json.parseToJsonElement(it).jsonObject }
                    .let {
                        JsonObject(
                            it +
                                (
                                    "displayName" to
                                        Json.parseToJsonElement("""{ "en": "My frame", "hi": "मेरा फ़्रेम", "hi-Latn": "Mera frame" }""")
                                ),
                        )
                    }.toString(),
            ).preset
        assertThat(p.displayName).isEqualTo(LocalizedText("My frame", "मेरा फ़्रेम", "Mera frame"))
        val withText = ok(bundledWith("caption.defaultText", """{ "en": "Line one\nLine two" }""")).preset
        assertThat(withText.defaultText).isEqualTo(LocalizedText(en = "Line one\nLine two"))
    }

    @Test
    fun `missing required fields are errors`() {
        assertThat(errors(bundledWith("schemaVersion", null))).contains("schemaVersion: is required")
        assertThat(errors(bundledWith("id", null))).contains("id: is required")
        assertThat(errors(bundledWith("displayNameRes", null))).contains("displayName: a preset needs displayNameRes or displayName")
        assertThat(errors(bundledWith("frame.keyColor", null))).contains("frame.keyColor: is required")
        assertThat(errors(bundledWith("frame.contentRect", null))).contains("frame.contentRect: is required")
        assertThat(errors(bundledWith("frame.contentRect.w", null))).contains("frame.contentRect.w: is required")
        assertThat(errors(bundledWith("ending.kind", null))).contains("ending.kind: is required")
        assertThat(errors(bundledWith("caption.outline.color", null))).contains("caption.outline.color: is required")
        assertThat(errors(bundledWith("caption.background.color", null))).contains("caption.background.color: is required")
        assertThat(errors(bundledWith("caption.outline.widthPx", null))).contains("caption.outline.widthPx: is required")
    }

    @Test
    fun `wrong values are errors, never quietly replaced`() {
        val cases =
            listOf(
                "schemaVersion" to "\"1\"",
                "id" to "\"../../etc\"",
                "displayNameRes" to "\"Bad Name\"",
                "intro" to "\"video\"",
                "intro.kind" to "\"audio\"",
                "intro.slot" to "\"frame\"",
                "ending.kind" to "\"music\"",
                "ending.visual" to "\"spin\"",
                "frame.keyColor" to "\"green\"",
                "frame.tolerance" to "1.5",
                "frame.softness" to "\"0.1\"",
                "frame.contentRect.x" to "-0.1",
                "frame.contentRect.w" to "0.999",
                "frame.coverEnding" to "\"sometimes\"",
                "frame.repeat" to "\"yes\"",
                "frame.music.gainDb" to "40",
                "frame.music.role" to "\"VOICE\"",
                "caption.box.h" to "0",
                "caption.align" to "\"justify\"",
                "caption.font" to "\"../../fonts/x\"",
                "caption.sizePx" to "64.5",
                "caption.sizePx" to "9999",
                "caption.color" to "\"#12\"",
                "caption.outline.widthPx" to "100",
                "caption.background.cornerPx" to "-1",
                "caption.defaultText" to "{ \"en\": \"\" }",
                "caption.defaultText" to "{ \"fr\": \"bonjour\" }",
                "caption.defaultText" to "\"text\"",
                "photoDurationMs" to "10",
                "defaultFit" to "\"stretch\"",
                "effects.photoDuring" to "\"pulse\"",
                "effects.textStart" to "\"gentleZoomIn\"",
                "effects.mainTransition" to "\"dissolve\"",
                "effects.transitionMs" to "5000",
                "ducking.musicUnderClipDb" to "6",
                "ducking.attackMs" to "-1",
                "export.height" to "1440",
                "export.fps" to "60",
                "export.videoCodec" to "\"hevc\"",
                "export.audioSampleRate" to "22050",
                "export.audioBitrate" to "1",
            )
        for ((path, value) in cases) {
            val errs = errors(bundledWith(path, value))
            assertThat(errs).isNotEmpty()
            // The message names the field, or the object holding it (a rectangle that runs off the picture).
            assertThat(errs.any { it.startsWith(path) || it.startsWith(path.substringBeforeLast('.')) }).isTrue()
        }
    }

    @Test
    fun `each bad field is reported with its path`() {
        val errs = errors(bundledWith("frame.contentRect.w", "2").let { t -> t.replace("\"sizePx\":60", "\"sizePx\":1") })
        assertThat(errs).containsAtLeast("frame.contentRect.w: must be between 0.0 and 1.0", "caption.sizePx: must be between 8 and 400")
        // A rectangle whose parts are in range but which runs off the picture.
        assertThat(
            errors(bundledWith("frame.contentRect.x", "0.5")),
        ).contains("frame.contentRect: must have a size and fit inside the picture")
        assertThat(errors(bundledWith("ending.kind", "\"video\""))).contains("ending.visual: only a spoken ending has a visual")
    }

    @Test
    fun `nulls switch off optional parts and are refused for required ones`() {
        val p = ok(bundledWith("caption.outline", "null").let { Json.parseToJsonElement(it).toString() }).preset
        assertThat(p.caption.outline).isNull()
        assertThat(ok(bundledWith("caption.background", "null")).preset.caption.background).isNull()
        assertThat(ok(bundledWith("effects.mainTransition", "null")).preset.effects.mainTransition).isNull()
        assertThat(ok(bundledWith("effects.mainTransition", "\"none\"")).preset.effects.mainTransition).isNull()
        assertThat(ok(bundledWith("effects.photoDuring", "null")).preset.effects.photoDuring).isNull()
        assertThat(errors(bundledWith("id", "null"))).contains("id: is required")
        assertThat(errors(bundledWith("frame", "[]"))).contains("frame: must be an object")
    }

    @Test
    fun `coverEnding accepts auto, true and false`() {
        assertThat(ok(bundledWith("frame.coverEnding", "true")).preset.frame!!.coverEnding).isEqualTo(CoverEnding.YES)
        assertThat(ok(bundledWith("frame.coverEnding", "false")).preset.frame!!.coverEnding).isEqualTo(CoverEnding.NO)
        assertThat(ok(bundledWith("frame.coverEnding", null)).preset.frame!!.coverEnding).isEqualTo(CoverEnding.AUTO)
        assertThat(ok(bundledWith("frame.coverEnding", "null")).preset.frame!!.coverEnding).isEqualTo(CoverEnding.AUTO)
        assertThat(
            ok(bundledWith("frame.music.enabled", "false"))
                .preset.frame!!
                .music.enabled,
        ).isFalse()
    }

    @Test
    fun `unknown keys are ignored with a warning`() {
        val r = ok(bundledWith("frame.glow", "true").let { bundledWithTop(it, "futureThing", "{}") })
        assertThat(r.warnings).containsExactly("frame.glow: ignored (unknown field)", "futureThing: ignored (unknown field)")
    }

    private fun bundledWithTop(
        text: String,
        key: String,
        value: String,
    ) = JsonObject(Json.parseToJsonElement(text).jsonObject + (key to Json.parseToJsonElement(value))).toString()

    @Test
    fun `a preset for a newer app, non-JSON and huge files are refused`() {
        assertThat(
            errors(bundledWith("schemaVersion", "2")),
        ).containsExactly("schemaVersion: this preset needs a newer version of ClearMovie")
        assertThat(errors(bundledWith("schemaVersion", "0"))).contains("schemaVersion: unknown preset version")
        assertThat(errors("{ nope")).containsExactly("not valid JSON")
        assertThat(errors("[]")).containsExactly("a preset is a JSON object")
        // Deep nesting would overflow the parser's stack; it is refused before parsing.
        assertThat(errors("{\"x\": " + "[".repeat(5000) + "]".repeat(5000) + "}")).containsExactly("not valid JSON")
        assertThat(errors(" ".repeat(PresetParser.MAX_CHARS + 1))).containsExactly("the file is too large to be a preset")
        val longName = "x".repeat(PresetParser.MAX_DISPLAY_NAME + 1)
        assertThat(errors(bundledWithTop(bundledWith("displayNameRes", null), "displayName", "{ \"en\": \"$longName\" }")))
            .contains("displayName.en: is longer than 60 characters")
        assertThat(errors(bundledWithTop(bundledWith("displayNameRes", null), "displayName", "{ \"en\": \"bell\\u0007\" }")))
            .contains("displayName.en: contains control or invisible characters")
        assertThat(errors(bundledWithTop(bundledWith("displayNameRes", null), "displayName", "{ \"en\": \"\u200d\" }")))
            .contains("displayName.en: shows no characters")
        assertThat(errors(bundledWithTop(bundledWith("displayNameRes", null), "displayName", "{ \"en\": \"two\\nlines\" }")))
            .contains("displayName.en: contains control or invisible characters")
        assertThat(errors(bundledWithTop(bundledWith("displayNameRes", null), "displayName", "{ \"en\": \"\u202Eevil\" }")))
            .contains("displayName.en: contains control or invisible characters")
    }

    @Test
    fun `a project from a preset copies its defaults`() {
        val preset =
            ok(bundled).preset.copy(
                photoDurationMs = 5000,
                defaultFit = Fit.FIT_BLUR,
                ducking = DuckingSpec(musicUnderClipDb = -10.0),
                frame = Fixtures.preset.frame!!.copy(music = FrameMusic(enabled = false, gainDb = -12.0)),
            )
        val p = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds())
        assertThat(p.photoDurationMs).isEqualTo(5000)
        assertThat(p.defaultFit).isEqualTo(Fit.FIT_BLUR)
        assertThat(p.ducking.musicUnderClipDb).isEqualTo(-10.0)
        assertThat(p.effectDefaults).isEqualTo(preset.effects)
        assertThat(p.presetId).isEqualTo(preset.id)
        val music = p.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }
        assertThat(music.muted).isTrue()
        assertThat((music.clips.single() as AudioClip).volumeDb).isEqualTo(-12.0)
        // Files that are not bound leave their part out.
        val noFrame = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, PresetMedia(introMs = 1000, endingMs = 2000), SequentialIds())
        assertThat(noFrame.tracks.none { it.presetRole == PresetRole.FRAME || it.presetRole == PresetRole.FRAME_MUSIC }).isTrue()
        assertThat(noFrame.validate()).isEmpty()
        val noEnding = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, PresetMedia(introMs = 1000, frameMs = 5000), SequentialIds())
        assertThat(noEnding.endingSlot()).isNull()
        assertThat(noEnding.tracks.none { it.presetRole == PresetRole.CLOSING_MESSAGE }).isTrue()
        assertThat(noEnding.totalDurationMs()).isEqualTo(1000)
    }
}
