package com.itsluminous.clearmovie.domain.model

/**
 * Brings a project back to its canonical form after an edit. Every edit
 * ends here, so the rules below hold after any change:
 * - MAIN clips have `startMs = 0`; a NONE transition is stored as null; the
 *   last MAIN clip owns no transition.
 * - With **Apply to all** set, every MAIN junction has that transition,
 *   except the cut between two continuous halves of a split, which never
 *   gets one (section 6.4).
 * - Each transition is fitted to the clips it joins (at most half the shorter
 *   one), or dropped when it no longer fits.
 * - Clips anchored to the frame span or the ending follow the MAIN row; a
 *   clip anchored to an ending that is gone is removed, and so is a row
 *   that leaves empty (MAIN and the preset frame row excepted).
 * - A clip an anchored clip grew over is moved to another row, so rows
 *   never overlap.
 * - Captions attached to a photo or video follow it (see `Attachments`); one
 *   that lands on another caption moves to another text row.
 * - START and END animations, and a sound's fades, fit inside their clip.
 *
 * It is a pure function of the project (a row it has to create gets an id
 * derived from the clip it holds), and idempotent: normalising a normalised
 * project changes nothing.
 */
fun Project.normalized(): Project = normalized(relocate = true)

/**
 * With [relocate] false, a clip an anchored clip would grow over is left
 * where it is (so the result may still overlap and fail validation). Loading
 * a stored project uses that: it may re-derive values, but never moves the
 * user's clips, and it stays cheap on hostile input.
 */
internal fun Project.normalized(relocate: Boolean): Project =
    normalizeMain()
        .normalizeAttachments(relocate)
        .normalizeAnchors(relocate)
        .normalizeFits()

/** Two neighbouring halves of one split, still continuous: the cut between them takes no transition. */
internal fun isContinuousSplit(
    a: Clip,
    b: Clip,
): Boolean =
    a is VideoClip &&
        b is VideoClip &&
        a.splitGroup != null &&
        a.splitGroup == b.splitGroup &&
        a.media == b.media &&
        a.trimEndMs == b.trimStartMs

private fun Project.normalizeMain(): Project {
    // Only MAIN-capable clips are fitted; anything else on MAIN is left for
    // validate() to report, never dropped.
    val clips = main.clips.filterIsInstance<MainClip>()
    val forAll = transitionForAll?.takeUnless { it.kind == TransitionKind.NONE }
    val fixed =
        clips.mapIndexed { i, clip ->
            val next = clips.getOrNull(i + 1)
            val wanted =
                when {
                    next == null -> null
                    forAll != null && !isContinuousSplit(clip, next) -> forAll
                    else -> clip.transitionToNext
                }
            val fitted = if (next != null) Timeline.fit(wanted, clip.durationMs, next.durationMs) else null
            val placed = if (clip.startMs != 0L) clip.withStartMs(0) as MainClip else clip
            if (placed.transitionToNext == fitted) placed else placed.withTransition(fitted)
        }
    val byId = fixed.associateBy { it.id }
    return withTrackClips(main.id, main.clips.map { byId[it.id] ?: it }).copy(transitionForAll = forAll)
}

private val Clip.anchor: SpanAnchor?
    get() =
        when (this) {
            is OverlayClip -> anchor
            is AudioClip -> anchor
            else -> null
        }

private fun Project.normalizeAnchors(relocate: Boolean): Project {
    if (tracks.none { t -> t.clips.any { it.anchor != null } }) return this
    // Spans depend only on MAIN, which this step never changes: work each out once.
    val spans = SpanCache(this)
    val anchored = mutableListOf<Pair<TrackId, Clip>>()
    val newTracks =
        tracks.mapNotNull { track ->
            if (track.clips.none { it.anchor != null }) return@mapNotNull track
            val kept =
                track.clips.mapNotNull { clip ->
                    val anchor = clip.anchor ?: return@mapNotNull clip
                    val span = spans[anchor] ?: return@mapNotNull null
                    clip.respanned(span.first, span.second).also { anchored += track.id to it }
                }
            // Like any delete, a row left empty by a dropped clip goes too.
            if (kept.isEmpty() && track.isRemovable) null else track.copy(clips = kept.sortedBy { it.startMs })
        }
    val remaining = newTracks.map { it.id }.toSet()
    var project = copy(tracks = newTracks, stacking = stacking.filter { it in remaining })
    if (relocate) {
        for ((trackId, clip) in anchored) {
            if (project.track(trackId) != null) project = project.moveAsideFrom(trackId, clip)
        }
    }
    return project
}

