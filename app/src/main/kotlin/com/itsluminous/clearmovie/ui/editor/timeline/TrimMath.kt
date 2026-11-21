package com.itsluminous.clearmovie.ui.editor.timeline

import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ResizeRules
import com.itsluminous.clearmovie.domain.model.VideoClip
import kotlin.math.roundToLong

/** Which edge a trim handle moves. */
enum class TrimEdge { START, END }

/**
 * What dragging a trim handle by some timeline time does to an item, the same
 * for the live preview of the drag and for the edit made when it ends:
 *
 * - A video's start or end moves through its file (at its speed, and per
 *   loop), never past the file's ends or closer than [MIN_VIDEO_MS] of
 *   timeline to the other edge.
 * - A photo or card gets longer or shorter from either edge, between
 *   [MIN_STILL_MS] and [MAX_STILL_MS].
 *
 * Anything else (the intro and the ending) has no handles and is returned unchanged.
 */
object TrimMath {
    const val MIN_VIDEO_MS = ResizeRules.MIN_MOVING_MS
    const val MIN_STILL_MS = ResizeRules.MIN_STILL_MS
    const val MAX_STILL_MS = ResizeRules.MAX_STILL_MS

    fun dragged(
        clip: MainClip,
        edge: TrimEdge,
        deltaTimelineMs: Double,
    ): MainClip =
        when (clip) {
            is VideoClip -> trimVideo(clip, edge, deltaTimelineMs)
            is PhotoClip -> clip.copy(durationMs = stillLength(clip.durationMs, edge, deltaTimelineMs))
            is CardClip -> clip.copy(durationMs = stillLength(clip.durationMs, edge, deltaTimelineMs))
            else -> clip
        }

    private fun stillLength(
        durationMs: Long,
        edge: TrimEdge,
        delta: Double,
    ): Long {
        val change = if (edge == TrimEdge.END) delta else -delta
        return (durationMs + change).roundToLong().coerceIn(MIN_STILL_MS, MAX_STILL_MS)
    }

    private fun trimVideo(
        clip: VideoClip,
        edge: TrimEdge,
        delta: Double,
    ): VideoClip {
        // Timeline time for one pass of the range, turned into file time.
        val sourceDelta = clip.speed.sourceMs((delta / clip.loops).roundToLong())
        val minSource = maxOf(Limits.MIN_CLIP_MS, clip.speed.sourceMs(MIN_VIDEO_MS))
        return when (edge) {
            TrimEdge.START -> {
                val start = (clip.trimStartMs + sourceDelta).coerceIn(0, maxOf(clip.trimEndMs - minSource, clip.trimStartMs))
                clip.copy(trimStartMs = start)
            }

            TrimEdge.END -> {
                val end =
                    (clip.trimEndMs + sourceDelta).coerceIn(
                        minOf(clip.trimStartMs + minSource, clip.trimEndMs),
                        clip.sourceDurationMs,
                    )
                clip.copy(trimEndMs = end)
            }
        }
    }
}
