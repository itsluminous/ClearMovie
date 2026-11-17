package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.TransitionKind

/** Which way a wipe, slide or slide-in goes, for its arrow and its spoken name. */
enum class EffectDirection { LEFT, RIGHT, UP, DOWN }

/** One tile of the transition picker: its kind and its short label under the group's heading. */
data class TransitionTile(
    val kind: TransitionKind,
    val label: Int,
)

/**
 * The transition picker's groups (ARCHITECTURE section 11.10): a heading
 * each, with short tile labels under it ("Wipe" over Left, Right, Up and
 * Down), so the sheet reads as four tidy rows rather than fourteen names.
 */
enum class TransitionGroup(
    val label: Int,
    val tiles: List<TransitionTile>,
) {
    FADE(
        R.string.transition_group_fade,
        listOf(
            TransitionTile(TransitionKind.NONE, R.string.effect_none),
            TransitionTile(TransitionKind.CROSSFADE, R.string.transition_crossfade),
            TransitionTile(TransitionKind.FADE_BLACK, R.string.transition_tile_black),
            TransitionTile(TransitionKind.FADE_WHITE, R.string.transition_tile_white),
        ),
    ),
    WIPE(
        R.string.transition_wipe,
        listOf(TransitionKind.WIPE_LEFT, TransitionKind.WIPE_RIGHT, TransitionKind.WIPE_UP, TransitionKind.WIPE_DOWN).map {
            TransitionTile(it, directionName(transitionDirection(it)!!))
        },
    ),
    SLIDE(
        R.string.transition_slide,
        listOf(TransitionKind.SLIDE_LEFT, TransitionKind.SLIDE_RIGHT, TransitionKind.SLIDE_UP, TransitionKind.SLIDE_DOWN).map {
            TransitionTile(it, directionName(transitionDirection(it)!!))
        },
    ),
    SHAPE(
        R.string.transition_group_shape,
        listOf(
            TransitionTile(TransitionKind.CIRCLE, R.string.transition_circle),
            TransitionTile(TransitionKind.ZOOM_IN, R.string.transition_tile_zoom),
        ),
    ),
}

/** The full name of a transition kind, without its direction (TalkBack, the junction marker). */
fun transitionName(kind: TransitionKind): Int =
    when (kind) {
        TransitionKind.NONE -> R.string.effect_none
        TransitionKind.CROSSFADE -> R.string.transition_crossfade
        TransitionKind.FADE_BLACK -> R.string.transition_fade_black
        TransitionKind.FADE_WHITE -> R.string.transition_fade_white
        TransitionKind.WIPE_LEFT, TransitionKind.WIPE_RIGHT, TransitionKind.WIPE_UP, TransitionKind.WIPE_DOWN -> R.string.transition_wipe
        TransitionKind.SLIDE_LEFT, TransitionKind.SLIDE_RIGHT, TransitionKind.SLIDE_UP, TransitionKind.SLIDE_DOWN -> R.string.transition_slide
        TransitionKind.CIRCLE -> R.string.transition_circle
        TransitionKind.ZOOM_IN -> R.string.transition_zoom
    }

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
