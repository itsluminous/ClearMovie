package com.itsluminous.clearmovie.render.plan

import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TrackId

/**
 * Everything needed to draw and mix a project (ARCHITECTURE section 7), as
 * plain data. `CompositionFactory` turns it into one Media3 `Composition`,
 * which both the preview and the export use, so the two cannot drift apart.
 *
 * Every time is in microseconds on the video's timeline. Every layer covers
 * exactly [0, [durationUs]): gaps are explicit transparent [VisualSegment.Fill]s
 * or [AudioSegment.Silence]s, never missing time.
 *
 * [visual] is in DRAWING order, bottom first (it follows `Project.stacking`,
 * with MAIN expanded into its A and B lanes). [audio] is mixed.
 */
data class RenderPlan(
    val width: Int,
    val height: Int,
    val fps: Int,
    val durationUs: Long,
    val visual: List<VisualLayer>,
    val audio: List<AudioLayer>,
) {
    /** How many video files are decoded at [timeUs]: the decoder count risk 5 limits. */
    fun videoDecodersAt(timeUs: Long): Int =
        visual.count { layer -> layer.segmentAt(timeUs)?.let { it is VisualSegment.Picture && it.source is Source.Video } == true }
}

/** What a visual layer comes from. MAIN_B exists only when MAIN has a transition. */
enum class LayerRole { MAIN, MAIN_B, VISUAL, OVERLAY, TEXT }

data class VisualLayer(
    val trackId: TrackId,
    val role: LayerRole,
    val segments: List<VisualSegment>,
) {
    fun segmentAt(timeUs: Long): VisualSegment? = segments.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
}

sealed interface VisualSegment {
    val startUs: Long
    val durationUs: Long
    val endUs: Long get() = startUs + durationUs

    /** Nothing is drawn: the layers below show through. */
    data class Fill(
        override val startUs: Long,
        override val durationUs: Long,
    ) : VisualSegment

    /**
     * A picture on the canvas. One timeline clip can become several
     * segments (a looped video, a repeated frame); [clipOffsetUs] is where
     * this segment starts inside its clip, so a transition or animation
     * written in clip time ([alpha]) spans the copies correctly. The opacity
     * is the product of the [alpha] ramps.
     */
    data class Picture(
        override val startUs: Long,
        override val durationUs: Long,
        val source: Source,
        val placement: Placement,
        val key: KeySpec? = null,
        val alpha: List<AlphaRamp> = emptyList(),
        val clipOffsetUs: Long = 0,
    ) : VisualSegment {
        /** Opacity at [clipUs] of clip time. */
        fun alphaAt(clipUs: Long): Float = alpha.fold(1f) { acc, ramp -> acc * ramp.alphaAt(clipUs) }
    }
}

/** Where a picture's pixels come from. */
sealed interface Source {
    /** A video, playing [clipStartUs]..[clipEndUs] of the file at [speed]. Its sound is planned separately. */
    data class Video(
        val media: MediaRef,
        val sourceDurationUs: Long,
        val clipStartUs: Long,
        val clipEndUs: Long,
        val speed: Speed = Speed.X1,
    ) : Source

    data class Photo(
        val media: MediaRef,
    ) : Source

    /** One frame of a video, held (the `holdLast` ending). */
    data class VideoFrame(
        val media: MediaRef,
        val atUs: Long,
    ) : Source

    /** A caption drawn into its box. */
    data class Caption(
        val text: String,
        val style: CaptionStyle,
    ) : Source

    /** A text or colour card. */
    data class Card(
        val color: ArgbColor,
        val text: String?,
        val style: CaptionStyle,
    ) : Source
}

/** Where a picture goes on the canvas. Rectangles are normalised (0..1, y down). */
sealed interface Placement {
    /** The whole canvas, stretched (the intro, a video ending, the frame). */
    data object FullFrame : Placement

    /** Into the frame window (or the whole canvas without a frame), by [fit] (R11). */
    data class Window(
        val rect: NormRect,
        val fit: Fit,
        val rotation: Rotation = Rotation.R0,
    ) : Placement

    /** Picture in picture: cover-cropped into [rect], with a thin white border. */
    data class Pip(
        val rect: NormRect,
        val rotation: Rotation = Rotation.R0,
    ) : Placement

    /** Stretched into [rect]: a caption bitmap drawn at its box size. */
    data class Box(
        val rect: NormRect,
    ) : Placement
}

/**
 * Opacity going linearly from [from] to [to] between [startUs] and [endUs]
 * of CLIP time; constant outside. The basic crossfade on MAIN uses it; the
 * other transitions arrive with cm-effects.
 */
data class AlphaRamp(
    val startUs: Long,
    val endUs: Long,
    val from: Float,
    val to: Float,
) {
    fun alphaAt(clipUs: Long): Float =
        when {
            clipUs <= startUs -> from
            clipUs >= endUs -> to
            else -> from + (to - from) * ((clipUs - startUs).toFloat() / (endUs - startUs))
        }
}

/** Why an audio layer exists, for the ducking rules (section 7). */
enum class AudioLayerRole { CLIP, MUSIC, VOICE, CLOSING_MESSAGE, EFFECT }

data class AudioLayer(
    val trackId: TrackId,
    val role: AudioLayerRole,
    val segments: List<AudioSegment>,
)

sealed interface AudioSegment {
    val startUs: Long
    val durationUs: Long
    val endUs: Long get() = startUs + durationUs

    data class Silence(
        override val startUs: Long,
        override val durationUs: Long,
    ) : AudioSegment

    /**
     * [clipStartUs]..[clipEndUs] of a file at [speed]. [gain] is in this
     * segment's own time (0 at [startUs]) on the timeline, i.e. after the
     * speed change, which is the time the audio processors see.
     */
    data class Sound(
        override val startUs: Long,
        override val durationUs: Long,
        val media: MediaRef,
        val sourceDurationUs: Long,
        val clipStartUs: Long,
        val clipEndUs: Long,
        val speed: Speed,
        val gain: GainEnvelope,
    ) : AudioSegment
}
