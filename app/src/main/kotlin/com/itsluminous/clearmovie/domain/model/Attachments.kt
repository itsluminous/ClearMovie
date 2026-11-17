package com.itsluminous.clearmovie.domain.model

// Captions attached to a photo or video (cm-captions; supersedes cm-model D3
// for captions made with "Add caption"). The caption keeps its own start
// and length, and normalisation works them out again from its item after
// every edit, the way anchored clips follow the frame span.

/**
 * The span a caption attached to [itemId] follows, as (start, end), or null
 * when [itemId] is not a photo or video on MAIN or a picture-in-picture row.
 * On MAIN it runs from the middle of the transition into the item to the
 * middle of the transition out of it, so two neighbouring captions meet
 * halfway through a crossfade instead of overlapping; if that leaves less
 * than [Limits.MIN_CLIP_MS] it is the item's whole slot. On a
 * picture-in-picture row it is the item's own span.
 */
fun Project.attachSpan(itemId: ClipId): Pair<Long, Long>? = AttachSpans(this)[itemId]

/** [attachSpan] for many captions, with MAIN's layout worked out once. */
internal class AttachSpans(
    private val project: Project,
) {
    private val layout by lazy { project.mainLayout() }
    private val cache = HashMap<ClipId, Pair<Long, Long>?>()

    operator fun get(itemId: ClipId): Pair<Long, Long>? = cache.getOrPut(itemId) { compute(itemId) }

    private fun compute(itemId: ClipId): Pair<Long, Long>? {
        val track = project.trackOf(itemId) ?: return null
        val clip = track.clips.first { it.id == itemId }
        if (clip !is MediaClip) return null
        return when (track.type) {
            TrackType.MAIN -> {
                val i = layout.indexOfFirst { it.clip.id == itemId }
                if (i < 0) return null
                val slot = layout[i]
                val into = layout.getOrNull(i - 1)?.overlapToNextMs ?: 0L
                val start = slot.startMs + into / 2
                val end = slot.endMs - slot.overlapToNextMs / 2
                if (end - start >= Limits.MIN_CLIP_MS) start to end else slot.startMs to slot.endMs
            }

            TrackType.VISUAL -> {
                clip.startMs to clip.endMs
            }

            else -> {
                null
            }
        }
    }
}

/** The caption placed in [span] by its attachment: the whole span, or from its offset with its length cut at the span's end. */
internal fun TextClip.following(span: Pair<Long, Long>): TextClip {
    val a = requireNotNull(attachedTo)
    val (s, e) = span
    val length = e - s
    if (a.whole) return copy(startMs = s, durationMs = length, attachedTo = a.copy(offsetMs = 0))
    val offset = a.offsetMs.coerceIn(0L, (length - Limits.MIN_CLIP_MS).coerceAtLeast(0L))
    val start = s + offset
    val room = e - start
    val duration = durationMs.coerceAtMost(room).coerceAtLeast(minOf(Limits.MIN_CLIP_MS, room))
    return copy(startMs = start, durationMs = duration, attachedTo = a.copy(offsetMs = offset))
}

/**
 * Captions attached to a photo or video, placed where their item is now. A
 * caption whose item is gone is detached and keeps its times (deleting an
 * item deletes its captions on purpose, in [deleteClip]; this is only the
 * safety net, so no edit and no stored file ever loses a caption silently).
 * With [relocate], a caption that moved onto another caption on its row
 * goes on the highest free text row, or on a new one, as "Add text" does.
 */
internal fun Project.normalizeAttachments(relocate: Boolean): Project {
    if (tracks.none { t -> t.type == TrackType.TEXT && t.clips.any { it is TextClip && it.attachedTo != null } }) return this
    val spans = AttachSpans(this)
    val moved = mutableListOf<Pair<TrackId, ClipId>>()
    val newTracks =
        tracks.map { track ->
            if (track.type != TrackType.TEXT || track.clips.none { it is TextClip && it.attachedTo != null }) return@map track
            val clips =
                track.clips.map { clip ->
                    val a = (clip as? TextClip)?.attachedTo ?: return@map clip
                    val span = spans[a.clip]
                    val next = if (span == null) clip.copy(attachedTo = null) else clip.following(span)
                    if (next.startMs != clip.startMs || next.durationMs != clip.durationMs) moved += track.id to clip.id
                    next
                }
            track.copy(clips = clips.sortedBy { it.startMs })
        }
    var project = copy(tracks = newTracks)
    if (relocate) {
        for ((trackId, clipId) in moved) project = project.moveOffIfOverlapping(trackId, clipId)
    }
    return project
}