/** [Project.anchorSpan] worked out once per distinct anchor. */
internal class SpanCache(
    private val project: Project,
) {
    private val spans = HashMap<SpanAnchor, Pair<Long, Long>?>()

    operator fun get(anchor: SpanAnchor): Pair<Long, Long>? =
        if (spans.containsKey(anchor)) spans[anchor] else project.anchorSpan(anchor).also { spans[anchor] = it }
}

/** The clip moved to [start] and stretched to [end] (capped at its media when it does not repeat). */
internal fun Clip.respanned(
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
): Project {
    val track = track(trackId)!!
    val keepOnly = track.copy(clips = listOf(keep))
    val inTheWay = track.clips.filter { it.id != keep.id && !keepOnly.isFree(it.startMs, it.endMs) }
    if (inTheWay.isEmpty()) return this
    var project = withTrackClips(trackId, track.clips - inTheWay.toSet())
    for (clip in inTheWay) {
        val p = project
        project = p.placeTimed(clip, preferred = null, role = track.audioRole, ids = { p.freshTrackId(clip.id).value }).first
    }
    return project
}

/** `row-<clip id>`, with `-2`, `-3`... when taken: a stable id for a row normalisation has to create. */
private fun Project.freshTrackId(forClip: ClipId): TrackId {
    val base = "row-" + forClip.value.take(MAX_BASE)
    var candidate = base
    var n = 1
    while (tracks.any { it.id.value == candidate }) candidate = "$base-${++n}"
    return TrackId(candidate)
}

private const val MAX_BASE = 54

private fun Project.normalizeFits(): Project =
    copy(tracks = tracks.map { track -> track.copy(clips = track.clips.map { it.withFittedAnimations().withFittedFades() }) })

/**
 * START and END animations shortened to fit the clip together. Each is first
 * capped at the clip; if together they are still too long, the longer one
 * gives way to the shorter, then both share the clip equally, and if even
 * that is under the minimum only the START animation is kept. The result
 * depends only on the clip, never on the order the animations were set.
 */
internal fun Clip.withFittedAnimations(): Clip {
    if (this is AudioClip || animations.isEmpty) return this
    val a = animations
    val d = durationMs
    var s =
        a.start
            ?.durationMs
            ?.coerceIn(Anim.MIN_MS, Anim.MAX_MS)
            ?.coerceAtMost(d)
    var e =
        a.end
            ?.durationMs
            ?.coerceIn(Anim.MIN_MS, Anim.MAX_MS)
            ?.coerceAtMost(d)
    if (s != null && e != null && s + e > d) {
        when {
            s <= e && d - s >= Anim.MIN_MS -> {
                e = d - s
            }

            e < s && d - e >= Anim.MIN_MS -> {
                s = d - e
            }

            d / 2 >= Anim.MIN_MS -> {
                s = d / 2
                e = d / 2
            }

            else -> {
                e = null
            }
        }
    }

    fun fit(
        anim: Anim?,
        length: Long?,
    ): Anim? =
        when {
            anim == null || length == null || length < Anim.MIN_MS -> null
            length == anim.durationMs -> anim
            else -> anim.copy(durationMs = length)
        }
    val fitted = a.copy(start = fit(a.start, s), end = fit(a.end, e))
    return if (fitted == a) this else withAnimations(fitted)
}

/** A sound's fades shortened, in proportion, to fit inside it together. */
internal fun Clip.withFittedFades(): Clip {
    if (this !is AudioClip || durationMs <= 0 || fadeInMs + fadeOutMs <= durationMs) return this
    val total = fadeInMs + fadeOutMs
    val fadeIn = fadeInMs * durationMs / total
    return copy(fadeInMs = fadeIn, fadeOutMs = minOf(fadeOutMs, durationMs - fadeIn))
}
