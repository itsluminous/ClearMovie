package com.itsluminous.clearmovie.domain.model

/** One broken invariant. [rule] lets tests and callers tell them apart. */
data class Violation(
    val rule: Rule,
    val detail: String,
) {
    enum class Rule {
        /** Exactly one MAIN row. */
        ONE_MAIN,

        /** Ids are unique across rows and across clips. */
        UNIQUE_IDS,

        /** `stacking` lists every visual row once, and no sound row. */
        STACKING,

        /** Each row holds only its own kinds of clip; MAIN has the intro first and the ending last. */
        CLIP_KIND,

        /** No two clips on a timed row overlap; MAIN clips carry no start time. */
        OVERLAP,

        /** Lengths, trims, speeds, loops, volumes and fades are in range. */
        RANGE,

        /** Transitions only on MAIN, never on the last item, fitted to their clips. */
        TRANSITION,

        /** Animations in their slots, on the right kind of clip, fitting the clip. */
        ANIMATION,

        /** Audio rows have a role; preset rows are the right type and appear once. */
        TRACK_ROLE,

        /** Names, captions and styles are within limits. */
        TEXT,

        /**
         * Clips anchored to the frame or the ending have exactly the span they
         * follow; a caption attached to a photo or video is where its item puts it.
         */
        ANCHOR,

        /** The project's frozen preset settings (ducking, effect defaults) are in range. */
        SETTINGS,
    }
}

/** True when [validate] finds nothing. */
val Project.isValid: Boolean get() = validate().isEmpty()

/**
 * Checks every invariant of section 6. An empty list means the project is
 * sound. `ProjectEditor` refuses an edit that breaks one, and `ProjectCodec`
 * refuses to load a project that breaks one.
 */
fun Project.validate(): List<Violation> =
    buildList {
        fun fail(
            rule: Violation.Rule,
            detail: String,
        ) = add(Violation(rule, detail))

        val mains = tracks.count { it.type == TrackType.MAIN }
        if (mains != 1) fail(Violation.Rule.ONE_MAIN, "found $mains MAIN rows")

        val trackIds = tracks.map { it.id }
        if (trackIds.toSet().size != trackIds.size) fail(Violation.Rule.UNIQUE_IDS, "two rows share an id")
        val clipIds = tracks.flatMap { t -> t.clips.map { it.id } }
        if (clipIds.toSet().size != clipIds.size) fail(Violation.Rule.UNIQUE_IDS, "two clips share an id")

        val visual = tracks.filter { it.type.isVisual }.map { it.id }
        if (stacking.size != visual.size || stacking.toSet() != visual.toSet()) {
            fail(Violation.Rule.STACKING, "stacking must list each picture row exactly once")
        }

        for (role in PresetRole.entries) {
            if (tracks.count { it.presetRole == role } > 1) fail(Violation.Rule.TRACK_ROLE, "more than one $role row")
        }
        for (track in tracks) checkTrack(track, ::fail)

        name?.let { if (!TextRules.isValidName(it)) fail(Violation.Rule.TEXT, "project name") }
        captionDefaults.problems().forEach { fail(Violation.Rule.TEXT, it) }
        captionDefaultText?.all?.forEach {
            if (it.length > Limits.MAX_TEXT_LENGTH || !TextRules.isAllowed(it, allowNewlines = true)) {
                fail(Violation.Rule.TEXT, "caption default text")
            }
        }
        if (photoDurationMs !in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) fail(Violation.Rule.RANGE, "photo duration")
        transitionForAll?.let {
            if (it.kind == TransitionKind.NONE || it.durationMs !in Transition.MIN_MS..Transition.MAX_MS) {
                fail(Violation.Rule.TRANSITION, "Apply to all transition")
            }
        }
        checkSettings(::fail)
        if (mains == 1) checkAnchors(::fail)
        if (mains == 1) checkAttachments(::fail)
    }

