package com.itsluminous.clearmovie.domain.model

import kotlin.math.max
import kotlin.math.min

// Resizing an item on a row other than MAIN by its handles (cm-timeline-handles).

/** Which side of an item a handle moves. */
enum class ResizeEdge { START, END }

/**
 * Where a handle may take each side of an item, the other side staying put:
 * the start anywhere in [startMin]..[startMax], the end anywhere in
 * [endMin]..[endMax]. Both ranges hold the side's time now.
 */
data class ResizeRange(
    val startMin: Long,
    val startMax: Long,
    val endMin: Long,
    val endMax: Long,
) {
    fun clamp(
        edge: ResizeEdge,
        ms: Long,
    ): Long = if (edge == ResizeEdge.START) ms.coerceIn(startMin, startMax) else ms.coerceIn(endMin, endMax)
}

/** The shortest and longest lengths a handle leaves, shared with the MAIN row's trim handles. */
object ResizeRules {
    /** A photo, a caption or a frame. */
    const val MIN_STILL_MS = 500L

    /** A video or a sound. */
    const val MIN_MOVING_MS = 300L

    /** A photo is shown for at most this long. */
    const val MAX_STILL_MS = 10L * 60 * 1000
}

/**
 * The range of each handle of item [clipId] on a row other than MAIN, or
 * null for an item without handles (on MAIN, or empty). The limits:
 * - never before the video's start, nor over the neighbours on its row, nor
 *   past the video's end (or the item's own end, if it already runs past it);
 * - at least [ResizeRules.MIN_STILL_MS] long ([ResizeRules.MIN_MOVING_MS] for
 *   a video or a sound), or its length now if it is already shorter;
 * - a video or a sound that does not repeat plays only what its file has, and
 *   a frame that does not repeat is at most its file's length; a photo is at
 *   most [ResizeRules.MAX_STILL_MS].
 */
fun Project.resizeRange(clipId: ClipId): ResizeRange? {
    val track = trackOf(clipId) ?: return null
    if (!track.type.isTimed) return null
    val clip = clip(clipId) ?: return null
    if (clip.durationMs <= 0) return null
    val others = track.clips.filter { it.id != clipId && it.durationMs > 0 }
    val floor = others.filter { it.endMs <= clip.startMs }.maxOfOrNull { it.endMs } ?: 0L
    val ceiling = others.filter { it.startMs >= clip.endMs }.minOfOrNull { it.startMs } ?: max(totalDurationMs(), clip.endMs)
    val shortest = min(clip.durationMs, if (clip is VideoClip || clip is AudioClip) ResizeRules.MIN_MOVING_MS else ResizeRules.MIN_STILL_MS)
    // The longest the item can get from each side, the other side staying put.
    val (fromStart, fromEnd) =
        when (clip) {
            is VideoClip -> {
                clip.speed.timelineMs(clip.trimEndMs * clip.loops) to
                    clip.speed.timelineMs((clip.sourceDurationMs - clip.trimStartMs) * clip.loops)
            }

            is PhotoClip -> {
                ResizeRules.MAX_STILL_MS to ResizeRules.MAX_STILL_MS
            }

            is AudioClip -> {
                if (clip.repeat) {
                    Limits.MAX_MEDIA_MS to Limits.MAX_MEDIA_MS
                } else {
                    // The start goes back through the file only as far as its beginning; the end to its end.
                    clip.durationMs + clip.trimStartMs to clip.sourceDurationMs - clip.trimStartMs
                }
            }

            is OverlayClip -> {
                if (clip.repeat) Limits.MAX_MEDIA_MS to Limits.MAX_MEDIA_MS else clip.sourceDurationMs to clip.sourceDurationMs
            }

            else -> {
                Limits.MAX_MEDIA_MS to Limits.MAX_MEDIA_MS
            }
        }
    val startMin = max(floor, clip.endMs - max(fromStart, clip.durationMs))
    val startMax = clip.endMs - shortest
    val endMin = clip.startMs + shortest
    val endMax = min(ceiling, clip.startMs + max(fromEnd, clip.durationMs))
    return ResizeRange(
        min(startMin, clip.startMs),
        max(startMax, clip.startMs),
        min(endMin, clip.endMs),
        max(endMax, clip.endMs),
    )
}

/**
 * Item [clipId]'s [edge] dragged to [toMs] (kept inside [resizeRange]); the
 * other side stays where it is. A video or a sound loses or gains file at
 * that side, so what plays stays in step with the time. A caption made on a
 * photo or video detaches only the side that was dragged (both detached: it
 * is free); a caption placed by hand inside its item keeps following it from
 * its new place. The preset's frame, its music and the closing message keep
 * following the video on the side that was not dragged. One undo step.
 */
