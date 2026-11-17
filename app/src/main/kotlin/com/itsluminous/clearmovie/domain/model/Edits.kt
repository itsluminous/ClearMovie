package com.itsluminous.clearmovie.domain.model

// The editing operations (ROADMAP cm-model, ARCHITECTURE sections 6 and 11.6).
// Each one is pure: it returns a new, normalised project and leaves the
// receiver alone. An operation the UI should never offer (a clip that is not
// there, a photo trimmed like a video) throws IllegalArgumentException;
// ProjectEditor then keeps the project unchanged. Operations that create
// rows or clips take their ids from an IdSource.

/** Which side of an item a "+" inserts on. */
enum class Side { BEFORE, AFTER }

// ---------------------------------------------------------------- MAIN row

/**
 * Adds photos, videos or cards to MAIN at content position [index] (0 is
 * just after the intro), or before the ending when [index] is null. This is
 * what the Photo Picker and a share do.
 */
fun Project.addToMain(
    clips: List<MainClip>,
    index: Int? = null,
    ids: IdSource = RandomIds,
): Project {
    clips.forEach { requireContentClip(it) }
    require(clips.map { it.id }.toSet().size == clips.size && clips.none { clip(it.id) != null }) { "Clip id already used" }
    val current = main.clips
    val firstContent = if (current.firstOrNull() is IntroClip) 1 else 0
    val lastContent = if (current.lastOrNull() is EndingClip) current.lastIndex else current.size
    val at =
        if (index ==
            null
        ) {
            lastContent
        } else {
            (firstContent + index).also { require(it in firstContent..lastContent) { "No such place" } }
        }
    val placed = current.toMutableList().apply { addAll(at, clips.map { it.withStartMs(0) }) }
    return withTrackClips(main.id, placed).normalized()
}

/** A caption's or card's text must pass [TextRules.isValidText] before it goes in. */
private fun requireUsableText(clip: Clip) {
    val text =
        when (clip) {
            is TextClip -> clip.text
            is CardClip -> clip.text
            else -> null
        }
    require(text == null || TextRules.isValidText(text)) { "That text can't be used" }
}

private fun requireContentClip(clip: Clip) {
    require(
        clip is PhotoClip || clip is VideoClip || clip is CardClip,
    ) { "Only photos, videos and cards go between the intro and the ending" }
    require(clip !is MediaClip || clip.pip == null) { "A MAIN item has no picture-in-picture placement" }
    requireUsableText(clip)
}

/**
 * Moves a MAIN content clip to content position [toIndex] (0 is just after
 * the intro). The moved clip loses the transition it owned; the clip that
 * used to lead into it keeps its own, which now leads to its new neighbour
 * (section 6.4).
 */
fun Project.moveMainClip(
    clipId: ClipId,
    toIndex: Int,
): Project {
    val clips = main.clips
    val from = clips.indexOfFirst { it.id == clipId }
    require(from >= 0) { "No such clip on the MAIN row" }
    val clip = clips[from] as MainClip
    requireContentClip(clip)
    val firstContent = if (clips.first() is IntroClip) 1 else 0
    val lastContent = if (clips.last() is EndingClip) clips.lastIndex - 1 else clips.lastIndex
    val to = firstContent + toIndex
    require(to in firstContent..lastContent) { "No such place" }
    if (to == from) return this
    val moved = clips.toMutableList().apply { removeAt(from) }
    moved.add(to, clip.withTransition(null))
    return withTrackClips(main.id, moved).normalized()
}

// ---------------------------------------------------------------- insert

/**
 * Inserts [clip] before or after the item [anchorClipId] on row [trackId]
 * (section 11.6):
 * - On MAIN it goes exactly before or after the item and every later clip
 *   moves later.
 * - On any other row it is placed at the boundary: before an item it ends
 *   where that item starts (never before 0), after an item it starts where the
 *   item ends. If that would overlap another clip on the row, it goes on the
 *   highest free row of the same type, or on a new row.
 */
