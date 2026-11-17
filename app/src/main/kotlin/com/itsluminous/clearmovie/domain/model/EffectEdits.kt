package com.itsluminous.clearmovie.domain.model

// Preset effect defaults (ARCHITECTURE section 11.10, cm-model residual): the
// project carries the preset's defaults, and they are applied to items as
// they are added. Adding is not the only way a clip enters a project (a share
// draft is rebuilt, review choices rebuild the template), so the operations
// that add stay neutral and the callers that add NEW items ask for the
// defaults explicitly.

/**
 * The preset's effect defaults ([Project.effectDefaults]) on the new items
 * [newIds], as part of the same edit:
 * - a new photo gets the default "during" animation (a gentle Ken Burns),
 *   with a pan direction of its own ([kenBurnsSeed]);
 * - a new caption gets the default start animation;
 * - every MAIN junction a new item made (into it, and out of it) gets the
 *   default transition, unless the item leading into it already has one or
 *   **Apply to all** is on. The cut between two halves of a split never
 *   gets one, and a transition that does not fit is shortened or dropped
 *   (cm-model D7).
 *
 * Nothing the item already has is replaced, so applying it twice changes
 * nothing. Ids that are not in the project are ignored.
 */
fun Project.withEffectDefaultsFor(newIds: Collection<ClipId>): Project {
    val ids = newIds.toSet()
    if (ids.isEmpty()) return this
    val d = effectDefaults
    val animated =
        copy(
            tracks =
                tracks.map { track ->
                    track.copy(clips = track.clips.map { clip -> if (clip.id in ids) clip.withDefaultAnimations(d) else clip })
                },
        )
    val transition = d.mainTransition?.takeUnless { it.kind == TransitionKind.NONE }
    if (transition == null || transitionForAll != null) return animated.normalized()
    val clips = animated.main.clips
    val changed =
        clips.mapIndexed { i, clip ->
            val next = clips.getOrNull(i + 1)
            val main = clip as? MainClip
            val newJunction = next != null && (clip.id in ids || next.id in ids)
            if (main != null && newJunction && main.transitionToNext == null && !isContinuousSplit(clip, next!!)) {
                main.withTransition(transition)
            } else {
                clip
            }
        }
    return animated.withTrackClips(animated.main.id, changed).normalized()
}

private fun Clip.withDefaultAnimations(d: EffectDefaults): Clip {
    val a = animations
    return when {
        this is PhotoClip && d.photoDuring != null && a.during == null && d.photoDuring.allowedOn(this) -> {
            withAnimations(a.copy(during = Anim(d.photoDuring, seed = kenBurnsSeed(id))))
        }

        this is TextClip && d.textStart != null && a.start == null && d.textStart.allowedOn(this) -> {
            withAnimations(a.copy(start = Anim(d.textStart)))
        }

        else -> {
            this
        }
    }
}

/**
 * A Ken Burns pan direction for [id]: fixed per item (String.hashCode is the
 * same on every device), so neighbouring photos drift different ways and the
 * preview and the export agree.
 */
fun kenBurnsSeed(id: ClipId): Int = id.value.hashCode() and 0x7fffffff

/** The animation kinds that may go in [slot] on [clip], in the order the picker shows them. */
fun Clip.animationChoices(slot: AnimSlot): List<AnimKind> = AnimKind.entries.filter { slot in it.slots && it.allowedOn(this) }