/** An attached caption follows a photo or video that is there, and sits where that item puts it. */
private fun Project.checkAttachments(fail: (Violation.Rule, String) -> Unit) {
    val spans = AttachSpans(this)
    for (track in tracks) {
        for (clip in track.clips) {
            val a = (clip as? TextClip)?.attachedTo ?: continue
            val span = spans[a.clip]
            when {
                span == null -> {
                    fail(Violation.Rule.ANCHOR, "${clip.id.value} is attached to an item that is not a photo or video here")
                }

                a.offsetMs !in 0..Limits.MAX_MEDIA_MS ||
                    a.lengthMs?.let { it !in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS } == true ||
                    (a.partlyDetached && (!a.whole || (a.detachedStartMs != null && a.detachedEndMs != null))) ||
                    listOfNotNull(a.detachedStartMs, a.detachedEndMs).any { it !in 0..Limits.MAX_MEDIA_MS } ||
                    clip.following(span) != clip -> {
                    fail(Violation.Rule.ANCHOR, "${clip.id.value} is not where its item puts it")
                }
            }
        }
    }
}

private fun Project.checkSettings(fail: (Violation.Rule, String) -> Unit) {
    val levels = Limits.MIN_VOLUME_DB..0.0
    val d = ducking
    if (listOf(d.musicUnderClipDb, d.musicUnderVoiceDb, d.speechDuckDb).any { !it.isFinite() || it !in levels }) {
        fail(Violation.Rule.SETTINGS, "ducking level")
    }
    if (d.attackMs !in 0..MAX_RAMP_MS || d.releaseMs !in 0..MAX_RAMP_MS) fail(Violation.Rule.SETTINGS, "ducking ramp")
    val e = effectDefaults
    e.mainTransition?.let {
        if (it.kind == TransitionKind.NONE || it.durationMs !in Transition.MIN_MS..Transition.MAX_MS) {
            fail(Violation.Rule.SETTINGS, "default transition")
        }
    }
    e.photoDuring?.let { if (AnimSlot.DURING !in it.slots || it.textOnly) fail(Violation.Rule.SETTINGS, "default photo animation") }
    e.textStart?.let { if (AnimSlot.START !in it.slots || it.photoOnly) fail(Violation.Rule.SETTINGS, "default caption animation") }
}

private const val MAX_RAMP_MS = 5000L

/** Anchored clips must already have the span they follow, so loading a file never moves them behind the user's back. */
private fun Project.checkAnchors(fail: (Violation.Rule, String) -> Unit) {
    val spans = SpanCache(this)
    for (track in tracks) {
        for (clip in track.clips) {
            val anchor =
                when (clip) {
                    is OverlayClip -> clip.anchor
                    is AudioClip -> clip.anchor
                    else -> null
                } ?: continue
            val span = spans[anchor]
            when {
                span == null -> fail(Violation.Rule.ANCHOR, "${clip.id.value} follows an ending that is not there")
                clip.following(span) != clip -> fail(Violation.Rule.ANCHOR, "${clip.id.value} does not follow its span")
            }
        }
    }
}