fun Project.insertAt(
    trackId: TrackId,
    anchorClipId: ClipId,
    side: Side,
    clip: Clip,
    ids: IdSource = RandomIds,
): Project {
    val track = requireTrack(trackId)
    val anchor = requireNotNull(track.clips.firstOrNull { it.id == anchorClipId }) { "No such item on that row" }
    require(clip(clip.id) == null) { "Clip id already used" }
    if (track.type == TrackType.MAIN) {
        require(clip is MainClip) { "That item does not go on the MAIN row" }
        requireContentClip(clip)
        require(!(anchor is IntroClip && side == Side.BEFORE)) { "Nothing goes before the intro" }
        require(!(anchor is EndingClip && side == Side.AFTER)) { "Nothing goes after the ending" }
        val at = track.clips.indexOf(anchor) + if (side == Side.AFTER) 1 else 0
        val placed = track.clips.toMutableList().apply { add(at, clip.withStartMs(0)) }
        return withTrackClips(trackId, placed).normalized()
    }
    require(track.accepts(clip)) { "That item does not go on this row" }
    requireUsableText(clip)
    val start =
        when (side) {
            Side.BEFORE -> (anchor.startMs - clip.durationMs).coerceAtLeast(0)
            Side.AFTER -> anchor.endMs
        }
    return placeTimed(clip.withStartMs(start), preferred = trackId, role = track.audioRole, ids = ids).first.normalized()
}

/**
 * Adds a timed clip (with its start time set) by the section 6.1 rule: on the
 * highest free row of its type (for sound, of [role]), or on a new row.
 * "Add text", "Add music or voice", "Add overlay" and "Add picture in
 * picture" all use it.
 */
fun Project.addTimedClip(
    clip: Clip,
    role: AudioRole? = null,
    ids: IdSource = RandomIds,
): Project {
    require(clip(clip.id) == null) { "Clip id already used" }
    val type = clip.timedTrackType()
    require(Track(TrackId("probe"), type).accepts(clip)) { "That item does not go on that kind of row" }
    require((type == TrackType.AUDIO) == (role != null)) { "Sound needs a role, other items none" }
    requireUsableText(clip)
    return placeTimed(clip, preferred = null, role = role, ids = ids).first.normalized()
}

/** "Add text": a caption from [startMs] lasting [durationMs], on the highest free text row (section 6.1). */
fun Project.addText(
    text: String,
    startMs: Long,
    durationMs: Long = Limits.DEFAULT_TEXT_MS,
    ids: IdSource = RandomIds,
): Project = addTimedClip(TextClip(ids.clipId(), startMs, durationMs, text), ids = ids)

/**
 * "Add caption" on a photo or video, on MAIN or a picture-in-picture row: a
 * caption attached to that item, covering it whole (see [CaptionAttachment]),
 * on the highest free text row or a new one. [style] is the user's changes
 * to the preset style, if any.
 */
fun Project.addCaptionFor(
    itemId: ClipId,
    text: String,
    ids: IdSource = RandomIds,
    captionId: ClipId = ids.clipId(),
    style: CaptionStyleOverride = CaptionStyleOverride.NONE,
): Project {
    val (start, end) = requireNotNull(attachSpan(itemId)) { "Only a photo or video takes a caption" }
    val caption = TextClip(captionId, start, end - start, text, style, attachedTo = CaptionAttachment(itemId))
    return addTimedClip(caption, ids = ids)
}

/**
 * Puts a timed clip on a chosen row at [startMs] ("Add here" on a row). If
 * that row is busy then, it falls back to the section 6.1 rule.
 */
fun Project.addToTrack(
    trackId: TrackId,
    clip: Clip,
    startMs: Long,
    ids: IdSource = RandomIds,
): Project {
    val track = requireTrack(trackId)
    require(track.type.isTimed) { "Use addToMain for the MAIN row" }
    require(track.accepts(clip)) { "That item does not go on this row" }
    require(clip(clip.id) == null) { "Clip id already used" }
    requireUsableText(clip)
    return placeTimed(clip.withStartMs(startMs), preferred = trackId, role = track.audioRole, ids = ids).first.normalized()
}

// ---------------------------------------------------------------- delete and move

/**
 * Deletes a clip. A row left empty is removed in the same step, except MAIN
 * and the preset frame row (section 6.1). Deleting the preset frame itself
 * switches its row off and keeps the frame, so switching it on again brings
 * it back. Deleting the intro or ending skips it for this video; skipping a
 * spoken ending also removes its closing message.
 */
fun Project.deleteClip(clipId: ClipId): Project {
    val track = requireNotNull(trackOf(clipId)) { "No such clip" }
    val clip = requireClip(clipId)
    if (track.presetRole == PresetRole.FRAME) {
        // The frame row holds only the frame (see Track.takes).
        return setTrackEnabled(track.id, false)
    }
    // A photo's or video's captions go with it, in the same step (undo brings both back).
    val captioned = if (clip is MediaClip) withoutCaptionsOf(setOf(clipId)) else this
    val home = captioned.requireTrack(track.id)
    var project = captioned.withTrackClips(home.id, home.clips.filterNot { it.id == clipId }).afterRemovingFrom(home.id)
    if (clip is EndingClip) {
        for (t in project.tracks) {
            val spoken = t.clips.filter { it is AudioClip && it.anchor == SpanAnchor.Ending }
            if (spoken.isNotEmpty()) {
                project = project.withTrackClips(t.id, t.clips - spoken.toSet()).afterRemovingFrom(t.id)
            }
        }
    }
    return project.normalized()
}