/** Moves caption [clipId] off row [trackId] when it overlaps another item there. */
private fun Project.moveOffIfOverlapping(
    trackId: TrackId,
    clipId: ClipId,
): Project {
    val track = track(trackId) ?: return this
    val clip = track.clips.firstOrNull { it.id == clipId } ?: return this
    if (track.isFree(clip.startMs, clip.endMs, except = clipId)) return this
    val without = withTrackClips(trackId, track.clips.filterNot { it.id == clipId })
    return without.placeTimed(clip, preferred = null, role = null, ids = { without.rowIdFor(clipId) }, allowExisting = true).first
}

/** `row-<clip id>`, with `-2`, `-3`... when taken: a stable id for a row normalisation creates. */
internal fun Project.rowIdFor(clipId: ClipId): String {
    val base = "row-" + clipId.value.take(MAX_ROW_BASE)
    var candidate = base
    var n = 1
    while (tracks.any { it.id.value == candidate }) candidate = "$base-${++n}"
    return candidate
}

private const val MAX_ROW_BASE = 54

/** The captions attached to [itemId], with their rows. */
internal fun Project.captionsAttachedTo(itemId: ClipId): List<Pair<TrackId, TextClip>> =
    tracks.flatMap { t ->
        t.clips
            .filterIsInstance<TextClip>()
            .filter { it.attachedTo?.clip == itemId }
            .map { t.id to it }
    }

/** The project without the captions attached to any of [itemIds]; rows left empty go, as with any delete. */
internal fun Project.withoutCaptionsOf(itemIds: Set<ClipId>): Project {
    if (itemIds.isEmpty()) return this
    val emptied = mutableListOf<TrackId>()
    val newTracks =
        tracks.mapNotNull { t ->
            if (t.type != TrackType.TEXT) return@mapNotNull t
            val kept = t.clips.filterNot { it is TextClip && it.attachedTo?.clip in itemIds }
            when {
                kept.size == t.clips.size -> t
                kept.isEmpty() && t.isRemovable -> null.also { emptied += t.id }
                else -> t.copy(clips = kept)
            }
        }
    return copy(tracks = newTracks, stacking = stacking - emptied.toSet())
}

/**
 * A caption's attachment after the user placed it at [startMs] for
 * [durationMs] by hand (dragged, nudged, or Show from/to): it stays on its
 * item, from the new offset, while it starts inside the item's span, and
 * covers the item whole again when it is put back exactly over it. Put
 * outside the item, it is free from then on. A free caption stays free.
 */
internal fun Project.reattached(
    clip: TextClip,
    startMs: Long,
    durationMs: Long,
): TextClip {
    val a = clip.attachedTo ?: return clip.copy(startMs = startMs, durationMs = durationMs)
    val (s, e) = attachSpan(a.clip) ?: return clip.copy(startMs = startMs, durationMs = durationMs, attachedTo = null)
    if (startMs < s || startMs >= e) return clip.copy(startMs = startMs, durationMs = durationMs, attachedTo = null)
    val whole = startMs == s && startMs + durationMs == e
    return clip.copy(startMs = startMs, durationMs = durationMs, attachedTo = a.copy(offsetMs = startMs - s, whole = whole))
}

/** A new caption id for a copy of [caption] made for item [forItem], unused in this project. */
internal fun Project.copyIdFor(
    caption: ClipId,
    forItem: ClipId,
): ClipId {
    val base = forItem.value.take(COPY_PART) + "-" + caption.value.take(COPY_PART)
    var candidate = base
    var n = 1
    while (clip(ClipId(candidate)) != null) candidate = "$base-${++n}"
    return ClipId(candidate)
}