fun Project.resizeClip(
    clipId: ClipId,
    edge: ResizeEdge,
    toMs: Long,
): Project {
    val range = requireNotNull(resizeRange(clipId)) { "This item has no handles" }
    val track = requireNotNull(trackOf(clipId))
    val clip = requireClip(clipId)
    val target = range.clamp(edge, toMs)
    val now = if (edge == ResizeEdge.START) clip.startMs else clip.endMs
    if (target == now) return this
    val start = if (edge == ResizeEdge.START) target else clip.startMs
    val end = if (edge == ResizeEdge.END) target else clip.endMs
    val changed =
        when (clip) {
            is TextClip -> resizedCaption(clip, edge, start, end)
            is PhotoClip -> clip.copy(startMs = start, durationMs = end - start)
            is VideoClip -> resizedVideo(clip, edge, end - start)
            is AudioClip -> resizedSound(clip, edge, start, end).heldOn(edge, start, end)
            is OverlayClip -> clip.copy(startMs = start, durationMs = end - start).heldOn(edge, start, end)
            else -> throw IllegalArgumentException("This item has no handles")
        }
    return withTrackClips(track.id, track.clips.map { if (it.id == clipId) changed else it }.sortedBy { it.startMs }).normalized()
}

private fun Project.resizedCaption(
    clip: TextClip,
    edge: ResizeEdge,
    start: Long,
    end: Long,
): TextClip {
    val a = clip.attachedTo
    return when {
        a == null -> {
            clip.copy(startMs = start, durationMs = end - start)
        }

        a.whole -> {
            val next = if (edge == ResizeEdge.START) a.copy(detachedStartMs = start) else a.copy(detachedEndMs = end)
            val free = next.detachedStartMs != null && next.detachedEndMs != null
            clip.copy(startMs = start, durationMs = end - start, attachedTo = next.takeUnless { free })
        }

        else -> {
            reattached(clip, start, end - start)
        }
    }
}

/** A picture-in-picture video lengthened or shortened at [edge] to about [lengthMs], through its file. */
private fun resizedVideo(
    clip: VideoClip,
    edge: ResizeEdge,
    lengthMs: Long,
): VideoClip {
    val range = (clip.speed.sourceMs(lengthMs) / clip.loops).coerceAtLeast(Limits.MIN_CLIP_MS)
    val trimmed =
        if (edge == ResizeEdge.START) {
            clip.copy(trimStartMs = (clip.trimEndMs - range).coerceAtLeast(0L))
        } else {
            clip.copy(trimEndMs = (clip.trimStartMs + range).coerceAtMost(clip.sourceDurationMs))
        }
    // The rounded length decides the start, so the end stays exactly where it was.
    return if (edge == ResizeEdge.START) trimmed.copy(startMs = (clip.endMs - trimmed.durationMs).coerceAtLeast(0L)) else trimmed
}

private fun resizedSound(
    clip: AudioClip,
    edge: ResizeEdge,
    start: Long,
    end: Long,
): AudioClip {
    if (clip.repeat) return clip.copy(startMs = start, durationMs = end - start)
    return if (edge == ResizeEdge.START) {
        clip.copy(startMs = start, durationMs = end - start, trimStartMs = clip.trimStartMs + (start - clip.startMs))
    } else {
        clip.copy(durationMs = end - start, trimEndMs = clip.trimStartMs + (end - start))
    }
}

/** An anchored clip with the dragged side held at its own time; both sides held, it is an ordinary clip. */
private fun Clip.heldOn(
    edge: ResizeEdge,
    start: Long,
    end: Long,
): Clip {
    val (ds, de) = detachedSides
    val held = if (edge == ResizeEdge.START) start to de else ds to end
    val free = held.first != null && held.second != null
    return when (this) {
        is OverlayClip -> {
            when {
                anchor == null -> this
                free -> copy(anchor = null, detachedStartMs = null, detachedEndMs = null)
                else -> copy(detachedStartMs = held.first, detachedEndMs = held.second)
            }
        }

        is AudioClip -> {
            when {
                anchor == null -> this
                free -> copy(anchor = null, detachedStartMs = null, detachedEndMs = null)
                else -> copy(detachedStartMs = held.first, detachedEndMs = held.second)
            }
        }

        else -> {
            this
        }
    }
}

/** Whether caption [clipId] can follow its photo or video again ("Follow item"): it is attached and does not cover it whole. */
fun Project.canFollowItem(clipId: ClipId): Boolean {
    val a = (clip(clipId) as? TextClip)?.attachedTo ?: return false
    return (!a.whole || a.partlyDetached) && attachSpan(a.clip) != null
}

/** "Follow item": the caption covers its photo or video whole again, both sides following it. */
fun Project.followItem(clipId: ClipId): Project {
    require(canFollowItem(clipId)) { "This caption already follows its item" }
    val track = requireNotNull(trackOf(clipId))
    val caption = requireClip(clipId) as TextClip
    val followed = caption.copy(attachedTo = CaptionAttachment(caption.attachedTo!!.clip))
    // Normalising places it over its item; one that then reaches over another caption moves to a free text row.
    return withTrackClips(track.id, track.clips.map { if (it.id == clipId) followed else it }).normalized()
}