/** Removes row [trackId] if an edit just emptied it (or switches the preset frame off). */
private fun Project.afterRemovingFrom(trackId: TrackId): Project {
    val track = track(trackId) ?: return this
    if (track.clips.isNotEmpty() || track.type == TrackType.MAIN) return this
    return if (track.isRemovable) {
        dropTrack(trackId)
    } else {
        copy(
            tracks =
                tracks.map {
                    if (it.id ==
                        trackId
                    ) {
                        it.copy(enabled = false)
                    } else {
                        it
                    }
                },
        )
    }
}

private fun Project.dropTrack(trackId: TrackId): Project =
    copy(
        tracks = tracks.filterNot { it.id == trackId },
        stacking =
            stacking - trackId,
    )

/**
 * Moves a timed clip in time and, optionally, to another row of the same
 * type. When the target row is busy at that time, the clip goes on a new row
 * of that type (section 6.1). A row left empty is removed. A clip on a row
 * the preset manages (the frame, its music, the closing message) moves only
 * in time, on its own row, and moving it by hand detaches it from the span it
 * followed.
 */
fun Project.moveClip(
    clipId: ClipId,
    startMs: Long,
    targetTrackId: TrackId? = null,
    ids: IdSource = RandomIds,
): Project {
    val source = requireNotNull(trackOf(clipId)) { "No such clip" }
    require(source.type.isTimed) { "Use moveMainClip on the MAIN row" }
    val target = requireTrack(targetTrackId ?: source.id)
    require(target.type == source.type) { "A clip moves only to a row of the same kind" }
    require(source.type != TrackType.AUDIO || target.audioRole == source.audioRole) { "Sound moves only to a row of the same kind" }
    val clip = placedByHand(requireClip(clipId).detached(), startMs)
    if (source.presetRole != null) {
        require(target.id == source.id) { "This item stays on its own row" }
        require(startMs >= 0) { "A clip can't start before the video" }
        return withTrackClips(source.id, source.clips.map { if (it.id == clipId) clip else it }).normalized()
    }
    val without = withTrackClips(source.id, source.clips.filterNot { it.id == clipId })
    val (placed, landed) = without.placeTimed(clip, preferred = target.id, role = source.audioRole, ids = ids, allowExisting = false)
    val cleaned = if (landed == source.id) placed else placed.afterRemovingFrom(source.id)
    return cleaned.normalized()
}

/**
 * "Move to another row" with **A new row**: the timed clip keeps its time
 * and goes on a new row of its type (for sound, of its role), at that type's
 * default screen and stacking positions. Its old row goes if it is left
 * empty. A clip on a row the preset manages stays where it is.
 */
fun Project.moveClipToNewRow(
    clipId: ClipId,
    ids: IdSource = RandomIds,
): Project {
    val source = requireNotNull(trackOf(clipId)) { "No such clip" }
    require(source.type.isTimed) { "Photos and videos on the main row stay on it" }
    require(source.presetRole == null) { "This item stays on its own row" }
    val clip = requireClip(clipId).detached()
    val without = withTrackClips(source.id, source.clips.filterNot { it.id == clipId })
    val (placed, _) = without.placeTimed(clip, preferred = null, role = source.audioRole, ids = ids, allowExisting = false)
    return placed.afterRemovingFrom(source.id).normalized()
}

/**
 * "Earlier" and "Later" on a timed clip: moves it by [deltaMs] on its own
 * row, stopping at the start and end of the video and at its neighbours, so
 * it never jumps to another row or out of the video. Refused when it can't move that way at all.
 */
fun Project.nudgeClip(
    clipId: ClipId,
    deltaMs: Long,
): Project {
    val track = requireNotNull(trackOf(clipId)) { "No such clip" }
    require(track.type.isTimed) { "Use moveMainClip on the MAIN row" }
    require(deltaMs != 0L) { "No move" }
    val target = requireNotNull(nudgedStart(clipId, deltaMs)) { "No room to move that way" }
    val clip = placedByHand(requireClip(clipId).detached(), target)
    return withTrackClips(track.id, track.clips.map { if (it.id == clipId) clip else it }).normalized()
}

