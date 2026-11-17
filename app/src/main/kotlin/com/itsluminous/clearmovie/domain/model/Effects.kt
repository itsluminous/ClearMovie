package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The transition catalogue (ARCHITECTURE sections 6.4 and 7.2). */
@Serializable
enum class TransitionKind {
    @SerialName("none")
    NONE,

    @SerialName("crossfade")
    CROSSFADE,

    @SerialName("fadeBlack")
    FADE_BLACK,

    @SerialName("fadeWhite")
    FADE_WHITE,

    @SerialName("wipeLeft")
    WIPE_LEFT,

    @SerialName("wipeRight")
    WIPE_RIGHT,

    @SerialName("wipeUp")
    WIPE_UP,

    @SerialName("wipeDown")
    WIPE_DOWN,

    @SerialName("slideLeft")
    SLIDE_LEFT,

    @SerialName("slideRight")
    SLIDE_RIGHT,

    @SerialName("slideUp")
    SLIDE_UP,

    @SerialName("slideDown")
    SLIDE_DOWN,

    @SerialName("circle")
    CIRCLE,

    @SerialName("zoomIn")
    ZOOM_IN,
}

/**
 * A transition between two MAIN clips. It is stored on the leading clip as
 * `transitionToNext` and overlaps the two clips, so it makes the video
 * shorter by [durationMs] (section 6.4).
 */
@Serializable
data class Transition(
    val kind: TransitionKind,
    val durationMs: Long = DEFAULT_MS,
) {
    companion object {
        const val MIN_MS = 300L
        const val MAX_MS = 2000L
        const val DEFAULT_MS = 1000L
    }
}

/**
 * Animation kinds; which slot each may go in is [AnimKind.slots]. A
 * [textOnly] kind goes on captions and on text and colour cards, a
 * [photoOnly] one on photos ([allowedOn]).
 */
@Serializable
enum class AnimKind(
    val slots: Set<AnimSlot>,
    val textOnly: Boolean = false,
    val photoOnly: Boolean = false,
) {
    @SerialName("fadeIn")
    FADE_IN(setOf(AnimSlot.START)),

    @SerialName("fadeOut")
    FADE_OUT(setOf(AnimSlot.END)),

    @SerialName("slideInLeft")
    SLIDE_IN_L(setOf(AnimSlot.START)),

    @SerialName("slideInRight")
    SLIDE_IN_R(setOf(AnimSlot.START)),

    @SerialName("slideInBottom")
    SLIDE_IN_B(setOf(AnimSlot.START)),

    @SerialName("slideOutLeft")
    SLIDE_OUT_L(setOf(AnimSlot.END)),

    @SerialName("slideOutRight")
    SLIDE_OUT_R(setOf(AnimSlot.END)),

    @SerialName("slideOutBottom")
    SLIDE_OUT_B(setOf(AnimSlot.END)),

    @SerialName("zoomIn")
    ZOOM_IN(setOf(AnimSlot.START)),

    @SerialName("zoomOut")
    ZOOM_OUT(setOf(AnimSlot.END)),

    @SerialName("pop")
    POP(setOf(AnimSlot.START), textOnly = true),

    @SerialName("bounce")
    BOUNCE(setOf(AnimSlot.START), textOnly = true),

    @SerialName("gentleZoomIn")
    KEN_BURNS_IN(setOf(AnimSlot.DURING), photoOnly = true),

    @SerialName("gentleZoomOut")
    KEN_BURNS_OUT(setOf(AnimSlot.DURING), photoOnly = true),

    @SerialName("pulse")
    PULSE(setOf(AnimSlot.DURING), textOnly = true),
    ;

    /** Whether this kind may go on [clip]: never on a sound. */
    fun allowedOn(clip: Clip): Boolean =
        clip !is AudioClip &&
            (!textOnly || clip is TextClip || clip is CardClip) &&
            (!photoOnly || clip is PhotoClip)
}

/** A clip can have one animation of each slot. */
@Serializable
enum class AnimSlot {
    @SerialName("start")
    START,

    @SerialName("end")
    END,

    @SerialName("during")
    DURING,
}

/**
 * One animation. [durationMs] applies to START and END; DURING always spans
 * the whole item, so its length is ignored. [seed] fixes the Ken Burns pan
 * direction so preview and export agree.
 */
@Serializable
data class Anim(
    val kind: AnimKind,
    val durationMs: Long = DEFAULT_MS,
    val seed: Int = 0,
) {
    companion object {
        const val MIN_MS = 200L
        const val MAX_MS = 1500L
        const val DEFAULT_MS = 500L
    }
}

/** Per-clip animations, on every row type except audio. */
@Serializable
data class Animations(
    val start: Anim? = null,
    val end: Anim? = null,
    val during: Anim? = null,
) {
    operator fun get(slot: AnimSlot): Anim? =
        when (slot) {
            AnimSlot.START -> start
            AnimSlot.END -> end
            AnimSlot.DURING -> during
        }

    fun with(
        slot: AnimSlot,
        anim: Anim?,
    ): Animations =
        when (slot) {
            AnimSlot.START -> copy(start = anim)
            AnimSlot.END -> copy(end = anim)
            AnimSlot.DURING -> copy(during = anim)
        }

    val isEmpty: Boolean get() = start == null && end == null && during == null

    companion object {
        val NONE = Animations()
    }
}
