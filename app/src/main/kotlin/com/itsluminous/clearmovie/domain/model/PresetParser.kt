package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The outcome of reading a `preset.json`. */
sealed interface PresetParseResult {
    /** A usable preset. [warnings] lists keys that were ignored (for example a typo). */
    data class Ok(
        val preset: Preset,
        val warnings: List<String>,
    ) : PresetParseResult

    /** Not usable. Each error names the field, for example `frame.contentRect.w: must be between 0 and 1`. */
    data class Invalid(
        val errors: List<String>,
    ) : PresetParseResult
}

/**
 * Reads preset schema 1 (ARCHITECTURE section 8). The input is untrusted: a
 * user's own `preset.json` comes from a phone folder. So it is size-limited,
 * walked field by field rather than mapped onto classes, and every value is
 * range-checked. A missing optional field takes its default; a field that is
 * present but wrong is an error, never silently replaced. Unknown keys are
 * ignored with a warning, so a newer app's extra fields do not break an older
 * one. Nothing here touches files: slot names are a fixed set, and the preset
 * id is safe to use as a folder name.
 */
object PresetParser {
    /** A preset is a few kilobytes; anything far larger is not one. */
    const val MAX_CHARS = 64 * 1024
    const val MAX_DISPLAY_NAME = 60
    private val RESOURCE_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
    internal const val DEFAULT_VOICE = "defaultVoice"