/** Where [nudgeClip] would put the clip's start, or null when it can't move that way. */
fun Project.nudgedStart(
    clipId: ClipId,
    deltaMs: Long,
): Long? {
    val track = trackOf(clipId) ?: return null
    if (!track.type.isTimed || deltaMs == 0L) return null
    val clip = requireClip(clipId)
    val others = track.clips.filter { it.id != clipId && it.durationMs > 0 }
    val target =
        if (deltaMs < 0) {
            val floor = others.filter { it.endMs <= clip.startMs }.maxOfOrNull { it.endMs } ?: 0L
            (clip.startMs + deltaMs).coerceAtLeast(floor)
        } else {
            // Never past its next neighbour, nor past the end of the video, where it would not be seen.
            val ceiling = others.filter { it.startMs >= clip.endMs }.minOfOrNull { it.startMs } ?: totalDurationMs()
            (clip.startMs + deltaMs).coerceAtMost(ceiling - clip.durationMs)
        }
    // A move only ever goes the way asked: "Later" never ends up earlier (a video that got shorter, say).
    val forward = deltaMs > 0
    return target.takeIf { it >= 0 && if (forward) it > clip.startMs else it < clip.startMs }
}

/** [clip] moved to [startMs] by hand; an attached caption stays on its item while it starts inside it. */
private fun Project.placedByHand(
    clip: Clip,
    startMs: Long,
): Clip = if (clip is TextClip) reattached(clip, startMs, clip.wantedMs) else clip.withStartMs(startMs)

/** The clip with its span anchor removed: the user has taken control of its timing. */
private fun Clip.detached(): Clip =
    when (this) {
        is OverlayClip -> copy(anchor = null)
        is AudioClip -> copy(anchor = null)
        else -> this
    }

// ---------------------------------------------------------------- rows

/** "Add row": an empty row of [type], at the default screen and stacking positions for that type. */
fun Project.addRow(
    type: TrackType,
    role: AudioRole? = null,
    id: TrackId = RandomIds.trackId(),
): Project {
    require(type != TrackType.MAIN) { "A project has one MAIN row" }
    require((type == TrackType.AUDIO) == (role != null)) { "Sound rows need a role, other rows none" }
    return withNewTrack(Track(id = id, type = type, audioRole = role))
}

/** "Remove row", with its clips. MAIN and the preset frame row can't be removed; the frame is switched off instead. */
fun Project.removeRow(trackId: TrackId): Project {
    val track = requireTrack(trackId)
    require(track.isRemovable) { "This row can't be removed" }
    // The captions of its photos and videos go with them.
    val captioned =
        withoutCaptionsOf(
            track.clips
                .filterIsInstance<MediaClip>()
                .map { it.id }
                .toSet(),
        )
    return captioned.dropTrack(trackId).normalized()
}

/** Moves a row on screen to [toIndex]. The stacking order, and so the video, is unchanged. */
fun Project.moveRow(
    trackId: TrackId,
    toIndex: Int,
): Project {
    val from = tracks.indexOfFirst { it.id == trackId }
    require(from >= 0) { "No such row" }
    require(toIndex in tracks.indices) { "No such place" }
    if (from == toIndex) return this
    val moved = tracks.toMutableList().apply { add(toIndex, removeAt(from)) }
    return copy(tracks = moved)
}

/** Switches a row on or off (the preset frame's On/Off). */
fun Project.setTrackEnabled(
    trackId: TrackId,
    enabled: Boolean,
): Project = updateTrack(trackId) { it.copy(enabled = enabled) }

/** Mutes or unmutes a row's sound. */
fun Project.setTrackMuted(
    trackId: TrackId,
    muted: Boolean,
): Project = updateTrack(trackId) { it.copy(muted = muted) }

/** "Lower music when people talk" on a MUSIC row. */
fun Project.setLowerWhenTalking(
    trackId: TrackId,
    lower: Boolean,
): Project =
    updateTrack(trackId) {
        require(it.audioRole == AudioRole.MUSIC) { "Only music rows are lowered" }
        it.copy(lowerWhenTalking = lower)
    }

private fun Project.updateTrack(
    trackId: TrackId,
    change: (Track) -> Track,
): Project {
    requireTrack(trackId)
    return copy(tracks = tracks.map { if (it.id == trackId) change(it) else it })
}

// ---------------------------------------------------------------- stacking (section 6.2)

/** "Show on top". Changes the stacking order only. */
fun Project.bringToTop(trackId: TrackId): Project = restack(trackId) { _, size -> size - 1 }

/** "Bring forward": one step up the stack. */
fun Project.bringForward(trackId: TrackId): Project = restack(trackId) { i, size -> minOf(i + 1, size - 1) }

/** "Send backward": one step down the stack. */
fun Project.sendBackward(trackId: TrackId): Project = restack(trackId) { i, _ -> maxOf(i - 1, 0) }