private const val COPY_PART = 28

/**
 * After a split of [firstId] into [firstId] and [secondId]: a caption that
 * covered the whole item covers each part, so the words still show over all
 * of it and each part keeps them when it moves; any other caption stays on
 * the part it starts in.
 */
internal fun Project.captionsAfterSplit(
    firstId: ClipId,
    secondId: ClipId,
): Project {
    val attached = captionsAttachedTo(firstId)
    if (attached.isEmpty()) return this
    val first = attachSpan(firstId) ?: return this
    val second = attachSpan(secondId) ?: return this
    var project = this
    for ((trackId, caption) in attached) {
        val a = caption.attachedTo!!
        if (a.whole) {
            // The first part's caption shrinks to it first, so the copy fits on the same row after it.
            val p = project.replaceCaption(trackId, caption.following(first))
            val copy = caption.copy(id = p.copyIdFor(caption.id, secondId), attachedTo = a.copy(clip = secondId))
            val (s, e) = second
            project = p.placeTimed(copy.copy(startMs = s, durationMs = e - s), trackId, null, { p.rowIdFor(copy.id) }).first
        } else if (caption.startMs >= second.first) {
            val moved = caption.copy(attachedTo = a.copy(clip = secondId, offsetMs = caption.startMs - second.first))
            project = project.replaceCaption(trackId, moved)
        }
    }
    return project
}

/**
 * Before a join of [firstId] and [secondId]: the second part's captions go
 * on the joined item at the times they have now; a whole-item caption on
 * the second part that repeats one on the first (a split made it) is
 * dropped, so the joined item has it once, covering it whole. A whole
 * caption on the first part without such a copy keeps the first part's
 * time instead of growing over the second's captions.
 */
internal fun Project.captionsBeforeJoin(
    firstId: ClipId,
    secondId: ClipId,
): Project {
    val first = captionsAttachedTo(firstId)
    val second = captionsAttachedTo(secondId)
    if (first.isEmpty() && second.isEmpty()) return this
    val firstSpan = attachSpan(firstId) ?: return this

    fun sameWords(
        a: TextClip,
        b: TextClip,
    ) = a.text == b.text && a.style == b.style
    val firstWhole = first.map { it.second }.filter { it.attachedTo!!.whole }
    var project = this
    val keptWhole = mutableSetOf<ClipId>()
    for ((trackId, caption) in second) {
        val twin = if (caption.attachedTo!!.whole) firstWhole.firstOrNull { sameWords(it, caption) && it.id !in keptWhole } else null
        project =
            if (twin != null) {
                keptWhole += twin.id
                project.withTrackClips(trackId, project.requireTrack(trackId).clips.filterNot { it.id == caption.id })
            } else {
                val offset = caption.startMs - firstSpan.first
                project.replaceCaption(trackId, caption.copy(attachedTo = CaptionAttachment(firstId, offset, whole = false)))
            }
    }
    for ((trackId, caption) in first) {
        if (caption.attachedTo!!.whole && caption.id !in keptWhole) {
            project = project.replaceCaption(trackId, caption.copy(attachedTo = caption.attachedTo.copy(whole = false, offsetMs = 0)))
        }
    }
    return project
}

/** For "Duplicate": the captions of [originalId], copied onto its copy [copyId]. */
internal fun Project.captionsForDuplicate(
    originalId: ClipId,
    copyId: ClipId,
): Project {
    val span = attachSpan(copyId) ?: return this
    var project = this
    for ((trackId, caption) in captionsAttachedTo(originalId)) {
        val p = project
        val copy = caption.copy(id = p.copyIdFor(caption.id, copyId), attachedTo = caption.attachedTo!!.copy(clip = copyId))
        project = p.placeTimed(copy.following(span), trackId, null, { p.rowIdFor(copy.id) }).first
    }
    return project
}

private fun Project.replaceCaption(
    trackId: TrackId,
    caption: TextClip,
): Project = withTrackClips(trackId, requireTrack(trackId).clips.map { if (it.id == caption.id) caption else it })
