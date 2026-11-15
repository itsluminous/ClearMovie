package com.itsluminous.clearmovie.domain.model

/**
 * One MAIN clip placed on the timeline. [overlapToNextMs] is the length of
 * the transition it owns (0 without one).
 */
data class MainSlot(
    val clip: MainClip,
    val index: Int,
    val startMs: Long,
    val durationMs: Long,
    val overlapToNextMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * The time mapping (section 6.4): the single source for where MAIN clips
 * start, used by the render plan, the UI and caption placement. Transitions
 * overlap the two clips, so each one moves every later clip earlier by its
 * length: `start(i + 1) = start(i) + duration(i) - overlap(i)`.
 */
object Timeline {
    /**
     * The longest transition allowed between clips of these lengths: half the
     * shorter clip, and never more than [Transition.MAX_MS]. Below
     * [Transition.MIN_MS] the junction can't have one.
     */
    fun maxTransitionMs(
        leadMs: Long,
        nextMs: Long,
    ): Long = minOf(Transition.MAX_MS, minOf(leadMs, nextMs) / 2)

    /**
     * [wanted] fitted to the clips it joins: shortened to [maxTransitionMs],
     * or null (no transition) when even [Transition.MIN_MS] does not fit or
     * the kind is NONE.
     */
    fun fit(
        wanted: Transition?,
        leadMs: Long,
        nextMs: Long,
    ): Transition? {
        if (wanted == null || wanted.kind == TransitionKind.NONE) return null
        val cap = maxTransitionMs(leadMs, nextMs)
        if (cap < Transition.MIN_MS) return null
        val length = wanted.durationMs.coerceIn(Transition.MIN_MS, cap)
        return if (length == wanted.durationMs) wanted else wanted.copy(durationMs = length)
    }
}

/** MAIN's clips with their real start times. */
fun Project.mainLayout(): List<MainSlot> {
    val clips = mainClips
    var start = 0L
    return clips.mapIndexed { i, clip ->
        val overlap = if (i < clips.lastIndex) clip.transitionToNext?.durationMs ?: 0L else 0L
        MainSlot(clip, i, start, clip.durationMs, overlap).also { start += clip.durationMs - overlap }
    }
}

/** The video's length: the end of the MAIN row, with transition overlaps taken off. */
fun Project.totalDurationMs(): Long = mainLayout().lastOrNull()?.endMs ?: 0L

/** Where a MAIN clip plays, or null when [clipId] is not on MAIN. */
fun Project.mainSlot(clipId: ClipId): MainSlot? = mainLayout().firstOrNull { it.clip.id == clipId }

/** The end of the intro, or 0 without one. */
fun Project.introEndMs(): Long = mainLayout().firstOrNull()?.takeIf { it.clip is IntroClip }?.endMs ?: 0L

/** The ending's slot, if the video has one. */
fun Project.endingSlot(): MainSlot? = mainLayout().lastOrNull()?.takeIf { it.clip is EndingClip }

/**
 * The span a time-anchored clip follows, as (start, end). For the frame this
 * is from the intro's end (or 0) to the end of the video, or to the start of
 * the ending when the frame does not cover it. Null for an [SpanAnchor.Ending]
 * when there is no ending.
 */
fun Project.anchorSpan(anchor: SpanAnchor): Pair<Long, Long>? =
    when (anchor) {
        is SpanAnchor.Frame -> {
            val start = if (anchor.startAfterIntro) introEndMs() else 0L
            val ending = endingSlot()
            val end = if (!anchor.coverEnding && ending != null) ending.startMs else totalDurationMs()
            start to maxOf(start, end)
        }

        SpanAnchor.Ending -> {
            endingSlot()?.let { it.startMs to it.endMs }
        }
    }

/**
 * The span of a MAIN clip, for "Add text" on a photo or video: a caption
 * that covers exactly that item (section 6.1).
 */
fun Project.captionSpanFor(mainClipId: ClipId): Pair<Long, Long>? = mainSlot(mainClipId)?.let { it.startMs to it.endMs }