private fun Project.checkTrack(
    track: Track,
    fail: (Violation.Rule, String) -> Unit,
) {
    val where = "row ${track.id.value}"
    when (track.presetRole) {
        PresetRole.FRAME -> {
            if (track.type != TrackType.OVERLAY) fail(Violation.Rule.TRACK_ROLE, "$where: the frame row is a frame row")
        }

        PresetRole.FRAME_MUSIC -> {
            if (track.audioRole != AudioRole.MUSIC) fail(Violation.Rule.TRACK_ROLE, "$where: the frame music is a music row")
        }

        PresetRole.CLOSING_MESSAGE -> {
            if (track.audioRole != AudioRole.VOICE) fail(Violation.Rule.TRACK_ROLE, "$where: the closing message is a voice row")
        }

        null -> {
            Unit
        }
    }
    if ((track.type == TrackType.AUDIO) != (track.audioRole != null)) {
        fail(Violation.Rule.TRACK_ROLE, "$where: only sound rows have a role, and they need one")
    }

    track.lowerToDb?.let {
        if (track.audioRole != AudioRole.MUSIC || !it.isFinite() || it !in Limits.MIN_VOLUME_DB..0.0) {
            fail(Violation.Rule.SETTINGS, "$where: talk level")
        }
    }

    track.clips.filterNot { track.accepts(it) }.forEach { fail(Violation.Rule.CLIP_KIND, "$where: ${it.id.value} does not belong here") }
    // A row the preset manages holds only the preset's clip (see Track.takes).
    track.clips.filterNot { track.takes(it) }.forEach { fail(Violation.Rule.CLIP_KIND, "$where: ${it.id.value} is not the preset's") }

    if (track.type == TrackType.MAIN) {
        checkMain(track, fail)
    } else {
        track.clips.forEach {
            if (it is IntroClip || it is EndingClip ||
                it is CardClip
            ) {
                fail(Violation.Rule.CLIP_KIND, "$where: MAIN-only item")
            }
        }
        val sorted = track.clips.sortedBy { it.startMs }
        if (sorted != track.clips) fail(Violation.Rule.OVERLAP, "$where: clips out of time order")
        var reach: Clip? = null
        for (clip in sorted.filter { it.durationMs > 0 }) {
            val before = reach
            if (before != null && clip.startMs < before.endMs) {
                fail(Violation.Rule.OVERLAP, "$where: ${before.id.value} overlaps ${clip.id.value}")
            }
            if (before == null || clip.endMs > before.endMs) reach = clip
        }
        track.clips.forEach {
            if (it.startMs !in 0..Limits.MAX_MEDIA_MS) fail(Violation.Rule.RANGE, "$where: ${it.id.value} start")
            if (it is MainClip && it.transitionToNext != null) fail(Violation.Rule.TRANSITION, "$where: transitions are on MAIN only")
        }
    }
    track.clips.forEach { clip ->
        checkClipRanges(clip, fail)
        checkAnimations(clip, fail)
    }
}

private fun Project.checkMain(
    track: Track,
    fail: (Violation.Rule, String) -> Unit,
) {
    val clips = track.clips
    clips.forEachIndexed { i, clip ->
        if (clip.startMs != 0L) fail(Violation.Rule.OVERLAP, "MAIN item ${clip.id.value} has a start time")
        if (clip is IntroClip && i != 0) fail(Violation.Rule.CLIP_KIND, "the intro must come first")
        if (clip is EndingClip && i != clips.lastIndex) fail(Violation.Rule.CLIP_KIND, "the ending must come last")
    }
    val main = clips.filterIsInstance<MainClip>()
    main.forEachIndexed { i, clip ->
        val t = clip.transitionToNext ?: return@forEachIndexed
        val next = main.getOrNull(i + 1)
        when {
            next == null -> {
                fail(Violation.Rule.TRANSITION, "the last item has a transition")
            }

            t.kind == TransitionKind.NONE -> {
                fail(Violation.Rule.TRANSITION, "${clip.id.value}: NONE is stored as no transition")
            }

            t.durationMs < Transition.MIN_MS || t.durationMs > Timeline.maxTransitionMs(clip.durationMs, next.durationMs) -> {
                fail(Violation.Rule.TRANSITION, "${clip.id.value}: transition length does not fit")
            }
        }
    }
    transitionForAll?.let { all ->
        main.zipWithNext().forEach { (a, b) ->
            if (!isContinuousSplit(a, b) && a.transitionToNext != Timeline.fit(all, a.durationMs, b.durationMs)) {
                fail(Violation.Rule.TRANSITION, "${a.id.value}: Apply to all is set but this junction differs")
            }
        }
    }
}

private fun inRange(ms: Long) = ms in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS

private fun Project.checkClipRanges(
    clip: Clip,
    fail: (Violation.Rule, String) -> Unit,
) {
    val id = clip.id.value

    fun bad(what: String) = fail(Violation.Rule.RANGE, "$id: $what")

    val anchored = (clip is OverlayClip && clip.anchor != null) || (clip is AudioClip && clip.anchor != null)
    val (ds, de) = clip.detachedSides
    if ((ds != null || de != null) && (!anchored || (ds != null && de != null))) bad("detached side")
    if (listOfNotNull(ds, de).any { it !in 0..Limits.MAX_MEDIA_MS }) bad("detached side")
    if (!anchored && !inRange(clip.durationMs)) bad("length")
    if (anchored && clip.durationMs !in 0..Limits.MAX_MEDIA_MS) bad("length")
    when (clip) {
        is VideoClip -> {
            if (!inRange(clip.sourceDurationMs)) bad("media length")
            if (clip.trimStartMs < 0 || clip.trimEndMs > clip.sourceDurationMs || clip.trimEndMs - clip.trimStartMs < Limits.MIN_CLIP_MS) {
                bad("trim")
            }
            if (clip.loops !in 1..Limits.MAX_LOOPS) bad("loops")
            if (!clip.volumeDb.isFinite() || clip.volumeDb !in Limits.MIN_VOLUME_DB..Limits.MAX_VOLUME_DB) bad("volume")
            clip.splitGroup?.let { if (!ID_PATTERN.matches(it)) bad("split group") }
        }

        is AudioClip -> {
            if (!inRange(clip.sourceDurationMs)) bad("media length")
            if (clip.trimStartMs < 0 || clip.trimEndMs > clip.sourceDurationMs || clip.trimEndMs - clip.trimStartMs < Limits.MIN_CLIP_MS) {
                bad("trim")
            }
            if (!clip.repeat && clip.durationMs > clip.trimEndMs - clip.trimStartMs) bad("longer than its media")
            if (!clip.volumeDb.isFinite() || clip.volumeDb !in Limits.MIN_VOLUME_DB..Limits.MAX_VOLUME_DB) bad("volume")
            if (clip.fadeInMs < 0 || clip.fadeOutMs < 0 || (clip.durationMs > 0 && clip.fadeInMs + clip.fadeOutMs > clip.durationMs)) {
                bad("fades")
            }
        }

        is OverlayClip -> {
            if (!inRange(clip.sourceDurationMs)) bad("media length")
            if (!clip.repeat && clip.durationMs > clip.sourceDurationMs) bad("longer than its media")
            clip.key?.let { if (!(it.tolerance in 0.0..1.0 && it.softness in 0.0..1.0)) bad("key") }
        }

        is MediaClip -> {
            Unit
        }

        is TextClip -> {
            if (!TextRules.isValidText(clip.text)) {
                fail(Violation.Rule.TEXT, "$id: caption text")
            }
        }

        is CardClip -> {
            clip.text?.let {
                if (!TextRules.isValidText(it)) {
                    fail(Violation.Rule.TEXT, "$id: card text")
                }
            }
        }

        is IntroClip -> {
            // A preset file in the intro's place must be the preset's intro.
            if ((clip.media as? MediaRef.Preset)?.slot.let { it != null && it != PresetSlot.INTRO }) bad("intro file")
        }

        is EndingClip -> {
            if ((clip.media as? MediaRef.Preset)?.slot.let { it != null && it != PresetSlot.ENDING }) bad("ending file")
        }
    }
    if (clip is MediaClip) {
        clip.pip?.let { if (!(it.scale.isFinite() && it.scale in PipPlacement.MIN_SCALE..PipPlacement.MAX_SCALE)) bad("picture size") }
    }
    val style =
        when (clip) {
            is TextClip -> clip.style
            is CardClip -> clip.style
            else -> null
        }
    style?.let { s -> s.resolve(captionDefaults).problems().forEach { fail(Violation.Rule.TEXT, "$id: $it") } }
}

private fun checkAnimations(
    clip: Clip,
    fail: (Violation.Rule, String) -> Unit,
) {
    val a = clip.animations
    val id = clip.id.value
    for (slot in AnimSlot.entries) {
        val anim = a[slot] ?: continue
        if (slot !in anim.kind.slots) fail(Violation.Rule.ANIMATION, "$id: ${anim.kind} is not a $slot animation")
        if (!anim.kind.allowedOn(clip)) fail(Violation.Rule.ANIMATION, "$id: ${anim.kind} is for another kind of item")
        if (slot != AnimSlot.DURING && anim.durationMs !in Anim.MIN_MS..Anim.MAX_MS) fail(Violation.Rule.ANIMATION, "$id: $slot length")
    }
    val ends = (a.start?.durationMs ?: 0) + (a.end?.durationMs ?: 0)
    if (ends > clip.durationMs) fail(Violation.Rule.ANIMATION, "$id: start and end animations are longer than the item")
}
