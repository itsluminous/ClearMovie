package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Violation.Rule
import org.junit.Test

/** `validate()` reports every broken invariant (section 6). */
class ValidationTest {
    private val ids = SequentialIds("val")

    private fun good(): Project =
        Fixtures
            .presetProject(ids)
            .addToMain(listOf(Fixtures.photo("a"), Fixtures.video("v")), ids = ids)
            .addText("x", 7000, 2000, ids)

    private fun Project.rules() = validate().map { it.rule }.toSet()

    private fun Project.mapMain(change: (List<Clip>) -> List<Clip>) =
        copy(
            tracks =
                tracks.map {
                    if (it.type ==
                        TrackType.MAIN
                    ) {
                        it.copy(clips = change(it.clips))
                    } else {
                        it
                    }
                },
        )

    private fun Project.mapTrack(
        id: TrackId,
        change: (Track) -> Track,
    ) = copy(tracks = tracks.map { if (it.id == id) change(it) else it })

    @Test
    fun `a sound project is valid`() {
        assertThat(good().validate()).isEmpty()
        assertThat(good().isValid).isTrue()
        assertThat(ProjectFactory.blank(Fixtures.PROJECT_ID, ids).isValid).isTrue()
    }

    @Test
    fun `one MAIN row, unique ids and a complete stacking order`() {
        val p = good()
        assertThat(p.copy(tracks = p.tracks + p.main.copy(id = TrackId("m2"), clips = emptyList())).rules()).contains(Rule.ONE_MAIN)
        assertThat(p.copy(tracks = p.tracks.filterNot { it.type == TrackType.MAIN }).rules()).contains(Rule.ONE_MAIN)
        assertThat(p.copy(tracks = p.tracks + p.tracks.first()).rules()).contains(Rule.UNIQUE_IDS)
        assertThat(p.mapMain { it + Fixtures.photo("a") }.rules()).contains(Rule.UNIQUE_IDS)
        assertThat(p.copy(stacking = p.stacking.drop(1)).rules()).contains(Rule.STACKING)
        assertThat(p.copy(stacking = p.stacking + p.byRole(PresetRole.FRAME_MUSIC).id).rules()).contains(Rule.STACKING)
        assertThat(p.copy(stacking = p.stacking + p.stacking.first()).rules()).contains(Rule.STACKING)
    }

    @Test
    fun `clips only on rows of their kind, intro first and ending last`() {
        val p = good()
        val text = p.tracks.first { it.type == TrackType.TEXT }.id
        assertThat(p.mapTrack(text) { it.copy(clips = it.clips + Fixtures.photo("z")) }.rules()).contains(Rule.CLIP_KIND)
        assertThat(p.mapMain { it + Fixtures.text("t", 0) }.rules()).contains(Rule.CLIP_KIND)
        assertThat(p.mapMain { it.reversed() }.rules()).contains(Rule.CLIP_KIND)
        assertThat(p.mapMain { it + Fixtures.photo("pp", pip = PipPlacement.DEFAULT) }.rules()).contains(Rule.CLIP_KIND)
        val pip = p.addRow(TrackType.VISUAL, id = TrackId("pip"))
        assertThat(pip.mapTrack(TrackId("pip")) { it.copy(clips = listOf(Fixtures.photo("np"))) }.rules()).contains(Rule.CLIP_KIND)
        assertThat(pip.mapTrack(TrackId("pip")) { it.copy(clips = listOf(IntroClip(ClipId("i2"), 1000))) }.rules()).contains(Rule.CLIP_KIND)
    }

    @Test
    fun `timed rows never overlap and MAIN has no start times`() {
        val p = good()
        val text = p.tracks.first { it.type == TrackType.TEXT }.id
        val overlapping = p.mapTrack(text) { it.copy(clips = it.clips + Fixtures.text("y", 8000)) }
        assertThat(overlapping.rules()).contains(Rule.OVERLAP)
        // Overlap hidden behind an empty clip in between is still found.
        val row = TrackId("ov")
        val hidden =
            p.addRow(TrackType.OVERLAY, id = row).mapTrack(row) {
                it.copy(
                    clips =
                        listOf(
                            Fixtures.overlay("o1", 0, 5000),
                            Fixtures.overlay("o2", 1000, 1000).copy(durationMs = 0, anchor = SpanAnchor.Ending),
                            Fixtures.overlay("o3", 3000, 1000),
                        ),
                )
            }
        assertThat(hidden.rules()).contains(Rule.OVERLAP)
        assertThat(
            p.mapTrack(text) { it.copy(clips = listOf(Fixtures.text("q", 5000), Fixtures.text("r", 0))) }.rules(),
        ).contains(Rule.OVERLAP)
        assertThat(p.mapMain { list -> list.map { it.withStartMs(100) } }.rules()).contains(Rule.OVERLAP)
        assertThat(p.mapTrack(text) { it.copy(clips = listOf(Fixtures.text("neg", -5))) }.rules()).contains(Rule.RANGE)
    }