/** "Show at bottom". */
fun Project.sendToBottom(trackId: TrackId): Project = restack(trackId) { _, _ -> 0 }

private fun Project.restack(
    trackId: TrackId,
    to: (index: Int, size: Int) -> Int,
): Project {
    val from = stacking.indexOf(trackId)
    require(from >= 0) { "Only picture rows have a stacking position" }
    val target = to(from, stacking.size)
    if (target == from) return this
    return copy(stacking = stacking.toMutableList().apply { add(target, removeAt(from)) })
}

// ---------------------------------------------------------------- per-clip edits

/**
 * Replaces one clip through [change]. The changed clip must still suit its
 * row and, on a timed row, must not overlap its neighbours.
 */
private fun Project.updateClip(
    clipId: ClipId,
    change: (Clip) -> Clip,
): Project {
    val track = requireNotNull(trackOf(clipId)) { "No such clip" }
    val changed = change(requireClip(clipId))
    require(changed.id == clipId) { "A clip keeps its id" }
    require(track.accepts(changed)) { "That change does not suit this row" }
    if (track.type.isTimed) {
        require(changed.startMs >= 0) { "A clip can't start before the video" }
        require(track.isFree(changed.startMs, changed.endMs, except = clipId)) { "That would overlap another item on this row" }
    }
    return withTrackClips(track.id, track.clips.map { if (it.id == clipId) changed else it }).normalized()
}

private inline fun <reified T : Clip> Project.updateTyped(
    clipId: ClipId,
    what: String,
    crossinline change: (T) -> Clip,
): Project =
    updateClip(clipId) {
        require(it is T) { "Only $what can do that" }
        change(it)
    }

/** Trims a video to [startMs]..[endMs] of its source. */
fun Project.trimVideo(
    clipId: ClipId,
    startMs: Long,
    endMs: Long,
): Project =
    updateTyped<VideoClip>(clipId, "videos") {
        requireTrim(startMs, endMs, it.sourceDurationMs)
        it.copy(trimStartMs = startMs, trimEndMs = endMs).requireSensibleLength()
    }

/** Trims a sound to [startMs]..[endMs] of its source; its length follows unless it repeats. */
fun Project.trimAudio(
    clipId: ClipId,
    startMs: Long,
    endMs: Long,
): Project =
    updateTyped<AudioClip>(clipId, "sounds") {
        requireTrim(startMs, endMs, it.sourceDurationMs)
        val length = if (it.repeat) it.durationMs else endMs - startMs
        it.copy(trimStartMs = startMs, trimEndMs = endMs, durationMs = length, anchor = null)
    }

private fun requireTrim(
    startMs: Long,
    endMs: Long,
    sourceMs: Long,
) {
    require(startMs >= 0 && endMs <= sourceMs) { "Trim is outside the media" }
    require(endMs - startMs >= Limits.MIN_CLIP_MS) { "Too short" }
}

/** Speed for a video (section 6.3: 0.25x to 4x, pitch kept). */
fun Project.setSpeed(
    clipId: ClipId,
    speed: Speed,
): Project = updateTyped<VideoClip>(clipId, "videos") { it.copy(speed = speed).requireSensibleLength() }

/** Loop: play the trimmed range [loops] times. */
fun Project.setLoops(
    clipId: ClipId,
    loops: Int,
): Project {
    require(loops in 1..Limits.MAX_LOOPS) { "Loops out of range" }
    return updateTyped<VideoClip>(clipId, "videos") { it.copy(loops = loops).requireSensibleLength() }
}

private fun VideoClip.requireSensibleLength(): VideoClip {
    require(durationMs in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) { "Length out of range" }
    return this
}

/** How long a photo, or a text or colour card, is shown. */
fun Project.setPhotoDuration(
    clipId: ClipId,
    durationMs: Long,
): Project {
    require(durationMs in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) { "Duration out of range" }
    return updateClip(clipId) {
        when (it) {
            is PhotoClip -> it.copy(durationMs = durationMs)
            is CardClip -> it.copy(durationMs = durationMs)
            else -> throw IllegalArgumentException("Only photos and cards have a duration")
        }
    }
}

/** A quarter turn clockwise. */
fun Project.rotate(clipId: ClipId): Project = updateTyped<MediaClip>(clipId, "photos and videos") { it.withRotation(it.rotation.next()) }

/** Fill and crop, fit with a blurred background, or fit with bars (R11). */
fun Project.setFit(
    clipId: ClipId,
    fit: Fit,
): Project = updateTyped<MediaClip>(clipId, "photos and videos") { it.withFit(fit) }

