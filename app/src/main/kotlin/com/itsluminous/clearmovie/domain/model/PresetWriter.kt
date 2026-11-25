package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Writes a [Preset] as a schema 1 `preset.json`, the format [PresetParser]
 * reads, so a preset made or edited in the app is stored, and read back, the
 * same way as one the user copied onto the phone: `PresetParser.parse(write(p))`
 * gives `p` again. Every field is written, defaults included, so a later
 * change to a default in the code never changes a stored preset.
 */
object PresetWriter {
    private val json = Json { prettyPrint = true }

    fun write(preset: Preset): String = json.encodeToString(JsonObject.serializer(), toJson(preset))

    internal fun toJson(preset: Preset): JsonObject =
        buildJsonObject {
            put("schemaVersion", Preset.SCHEMA_VERSION)
            put("id", preset.id.value)
            preset.displayNameRes?.let { put("displayNameRes", it) }
            preset.displayName?.let { put("displayName", localized(it)) }
            if (preset.intro != null) {
                putJsonObject("intro") {
                    put("slot", "intro")
                    put("kind", "video")
                }
            }
            preset.ending?.let { ending ->
                putJsonObject("ending") {
                    put("slot", "ending")
                    put("kind", name(EndingKind.serializer(), ending.kind))
                    if (ending.kind == EndingKind.AUDIO) put("visual", "holdLast")
                }
            }
            preset.frame?.let { frame ->
                putJsonObject("frame") {
                    put("slot", "frame")
                    put("keyColor", frame.key.color.toString())
                    put("tolerance", frame.key.tolerance)
                    put("softness", frame.key.softness)
                    put("contentRect", rect(frame.contentRect))
                    put("startAfterIntro", frame.startAfterIntro)
                    put(
                        "coverEnding",
                        when (frame.coverEnding) {
                            CoverEnding.AUTO -> JsonPrimitive("auto")
                            CoverEnding.YES -> JsonPrimitive(true)
                            CoverEnding.NO -> JsonPrimitive(false)
                        },
                    )
                    put("repeat", frame.repeat)
                    putJsonObject("music") {
                        put("enabled", frame.music.enabled)
                        put("gainDb", frame.music.gainDb)
                        put("role", "MUSIC")
                    }
                }
            }
            put("caption", caption(preset.caption, preset.defaultText))
            put("photoDurationMs", preset.photoDurationMs)
            put("defaultFit", name(Fit.serializer(), preset.defaultFit))
            putJsonObject("effects") {
                putEnum("photoDuring", AnimKind.serializer(), preset.effects.photoDuring)
                putEnum("mainTransition", TransitionKind.serializer(), preset.effects.mainTransition?.kind)
                preset.effects.mainTransition?.let { put("transitionMs", it.durationMs) }
                putEnum("textStart", AnimKind.serializer(), preset.effects.textStart)
            }
            putJsonObject("ducking") {
                put("musicUnderClipDb", preset.ducking.musicUnderClipDb)
                put("musicUnderVoiceDb", preset.ducking.musicUnderVoiceDb)
                put("speechDuckDb", preset.ducking.speechDuckDb)
                put("attackMs", preset.ducking.attackMs)
                put("releaseMs", preset.ducking.releaseMs)
            }
            putJsonObject("export") {
                put("height", preset.export.height)
                put("fps", preset.export.fps)
                put("videoCodec", "h264")
                put("audioSampleRate", preset.export.audioSampleRate)
                put("audioBitrate", preset.export.audioBitrate)
            }
            // Written only when it is not the landscape every older preset is, so those read back unchanged.
            if (preset.shape != CanvasShape.LANDSCAPE) put("shape", name(CanvasShape.serializer(), preset.shape))
            // Likewise only a voice other than No change (cm-preset-voice-default), so a preset without one reads back unchanged.
            if (preset.voice != VoiceChoice.NONE) put(PresetParser.DEFAULT_VOICE, preset.voice.id)
        }

    private fun caption(
        style: CaptionStyle,
        defaultText: LocalizedText?,
    ): JsonObject =
        buildJsonObject {
            put("box", rect(style.box))
            put("align", name(TextAlign.serializer(), style.align))
            put("font", style.font)
            put("sizePx", style.sizePx)
            put("color", style.color.toString())
            put(
                "outline",
                style.outline?.let {
                    buildJsonObject {
                        put("color", it.color.toString())
                        put("widthPx", it.widthPx)
                    }
                } ?: JsonNull,
            )
            put(
                "background",
                style.background?.let {
                    buildJsonObject {
                        put("color", it.color.toString())
                        put("cornerPx", it.cornerPx)
                    }
                } ?: JsonNull,
            )
            put("defaultText", defaultText?.let(::localized) ?: JsonNull)
        }

    private fun rect(r: NormRect): JsonObject =
        buildJsonObject {
            put("x", r.x)
            put("y", r.y)
            put("w", r.w)
            put("h", r.h)
        }

    private fun localized(text: LocalizedText): JsonObject =
        buildJsonObject {
            text.en?.let { put("en", it) }
            text.hi?.let { put("hi", it) }
            text.hiLatn?.let { put("hi-Latn", it) }
        }

    private fun <E : Enum<E>> JsonObjectBuilder.putEnum(
        key: String,
        serializer: KSerializer<E>,
        value: E?,
    ) {
        put(key, value?.let { JsonPrimitive(name(serializer, it)) } ?: JsonNull)
    }

    private fun <E : Enum<E>> name(
        serializer: KSerializer<E>,
        value: E,
    ): String = (Json.encodeToJsonElement(serializer, value) as JsonPrimitive).content
}
