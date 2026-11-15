package com.itsluminous.clearmovie.domain.model

/**
 * A preset (ARCHITECTURE section 8, schema 1): the intro, frame and ending,
 * the caption style and the defaults a new project starts from. It carries
 * no file paths: the files are bound to [PresetSlot]s by `data`.
 *
 * A bundled preset names itself through a string resource ([displayNameRes]);
 * a user's own `preset.json` may carry a literal [displayName] instead, since
 * that is the user's data, not app text.
 */
data class Preset(
    val id: PresetId,
    val displayNameRes: String? = null,
    val displayName: LocalizedText? = null,
    val intro: IntroSpec? = null,
    val ending: EndingSpec? = null,
    val frame: FrameSpec? = null,
    val caption: CaptionStyle = CaptionStyle(),
    val defaultText: LocalizedText? = null,
    val photoDurationMs: Long = Project.DEFAULT_PHOTO_MS,
    val defaultFit: Fit = Fit.FILL_CROP,
    val effects: EffectDefaults = EffectDefaults(),
    val ducking: DuckingSpec = DuckingSpec(),
    val export: ExportSpec = ExportSpec(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Text in the shipped languages: English, Hindi and Hinglish. At least one is set. */
data class LocalizedText(
    val en: String? = null,
    val hi: String? = null,
    val hiLatn: String? = null,
)

/** The intro is always a video. */
data object IntroSpec

/** A video ending, or a spoken one over the last frame (`visual: holdLast`). */
data class EndingSpec(
    val kind: EndingKind,
)

/** `coverEnding`: whether the frame stays on over the ending. AUTO keeps it for a spoken ending only. */
enum class CoverEnding {
    AUTO,
    YES,
    NO,
    ;

    fun resolve(ending: EndingKind?): Boolean =
        when (this) {
            AUTO -> ending == EndingKind.AUDIO
            YES -> true
            NO -> false
        }
}

/** The frame's background music: its own soundtrack, played as a music row. */
data class FrameMusic(
    val enabled: Boolean = true,
    val gainDb: Double = DEFAULT_GAIN_DB,
) {
    companion object {
        const val DEFAULT_GAIN_DB = -8.0
    }
}

/** The green-screen frame that runs over the video. */
data class FrameSpec(
    val key: KeySpec,
    val contentRect: NormRect,
    val startAfterIntro: Boolean = true,
    val coverEnding: CoverEnding = CoverEnding.AUTO,
    val repeat: Boolean = true,
    val music: FrameMusic = FrameMusic(),
) {
    companion object {
        const val DEFAULT_TOLERANCE = 0.18
        const val DEFAULT_SOFTNESS = 0.08
    }
}

/** Export defaults. Only H.264 is offered. */
data class ExportSpec(
    val height: Int = 1080,
    val fps: Int = 30,
    val audioSampleRate: Int = 48000,
    val audioBitrate: Int = 192000,
) {
    companion object {
        val HEIGHTS = setOf(480, 720, 1080)
        val FRAME_RATES = setOf(24, 25, 30)
        val SAMPLE_RATES = setOf(44100, 48000)
        val BITRATES = 64_000..320_000
    }
}
