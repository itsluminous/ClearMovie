package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import org.junit.Assert.assertThrows
import org.junit.Test

/** Corner cases: spans that shrink, slowed clips, rounding, stacking edge positions, hostile text. */
class EdgeCasesTest {
    private val ids = SequentialIds("x")
    private val crossfade = Transition(TransitionKind.CROSSFADE, 1000)

    @Test
    fun `the frame music's fades shrink with it when the video gets shorter`() {
        val p = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a", 3000)), ids = ids)
        val music =
            p
                .byRole(PresetRole.FRAME_MUSIC)
                .clips
                .single()
                .id
        // The frame span is 9 s: after the intro, through the spoken ending.
        val faded = p.setAudioFades(music, 4500, 4500).assertValid()
        val editor = ProjectEditor(faded, ids)
        assertThat(editor.edit { deleteClip(ClipId("a")) }).isEqualTo(EditResult.Applied)
        val clip = editor.project.requireClip(music) as AudioClip
        assertThat(clip.durationMs).isEqualTo(6000)
        assertThat(clip.fadeInMs + clip.fadeOutMs).isAtMost(clip.durationMs)
        assertThat(clip.fadeInMs).isEqualTo(3000)
    }

    @Test
    fun `split checks both halves in media time, and speed or loops can't make a clip too short`() {
        val v = ClipId("v")
        val slow = Fixtures.blankWith(Fixtures.video("v", 10_000), ids = ids).setSpeed(v, Speed.X0_25)
        // 200 ms of timeline at 0.25x is only 50 ms of media.
        assertThrows(IllegalArgumentException::class.java) { slow.splitClip(v, 200, ClipId("v2")) }
        assertThat(slow.splitClip(v, 400, ClipId("v2")).validate()).isEmpty()
        val short = Fixtures.blankWith(Fixtures.video("v", 10_000), ids = ids).trimVideo(v, 0, 300)
        assertThrows(IllegalArgumentException::class.java) { short.setSpeed(v, Speed.X4) }
        val long = Fixtures.blankWith(Fixtures.video("v", Limits.MAX_MEDIA_MS), ids = ids)
        assertThrows(IllegalArgumentException::class.java) { long.setSpeed(v, Speed.X0_25) }
        assertThrows(IllegalArgumentException::class.java) { long.setLoops(v, 2) }
    }

    @Test
    fun `a split at an odd speed keeps the total length exactly`() {
        val v = ClipId("v")
        val p = Fixtures.blankWith(Fixtures.video("v", 9000), ids = ids).setSpeed(v, Speed.X1_5)
        assertThat(p.totalDurationMs()).isEqualTo(6000)
        for (at in listOf(1001L, 1234L, 3333L, 4999L)) {
            val split = p.splitClip(v, at, ClipId("v2")).assertValid()
            assertThat(split.totalDurationMs()).isEqualTo(6000)
            assertThat(split.joinClips(v, ClipId("v2")).totalDurationMs()).isEqualTo(6000)
        }
    }

    @Test
    fun `Apply to all leaves the cut between split halves without a transition`() {
        val p =
            Fixtures
                .blankWith(Fixtures.photo("a", 4000), Fixtures.video("b", 4000), Fixtures.photo("c", 4000), ids = ids)
                .applyTransitionToAll(crossfade)
                .splitClip(ClipId("b"), 2000, ClipId("b2"))
                .assertValid()
        assertThat((p.requireClip(ClipId("b")) as MainClip).transitionToNext).isNull()
        assertThat((p.requireClip(ClipId("b2")) as MainClip).transitionToNext).isEqualTo(crossfade)
        assertThat((p.requireClip(ClipId("a")) as MainClip).transitionToNext).isEqualTo(crossfade)
        // Once one half is trimmed the cut is a real junction again and gets the transition.
        val trimmed = p.trimVideo(ClipId("b2"), 2500, 4000).assertValid()
        assertThat((trimmed.requireClip(ClipId("b")) as MainClip).transitionToNext).isNotNull()
    }

    @Test
    fun `a new overlay never lands under MAIN, even when a text row was sent there`() {
        val p = Fixtures.presetProject()
        val text = p.tracks.first { it.type == TrackType.TEXT }.id
        val q = p.sendToBottom(text).addRow(TrackType.OVERLAY, id = TrackId("ov")).assertValid()
        assertThat(q.isBelowMain(TrackId("ov"))).isFalse()
    }

    @Test
    fun `the frame arrives with the first item when the intro has a transition`() {
        val p = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a", 3000)), ids = ids)
        val t =
            p
                .setTransition(
                    p.main.clips
                        .first()
                        .id,
                    crossfade,
                ).assertValid()
        val frame = t.byRole(PresetRole.FRAME).clips.single()
        assertThat(t.mainSlot(ClipId("a"))!!.startMs).isEqualTo(5500)
        assertThat(frame.startMs).isEqualTo(5500)
        assertThat(t.contentStartMs()).isEqualTo(5500)
        assertThat(t.introEndMs()).isEqualTo(6500)
    }