/** Picture-in-picture corner and size. */
fun Project.setPip(
    clipId: ClipId,
    pip: PipPlacement,
): Project {
    require(pip.scale in PipPlacement.MIN_SCALE..PipPlacement.MAX_SCALE) { "Size out of range" }
    return updateTyped<MediaClip>(clipId, "photos and videos") {
        require(it.pip != null) { "Only picture-in-picture items have a placement" }
        it.withPip(pip)
    }
}

/** Volume of a video's own sound or of a sound clip. */
fun Project.setVolume(
    clipId: ClipId,
    volumeDb: Double,
): Project {
    require(volumeDb.isFinite() && volumeDb in Limits.MIN_VOLUME_DB..Limits.MAX_VOLUME_DB) { "Volume out of range" }
    return updateClip(clipId) {
        when (it) {
            is VideoClip -> it.copy(volumeDb = volumeDb)
            is AudioClip -> it.copy(volumeDb = volumeDb)
            else -> throw IllegalArgumentException("Only videos and sounds have a volume")
        }
    }
}

/** Fade in and out on a sound clip; together they fit inside it. */
fun Project.setAudioFades(
    clipId: ClipId,
    fadeInMs: Long,
    fadeOutMs: Long,
): Project =
    updateTyped<AudioClip>(clipId, "sounds") {
        require(fadeInMs >= 0 && fadeOutMs >= 0 && fadeInMs + fadeOutMs <= it.durationMs) { "Fades don't fit" }
        it.copy(fadeInMs = fadeInMs, fadeOutMs = fadeOutMs)
    }

/** Changes a caption's or card's text. */
fun Project.setText(
    clipId: ClipId,
    text: String,
): Project {
    require(TextRules.isValidText(text)) { "That text can't be used" }
    return updateClip(clipId) {
        when (it) {
            is TextClip -> it.copy(text = text)
            is CardClip -> it.copy(text = text)
            else -> throw IllegalArgumentException("Only captions and cards have text")
        }
    }
}

/** "Show from/to" for a caption. */
fun Project.setTextTiming(
    clipId: ClipId,
    startMs: Long,
    durationMs: Long,
): Project {
    require(durationMs >= Limits.MIN_CLIP_MS) { "Too short" }
    return updateTyped<TextClip>(clipId, "captions") { reattached(it, startMs, durationMs) }
}

/** Overrides parts of a caption's or card's preset style; [CaptionStyleOverride.NONE] is **Reset to preset**. */
fun Project.setCaptionStyle(
    clipId: ClipId,
    style: CaptionStyleOverride,
): Project =
    updateClip(clipId) {
        when (it) {
            is TextClip -> it.copy(style = style)
            is CardClip -> it.copy(style = style)
            else -> throw IllegalArgumentException("Only captions and cards have a style")
        }
    }

/** **Reset to preset** for a caption or card style. */
fun Project.resetCaptionStyle(clipId: ClipId): Project = setCaptionStyle(clipId, CaptionStyleOverride.NONE)

/** Names the project; null makes it a draft again. */
fun Project.rename(name: String?): Project {
    val trimmed = name?.trim()
    require(trimmed == null || TextRules.isValidName(trimmed)) { "That name can't be used" }
    return copy(name = trimmed)
}

// ---------------------------------------------------------------- split and join (section 6.3)

/**
 * Splits a video [atMs] into its own timeline length. The halves share a
 * split group and have adjacent trims. The second half keeps the transition
 * to the old neighbour, with none between the halves; the first keeps the
 * start animation, the second the end one, and both the during one.
 */
fun Project.splitClip(
    clipId: ClipId,
    atMs: Long,
    newId: ClipId = RandomIds.clipId(),
): Project {
    val track = requireNotNull(trackOf(clipId)) { "No such clip" }
    val clip = requireClip(clipId)
    require(clip is VideoClip) { "Only videos can be split" }
    require(clip.loops == 1) { "Turn loop off before splitting" }
    require(clip(newId) == null) { "Clip id already used" }
    // The cut, in media time, rounded down to a whole step of the speed
    // fraction, so the two halves add up to exactly the original length.
    val sourceIn = clip.speed.sourceMs(atMs).let { it - it % clip.speed.numerator }
    val cut = clip.trimStartMs + sourceIn
    val firstMs = clip.speed.timelineMs(sourceIn)
    val secondMs = clip.durationMs - firstMs
    require(firstMs >= Limits.MIN_CLIP_MS && secondMs >= Limits.MIN_CLIP_MS) { "Too close to the edge" }
    require(sourceIn >= Limits.MIN_CLIP_MS && clip.trimEndMs - cut >= Limits.MIN_CLIP_MS) { "Too close to the edge" }
    val group = clip.splitGroup ?: clip.id.value
    val a = clip.animations
    val first =
        clip.copy(
            trimEndMs = cut,
            splitGroup = group,
            transitionToNext = null,
            animations = a.copy(end = null),
        )
    val second =
        clip.copy(
            id = newId,
            trimStartMs = cut,
            splitGroup = group,
            startMs = if (track.type.isTimed) clip.startMs + first.durationMs else 0,
            animations = a.copy(start = null),
        )
    val index = track.clips.indexOfFirst { it.id == clipId }
    val clips =
        track.clips
            .toMutableList()
            .apply { set(index, first) }
            .apply { add(index + 1, second) }
    return withTrackClips(track.id, clips).captionsAfterSplit(clipId, newId).normalized()
}