    fun parse(text: String): PresetParseResult {
        if (text.length > MAX_CHARS) return PresetParseResult.Invalid(listOf("the file is too large to be a preset"))
        if (JsonGuard.nestsDeeperThan(text)) return PresetParseResult.Invalid(listOf("not valid JSON"))
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return PresetParseResult.Invalid(listOf("not valid JSON"))
            }
        if (root !is JsonObject) return PresetParseResult.Invalid(listOf("a preset is a JSON object"))
        val report = Report()
        val preset = Fields(root, "", report).readPreset()
        return if (report.errors.isEmpty() && preset != null) {
            PresetParseResult.Ok(preset, report.warnings)
        } else {
            PresetParseResult.Invalid(report.errors)
        }
    }

    private fun Fields.readPreset(): Preset? {
        val version = long("schemaVersion", required = true, range = 0L..Long.MAX_VALUE)
        if (version != null && version > Preset.SCHEMA_VERSION) {
            error("schemaVersion", "this preset needs a newer version of ClearMovie")
            return null
        }
        if (version != null && version < Preset.SCHEMA_VERSION) error("schemaVersion", "unknown preset version")
        val id = string("id", required = true, pattern = PresetId.PATTERN, maxLength = 64)
        val nameRes = string("displayNameRes", pattern = RESOURCE_NAME, maxLength = 64)
        val name = localized("displayName", MAX_DISPLAY_NAME, oneLine = true)
        if (nameRes == null && name == null && !hasError("displayNameRes") && !hasError("displayName")) {
            error("displayName", "a preset needs displayNameRes or displayName")
        }
        val intro = obj("intro")?.readIntro()
        val ending = obj("ending")?.readEnding()
        val frame = obj("frame")?.readFrame()
        val captionFields = obj("caption")
        val caption = captionFields?.readCaption() ?: CaptionStyle.STANDARD
        val defaultText = CaptionWords.withoutRetired(captionFields?.localized("defaultText", Limits.MAX_TEXT_LENGTH))
        captionFields?.finish()
        val photoMs = long("photoDurationMs", range = 500L..60_000L) ?: Project.DEFAULT_PHOTO_MS
        val fit = enum("defaultFit", Fit.serializer()) ?: Fit.FILL_CROP
        val effects = obj("effects")?.readEffects() ?: EffectDefaults()
        val ducking = obj("ducking")?.readDucking() ?: DuckingSpec()
        val export = obj("export")?.readExport() ?: ExportSpec()
        val shape = enum("shape", CanvasShape.serializer()) ?: CanvasShape.LANDSCAPE
        val voice = readVoice()
        finish()
        if (id == null || report.errors.isNotEmpty()) return null
        return Preset(
            id = PresetId(id),
            displayNameRes = nameRes,
            displayName = name,
            intro = intro,
            ending = ending,
            frame = frame,
            caption = caption,
            defaultText = defaultText,
            photoDurationMs = photoMs,
            defaultFit = fit,
            effects = effects,
            ducking = ducking,
            export = export,
            shape = shape,
            voice = voice,
        )
    }

    /**
     * `defaultVoice`, the voice a new voice recording starts in (cm-preset-voice-default): a
     * [VoiceChoice] id. Absent or null is No change, and so is an id this version does not know
     * (a newer app's voice), with a warning, so such a preset still opens. Anything but text is
     * an error, as for every other field.
     */
    private fun Fields.readVoice(): VoiceChoice {
        if (isNull(DEFAULT_VOICE)) {
            take(DEFAULT_VOICE)
            return VoiceChoice.NONE
        }
        val v = take(DEFAULT_VOICE) ?: return VoiceChoice.NONE
        if (v !is JsonPrimitive || !v.isString) {
            error(DEFAULT_VOICE, "must be text")
            return VoiceChoice.NONE
        }
        return VoiceChoice.fromId(v.content) ?: VoiceChoice.NONE.also { warn(DEFAULT_VOICE, "unknown voice, read as No change") }
    }

    private fun Fields.requireSlot(slot: String) {
        val value = string("slot") ?: return
        if (value != slot) error("slot", "must be \"$slot\"")
    }

    private fun Fields.readIntro(): IntroSpec {
        requireSlot("intro")
        val kind = string("kind")
        if (kind != null && kind != "video") error("kind", "the intro must be a video")
        finish()
        return IntroSpec
    }

    private fun Fields.readEnding(): EndingSpec? {
        requireSlot("ending")
        val kind = enum("kind", EndingKind.serializer(), required = true)
        val visual = string("visual")
        when {
            visual == null -> Unit
            kind == EndingKind.VIDEO -> error("visual", "only a spoken ending has a visual")
            visual != "holdLast" -> error("visual", "must be \"holdLast\"")
        }
        finish()
        return kind?.let { EndingSpec(it) }
    }

    private fun Fields.readFrame(): FrameSpec? {
        requireSlot("frame")
        val color = color("keyColor", required = true)
        val tolerance = double("tolerance", 0.0..1.0) ?: FrameSpec.DEFAULT_TOLERANCE
        val softness = double("softness", 0.0..1.0) ?: FrameSpec.DEFAULT_SOFTNESS
        val rect = obj("contentRect", required = true)?.readRect()
        val startAfterIntro = bool("startAfterIntro") ?: true
        val cover = readCoverEnding()
        val repeat = bool("repeat") ?: true
        val music = obj("music")?.readMusic() ?: FrameMusic()
        finish()
        if (color == null || rect == null) return null
        return FrameSpec(KeySpec(color, tolerance, softness), rect, startAfterIntro, cover, repeat, music)
    }

    private fun Fields.readCoverEnding(): CoverEnding {
        val value = take("coverEnding")
        if (value == null || value is JsonNull) return CoverEnding.AUTO
        if (value is JsonPrimitive) {
            if (value.isString && value.content == "auto") return CoverEnding.AUTO
            if (!value.isString) value.booleanOrNull?.let { return if (it) CoverEnding.YES else CoverEnding.NO }
        }
        error("coverEnding", "must be \"auto\", true or false")
        return CoverEnding.AUTO
    }

    private fun Fields.readMusic(): FrameMusic {
        val enabled = bool("enabled") ?: true
        val gain = double("gainDb", Limits.MIN_VOLUME_DB..Limits.MAX_VOLUME_DB) ?: FrameMusic.DEFAULT_GAIN_DB
        val role = string("role")
        if (role != null && role != "MUSIC") error("role", "the frame's sound is music")
        finish()
        return FrameMusic(enabled, gain)
    }

    private fun Fields.readRect(): NormRect? {
        val x = double("x", 0.0..1.0, required = true)
        val y = double("y", 0.0..1.0, required = true)
        val w = double("w", 0.0..1.0, required = true)
        val h = double("h", 0.0..1.0, required = true)
        finish()
        if (x == null || y == null || w == null || h == null) return null
        val rect = NormRect(x, y, w, h)
        if (!rect.isValid) {
            error("", "must have a size and fit inside the picture")
            return null
        }
        return rect
    }

    private fun Fields.readCaption(): CaptionStyle {
        val d = CaptionStyle.STANDARD
        val box = obj("box")?.readRect() ?: d.box
        val align = enum("align", TextAlign.serializer()) ?: d.align
        val font = string("font", pattern = CaptionStyle.FONT_PATTERN, maxLength = 64) ?: d.font
        val size = long("sizePx", CaptionStyle.MIN_SIZE_PX.toLong()..CaptionStyle.MAX_SIZE_PX.toLong())?.toInt() ?: d.sizePx
        val color = color("color") ?: d.color
        val outline =
            if (isNull("outline")) {
                take("outline")
                null
            } else {
                obj("outline")?.readOutline() ?: d.outline
            }
        val background =
            if (isNull("background")) {
                take("background")
                null
            } else {
                obj("background")?.readBackground() ?: d.background
            }
        return CaptionStyle(box, align, font, size, color, outline, background)
    }

    private fun Fields.readOutline(): TextOutline? {
        val color = color("color", required = true)
        val width = long("widthPx", 0L..CaptionStyle.MAX_OUTLINE_PX.toLong(), required = true)
        finish()
        return if (color != null && width != null) TextOutline(color, width.toInt()) else null
    }

    private fun Fields.readBackground(): TextBackground? {
        val color = color("color", required = true)
        val corner = long("cornerPx", 0L..CaptionStyle.MAX_CORNER_PX.toLong()) ?: 0L
        finish()
        return color?.let { TextBackground(it, corner.toInt()) }
    }

    private fun Fields.readEffects(): EffectDefaults {
        val during = nullableEnum("photoDuring", AnimKind.serializer())
        if (during != null && (AnimSlot.DURING !in during.slots || during.textOnly)) error("photoDuring", "not a photo animation")
        val textStart = nullableEnum("textStart", AnimKind.serializer())
        if (textStart != null &&
            (AnimSlot.START !in textStart.slots || textStart.photoOnly)
        ) {
            error("textStart", "not a caption start animation")
        }
        val kind = nullableEnum("mainTransition", TransitionKind.serializer())
        val length = long("transitionMs", Transition.MIN_MS..Transition.MAX_MS) ?: Transition.DEFAULT_MS
        finish()
        val transition = kind?.takeUnless { it == TransitionKind.NONE }?.let { Transition(it, length) }
        return EffectDefaults(photoDuring = during, mainTransition = transition, textStart = textStart)
    }

    private fun Fields.readDucking(): DuckingSpec {
        val d = DuckingSpec()
        val levels = Limits.MIN_VOLUME_DB..0.0
        val times = 0L..5000L
        val result =
            DuckingSpec(
                musicUnderClipDb = double("musicUnderClipDb", levels) ?: d.musicUnderClipDb,
                musicUnderVoiceDb = double("musicUnderVoiceDb", levels) ?: d.musicUnderVoiceDb,
                speechDuckDb = double("speechDuckDb", levels) ?: d.speechDuckDb,
                attackMs = long("attackMs", times) ?: d.attackMs,
                releaseMs = long("releaseMs", times) ?: d.releaseMs,
            )
        finish()
        return result
    }

    private fun Fields.readExport(): ExportSpec {
        val d = ExportSpec()
        val height = long("height", 0L..10_000L)?.toInt()
        if (height != null && height !in ExportSpec.HEIGHTS) error("height", "must be 480, 720 or 1080")
        val fps = long("fps", 0L..1000L)?.toInt()
        if (fps != null && fps !in ExportSpec.FRAME_RATES) error("fps", "must be 24, 25 or 30")
        val codec = string("videoCodec")
        if (codec != null && codec != "h264") error("videoCodec", "must be \"h264\"")
        val rate = long("audioSampleRate", 0L..1_000_000L)?.toInt()
        if (rate != null && rate !in ExportSpec.SAMPLE_RATES) error("audioSampleRate", "must be 44100 or 48000")
        val bitrate = long("audioBitrate", ExportSpec.BITRATES.first.toLong()..ExportSpec.BITRATES.last.toLong())?.toInt()
        finish()
        return ExportSpec(height ?: d.height, fps ?: d.fps, rate ?: d.audioSampleRate, bitrate ?: d.audioBitrate)
    }

    /** Text in the three languages; [oneLine] for a name (one visible line), otherwise caption text. */
    private fun Fields.localized(
        key: String,
        maxLength: Int,
        oneLine: Boolean = false,
    ): LocalizedText? {
        if (isNull(key)) {
            take(key)
            return null
        }
        val f = obj(key) ?: return null
        val en = f.string("en", maxLength = maxLength, text = true, oneLine = oneLine)
        val hi = f.string("hi", maxLength = maxLength, text = true, oneLine = oneLine)
        val hiLatn = f.string("hi-Latn", maxLength = maxLength, text = true, oneLine = oneLine)
        f.finish()
        if (en == null && hi == null && hiLatn == null) {
            if (!f.hasAnyError()) error(key, "needs at least one of en, hi or hi-Latn")
            return null
        }
        return LocalizedText(en, hi, hiLatn)
    }

    private class Report {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
    }

    /** One JSON object being read, with the path used in messages. */
    private class Fields(
        private val obj: JsonObject,
        private val path: String,
        val report: Report,
    ) {
        private val read = mutableSetOf<String>()

        private fun at(key: String) =
            when {
                key.isEmpty() -> path.ifEmpty { "preset" }
                path.isEmpty() -> key
                else -> "$path.$key"
            }

        fun error(
            key: String,
            message: String,
        ) {
            report.errors += "${at(key)}: $message"
        }

        fun warn(
            key: String,
            message: String,
        ) {
            report.warnings += "${at(key)}: $message"
        }

        fun hasError(key: String) = report.errors.any { it.startsWith("${at(key)}:") }

        fun hasAnyError() = report.errors.any { it.startsWith(path) }

        fun isNull(key: String) = obj[key] is JsonNull

        /** The raw value, marked as read; null when absent. */
        fun take(key: String): JsonElement? {
            read += key
            return obj[key]
        }

        /** Absent: null. JSON null where a value is required: an error. */
        private fun value(
            key: String,
            required: Boolean,
        ): JsonElement? {
            val v = take(key)
            if (v == null || v is JsonNull) {
                if (required) error(key, "is required")
                return null
            }
            return v
        }

        fun obj(
            key: String,
            required: Boolean = false,
        ): Fields? {
            val v = value(key, required) ?: return null
            if (v !is JsonObject) {
                error(key, "must be an object")
                return null
            }
            return Fields(v, at(key), report)
        }

        fun string(
            key: String,
            required: Boolean = false,
            pattern: Regex? = null,
            maxLength: Int = 64,
            text: Boolean = false,
            oneLine: Boolean = false,
        ): String? {
            val v = value(key, required) ?: return null
            if (v !is JsonPrimitive || !v.isString) {
                error(key, "must be text")
                return null
            }
            val s = v.content
            when {
                s.length > maxLength -> error(key, "is longer than $maxLength characters")
                text && s.isBlank() -> error(key, "is empty")
                text && !TextRules.isAllowed(s, allowNewlines = !oneLine) -> error(key, "contains control or invisible characters")
                text && oneLine && !TextRules.isValidName(s) -> error(key, "shows no characters")
                !text && pattern != null && !pattern.matches(s) -> error(key, "has characters that aren't allowed")
                else -> return s
            }
            return null
        }

        private fun number(
            key: String,
            required: Boolean,
        ): JsonPrimitive? {
            val v = value(key, required) ?: return null
            if (v !is JsonPrimitive || v.isString) {
                error(key, "must be a number")
                return null
            }
            return v
        }

        fun long(
            key: String,
            range: LongRange,
            required: Boolean = false,
        ): Long? {
            val v = number(key, required) ?: return null
            val n = v.longOrNull
            when {
                n == null -> error(key, "must be a whole number")
                n !in range -> error(key, "must be between ${range.first} and ${range.last}")
                else -> return n
            }
            return null
        }

        fun double(
            key: String,
            range: ClosedFloatingPointRange<Double>,
            required: Boolean = false,
        ): Double? {
            val v = number(key, required) ?: return null
            val n = v.doubleOrNull
            when {
                n == null || !n.isFinite() -> error(key, "must be a number")
                n !in range -> error(key, "must be between ${range.start} and ${range.endInclusive}")
                else -> return n
            }
            return null
        }

        fun bool(key: String): Boolean? {
            val v = value(key, required = false) ?: return null
            val b = (v as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (b == null) error(key, "must be true or false")
            return b
        }

        fun color(
            key: String,
            required: Boolean = false,
        ): ArgbColor? {
            val s = string(key, required, maxLength = 9) ?: return null
            val c = ArgbColor.parse(s)
            if (c == null) error(key, "must be a colour like #00D922")
            return c
        }

        /** An enum by its JSON name (the same names project files use). */
        fun <E : Enum<E>> enum(
            key: String,
            serializer: KSerializer<E>,
            required: Boolean = false,
        ): E? {
            val s = string(key, required, maxLength = 64) ?: return null
            return byName(key, s, serializer)
        }

        /** Like [enum], but JSON null means "none" rather than an error. */
        fun <E : Enum<E>> nullableEnum(
            key: String,
            serializer: KSerializer<E>,
        ): E? {
            if (isNull(key)) {
                take(key)
                return null
            }
            return enum(key, serializer)
        }

        private fun <E : Enum<E>> byName(
            key: String,
            name: String,
            serializer: KSerializer<E>,
        ): E? {
            val d = serializer.descriptor
            val index = (0 until d.elementsCount).firstOrNull { d.getElementName(it) == name }
            if (index == null) {
                val allowed = (0 until d.elementsCount).joinToString { d.getElementName(it) }
                error(key, "must be one of $allowed")
                return null
            }
            return Json.decodeFromJsonElement(serializer, JsonPrimitive(name))
        }

        /** Warns about keys nobody read: typos, or fields from a newer version. */
        fun finish() {
            obj.keys.filterNot { it in read }.forEach { report.warnings += "${at(it)}: ignored (unknown field)" }
        }
    }
}
