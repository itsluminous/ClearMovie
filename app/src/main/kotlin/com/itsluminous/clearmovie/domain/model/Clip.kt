package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bounds every clip respects; `validate()` enforces them. */
object Limits {
    /** The shortest clip on any row. */
    const val MIN_CLIP_MS = 100L

    /** Longer than any real media, and small enough that sums of times never overflow. */
    const val MAX_MEDIA_MS = 24L * 60 * 60 * 1000
    const val MAX_LOOPS = 20
    const val MIN_VOLUME_DB = -60.0
    const val MAX_VOLUME_DB = 12.0
    const val MAX_TEXT_LENGTH = 500
    const val MAX_NAME_LENGTH = 100

    /** A new caption without a clip to follow lasts this long (section 6.1). */
    const val DEFAULT_TEXT_MS = 3000L

    /** Text and colour cards (section 11.6). */
    const val DEFAULT_CARD_MS = 3000L
}

/** How a photo or video fills the frame window (R11). */
@Serializable
enum class Fit {
    @SerialName("fillCrop")
    FILL_CROP,

    @SerialName("fitBlur")
    FIT_BLUR,

    @SerialName("fitBars")
    FIT_BARS,
}

@Serializable
enum class Rotation(
    val degrees: Int,
) {
    @SerialName("0")
    R0(0),

    @SerialName("90")
    R90(90),

    @SerialName("180")
    R180(180),

    @SerialName("270")
    R270(270),
    ;

    /** A quarter turn clockwise. */
    fun next(): Rotation = entries[(ordinal + 1) % entries.size]
}

/** The speeds the editor offers (section 6.3), as exact fractions so durations never drift. */
@Serializable
enum class Speed(
    val numerator: Int,
    val denominator: Int,
) {
    @SerialName("0.25")
    X0_25(1, 4),

    @SerialName("0.5")
    X0_5(1, 2),

    @SerialName("1")
    X1(1, 1),

    @SerialName("1.5")
    X1_5(3, 2),

    @SerialName("2")
    X2(2, 1),

    @SerialName("4")
    X4(4, 1),
    ;

    /** Timeline length of [sourceMs] of media played at this speed. */
    fun timelineMs(sourceMs: Long): Long = sourceMs * denominator / numerator

    /** Media consumed by [timelineMs] of timeline at this speed. */
    fun sourceMs(timelineMs: Long): Long = timelineMs * numerator / denominator
}

@Serializable
enum class Corner {
    @SerialName("topLeft")
    TOP_LEFT,

    @SerialName("topRight")
    TOP_RIGHT,

    @SerialName("bottomLeft")
    BOTTOM_LEFT,

    @SerialName("bottomRight")
    BOTTOM_RIGHT,
}

/** Picture-in-picture placement inside the frame window: a corner and a width fraction. */
@Serializable
data class PipPlacement(
    val corner: Corner = Corner.TOP_RIGHT,
    val scale: Double = DEFAULT_SCALE,
) {
    companion object {
        const val DEFAULT_SCALE = 0.35
        const val MIN_SCALE = 0.15
        const val MAX_SCALE = 0.9
        val DEFAULT = PipPlacement()
    }
}

/** Chroma key settings for a green-screen overlay. */
@Serializable
data class KeySpec(
    val color: ArgbColor,
    val tolerance: Double,
    val softness: Double,
)

/**
 * A clip whose time span follows the project instead of being set by the
 * user. The model re-computes it after every edit; trimming or moving the
 * clip by hand clears the anchor and it becomes an ordinary clip.
 */
@Serializable
sealed interface SpanAnchor {
    /**
     * The preset frame's span: from the end of the intro (or 0) to the end of
     * the video, or to the start of the ending when [coverEnding] is false.
     */
    @Serializable
    @SerialName("frame")
    data class Frame(
        val startAfterIntro: Boolean,
        val coverEnding: Boolean,
    ) : SpanAnchor

    /** The span of the MAIN row's ending (the closing message). */
    @Serializable
    @SerialName("ending")
    data object Ending : SpanAnchor
}

@Serializable
enum class EndingKind {
    /** A video drawn full frame. */
    @SerialName("video")
    VIDEO,

    /** A spoken message over the last content frame (`visual = holdLast`). */
    @SerialName("audio")
    AUDIO,
}

/**
 * A timeline item. On MAIN, [startMs] is always 0 and the real start comes
 * from the clip order (see `Timeline`); on every other row it is the item's
 * start time. [durationMs] is the item's length on the timeline.
 */
@Serializable
sealed interface Clip {
    val id: ClipId
    val startMs: Long
    val durationMs: Long
    val animations: Animations

    val endMs: Long get() = startMs + durationMs

    fun withStartMs(ms: Long): Clip

    fun withAnimations(animations: Animations): Clip

    fun withId(id: ClipId): Clip
}

/** A clip that can sit on the MAIN row and own the transition to the next one. */
@Serializable
sealed interface MainClip : Clip {
    val transitionToNext: Transition?

    fun withTransition(transition: Transition?): MainClip
}

/** A photo or video: on MAIN, or on a picture-in-picture row with a [pip] placement. */
@Serializable
sealed interface MediaClip : MainClip {
    val media: MediaRef
    val fit: Fit
    val rotation: Rotation
    val pip: PipPlacement?

    fun withPip(pip: PipPlacement?): MediaClip

    fun withFit(fit: Fit): MediaClip

    fun withRotation(rotation: Rotation): MediaClip
}