    @Test
    fun `animations fit by length, not by the order they were set`() {
        val a = ClipId("a")
        val base = Fixtures.blankWith(Fixtures.photo("a", 3000), ids = ids)
        val startFirst =
            base
                .setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_IN, 500))
                .setAnimation(a, AnimSlot.END, Anim(AnimKind.FADE_OUT, 200))
                .setPhotoDuration(a, 350)
        val endFirst =
            base
                .setAnimation(a, AnimSlot.END, Anim(AnimKind.FADE_OUT, 200))
                .setPhotoDuration(a, 350)
                .setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_IN, 500))
        assertThat(startFirst.requireClip(a).animations).isEqualTo(endFirst.requireClip(a).animations)
        startFirst.assertValid()
        // At 700 ms the shorter one keeps its length and the longer one gets the rest.
        val roomy =
            base
                .setAnimation(a, AnimSlot.START, Anim(AnimKind.FADE_IN, 500))
                .setAnimation(a, AnimSlot.END, Anim(AnimKind.FADE_OUT, 200))
                .setPhotoDuration(a, 600)
                .requireClip(a)
                .animations
        assertThat(roomy.start!!.durationMs to roomy.end!!.durationMs).isEqualTo(400L to 200L)
    }

    @Test
    fun `hostile text is refused in names and captions, but Hindi joiners are fine`() {
        val p = Fixtures.blankWith(Fixtures.photo("a"), ids = ids)
        for (bad in listOf("\u202Eevil", "a\u2066b", "zero\u200Bwidth", "\uFEFFbom", "\u200B", "!!!", "line\nbreak")) {
            assertThrows(IllegalArgumentException::class.java) { p.rename(bad) }
            assertThat(p.copy(name = bad).validate().map { it.rule }).contains(Violation.Rule.TEXT)
        }
        // Devanagari with a zero-width joiner and non-joiner.
        val hindi = "क्\u200Dष \u200Cनमस्ते"
        assertThat(p.rename(hindi).name).isEqualTo(hindi)
        assertThat(p.addText(hindi, 0, 1000, ids).validate()).isEmpty()
        assertThat(p.addText("evil\u202E", 0, 1000, ids).validate().map { it.rule }).contains(Violation.Rule.TEXT)
    }

    @Test
    fun `a closing message left without an ending goes with its row`() {
        val p = Fixtures.presetProject()
        val voice = p.byRole(PresetRole.CLOSING_MESSAGE)
        // A damaged state: the ending is gone but the closing message is still there.
        val damaged = p.copy(tracks = p.tracks.map { if (it.type == TrackType.MAIN) it.copy(clips = it.clips.dropLast(1)) else it })
        assertThat(damaged.validate().map { it.rule }).contains(Violation.Rule.ANCHOR)
        val repaired = damaged.normalized().assertValid()
        assertThat(repaired.track(voice.id)).isNull()
    }

    @Test
    fun `a preset file with an unusable length is refused`() {
        for (media in listOf(PresetMedia(introMs = 50), PresetMedia(frameMs = 0), PresetMedia(endingMs = Limits.MAX_MEDIA_MS + 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectFactory.fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, media, ids)
            }
        }
    }

    @Test
    fun `insert next to the preset frame tries the frame row first`() {
        val p = Fixtures.presetProject()
        val frameRow = p.byRole(PresetRole.FRAME)
        val frame = frameRow.clips.single()
        // Before the frame (which starts after the intro) the frame row is free.
        val before = p.insertAt(frameRow.id, frame.id, Side.BEFORE, Fixtures.overlay("ov", 0, 2000), ids).assertValid()
        assertThat(before.trackOf(ClipId("ov"))!!.id).isEqualTo(frameRow.id)
        assertThat(before.requireClip(ClipId("ov")).endMs).isEqualTo(frame.startMs)
        // After the frame there is no video left, but the clip is still placed at the boundary.
        val after = p.insertAt(frameRow.id, frame.id, Side.AFTER, Fixtures.overlay("ov2", 0, 2000), ids).assertValid()
        assertThat(after.requireClip(ClipId("ov2")).startMs).isEqualTo(frame.endMs)
    }

    @Test
    fun `settings copied from a preset are range-checked`() {
        val p = Fixtures.presetProject()
        assertThat(p.copy(ducking = DuckingSpec(attackMs = -5)).validate().map { it.rule }).contains(Violation.Rule.SETTINGS)
        assertThat(p.copy(ducking = DuckingSpec(musicUnderClipDb = 6.0)).validate().map { it.rule }).contains(Violation.Rule.SETTINGS)
        assertThat(p.copy(ducking = DuckingSpec(speechDuckDb = Double.NaN)).validate().map { it.rule }).contains(Violation.Rule.SETTINGS)
        val badEffects =
            listOf(
                EffectDefaults(mainTransition = Transition(TransitionKind.NONE)),
                EffectDefaults(mainTransition = Transition(TransitionKind.CROSSFADE, 50)),
                EffectDefaults(photoDuring = AnimKind.PULSE),
                EffectDefaults(textStart = AnimKind.KEN_BURNS_IN),
            )
        for (e in badEffects) assertThat(p.copy(effectDefaults = e).validate().map { it.rule }).contains(Violation.Rule.SETTINGS)
    }

    @Test
    fun `a frame that does not follow MAIN is refused, not quietly moved`() {
        val p = Fixtures.presetProject()
        val frame = p.byRole(PresetRole.FRAME)
        val moved = p.copy(tracks = p.tracks.map { t -> if (t.id == frame.id) t.copy(clips = t.clips.map { it.withStartMs(0) }) else t })
        assertThat(moved.validate().map { it.rule }).contains(Violation.Rule.ANCHOR)
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor(moved) }
    }

    @Test
    fun `MAIN emptied of every item stays a normal, empty row`() {
        val p = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a")), ids = ids)
        val empty =
            p.main.clips
                .fold(p) { acc, c -> acc.deleteClip(c.id) }
                .assertValid()
        assertThat(empty.main.enabled).isTrue()
        assertThat(empty.mainIds()).isEmpty()
        assertThat(empty.totalDurationMs()).isEqualTo(0)
        // New photos can be added again.
        assertThat(empty.addToMain(listOf(Fixtures.photo("b")), ids = ids).mainIds()).containsExactly("b")
    }
}
