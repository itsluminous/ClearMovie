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
 * One timeline row. Its index in `Project.tracks` is its screen position; its
 * drawing position is its index in `Project.stacking`. [enabled] is the
 * row's On/Off switch (the preset frame is switched off rather than
 * removed); [muted] silences an audio row. [lowerWhenTalking] is a MUSIC
 * row's "Lower music when people talk", and [lowerToDb] how far it is
 * lowered then, under voices and under videos' own sound alike; null keeps
 * the project's levels (`DuckingSpec`: -20 dB under a voice, -12 dB under a
 * video), as every row made before cm-timeline-handles does. A frame row
 * (OVERLAY) has the same two settings for its frames' own sound
 * (cm-frame-audio).
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
    /** A music row, or a frame row of the user's (its frames' own sound): Lower for talk applies to it. */
    val isLoweredForTalk: Boolean
        get() = audioRole == AudioRole.MUSIC || (type == TrackType.OVERLAY && presetRole != PresetRole.FRAME)

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