@Serializable
@SerialName("photo")
data class PhotoClip(
    override val id: ClipId,
    override val media: MediaRef,
    override val durationMs: Long,
    override val startMs: Long = 0,
    override val rotation: Rotation = Rotation.R0,
    override val fit: Fit = Fit.FILL_CROP,
    override val pip: PipPlacement? = null,
    override val transitionToNext: Transition? = null,
    override val animations: Animations = Animations.NONE,
) : MediaClip {
    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)

    override fun withTransition(transition: Transition?) = copy(transitionToNext = transition)

    override fun withPip(pip: PipPlacement?) = copy(pip = pip)

    override fun withFit(fit: Fit) = copy(fit = fit)

    override fun withRotation(rotation: Rotation) = copy(rotation = rotation)
}

/**
 * A video. It plays [trimStartMs]..[trimEndMs] of the source at [speed],
 * [loops] times. Clips made by a split share a [splitGroup] and have
 * adjacent trims, which is what makes them joinable.
 */
@Serializable
@SerialName("video")
data class VideoClip(
    override val id: ClipId,
    override val media: MediaRef,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val speed: Speed = Speed.X1,
    val loops: Int = 1,
    val splitGroup: String? = null,
    val volumeDb: Double = 0.0,
    override val startMs: Long = 0,
    override val rotation: Rotation = Rotation.R0,
    override val fit: Fit = Fit.FILL_CROP,
    override val pip: PipPlacement? = null,
    override val transitionToNext: Transition? = null,
    override val animations: Animations = Animations.NONE,
) : MediaClip {
    override val durationMs: Long get() = speed.timelineMs((trimEndMs - trimStartMs) * loops)

    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)

    override fun withTransition(transition: Transition?) = copy(transitionToNext = transition)

    override fun withPip(pip: PipPlacement?) = copy(pip = pip)

    override fun withFit(fit: Fit) = copy(fit = fit)

    override fun withRotation(rotation: Rotation) = copy(rotation = rotation)
}

/** A text card or colour card on MAIN: optional large text on a solid colour (section 11.6). */
@Serializable
@SerialName("card")
data class CardClip(
    override val id: ClipId,
    override val durationMs: Long,
    val color: ArgbColor,
    val text: String? = null,
    val style: CaptionStyleOverride = CaptionStyleOverride.NONE,
    override val startMs: Long = 0,
    override val transitionToNext: Transition? = null,
    override val animations: Animations = Animations.NONE,
) : MainClip {
    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)

    override fun withTransition(transition: Transition?) = copy(transitionToNext = transition)
}

/** The preset intro, pinned first on MAIN. */
@Serializable
@SerialName("intro")
data class IntroClip(
    override val id: ClipId,
    override val durationMs: Long,
    override val startMs: Long = 0,
    override val transitionToNext: Transition? = null,
    override val animations: Animations = Animations.NONE,
) : MainClip {
    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)

    override fun withTransition(transition: Transition?) = copy(transitionToNext = transition)
}

/**
 * The preset ending, pinned last on MAIN. For an [EndingKind.AUDIO] ending
 * this is the held last frame; the speech itself is a VOICE clip on the
 * closing-message row, anchored to this clip.
 */
@Serializable
@SerialName("ending")
data class EndingClip(
    override val id: ClipId,
    override val durationMs: Long,
    val kind: EndingKind,
    override val startMs: Long = 0,
    override val transitionToNext: Transition? = null,
    override val animations: Animations = Animations.NONE,
) : MainClip {
    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)

    override fun withTransition(transition: Transition?) = copy(transitionToNext = transition)
}

/** A caption on a TEXT row. [style] holds only the user's changes to the preset style. */
@Serializable
@SerialName("text")
data class TextClip(
    override val id: ClipId,
    override val startMs: Long,
    override val durationMs: Long,
    val text: String,
    val style: CaptionStyleOverride = CaptionStyleOverride.NONE,
    override val animations: Animations = Animations.NONE,
) : Clip {
    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)
}

/**
 * A frame or overlay video on an OVERLAY row. With [repeat] the source is
 * repeated to fill [durationMs] and the last copy is cut; [key] keys out its
 * green window (null draws it as it is).
 */
@Serializable
@SerialName("overlay")
data class OverlayClip(
    override val id: ClipId,
    val media: MediaRef,
    override val startMs: Long,
    override val durationMs: Long,
    val sourceDurationMs: Long,
    val key: KeySpec? = null,
    val repeat: Boolean = true,
    val anchor: SpanAnchor? = null,
    override val animations: Animations = Animations.NONE,
) : Clip {
    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = copy(animations = animations)

    override fun withId(id: ClipId) = copy(id = id)
}

/**
 * Sound on an AUDIO row. It plays [trimStartMs]..[trimEndMs] of the source;
 * with [repeat] that range repeats to fill [durationMs], otherwise
 * [durationMs] is at most the range. Audio clips have no animations, only fades.
 */
@Serializable
@SerialName("audio")
data class AudioClip(
    override val id: ClipId,
    val media: MediaRef,
    override val startMs: Long,
    override val durationMs: Long,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val repeat: Boolean = false,
    val volumeDb: Double = 0.0,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val anchor: SpanAnchor? = null,
) : Clip {
    override val animations: Animations get() = Animations.NONE

    override fun withStartMs(ms: Long) = copy(startMs = ms)

    override fun withAnimations(animations: Animations) = this

    override fun withId(id: ClipId) = copy(id = id)
}
