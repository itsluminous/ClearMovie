package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import org.junit.Assert.assertThrows
import org.junit.Test

/** Trim, split, join, speed, loop, photo duration, rotate, fit, volume, text and animations. */
class ClipEditsTest {
    private val ids = SequentialIds("e")
    private val a = ClipId("a")
    private val v = ClipId("v")

    private fun project() = Fixtures.blankWith(Fixtures.photo("a", 3000), Fixtures.video("v", 10_000), ids = ids)

    private fun Project.video(id: ClipId = v) = requireClip(id) as VideoClip

    @Test
    fun `trim, speed and loop set a video's length`() {
        val p = project()
        val trimmed = p.trimVideo(v, 2000, 6000).assertValid()
        assertThat(trimmed.video().durationMs).isEqualTo(4000)
        assertThat(trimmed.setSpeed(v, Speed.X2).video().durationMs).isEqualTo(2000)
        assertThat(trimmed.setSpeed(v, Speed.X0_25).video().durationMs).isEqualTo(16_000)
        assertThat(trimmed.setSpeed(v, Speed.X1_5).video().durationMs).isEqualTo(2666)
        assertThat(trimmed.setLoops(v, 3).video().durationMs).isEqualTo(12_000)
        assertThat(trimmed.setLoops(v, 3).totalDurationMs()).isEqualTo(15_000)
        assertThrows(IllegalArgumentException::class.java) { p.trimVideo(v, 5000, 5050) }
        assertThrows(IllegalArgumentException::class.java) { p.trimVideo(v, -1, 5000) }
        assertThrows(IllegalArgumentException::class.java) { p.trimVideo(v, 0, 10_001) }
        assertThrows(IllegalArgumentException::class.java) { p.trimVideo(a, 0, 1000) }
        assertThrows(IllegalArgumentException::class.java) { p.setLoops(v, 0) }
        assertThrows(IllegalArgumentException::class.java) { p.setLoops(v, Limits.MAX_LOOPS + 1) }
        assertThrows(IllegalArgumentException::class.java) { p.setSpeed(a, Speed.X2) }
    }

    @Test
    fun `speed fractions convert both ways`() {
        assertThat(Speed.X4.timelineMs(8000)).isEqualTo(2000)
        assertThat(Speed.X0_5.sourceMs(2000)).isEqualTo(1000)
        assertThat(Speed.entries.map { it.numerator.toDouble() / it.denominator }).containsExactly(0.25, 0.5, 1.0, 1.5, 2.0, 4.0).inOrder()
    }

    @Test
    fun `split makes two adjacent halves that join back into the original`() {
        val p = project().trimVideo(v, 1000, 9000).setSpeed(v, Speed.X2)
        // 8 s of media at 2x is 4 s; split 1 s in, which is 2 s of media.
        val split = p.splitClip(v, 1000, ClipId("v2")).assertValid()
        val first = split.video()
        val second = split.video(ClipId("v2"))
        assertThat(first.trimStartMs to first.trimEndMs).isEqualTo(1000L to 3000L)
        assertThat(second.trimStartMs to second.trimEndMs).isEqualTo(3000L to 9000L)
        assertThat(first.splitGroup).isEqualTo("v")
        assertThat(second.splitGroup).isEqualTo("v")
        assertThat(split.canJoin(v, ClipId("v2"))).isTrue()
        assertThat(split.canJoin(ClipId("v2"), v)).isFalse()
        // Joining gives back the original clip, now marked with its split group.
        val original = p.video().copy(splitGroup = "v")
        assertThat(split.joinClips(v, ClipId("v2")).video()).isEqualTo(original)
        assertThat(split.joinClips(v, ClipId("v2")).mainIds()).containsExactly("a", "v").inOrder()

        assertThrows(IllegalArgumentException::class.java) { p.splitClip(v, 50, ClipId("x")) }
        assertThrows(IllegalArgumentException::class.java) { p.splitClip(v, 3950, ClipId("x")) }
        assertThrows(IllegalArgumentException::class.java) { p.splitClip(a, 1000, ClipId("x")) }
        assertThrows(IllegalArgumentException::class.java) { p.splitClip(v, 1000, a) }
        assertThrows(IllegalArgumentException::class.java) { p.setLoops(v, 2).splitClip(v, 1000, ClipId("x")) }
    }

