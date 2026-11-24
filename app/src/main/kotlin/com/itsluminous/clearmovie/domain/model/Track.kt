package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kinds of timeline row (ARCHITECTURE section 6). */
@Serializable
enum class TrackType {
    /** The one photo/video row that sets the video's length: magnetic, no gaps, intro first and ending last. */
    @SerialName("main")
    MAIN,

    /** Extra photo/video rows, drawn picture in picture. */
    @SerialName("visual")
    VISUAL,

    /** Caption rows. */
    @SerialName("text")
    TEXT,

    /** Frame rows: the preset frame and any extra overlays. */
    @SerialName("overlay")
    OVERLAY,

    /** Music, voice and sound-effect rows. They are mixed, never stacked. */
    @SerialName("audio")
    AUDIO,
    ;

    /** Visual rows are drawn in `Project.stacking` order. */
    val isVisual: Boolean get() = this != AUDIO

    /** Rows whose clips carry their own start time (everything but MAIN). */
    val isTimed: Boolean get() = this != MAIN
}

/** What an AUDIO row holds; it decides the ducking rules (section 7). */
@Serializable
enum class AudioRole {
    @SerialName("music")
    MUSIC,

    @SerialName("voice")
    VOICE,

    @SerialName("effect")
    EFFECT,
}

/** Rows a preset creates and the model keeps in step with the video. */
@Serializable
enum class PresetRole {
    /** The preset frame. Never removed: it is switched off instead. */
    @SerialName("frame")
    FRAME,

    /** The frame video's own music, as background music. */
    @SerialName("frameMusic")
    FRAME_MUSIC,

    /** The spoken ending, under the held last frame. */
    @SerialName("closingMessage")
    CLOSING_MESSAGE,
}

/**
 * Where a row's sound sits in the ducking ladder (cm-voice-ducking): while
 * a row's sound plays, every row of a lower rank with Lower for talk on is
 * lowered under it. Frame sound is lowest (a frame row's own sound, and the
 * preset frame's sound, its frame music row), then music rows, then the
 * sound of photo-and-video rows, then voice rows. The closing message
 * lowers every other row (`DuckingSpec.speechDuckDb`, or a row's own level),
 * as it always has.
 */
enum class SoundRank {
    FRAME,
    MUSIC,
    VIDEO,
    VOICE,
}

/**
 * One timeline row. Its index in `Project.tracks` is its screen position; its
 * drawing position is its index in `Project.stacking`. [enabled] is the
 * row's On/Off switch (the preset frame is switched off rather than
 * removed); [muted] silences an audio row. [lowerWhenTalking] is a row's
 * Lower for talk: whether its sound is lowered under the rows above it in
 * the [SoundRank] ladder, and [lowerToDb] how far, under every one of them
 * alike; null keeps the project's levels (`DuckingSpec`: -20 dB under a
 * voice, -12 dB under a video or music), as every row made before
 * cm-timeline-handles does. Music rows have it, frame rows (OVERLAY) for
 * their frames' own sound (cm-frame-audio), and photo-and-video rows for
 * their videos' own sound (cm-voice-ducking).
 */
@Serializable
data class Track(
    val id: TrackId,
    val type: TrackType,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val enabled: Boolean = true,
    val audioRole: AudioRole? = null,
    val presetRole: PresetRole? = null,
    val lowerWhenTalking: Boolean = true,
    val lowerToDb: Double? = null,
) {
    /**
     * This row's place in the ducking ladder, or null for a row whose sound
     * neither lowers nor is lowered by it (text, the preset frame, whose
     * sound is the frame music row, and sound effects). The frame music row
     * is frame sound, below the user's own music.
     */
    val soundRank: SoundRank?
        get() =
            when (type) {
                TrackType.MAIN, TrackType.VISUAL -> {
                    SoundRank.VIDEO
                }

                TrackType.OVERLAY -> {
                    if (presetRole == PresetRole.FRAME) null else SoundRank.FRAME
                }

                TrackType.TEXT -> {
                    null
                }

                TrackType.AUDIO -> {
                    when (audioRole) {
                        AudioRole.MUSIC -> if (presetRole == PresetRole.FRAME_MUSIC) SoundRank.FRAME else SoundRank.MUSIC
                        AudioRole.VOICE -> SoundRank.VOICE
                        AudioRole.EFFECT, null -> null
                    }
                }
            }

    /** A row that something else can lower (music, a frame row of the user's, photos and videos): Lower for talk applies to it. */
    val isLoweredForTalk: Boolean
        get() = soundRank.let { it != null && it < SoundRank.VOICE }

    /** MAIN and the preset frame row can't be removed. */
    val isRemovable: Boolean get() = type != TrackType.MAIN && presetRole != PresetRole.FRAME

    /** Whether [clip] is a kind this row can hold. */
    fun accepts(clip: Clip): Boolean =
        when (type) {
            TrackType.MAIN -> clip is MainClip && (clip !is MediaClip || clip.pip == null)
            TrackType.VISUAL -> clip is MediaClip && clip.pip != null
            TrackType.TEXT -> clip is TextClip
            TrackType.OVERLAY -> clip is OverlayClip
            TrackType.AUDIO -> clip is AudioClip
        }

    /**
     * True when no clip on this timed row overlaps the half-open span
     * [startMs, endMs), ignoring [except]. Empty spans never overlap.
     */
    fun isFree(
        startMs: Long,
        endMs: Long,
        except: ClipId? = null,
    ): Boolean =
        endMs <= startMs ||
            clips.none { it.id != except && it.durationMs > 0 && it.startMs < endMs && startMs < it.endMs }
}
