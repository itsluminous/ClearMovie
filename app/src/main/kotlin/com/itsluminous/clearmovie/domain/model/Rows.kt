package com.itsluminous.clearmovie.domain.model

// Where new rows go, and the "highest free row" rule (sections 6 and 6.1).

/**
 * The screen position for a new row of [type]: directly above the existing
 * rows of that type. Without one: a TEXT row goes at the top, a VISUAL or
 * OVERLAY row directly above MAIN, and an AUDIO row directly below MAIN.
 */
internal fun Project.screenIndexForNewTrack(type: TrackType): Int {
    val topmostOfType = tracks.indexOfFirst { it.type == type }
    if (topmostOfType >= 0) return topmostOfType
    val mainIndex = tracks.indexOfFirst { it.type == TrackType.MAIN }
    return when (type) {
        TrackType.TEXT -> 0
        TrackType.VISUAL, TrackType.OVERLAY, TrackType.MAIN -> mainIndex.coerceAtLeast(0)
        TrackType.AUDIO -> mainIndex + 1
    }
}

/**
 * The stacking position (an index into the bottom-first [Project.stacking])
 * for a new visual row of [type]: TEXT on top of everything, OVERLAY just under
 * the lowest TEXT row above MAIN, VISUAL just above MAIN.
 */
internal fun Project.stackIndexForNewTrack(type: TrackType): Int =
    when (type) {
        TrackType.TEXT -> {
            stacking.size
        }

        TrackType.OVERLAY -> {
            // Just under the lowest text row above MAIN; never under MAIN, where it would be hidden.
            val mainIndex = stackIndex(main.id)
            val lowestText = stacking.indices.firstOrNull { it > mainIndex && track(stacking[it])?.type == TrackType.TEXT }
            lowestText ?: stacking.size
        }

        TrackType.VISUAL -> {
            stackIndex(main.id) + 1
        }

        TrackType.MAIN -> {
            0
        }

        TrackType.AUDIO -> {
            -1
        }
    }

/** Adds [track] at the default screen and stacking positions for its type. */
internal fun Project.withNewTrack(track: Track): Project {
    require(tracks.none { it.id == track.id }) { "Row id already used" }
    require(track.type != TrackType.MAIN) { "A project has one MAIN row" }
    val screen = screenIndexForNewTrack(track.type)
    val newTracks = tracks.toMutableList().apply { add(screen, track) }
    val newStacking =
        if (track.type.isVisual) {
            stacking.toMutableList().apply { add(stackIndexForNewTrack(track.type), track.id) }
        } else {
            stacking
        }
    return copy(tracks = newTracks, stacking = newStacking)
}

/**
 * Rows a new clip of [type] may go on, best first: visual rows from the top of
 * the stacking order, audio rows (only those of [role]) from the top of the
 * screen. Rows that are switched off, and the rows a preset manages, are left
 * out: those only take clips when the user picks them directly.
 */
internal fun Project.candidateTracks(
    type: TrackType,
    role: AudioRole?,
): List<Track> {
    val ofType = tracks.filter { it.type == type && it.enabled && it.presetRole == null }
    return if (type.isVisual) {
        val byId = ofType.associateBy { it.id }
        stackingTopFirst.mapNotNull { byId[it] }
    } else {
        ofType.filter { it.audioRole == role }
    }
}

/**
 * A row a preset manages (the frame, its music, the closing message) holds
 * only its managed clip, so the user's items always go on rows of their own
 * and switching the frame off never hides them.
 */
internal fun Track.takes(clip: Clip): Boolean =
    presetRole == null || clip.isPresetManaged ||
        // The preset's frame cut where a new frame takes over (cm-plus-position) keeps fixed times, and its row still
        // holds it alone; nothing new is ever placed there.
        (presetRole == PresetRole.FRAME && clip is OverlayClip && clips.size == 1 && clips.single().id == clip.id)

/**
 * A clip the preset put in: one that plays a preset file or follows a span.
 * (A frame replaced for one video keeps its span, so it still counts.)
 */
internal val Clip.isPresetManaged: Boolean
    get() =
        when (this) {
            is OverlayClip -> media is MediaRef.Preset || anchor != null
            is AudioClip -> media is MediaRef.Preset || anchor != null
            else -> false
        }

/** The row type a timed clip belongs on. */
internal fun Clip.timedTrackType(): TrackType =
    when (this) {
        is TextClip -> TrackType.TEXT
        is OverlayClip -> TrackType.OVERLAY
        is AudioClip -> TrackType.AUDIO
        is MediaClip -> TrackType.VISUAL
        else -> throw IllegalArgumentException("This clip only goes on the MAIN row")
    }

/** Replaces the clips of row [id]; timed rows are kept sorted by start time. */
internal fun Project.withTrackClips(
    id: TrackId,
    clips: List<Clip>,
): Project =
    copy(
        tracks =
            tracks.map { t ->
                if (t.id != id) {
                    t
                } else {
                    t.copy(clips = if (t.type.isTimed) clips.sortedBy { it.startMs } else clips)
                }
            },
    )

/**
 * Puts the timed [clip] (with its start time set) on [preferred] when that
 * row is free for its span; otherwise on the highest free row of the same
 * type (and audio role); otherwise on a new row, which [ids] names. Returns
 * the project and the row the clip landed on.
 */
internal fun Project.placeTimed(
    clip: Clip,
    preferred: TrackId?,
    role: AudioRole?,
    ids: IdSource,
    allowExisting: Boolean = true,
): Pair<Project, TrackId> {
    val type = clip.timedTrackType()
    require(clip.startMs >= 0) { "A clip can't start before the video" }
    val preferredTrack = preferred?.let { requireTrack(it) }
    if (preferredTrack != null) {
        require(preferredTrack.type == type) { "That row holds a different kind of item" }
        require(type != TrackType.AUDIO || preferredTrack.audioRole == role) { "That row holds a different kind of sound" }
    }
    val fits = { t: Track -> t.isFree(clip.startMs, clip.endMs, except = clip.id) }
    val target =
        when {
            preferredTrack != null && preferredTrack.enabled && preferredTrack.takes(clip) && fits(preferredTrack) -> preferredTrack
            allowExisting -> candidateTracks(type, role).firstOrNull { it.id != preferred && fits(it) }
            else -> null
        }
    if (target != null) {
        return withTrackClips(target.id, target.clips + clip) to target.id
    }
    val row = Track(id = ids.trackId(), type = type, clips = listOf(clip), audioRole = if (type == TrackType.AUDIO) role else null)
    return withNewTrack(row) to row.id
}
