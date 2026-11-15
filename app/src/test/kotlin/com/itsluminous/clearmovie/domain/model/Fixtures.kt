package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertWithMessage

/** Small builders for model tests. Ids are readable and deterministic. */
object Fixtures {
    val PROJECT_ID = ProjectId("p1")

    fun media(name: String): MediaRef = MediaRef.Store("content://media/external/images/media/$name")

    fun photo(
        id: String,
        ms: Long = 3000,
        pip: PipPlacement? = null,
    ) = PhotoClip(ClipId(id), media(id), ms, pip = pip)

    fun video(
        id: String,
        ms: Long = 4000,
        pip: PipPlacement? = null,
    ) = VideoClip(ClipId(id), media(id), sourceDurationMs = ms, pip = pip)

    fun text(
        id: String,
        start: Long,
        ms: Long = 2000,
    ) = TextClip(ClipId(id), start, ms, "caption $id")

    fun overlay(
        id: String,
        start: Long,
        ms: Long = 2000,
    ) = OverlayClip(ClipId(id), media(id), start, ms, sourceDurationMs = ms, repeat = false)

    fun audio(
        id: String,
        start: Long,
        ms: Long = 2000,
    ) = AudioClip(ClipId(id), media(id), start, ms, sourceDurationMs = ms)

    /** The bundled generic preset's shape, built in code. */
    val preset: Preset =
        Preset(
            id = PresetId("event-frame"),
            displayNameRes = "preset_event_frame_name",
            intro = IntroSpec,
            ending = EndingSpec(EndingKind.AUDIO),
            frame =
                FrameSpec(
                    key = KeySpec(ArgbColor(0xFF00D922), 0.18, 0.08),
                    contentRect = NormRect(0.0094, 0.0222, 0.9813, 0.7741),
                ),
            effects = EffectDefaults(AnimKind.KEN_BURNS_IN, Transition(TransitionKind.CROSSFADE, 600), AnimKind.FADE_IN),
        )

    val presetMedia = PresetMedia(introMs = 6500, frameMs = 304_000, endingMs = 6000)

    /**
     * The preset project: Text, Frame, MAIN (intro, ending), Music, Closing.
     * Track ids are t1.., clip ids c1.. (see [ids]).
     */
    fun presetProject(ids: IdSource = SequentialIds("x")): Project = ProjectFactory.fromPreset(PROJECT_ID, preset, presetMedia, ids)

    /** A blank project (Text row and MAIN) with [clips] on MAIN. */
    fun blankWith(
        vararg clips: MainClip,
        ids: IdSource = SequentialIds("t"),
    ): Project = ProjectFactory.blank(PROJECT_ID, ids).addToMain(clips.toList(), ids = ids)

    fun Project.trackOfType(
        type: TrackType,
        n: Int = 0,
    ): Track = tracks.filter { it.type == type }[n]

    fun Project.byRole(role: PresetRole): Track = tracks.single { it.presetRole == role }

    fun Project.mainIds(): List<String> = main.clips.map { it.id.value }

    fun Project.assertValid(): Project {
        assertWithMessage("violations").that(validate()).isEmpty()
        return this
    }

    /**
     * A project that uses every clip kind, media reference, anchor and
     * optional field at least once: the source of the golden fixture
     * `fixtures/project/rich-v1.json`.
     */
    fun richProject(): Project {
        val ids = SequentialIds("g")
        val pipRow = TrackId("pip")
        val overlayRow = TrackId("overlay")
        val voiceRow = TrackId("voice")
        val copied = MediaRef.Copied("shared/clip_0001.mp4")
        return presetProject(ids)
            .rename("Family day")
            .addToMain(
                listOf(
                    PhotoClip(ClipId("photo1"), media("photo1"), 4000, rotation = Rotation.R90, fit = Fit.FIT_BLUR),
                    VideoClip(ClipId("video1"), copied, 12_000, trimStartMs = 1000, trimEndMs = 9000, speed = Speed.X1_5, volumeDb = -3.5),
                    CardClip(ClipId("card1"), 3000, ArgbColor.parse("#202040")!!, text = "Thank you"),
                    VideoClip(ClipId("video2"), media("video2"), 5000, fit = Fit.FIT_BARS),
                    VideoClip(ClipId("video3"), media("video3"), 2000, loops = 3),
                ),
                ids = ids,
            ).setTransition(ClipId("photo1"), Transition(TransitionKind.CROSSFADE, 800))
            .setTransition(ClipId("card1"), Transition(TransitionKind.SLIDE_LEFT, 600))
            .setAnimation(ClipId("photo1"), AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_IN, seed = 7))
            .setAnimation(ClipId("video2"), AnimSlot.END, Anim(AnimKind.FADE_OUT, 700))
            .splitClip(ClipId("video2"), 4000, ClipId("video2b"))
            .addCaptionFor(ClipId("photo1"), "Welcome", ids)
            .addText("Line one\nLine two", 12_000, 2500, ids)
            .let { p ->
                val caption =
                    p.tracks
                        .flatMap { it.clips }
                        .first { it is TextClip }
                        .id
                p
                    .setCaptionStyle(caption, CaptionStyleOverride(sizePx = 72, align = TextAlign.START, clearBackground = true))
                    .setAnimation(caption, AnimSlot.START, Anim(AnimKind.POP, 400))
            }.addRow(TrackType.VISUAL, id = pipRow)
            .addToTrack(pipRow, photo("pip1", 2500, PipPlacement(Corner.BOTTOM_LEFT, 0.3)), 8000, ids)
            .addRow(TrackType.OVERLAY, id = overlayRow)
            .addToTrack(
                overlayRow,
                OverlayClip(ClipId("sparkle"), MediaRef.Copied("sparkle.mp4"), 0, 3000, 1500, KeySpec(ArgbColor(0xFF00FF00), 0.2, 0.1)),
                10_000,
                ids,
            ).addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
            .addToTrack(voiceRow, AudioClip(ClipId("hello"), media("hello"), 0, 2000, 2500, fadeInMs = 200, fadeOutMs = 300), 9000, ids)
            .sendBackward(pipRow)
            .moveRow(voiceRow, 0)
            .assertValid()
    }
}
