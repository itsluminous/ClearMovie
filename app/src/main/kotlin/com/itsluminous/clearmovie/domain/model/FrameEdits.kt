package com.itsluminous.clearmovie.domain.model

// A new frame from the cursor (cm-plus-position): a different frame for a different part of the video.

/**
 * Adds [frame] (an overlay video just picked) from [atMs] on frame row
 * [trackId], in one edit:
 * - With [takeOver], the frame under the cursor: that frame now ends at the
 *   cursor and the new frame takes over the rest of its time (it repeats to
 *   fill it). Taking over the preset's frame, the new frame also takes over
 *   its following the video's end, and the cut stays at the time it was put
 *   when photos are added or taken away later (the preset's frame then keeps
 *   fixed times: skipping the intro afterwards no longer moves its start).
 *   Never less than [ResizeRules.MIN_STILL_MS] of either: a cursor that close
 *   to the old frame's start cuts it there; one that close to its end leaves
 *   it whole and starts the new frame right after it.
 * - Without, from the cursor up to the next frame shown on that row or the
 *   end of the video.
 * The new frame goes on [trackId] when it takes it, else on another frame
 * row; from the preset's frame row always on another frame row (that row
 * holds only the preset's frame).
 */
fun Project.addFrameFrom(
    trackId: TrackId,
    frame: OverlayClip,
    atMs: Long,
    takeOver: ClipId?,
    ids: IdSource = RandomIds,
): Project {
    val track = requireTrack(trackId)
    require(track.type == TrackType.OVERLAY) { "Frames go on a frame row" }
    val total = totalDurationMs()
    val old = takeOver?.let { id -> track.clips.firstOrNull { it.id == id } as? OverlayClip }
    var p = this
    val start: Long
    val end: Long
    // The new frame follows the video as the old one did: an anchored frame (the preset's) hands its anchor on, so the
    // new frame still runs to the video's end when photos are added or taken away later.
    var anchor: SpanAnchor? = null
    var detachedStart: Long? = null
    var detachedEnd: Long? = null
    if (old != null) {
        end = old.endMs
        val cutAt = maxOf(atMs, old.startMs + ResizeRules.MIN_STILL_MS)
        val oldAnchor = old.anchor as? SpanAnchor.Frame
        if (cutAt > old.endMs - ResizeRules.MIN_STILL_MS) {
            start = old.endMs
        } else if (oldAnchor != null) {
            // The handover stays at the time it was put, whatever is added or taken away later: the old frame keeps
            // its times (it no longer follows the video), and the new frame starts there, held from the video's start,
            // and ends as the old one did (with the video's end, or the ending's start).
            val kept = old.copy(durationMs = cutAt - old.startMs, anchor = null, detachedStartOffsetMs = null, detachedEndOffsetMs = null)
            p = p.withTrackClips(track.id, track.clips.map { if (it.id == old.id) kept else it })
            start = cutAt
            anchor = oldAnchor.copy(startAfterIntro = false)
            detachedStart = cutAt.takeUnless { it == 0L }
            detachedEnd = old.detachedEndOffsetMs
        } else {
            p = p.resizeClip(old.id, ResizeEdge.END, cutAt)
            start = p.clip(old.id)?.endMs ?: cutAt
        }
    } else {
        start = atMs.coerceIn(0, maxOf(0, total - ResizeRules.MIN_STILL_MS))
        // A switched-off row shows none of its frames, so none of them bounds the new one.
        val shown = if (track.enabled) track.clips else emptyList()
        end = shown.filter { it.startMs > start && it.durationMs > 0 }.minOfOrNull { it.startMs }?.coerceAtMost(total) ?: total
    }
    // Both sides held at a distance is a frame that no longer follows anything.
    if (detachedStart != null && detachedEnd != null) {
        anchor = null
        detachedStart = null
        detachedEnd = null
    }
    val length = if (end - start >= ResizeRules.MIN_STILL_MS) end - start else frame.durationMs
    val made =
        frame.copy(
            startMs = start,
            durationMs = length,
            repeat = true,
            anchor = anchor,
            detachedStartOffsetMs = detachedStart,
            detachedEndOffsetMs = detachedEnd,
        )
    // The preset's frame row holds only its own frame (it can be switched off, and deleting there switches it off): a
    // frame taken over from it goes on the highest free frame row of the user's, or a new one.
    return if (track.presetRole != null) p.addTimedClip(made, ids = ids) else p.addToTrack(trackId, made, start, ids)
}