    @Test
    fun `transitions only on MAIN, never last, and fitted`() {
        val p = good()
        val t = Transition(TransitionKind.CROSSFADE, 1000)
        assertThat(p.mapMain { l -> l.map { (it as MainClip).withTransition(t) } }.rules()).contains(Rule.TRANSITION)
        assertThat(
            p
                .mapMain { l ->
                    l.mapIndexed { i, c ->
                        if (i ==
                            1
                        ) {
                            (c as MainClip).withTransition(Transition(TransitionKind.NONE))
                        } else {
                            c
                        }
                    }
                }.rules(),
        ).contains(Rule.TRANSITION)
        assertThat(
            p
                .mapMain { l ->
                    l.mapIndexed { i, c ->
                        if (i ==
                            1
                        ) {
                            (c as MainClip).withTransition(Transition(TransitionKind.WIPE_UP, 2000))
                        } else {
                            c
                        }
                    }
                }.rules(),
        ).contains(Rule.TRANSITION)
        val pip = p.addRow(TrackType.VISUAL, id = TrackId("pip"))
        val withT = Fixtures.photo("pt", pip = PipPlacement.DEFAULT).copy(transitionToNext = t)
        assertThat(pip.mapTrack(TrackId("pip")) { it.copy(clips = listOf(withT)) }.rules()).contains(Rule.TRANSITION)
        assertThat(p.copy(transitionForAll = t).rules()).contains(Rule.TRANSITION)
        assertThat(p.copy(transitionForAll = Transition(TransitionKind.NONE)).rules()).contains(Rule.TRANSITION)
    }

    @Test
    fun `lengths, trims, loops, volumes, fades and placements are in range`() {
        val p = good()
        val v = ClipId("v")

        fun Project.video(change: (VideoClip) -> VideoClip) = mapMain { l -> l.map { if (it.id == v) change(it as VideoClip) else it } }
        assertThat(p.video { it.copy(trimEndMs = 99_000) }.rules()).contains(Rule.RANGE)
        assertThat(p.video { it.copy(trimStartMs = 3990) }.rules()).contains(Rule.RANGE)
        assertThat(p.video { it.copy(loops = 0) }.rules()).contains(Rule.RANGE)
        assertThat(p.video { it.copy(volumeDb = Double.POSITIVE_INFINITY) }.rules()).contains(Rule.RANGE)
        assertThat(p.video { it.copy(splitGroup = "bad group") }.rules()).contains(Rule.RANGE)
        assertThat(p.video { it.copy(sourceDurationMs = 0, trimEndMs = 0) }.rules()).contains(Rule.RANGE)
        assertThat(p.mapMain { l -> l.map { if (it is PhotoClip) it.copy(durationMs = 10) else it } }.rules()).contains(Rule.RANGE)
        assertThat(p.copy(photoDurationMs = 0).rules()).contains(Rule.RANGE)

        val music = p.byRole(PresetRole.FRAME_MUSIC)

        fun Project.audio(change: (AudioClip) -> AudioClip) =
            mapTrack(music.id) { t -> t.copy(clips = t.clips.map { change(it as AudioClip) }) }
        assertThat(p.audio { it.copy(fadeInMs = -1) }.rules()).contains(Rule.RANGE)
        assertThat(p.audio { it.copy(fadeInMs = it.durationMs, fadeOutMs = 1) }.rules()).contains(Rule.RANGE)
        assertThat(p.audio { it.copy(repeat = false, anchor = null, durationMs = 400_000) }.rules()).contains(Rule.RANGE)
        assertThat(p.audio { it.copy(volumeDb = -100.0) }.rules()).contains(Rule.RANGE)
        assertThat(p.audio { it.copy(trimStartMs = 400_000) }.rules()).contains(Rule.RANGE)
        assertThat(p.audio { it.copy(anchor = null, durationMs = 0) }.rules()).contains(Rule.RANGE)
        assertThat(p.audio { it.copy(sourceDurationMs = 0, trimEndMs = 0) }.rules()).contains(Rule.RANGE)

        val frame = p.byRole(PresetRole.FRAME)

        fun Project.over(change: (OverlayClip) -> OverlayClip) =
            mapTrack(frame.id) { t ->
                t.copy(
                    clips =
                        t.clips.map {
                            change(it as OverlayClip)
                        },
                )
            }
        assertThat(p.over { it.copy(repeat = false, durationMs = 999_999) }.rules()).contains(Rule.RANGE)
        assertThat(p.over { it.copy(key = it.key!!.copy(tolerance = 2.0)) }.rules()).contains(Rule.RANGE)
        assertThat(p.over { it.copy(sourceDurationMs = 0) }.rules()).contains(Rule.RANGE)
        assertThat(p.over { it.copy(durationMs = -1) }.rules()).contains(Rule.RANGE)

        val pip = p.addRow(TrackType.VISUAL, id = TrackId("pip"))
        val bad = Fixtures.photo("pz", pip = PipPlacement(scale = 5.0))
        assertThat(pip.mapTrack(TrackId("pip")) { it.copy(clips = listOf(bad)) }.rules()).contains(Rule.RANGE)
    }