    @Test
    fun `join is offered only for continuous halves of one split`() {
        val split = project().splitClip(v, 4000, ClipId("v2"))
        // Not continuous once one half is trimmed.
        val gap = split.trimVideo(ClipId("v2"), 5000, 10_000)
        assertThat(gap.canJoin(v, ClipId("v2"))).isFalse()
        // Different speeds.
        assertThat(split.setSpeed(ClipId("v2"), Speed.X2).canJoin(v, ClipId("v2"))).isFalse()
        // Not neighbours any more.
        val apart = split.moveMainClip(a, 2)
        assertThat(apart.mainIds()).containsExactly("v", "v2", "a").inOrder()
        assertThat(apart.canJoin(v, ClipId("v2"))).isTrue()
        assertThat(split.moveMainClip(ClipId("v2"), 0).canJoin(v, ClipId("v2"))).isFalse()
        // Two separate videos of the same file never join.
        val two = Fixtures.blankWith(Fixtures.video("x"), Fixtures.video("y"), ids = ids)
        assertThat(two.canJoin(ClipId("x"), ClipId("y"))).isFalse()
        assertThat(split.canJoin(a, v)).isFalse()
        assertThat(split.canJoin(ClipId("nope"), v)).isFalse()
        assertThrows(IllegalArgumentException::class.java) { gap.joinClips(v, ClipId("v2")) }
    }

    @Test
    fun `split keeps the start animation first and the end animation second`() {
        val p =
            project()
                .setAnimation(v, AnimSlot.START, Anim(AnimKind.FADE_IN))
                .setAnimation(v, AnimSlot.END, Anim(AnimKind.FADE_OUT))
        val split = p.splitClip(v, 5000, ClipId("v2"))
        assertThat(split.video().animations).isEqualTo(Animations(start = Anim(AnimKind.FADE_IN)))
        assertThat(split.video(ClipId("v2")).animations).isEqualTo(Animations(end = Anim(AnimKind.FADE_OUT)))
        assertThat(split.joinClips(v, ClipId("v2")).video().animations).isEqualTo(p.video().animations)
    }

    @Test
    fun `split and join on a picture-in-picture row keep the halves adjacent in time`() {
        val row = TrackId("pip")
        val p =
            project()
                .addRow(TrackType.VISUAL, id = row)
                .addToTrack(row, Fixtures.video("pv", 6000, PipPlacement.DEFAULT), 1000, ids)
        val split = p.splitClip(ClipId("pv"), 2000, ClipId("pv2")).assertValid()
        assertThat(split.requireClip(ClipId("pv2")).startMs).isEqualTo(3000)
        assertThat(split.canJoin(ClipId("pv"), ClipId("pv2"))).isTrue()
        assertThat(split.joinClips(ClipId("pv"), ClipId("pv2")).requireClip(ClipId("pv")).durationMs).isEqualTo(6000)
    }

    @Test
    fun `photo duration, rotate, fit and picture placement`() {
        val p = project()
        assertThat(p.setPhotoDuration(a, 5000).requireClip(a).durationMs).isEqualTo(5000)
        assertThrows(IllegalArgumentException::class.java) { p.setPhotoDuration(a, 50) }
        assertThrows(IllegalArgumentException::class.java) { p.setPhotoDuration(v, 5000) }
        val rotated = generateSequence(p) { it.rotate(a) }.take(5).map { (it.requireClip(a) as PhotoClip).rotation }.toList()
        assertThat(rotated).containsExactly(Rotation.R0, Rotation.R90, Rotation.R180, Rotation.R270, Rotation.R0).inOrder()
        assertThat((p.setFit(v, Fit.FIT_BLUR).requireClip(v) as VideoClip).fit).isEqualTo(Fit.FIT_BLUR)
        assertThat((p.rotate(v).requireClip(v) as VideoClip).rotation).isEqualTo(Rotation.R90)
        // MAIN items have no placement; PiP items do.
        assertThrows(IllegalArgumentException::class.java) { p.setPip(a, PipPlacement(Corner.BOTTOM_LEFT, 0.5)) }
        val row = TrackId("pip")
        val pip = p.addRow(TrackType.VISUAL, id = row).addToTrack(row, Fixtures.photo("pp", 2000, PipPlacement.DEFAULT), 0, ids)
        val moved = pip.setPip(ClipId("pp"), PipPlacement(Corner.BOTTOM_LEFT, 0.5)).assertValid()
        assertThat((moved.requireClip(ClipId("pp")) as PhotoClip).pip).isEqualTo(PipPlacement(Corner.BOTTOM_LEFT, 0.5))
        assertThrows(IllegalArgumentException::class.java) { pip.setPip(ClipId("pp"), PipPlacement(scale = 0.05)) }
    }

