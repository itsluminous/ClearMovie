package com.itsluminous.clearmovie.domain.model

/**
 * Lengths of the preset files bound on this phone (known after setup, from
 * cm-storage). A null length means that file is not there or is skipped for
 * this video, so its part is left out.
 *
 * [intro], [frame] and [ending] name the file each part plays: the preset's
 * own by default, or a photo-library or copied file the user chose instead
 * for this video only (preset review), with its length in the matching
 * `...Ms` field.
 */
data class PresetMedia(
    val introMs: Long? = null,
    val frameMs: Long? = null,
    val endingMs: Long? = null,
    val intro: MediaRef = MediaRef.Preset(PresetSlot.INTRO),
    val frame: MediaRef = MediaRef.Preset(PresetSlot.FRAME),
    val ending: MediaRef = MediaRef.Preset(PresetSlot.ENDING),
) {
    init {
        require(intro.isFileFor(PresetSlot.INTRO) && frame.isFileFor(PresetSlot.FRAME) && ending.isFileFor(PresetSlot.ENDING)) {
            "A preset part names another part's file"
        }
    }

    private fun MediaRef.isFileFor(slot: PresetSlot) = this !is MediaRef.Preset || this.slot == slot
}

/** New projects (ARCHITECTURE section 6, "Defaults for a new project"). */
object ProjectFactory {
    /**
     * "Start without a preset": the MAIN row plus an empty Text row on top,
     * 16:9 until a shape is chosen. With [askShape] the editor asks for the
     * shape when it first opens the video.
     */
    fun blank(
        id: ProjectId,
        ids: IdSource = RandomIds,
        askShape: Boolean = false,
    ): Project {
        val main = Track(ids.trackId(), TrackType.MAIN)
        val text = Track(ids.trackId(), TrackType.TEXT)
        return Project(id = id, tracks = listOf(text, main), stacking = listOf(main.id, text.id), askShape = askShape)
    }

    /**
     * A project from [preset]'s track template:
     *
     * | Screen order       | Type          | Stacking     |
     * |--------------------|---------------|--------------|
     * | Text 1 (empty)     | TEXT          | top          |
     * | Frame              | OVERLAY       | over MAIN    |
     * | Photos and videos  | MAIN          | bottom       |
     * | Music (frame's)    | AUDIO, MUSIC  | (mixed)      |
     * | Closing message    | AUDIO, VOICE  | (mixed)      |
     *
     * MAIN starts with the intro and ending. The frame and its music follow the
     * frame span (after the intro, to the end, or to the ending when the frame
     * does not cover it); the closing message follows a spoken ending. The
     * preset's shape, caption style, photo length, fit, effects and ducking are
     * copied into the project. A bound file whose length is under
     * [Limits.MIN_CLIP_MS] or over [Limits.MAX_MEDIA_MS] is refused with
     * [IllegalArgumentException].
     */
    fun fromPreset(
        id: ProjectId,
        preset: Preset,
        media: PresetMedia,
        ids: IdSource = RandomIds,
    ): Project {
        for (ms in listOfNotNull(media.introMs, media.frameMs, media.endingMs)) {
            require(ms in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) { "A preset file has an unusable length" }
        }
        val mainClips = mutableListOf<MainClip>()
        if (preset.intro != null && media.introMs != null) mainClips += IntroClip(ids.clipId(), media.introMs, media = media.intro)
        val ending = preset.ending?.takeIf { media.endingMs != null }
        if (ending != null) mainClips += EndingClip(ids.clipId(), media.endingMs!!, ending.kind, media = media.ending)

        val text = Track(ids.trackId(), TrackType.TEXT)
        val main = Track(ids.trackId(), TrackType.MAIN, clips = mainClips)
        val screen = mutableListOf(text, main)
        val stacking = mutableListOf(main.id)

        val frame = preset.frame?.takeIf { media.frameMs != null }
        if (frame != null) {
            val frameMs = media.frameMs!!
            val anchor = SpanAnchor.Frame(frame.startAfterIntro, frame.coverEnding.resolve(ending?.kind))
            val overlay =
                OverlayClip(
                    id = ids.clipId(),
                    media = media.frame,
                    startMs = 0,
                    durationMs = 0,
                    sourceDurationMs = frameMs,
                    key = frame.key,
                    repeat = frame.repeat,
                    anchor = anchor,
                )
            val frameRow = Track(ids.trackId(), TrackType.OVERLAY, clips = listOf(overlay), presetRole = PresetRole.FRAME)
            screen.add(1, frameRow)
            stacking += frameRow.id
            val music =
                AudioClip(
                    id = ids.clipId(),
                    media = media.frame,
                    startMs = 0,
                    durationMs = 0,
                    sourceDurationMs = frameMs,
                    repeat = frame.repeat,
                    volumeDb = frame.music.gainDb,
                    anchor = anchor,
                )
            screen +=
                Track(
                    ids.trackId(),
                    TrackType.AUDIO,
                    clips = listOf(music),
                    muted = !frame.music.enabled,
                    audioRole = AudioRole.MUSIC,
                    presetRole = PresetRole.FRAME_MUSIC,
                )
        }
        if (ending?.kind == EndingKind.AUDIO) {
            val speech =
                AudioClip(
                    id = ids.clipId(),
                    media = media.ending,
                    startMs = 0,
                    durationMs = 0,
                    sourceDurationMs = media.endingMs!!,
                    anchor = SpanAnchor.Ending,
                )
            screen +=
                Track(
                    ids.trackId(),
                    TrackType.AUDIO,
                    clips = listOf(speech),
                    audioRole = AudioRole.VOICE,
                    presetRole = PresetRole.CLOSING_MESSAGE,
                )
        }
        stacking += text.id

        return Project(
            id = id,
            tracks = screen,
            stacking = stacking,
            presetId = preset.id,
            captionDefaults = preset.caption,
            captionDefaultText = preset.defaultText,
            photoDurationMs = preset.photoDurationMs,
            defaultFit = preset.defaultFit,
            effectDefaults = preset.effects,
            ducking = preset.ducking,
            shape = preset.shape,
        ).normalized()
    }
}
