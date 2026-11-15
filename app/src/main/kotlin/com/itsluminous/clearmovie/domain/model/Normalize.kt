package com.itsluminous.clearmovie.domain.model

/**
 * Brings a project back to its canonical form after an edit. Every edit
 * ends here, so the rules below hold after any change:
 * - MAIN clips have `startMs = 0`; a NONE transition is stored as null; the
 *   last MAIN clip owns no transition.
 * - With **Apply to all** set, every MAIN junction has that transition.
 * - Each transition is fitted to the clips it joins (at most half the shorter
 *   one, section 6.4), or dropped when it no longer fits.
 * - START and END animations fit inside their clip.
 * - Clips anchored to the frame span or the ending follow the MAIN row; a
 *   clip anchored to an ending that is gone is removed.
 * - A clip an anchored clip grew over is moved to another row, so rows
 *   never overlap.
 *
 * It is idempotent: normalising a normalised project changes nothing.
 */
fun Project.normalized(ids: IdSource = RandomIds): Project =
    normalizeMain()
        .normalizeAnimations()
        .normalizeAnchors(ids)

private fun Project.normalizeMain(): Project {
    val clips = main.clips.filterIsInstance<MainClip>()
    val fixed =
        clips.mapIndexed { i, clip ->
            val wanted =
                when {
                    i == clips.lastIndex -> null
                    transitionForAll != null -> transitionForAll
                    else -> clip.transitionToNext
                }
            val fitted = if (i < clips.lastIndex) Timeline.fit(wanted, clip.durationMs, clips[i + 1].durationMs) else null
            val placed = if (clip.startMs != 0L) clip.withStartMs(0) as MainClip else clip
            if (placed.transitionToNext == fitted) placed else placed.withTransition(fitted)
        }
    val forAll = transitionForAll?.takeUnless { it.kind == TransitionKind.NONE }
    return withTrackClips(main.id, fixed).copy(transitionForAll = forAll)
}

private fun Project.normalizeAnimations(): Project =
    copy(
        tracks =
            tracks.map { track ->
                track.copy(clips = track.clips.map { it.withFittedAnimations() })
            },
    )

/** START and END animations shortened to fit the clip together, or dropped when they can't. */
internal fun Clip.withFittedAnimations(): Clip {
    if (this is AudioClip || animations.isEmpty) return this
    val a = animations
    val both = a.start != null && a.end != null
    val cap = if (both) durationMs / 2 else durationMs

    fun fit(anim: Anim?): Anim? {
        if (anim == null) return null
        val length = minOf(anim.durationMs.coerceIn(Anim.MIN_MS, Anim.MAX_MS), cap)
        return when {
            length < Anim.MIN_MS -> null
            length == anim.durationMs -> anim
            else -> anim.copy(durationMs = length)
        }
    }
    val fitted = a.copy(start = fit(a.start), end = fit(a.end))
    return if (fitted == a) this else withAnimations(fitted)
}

private fun Project.normalizeAnchors(ids: IdSource): Project {
    var project = this
    for (track in tracks) {
        for (clip in track.clips) {
            val anchor =
                when (clip) {
                    is OverlayClip -> clip.anchor
                    is AudioClip -> clip.anchor
                    else -> null
                } ?: continue
            val span = project.anchorSpan(anchor)
            val current = project.track(track.id)!!
            if (span == null) {
                project = project.withTrackClips(track.id, current.clips.filterNot { it.id == clip.id })
                continue
            }
            val respanned = clip.respanned(span.first, span.second)
            if (respanned != clip) {
                project = project.withTrackClips(track.id, current.clips.map { if (it.id == clip.id) respanned else it })
            }
            project = project.moveAsideFrom(track.id, respanned, ids)
        }
    }
    return project
}

/** The clip moved to [start] and stretched to [end] (capped at its media when it does not repeat). */
private fun Clip.respanned(
    start: Long,
    end: Long,
): Clip {
    val span = end - start
    return when (this) {
        is OverlayClip -> copy(startMs = start, durationMs = if (repeat) span else minOf(span, sourceDurationMs))
        is AudioClip -> copy(startMs = start, durationMs = if (repeat) span else minOf(span, trimEndMs - trimStartMs))
        else -> this
    }
}

/** Moves every other clip on row [trackId] that overlaps [keep] to another row of the same type. */
private fun Project.moveAsideFrom(
    trackId: TrackId,
    keep: Clip,
    ids: IdSource,
): Project {
    val track = track(trackId)!!
    val inTheWay = track.clips.filter { it.id != keep.id && !track.copy(clips = listOf(keep)).isFree(it.startMs, it.endMs) }
    if (inTheWay.isEmpty()) return this
    var project = withTrackClips(trackId, track.clips - inTheWay.toSet())
    for (clip in inTheWay) {
        project = project.placeTimed(clip, preferred = null, role = track.audioRole, ids = ids).first
    }
    return project
}