    @Test
    fun `cards have a duration and text`() {
        val card = CardClip(ClipId("k"), 3000, ArgbColor.BLACK)
        val p = project().addToMain(listOf(card), ids = ids)
        assertThat(p.setPhotoDuration(card.id, 4000).requireClip(card.id).durationMs).isEqualTo(4000)
        assertThat((p.setText(card.id, "Welcome").requireClip(card.id) as CardClip).text).isEqualTo("Welcome")
        val styled = p.setCaptionStyle(card.id, CaptionStyleOverride(sizePx = 120)).assertValid()
        assertThat((styled.requireClip(card.id) as CardClip).style.sizePx).isEqualTo(120)
        assertThat((styled.resetCaptionStyle(card.id).requireClip(card.id) as CardClip).style.isEmpty).isTrue()
    }

    @Test
    fun `volume and fades`() {
        val p = Fixtures.presetProject().addToMain(listOf(Fixtures.video("v")), ids = ids)
        assertThat((p.setVolume(v, -6.0).requireClip(v) as VideoClip).volumeDb).isEqualTo(-6.0)
        val music =
            p
                .byRole(PresetRole.FRAME_MUSIC)
                .clips
                .single()
                .id
        assertThat((p.setVolume(music, -20.0).requireClip(music) as AudioClip).volumeDb).isEqualTo(-20.0)
        assertThrows(IllegalArgumentException::class.java) { p.setVolume(v, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { p.setVolume(v, 30.0) }
        assertThrows(IllegalArgumentException::class.java) {
            p.setVolume(
                p.main.clips
                    .first()
                    .id,
                0.0,
            )
        }
        val faded = p.setAudioFades(music, 2000, 2000).assertValid().requireClip(music) as AudioClip
        assertThat(faded.fadeInMs to faded.fadeOutMs).isEqualTo(2000L to 2000L)
        assertThrows(IllegalArgumentException::class.java) { p.setAudioFades(music, 999_999, 0) }
        assertThrows(IllegalArgumentException::class.java) { p.setAudioFades(music, -1, 0) }
    }

    @Test
    fun `trimming a sound by hand detaches it from the frame`() {
        val p = Fixtures.presetProject()
        val music = p.byRole(PresetRole.FRAME_MUSIC).clips.single() as AudioClip
        assertThat(music.anchor).isNotNull()
        val trimmed = p.trimAudio(music.id, 1000, 5000).assertValid().requireClip(music.id) as AudioClip
        assertThat(trimmed.anchor).isNull()
        assertThat(trimmed.trimStartMs to trimmed.trimEndMs).isEqualTo(1000L to 5000L)
        // It repeats, so its length on the timeline stays.
        assertThat(trimmed.durationMs).isEqualTo(music.durationMs)

        val voice = p.byRole(PresetRole.CLOSING_MESSAGE).clips.single() as AudioClip
        val shorter = p.trimAudio(voice.id, 0, 3000).requireClip(voice.id) as AudioClip
        assertThat(shorter.durationMs).isEqualTo(3000)
        assertThrows(IllegalArgumentException::class.java) { p.trimAudio(voice.id, 0, 99_000) }
        assertThrows(IllegalArgumentException::class.java) { p.trimAudio(a, 0, 1000) }
    }

    @Test
    fun `captions take the preset style until the user overrides it`() {
        var p = project().addText("hi", 0, 2000, ids)
        val id =
            p.tracks
                .flatMap { it.clips }
                .single { it is TextClip }
                .id
        val clip = p.requireClip(id) as TextClip
        assertThat(clip.style.resolve(p.captionDefaults)).isEqualTo(p.captionDefaults)

        val override = CaptionStyleOverride(sizePx = 90, color = ArgbColor.parse("#FFEE00"), clearBackground = true)
        p = p.setCaptionStyle(id, override).assertValid()
        val resolved = (p.requireClip(id) as TextClip).style.resolve(p.captionDefaults)
        assertThat(resolved.sizePx).isEqualTo(90)
        assertThat(resolved.color.toString()).isEqualTo("#FFEE00")
        assertThat(resolved.background).isNull()
        assertThat(resolved.box).isEqualTo(p.captionDefaults.box)
        assertThat(resolved.font).isEqualTo(p.captionDefaults.font)
        assertThat(CaptionStyleOverride(clearOutline = true).resolve(p.captionDefaults).outline).isNull()

        p = p.resetCaptionStyle(id)
        assertThat((p.requireClip(id) as TextClip).style).isEqualTo(CaptionStyleOverride.NONE)
        p = p.setText(id, "new").setTextTiming(id, 1000, 4000).assertValid()
        assertThat((p.requireClip(id) as TextClip).text).isEqualTo("new")
        assertThat(p.requireClip(id).startMs to p.requireClip(id).endMs).isEqualTo(1000L to 5000L)
        assertThrows(IllegalArgumentException::class.java) { p.setText(a, "x") }
        assertThrows(IllegalArgumentException::class.java) { p.setCaptionStyle(a, CaptionStyleOverride.NONE) }
        assertThrows(IllegalArgumentException::class.java) { p.setTextTiming(id, 0, 10) }
    }

    @Test
    fun `a change that would overlap a neighbour on a timed row is refused`() {
        var p = project().addText("one", 0, 2000, ids)
        val row =
            p
                .trackOf(
                    p.tracks
                        .flatMap { it.clips }
                        .single { it is TextClip }
                        .id,
                )!!
                .id
        p = p.addToTrack(row, Fixtures.text("two", 3000), 3000, ids)
        val one =
            p
                .requireTrack(row)
                .clips
                .first()
                .id
        assertThrows(IllegalArgumentException::class.java) { p.setTextTiming(one, 0, 3500) }
        assertThrows(IllegalArgumentException::class.java) { p.setTextTiming(one, -10, 1000) }
    }

    @Test
    fun `moveClip moves in time, between rows, and to a new row when busy`() {
        var p = project().addText("one", 0, 2000, ids).addRow(TrackType.TEXT, id = TrackId("other"))
        val one =
            p.tracks
                .flatMap { it.clips }
                .single { it is TextClip }
                .id
        val home = p.trackOf(one)!!.id
        // Later on the same row.
        val later = p.moveClip(one, 4000, ids = ids).assertValid()
        assertThat(later.requireClip(one).startMs).isEqualTo(4000)
        assertThat(later.trackOf(one)!!.id).isEqualTo(home)
        // To the other row: the old row is left empty and removed.
        val other = p.moveClip(one, 0, TrackId("other"), ids).assertValid()
        assertThat(other.trackOf(one)!!.id).isEqualTo(TrackId("other"))
        assertThat(other.track(home)).isNull()
        // Busy target: a new row of that type.
        p = p.addToTrack(TrackId("other"), Fixtures.text("block", 0), 0, ids)
        val busy = p.moveClip(one, 500, TrackId("other"), ids).assertValid()
        val landed = busy.trackOf(one)!!
        assertThat(landed.id).isNoneOf(home, TrackId("other"))
        assertThat(landed.type).isEqualTo(TrackType.TEXT)
        assertThrows(IllegalArgumentException::class.java) { p.moveClip(a, 0, ids = ids) }
        assertThrows(IllegalArgumentException::class.java) { p.moveClip(one, 0, p.main.id, ids) }
    }

    @Test
    fun `moving the frame by hand detaches it, and sound moves only between rows of its role`() {
        val p = Fixtures.presetProject().addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("voice2"))
        val frame = p.byRole(PresetRole.FRAME)
        val moved = p.moveClip(frame.clips.single().id, 100, ids = ids).assertValid()
        assertThat((moved.requireClip(frame.clips.single().id) as OverlayClip).anchor).isNull()
        assertThat(moved.requireClip(frame.clips.single().id).startMs).isEqualTo(100)
        val music =
            p
                .byRole(PresetRole.FRAME_MUSIC)
                .clips
                .single()
                .id
        assertThrows(IllegalArgumentException::class.java) { p.moveClip(music, 0, TrackId("voice2"), ids) }
    }

    @Test
    fun `animations go in their slot, on the right kind of clip, and fit the clip`() {
        val p = project().addText("t", 0, 3000, ids)
        val text =
            p.tracks
                .flatMap { it.clips }
                .single { it is TextClip }
                .id
        assertThat(
            p
                .setAnimation(a, AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_IN, seed = 3))
                .requireClip(a)
                .animations.during!!
                .seed,
        ).isEqualTo(3)
        assertThat(
            p
                .setAnimation(text, AnimSlot.START, Anim(AnimKind.POP))
                .requireClip(text)
                .animations.start!!
                .kind,
        ).isEqualTo(AnimKind.POP)
        assertThat(p.setAnimation(text, AnimSlot.DURING, Anim(AnimKind.PULSE)).assertValid()).isNotNull()
        // Wrong slot, wrong clip kind, wrong length.
        assertThrows(IllegalArgumentException::class.java) { p.setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_OUT)) }
        assertThrows(IllegalArgumentException::class.java) { p.setAnimation(a, AnimSlot.START, Anim(AnimKind.POP)) }
        assertThrows(IllegalArgumentException::class.java) { p.setAnimation(v, AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_IN)) }
        assertThrows(IllegalArgumentException::class.java) { p.setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_IN, 100)) }
        // Sounds have no animations.
        val music =
            Fixtures
                .presetProject()
                .byRole(PresetRole.FRAME_MUSIC)
                .clips
                .single()
                .id
        assertThrows(IllegalArgumentException::class.java) {
            Fixtures.presetProject().setAnimation(music, AnimSlot.START, Anim(AnimKind.FADE_IN))
        }
        // Clearing.
        val set = p.setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_IN))
        assertThat(
            set
                .setAnimation(a, AnimSlot.START, null)
                .requireClip(a)
                .animations.isEmpty,
        ).isTrue()
    }

    @Test
    fun `start and end animations shrink when the clip gets shorter`() {
        val p =
            project()
                .setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_IN, 1500))
                .setAnimation(a, AnimSlot.END, Anim(AnimKind.ZOOM_OUT, 1500))
                .assertValid()
        val short =
            p
                .setPhotoDuration(a, 1000)
                .assertValid()
                .requireClip(a)
                .animations
        assertThat(short.start!!.durationMs).isEqualTo(500)
        assertThat(short.end!!.durationMs).isEqualTo(500)
        // Too short for the minimum on both: they go.
        val tiny =
            p
                .setPhotoDuration(a, 300)
                .assertValid()
                .requireClip(a)
                .animations
        assertThat(tiny.start).isNull()
        assertThat(tiny.end).isNull()
    }

    @Test
    fun `rename names a draft and null makes it a draft again`() {
        val p = project()
        assertThat(p.rename("  Birthday  ").name).isEqualTo("Birthday")
        assertThat(p.rename("x").rename(null).name).isNull()
        assertThrows(IllegalArgumentException::class.java) { p.rename("   ") }
    }

    @Test
    fun `operations on a clip that is not there are refused`() {
        val p = project()
        val nope = ClipId("nope")
        val ops: List<(Project) -> Project> =
            listOf(
                { it.deleteClip(nope) },
                { it.rotate(nope) },
                { it.setFit(nope, Fit.FIT_BARS) },
                { it.setSpeed(nope, Speed.X2) },
                { it.splitClip(nope, 1000, ClipId("n2")) },
                { it.setAnimation(nope, AnimSlot.START, null) },
            )
        ops.forEach { op -> assertThrows(IllegalArgumentException::class.java) { op(p) } }
    }
}
