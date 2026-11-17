package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.TransitionKind

/** Which way a wipe, slide or slide-in goes, for its arrow and its spoken name. */
enum class EffectDirection { LEFT, RIGHT, UP, DOWN }

/**
 * The transition picker's groups (ARCHITECTURE section 11.10): one heading
 * per effect, the directions as tiles under it, so the sheet shows eight
 * headings rather than fourteen names.
 */
enum class TransitionGroup(
    val label: Int,
    val kinds: List<TransitionKind>,
) {
    NONE(R.string.effect_none, listOf(TransitionKind.NONE)),
    CROSSFADE(R.string.transition_crossfade, listOf(TransitionKind.CROSSFADE)),
    FADE_BLACK(R.string.transition_fade_black, listOf(TransitionKind.FADE_BLACK)),
    FADE_WHITE(R.string.transition_fade_white, listOf(TransitionKind.FADE_WHITE)),
    WIPE(
        R.string.transition_wipe,
        listOf(TransitionKind.WIPE_LEFT, TransitionKind.WIPE_RIGHT, TransitionKind.WIPE_UP, TransitionKind.WIPE_DOWN),
    ),
    SLIDE(
        R.string.transition_slide,
        listOf(TransitionKind.SLIDE_LEFT, TransitionKind.SLIDE_RIGHT, TransitionKind.SLIDE_UP, TransitionKind.SLIDE_DOWN),
    ),
    CIRCLE(R.string.transition_circle, listOf(TransitionKind.CIRCLE)),
    ZOOM(R.string.transition_zoom, listOf(TransitionKind.ZOOM_IN)),
}

/** The name of a transition kind, without its direction. */
fun transitionName(kind: TransitionKind): Int = TransitionGroup.entries.first { kind in it.kinds }.label

fun transitionDirection(kind: TransitionKind): EffectDirection? =
    when (kind) {
        TransitionKind.WIPE_LEFT, TransitionKind.SLIDE_LEFT -> EffectDirection.LEFT
        TransitionKind.WIPE_RIGHT, TransitionKind.SLIDE_RIGHT -> EffectDirection.RIGHT
        TransitionKind.WIPE_UP, TransitionKind.SLIDE_UP -> EffectDirection.UP
        TransitionKind.WIPE_DOWN, TransitionKind.SLIDE_DOWN -> EffectDirection.DOWN
        else -> null
    }

/** The name of an animation kind, without its direction. */
fun animationName(kind: AnimKind): Int =
    when (kind) {
        AnimKind.FADE_IN -> R.string.anim_fade_in
        AnimKind.FADE_OUT -> R.string.anim_fade_out
        AnimKind.SLIDE_IN_L, AnimKind.SLIDE_IN_R, AnimKind.SLIDE_IN_B -> R.string.anim_slide_in
        AnimKind.SLIDE_OUT_L, AnimKind.SLIDE_OUT_R, AnimKind.SLIDE_OUT_B -> R.string.anim_slide_out
        AnimKind.ZOOM_IN -> R.string.anim_zoom_in
        AnimKind.ZOOM_OUT -> R.string.anim_zoom_out
        AnimKind.POP -> R.string.anim_pop
        AnimKind.BOUNCE -> R.string.anim_bounce
        AnimKind.KEN_BURNS_IN -> R.string.anim_ken_burns_in
        AnimKind.KEN_BURNS_OUT -> R.string.anim_ken_burns_out
        AnimKind.PULSE -> R.string.anim_pulse
    }

/** The way a slide-in comes from, or a slide-out goes to; null for the other kinds. */
fun animationDirection(kind: AnimKind): EffectDirection? =
    when (kind) {
        AnimKind.SLIDE_IN_L -> EffectDirection.RIGHT
        AnimKind.SLIDE_IN_R -> EffectDirection.LEFT
        AnimKind.SLIDE_IN_B -> EffectDirection.UP
        AnimKind.SLIDE_OUT_L -> EffectDirection.LEFT
        AnimKind.SLIDE_OUT_R -> EffectDirection.RIGHT
        AnimKind.SLIDE_OUT_B -> EffectDirection.DOWN
        else -> null
    }

fun directionName(direction: EffectDirection): Int =
    when (direction) {
        EffectDirection.LEFT -> R.string.direction_left
        EffectDirection.RIGHT -> R.string.direction_right
        EffectDirection.UP -> R.string.direction_up
        EffectDirection.DOWN -> R.string.direction_down
    }