    @Test
    fun `animations in their slot, on the right clip, and fitting it`() {
        val p = good()

        fun Project.anim(a: Animations) = mapMain { l -> l.map { if (it.id == ClipId("a")) it.withAnimations(a) else it } }
        assertThat(p.anim(Animations(start = Anim(AnimKind.FADE_OUT))).rules()).contains(Rule.ANIMATION)
        assertThat(p.anim(Animations(start = Anim(AnimKind.POP))).rules()).contains(Rule.ANIMATION)
        assertThat(p.anim(Animations(start = Anim(AnimKind.FADE_IN, 5000))).rules()).contains(Rule.ANIMATION)
        assertThat(p.anim(Animations(start = Anim(AnimKind.FADE_IN, 1500), end = Anim(AnimKind.FADE_OUT, 1600))).rules())
            .contains(Rule.ANIMATION)
        val v =
            p.mapMain { l ->
                l.map {
                    if (it.id ==
                        ClipId("v")
                    ) {
                        it.withAnimations(Animations(during = Anim(AnimKind.KEN_BURNS_IN)))
                    } else {
                        it
                    }
                }
            }
        assertThat(v.rules()).contains(Rule.ANIMATION)
    }

    @Test
    fun `row roles match their type and preset rows appear once`() {
        val p = good()
        val text = p.tracks.first { it.type == TrackType.TEXT }.id
        assertThat(p.mapTrack(text) { it.copy(audioRole = AudioRole.MUSIC) }.rules()).contains(Rule.TRACK_ROLE)
        assertThat(p.mapTrack(text) { it.copy(presetRole = PresetRole.FRAME) }.rules()).contains(Rule.TRACK_ROLE)
        val music = p.byRole(PresetRole.FRAME_MUSIC).id
        assertThat(p.mapTrack(music) { it.copy(audioRole = null) }.rules()).contains(Rule.TRACK_ROLE)
        assertThat(p.mapTrack(music) { it.copy(presetRole = PresetRole.CLOSING_MESSAGE) }.rules()).contains(Rule.TRACK_ROLE)
        assertThat(p.mapTrack(music) { it.copy(audioRole = AudioRole.VOICE) }.rules()).contains(Rule.TRACK_ROLE)
        assertThat(p.byRole(PresetRole.CLOSING_MESSAGE).let { c -> p.mapTrack(c.id) { it.copy(audioRole = AudioRole.MUSIC) } }.rules())
            .contains(Rule.TRACK_ROLE)
    }

    @Test
    fun `names, captions and styles stay within limits`() {
        val p = good()
        assertThat(p.copy(name = "x".repeat(101)).rules()).contains(Rule.TEXT)
        assertThat(p.copy(name = " ").rules()).contains(Rule.TEXT)
        assertThat(p.copy(name = "a\u0000b").rules()).contains(Rule.TEXT)
        val text = p.tracks.first { it.type == TrackType.TEXT }

        fun Project.caption(change: (TextClip) -> TextClip) =
            mapTrack(text.id) { t -> t.copy(clips = t.clips.map { change(it as TextClip) }) }
        assertThat(p.caption { it.copy(text = "y".repeat(501)) }.rules()).contains(Rule.TEXT)
        assertThat(p.caption { it.copy(text = "bell\u0007") }.rules()).contains(Rule.TEXT)
        assertThat(p.caption { it.copy(text = "two\nlines") }.validate()).isEmpty()
        assertThat(p.caption { it.copy(style = CaptionStyleOverride(sizePx = 2)) }.rules()).contains(Rule.TEXT)
        assertThat(p.caption { it.copy(style = CaptionStyleOverride(font = "../x")) }.rules()).contains(Rule.TEXT)
        assertThat(p.caption { it.copy(style = CaptionStyleOverride(box = NormRect(0.5, 0.5, 0.6, 0.1))) }.rules()).contains(Rule.TEXT)
        assertThat(
            p.caption { it.copy(style = CaptionStyleOverride(outline = TextOutline(ArgbColor.BLACK, 999))) }.rules(),
        ).contains(Rule.TEXT)
        assertThat(p.caption { it.copy(style = CaptionStyleOverride(background = TextBackground(ArgbColor.BLACK, -1))) }.rules())
            .contains(Rule.TEXT)
        assertThat(p.copy(captionDefaults = CaptionStyle(sizePx = 1000)).rules()).contains(Rule.TEXT)
        val card = CardClip(ClipId("card"), 3000, ArgbColor.WHITE, text = "z".repeat(600))
        assertThat(p.mapMain { listOf(it.first(), card) + it.drop(1) }.rules()).contains(Rule.TEXT)
    }
}