/**
 * Joins two halves of a split back into one clip. They must be neighbours on
 * the same row, from the same split, with continuous trims, the same speed
 * and no loop. The inner transition goes; the joined clip keeps the second
 * half's transition to the next clip.
 */
fun Project.joinClips(
    firstId: ClipId,
    secondId: ClipId,
): Project {
    require(canJoin(firstId, secondId)) { "Those clips can't be joined" }
    val track = trackOf(firstId)!!
    val first = requireClip(firstId) as VideoClip
    val second = requireClip(secondId) as VideoClip
    val joined =
        first.copy(
            trimEndMs = second.trimEndMs,
            transitionToNext = second.transitionToNext,
            animations = first.animations.copy(end = second.animations.end),
        )
    val clips = track.clips.filterNot { it.id == secondId }.map { if (it.id == firstId) joined else it }
    return captionsBeforeJoin(firstId, secondId).withTrackClips(track.id, clips).normalized()
}

/** Whether "Join" is offered for these two clips (section 11.2). */
fun Project.canJoin(
    firstId: ClipId,
    secondId: ClipId,
): Boolean {
    val track = trackOf(firstId) ?: return false
    if (trackOf(secondId)?.id != track.id) return false
    val first = clip(firstId) as? VideoClip ?: return false
    val second = clip(secondId) as? VideoClip ?: return false
    val i = track.clips.indexOf(first)
    val neighbours = track.clips.getOrNull(i + 1) == second && (!track.type.isTimed || second.startMs == first.endMs)
    return neighbours &&
        isContinuousSplit(first, second) &&
        first.speed == second.speed &&
        first.loops == 1 &&
        second.loops == 1
}

// ---------------------------------------------------------------- transitions and animations (section 6.4)

/**
 * Sets the transition from MAIN clip [clipId] into the next one; null or NONE
 * removes it. It is fitted to the two clips (at most half the shorter one).
 * Changing one junction ends **Apply to all**.
 */
fun Project.setTransition(
    clipId: ClipId,
    transition: Transition?,
): Project {
    val clips = main.clips
    val i = clips.indexOfFirst { it.id == clipId }
    require(i >= 0) { "Transitions are between MAIN items" }
    require(i < clips.lastIndex) { "The last item has nothing to lead into" }
    val wanted = transition?.takeUnless { it.kind == TransitionKind.NONE }
    wanted?.let { require(it.durationMs in Transition.MIN_MS..Transition.MAX_MS) { "Length out of range" } }
    val changed = clips.mapIndexed { j, c -> if (j == i) (c as MainClip).withTransition(wanted) else c }
    val forAll = transitionForAll?.takeIf { it == wanted }
    return withTrackClips(main.id, changed).copy(transitionForAll = forAll).normalized()
}

/** **Apply to all**: the same transition on every MAIN junction, as one edit. Null or NONE removes them all. */
fun Project.applyTransitionToAll(transition: Transition?): Project {
    val wanted = transition?.takeUnless { it.kind == TransitionKind.NONE }
    wanted?.let { require(it.durationMs in Transition.MIN_MS..Transition.MAX_MS) { "Length out of range" } }
    val cleared = withTrackClips(main.id, main.clips.map { (it as MainClip).withTransition(null) })
    return cleared.copy(transitionForAll = wanted).normalized()
}

/**
 * Sets or clears ([anim] null) one of a clip's animations. Sounds have none;
 * Ken Burns is for photos, Pop, Bounce and Pulse for captions and cards, and
 * each kind goes only in its own slot.
 */
