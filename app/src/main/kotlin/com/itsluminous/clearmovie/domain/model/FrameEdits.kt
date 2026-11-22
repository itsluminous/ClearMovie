package com.itsluminous.clearmovie.domain.model

// A new frame from the cursor (cm-plus-position): a different frame for a different part of the video.

/**
 * Adds [frame] (an overlay video just picked) from [atMs] on frame row
 * [trackId], in one edit:
 * - With [takeOver], the frame under the cursor: that frame now ends at the
 *   cursor and the new frame takes over the rest of its time (it repeats to
 *   fill it). The preset's own frame keeps following the video at its start.
 *   Never less than [ResizeRules.MIN_STILL_MS] of either: a cursor that close
 *   to the old frame's start cuts it there; one that close to its end leaves
 *   it whole and starts the new frame right after it.
 * - Without, from the cursor up to the next frame on that row or the end of
 *   the video.
 * The new frame goes on [trackId] when it takes it, else on another frame
 * row, as every frame does (the preset's frame row holds only its frame).
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
    if (old != null) {
        end = old.endMs
        val cutAt = maxOf(atMs, old.startMs + ResizeRules.MIN_STILL_MS)
        if (cutAt <= old.endMs - ResizeRules.MIN_STILL_MS) {
            p = p.resizeClip(old.id, ResizeEdge.END, cutAt)
            start = p.clip(old.id)?.endMs ?: cutAt
        } else {
            start = old.endMs
        }
    } else {
        start = atMs.coerceIn(0, maxOf(0, total - ResizeRules.MIN_STILL_MS))
        end = track.clips
            .filter { it.startMs > start && it.durationMs > 0 }
            .minOfOrNull { it.startMs }
            ?.coerceAtMost(total) ?: total
    }
    val length = if (end - start >= ResizeRules.MIN_STILL_MS) end - start else frame.durationMs
    val made = frame.copy(startMs = start, durationMs = length, repeat = true, anchor = null)
    return p.addToTrack(trackId, made, start, ids)
}