fun Project.setAnimation(
    clipId: ClipId,
    slot: AnimSlot,
    anim: Anim?,
): Project =
    updateClip(clipId) { clip ->
        require(clip !is AudioClip) { "Sounds have fades, not animations" }
        if (anim != null) {
            require(slot in anim.kind.slots) { "That animation goes in another slot" }
            require(anim.kind.allowedOn(clip)) { "That animation is for another kind of item" }
            require(anim.durationMs in Anim.MIN_MS..Anim.MAX_MS) { "Length out of range" }
        }
        clip.withAnimations(clip.animations.with(slot, anim))
    }

// ---------------------------------------------------------------- duplicate and replace (section 11.2)

/**
 * "Duplicate": a copy of a MAIN photo, video or card right after it. The
 * copy takes over the transition to the next item (as the second half of a
 * split does), the original keeps none, and the copy belongs to no split.
 */
fun Project.duplicateClip(
    clipId: ClipId,
    newId: ClipId = RandomIds.clipId(),
): Project {
    val clips = main.clips
    val i = clips.indexOfFirst { it.id == clipId }
    require(i >= 0) { "No such clip on the MAIN row" }
    require(clip(newId) == null) { "Clip id already used" }
    val original = clips[i] as MainClip
    requireContentClip(original)
    val copy =
        when (original) {
            is VideoClip -> original.copy(id = newId, splitGroup = null)
            else -> original.withId(newId) as MainClip
        }
    val placed =
        clips.toMutableList().apply {
            set(i, original.withTransition(null))
            add(i + 1, copy)
        }
    return withTrackClips(main.id, placed).captionsForDuplicate(clipId, newId).normalized()
}

/**
 * "Replace" on a MAIN photo, video or card: [replacement] takes its place
 * and its id (so it stays selected), with the transition into the next item,
 * the fit and, for an item of the same kind, the animations. A photo's
 * length comes from the replacement; a video plays whole.
 */
fun Project.replaceMainItem(
    clipId: ClipId,
    replacement: MainClip,
): Project {
    val clips = main.clips
    val i = clips.indexOfFirst { it.id == clipId }
    require(i >= 0) { "No such clip on the MAIN row" }
    val old = clips[i] as MainClip
    requireContentClip(old)
    requireContentClip(replacement)
    var next = (replacement.withId(old.id) as MainClip).withTransition(old.transitionToNext)
    if (old is MediaClip && next is MediaClip) next = next.withFit(old.fit)
    if (old::class == next::class) next = next.withAnimations(old.animations) as MainClip
    val placed = clips.toMutableList().apply { set(i, next.withStartMs(0)) }
    return withTrackClips(main.id, placed).normalized()
}

/**
 * "Replace (this video only)" for the intro or the ending: plays [media],
 * [durationMs] long, instead. For a spoken ending the closing message plays
 * the new sound as well, so the held last frame and the speech stay the same
 * length.
 */
fun Project.replacePresetPart(
    clipId: ClipId,
    media: MediaRef,
    durationMs: Long,
): Project {
    require(media !is MediaRef.Preset) { "A replacement is the user's own file" }
    require(durationMs in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) { "A replacement needs a usable length" }
    val clips = main.clips
    val i = clips.indexOfFirst { it.id == clipId }
    require(i >= 0) { "No such clip on the MAIN row" }
    val part = clips[i]
    var project =
        when (part) {
            is IntroClip -> {
                withTrackClips(
                    main.id,
                    clips.toMutableList().apply { set(i, part.copy(media = media, durationMs = durationMs)) },
                )
            }

            is EndingClip -> {
                withTrackClips(
                    main.id,
                    clips.toMutableList().apply { set(i, part.copy(media = media, durationMs = durationMs)) },
                )
            }

            else -> {
                throw IllegalArgumentException("Only the intro and the ending are replaced this way")
            }
        }
    if (part is EndingClip && part.kind == EndingKind.AUDIO) {
        for (t in project.tracks) {
            val spoken = t.clips.filter { it is AudioClip && it.anchor == SpanAnchor.Ending }
            if (spoken.isEmpty()) continue
            val renewed =
                t.clips.map {
                    if (it is AudioClip && it.anchor == SpanAnchor.Ending) {
                        it.copy(media = media, sourceDurationMs = durationMs, trimStartMs = 0, trimEndMs = durationMs)
                    } else {
                        it
                    }
                }
            project = project.withTrackClips(t.id, renewed)
        }
    }
    return project.normalized()
}

/** The background colour of a text or colour card; it must be opaque, as a card fills the window. */
fun Project.setCardColor(
    clipId: ClipId,
    color: ArgbColor,
): Project {
    require(color.alpha == 0xFF) { "A card's colour is opaque" }
    return updateTyped<CardClip>(clipId, "cards") { it.copy(color = color) }
}
