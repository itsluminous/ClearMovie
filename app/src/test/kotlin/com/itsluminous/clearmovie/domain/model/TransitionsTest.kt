package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import org.junit.Assert.assertThrows
import org.junit.Test

/** Transition ownership and timing, and the time mapping (section 6.4). */
class TransitionsTest {
    private val ids = SequentialIds("tr")
    private val crossfade = Transition(TransitionKind.CROSSFADE, 1000)

    private fun abc(
        a: Long = 4000,
        b: Long = 4000,
        c: Long = 4000,
    ) = Fixtures.blankWith(Fixtures.photo("a", a), Fixtures.video("b", b), Fixtures.photo("c", c), ids = ids)

    private fun Project.transitionOf(id: String) = (requireClip(ClipId(id)) as MainClip).transitionToNext

    @Test
    fun `an overlap shortens the total by the transition's length`() {
        val p = abc()
        assertThat(p.totalDurationMs()).isEqualTo(12_000)
        val one = p.setTransition(ClipId("a"), crossfade).assertValid()
        assertThat(one.totalDurationMs()).isEqualTo(11_000)
        assertThat(one.mainSlot(ClipId("b"))!!.startMs).isEqualTo(3000)
        assertThat(one.mainSlot(ClipId("a"))!!.overlapToNextMs).isEqualTo(1000)
        val two = one.setTransition(ClipId("b"), Transition(TransitionKind.WIPE_LEFT, 500)).assertValid()
        assertThat(two.totalDurationMs()).isEqualTo(10_500)
        assertThat(two.mainSlot(ClipId("c"))!!.startMs).isEqualTo(6500)
    }

    @Test
    fun `the transition belongs to the leading clip`() {
        val p = abc().setTransition(ClipId("a"), crossfade)
        assertThat(p.transitionOf("a")).isEqualTo(crossfade)
        assertThat(p.transitionOf("b")).isNull()
        assertThrows(IllegalArgumentException::class.java) { p.setTransition(ClipId("c"), crossfade) }
        assertThrows(IllegalArgumentException::class.java) { p.setTransition(ClipId("nope"), crossfade) }
    }

    @Test
    fun `a transition is capped at half the shorter clip`() {
        val p = abc(a = 4000, b = 1500).setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 2000)).assertValid()
        assertThat(p.transitionOf("a")!!.durationMs).isEqualTo(750)
        assertThat(Timeline.maxTransitionMs(10_000, 10_000)).isEqualTo(Transition.MAX_MS)
        // Too short for even the minimum: no transition at all.
        val tiny = abc(a = 4000, b = 500).setTransition(ClipId("a"), crossfade)
        assertThat(tiny.transitionOf("a")).isNull()
    }

    @Test
    fun `a transition is clamped when a clip is trimmed shorter`() {
        val p = abc(b = 4000).setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1800))
        assertThat(p.transitionOf("a")!!.durationMs).isEqualTo(1800)
        val trimmed = p.trimVideo(ClipId("b"), 0, 2000).assertValid()
        assertThat(trimmed.transitionOf("a")!!.durationMs).isEqualTo(1000)
        assertThat(trimmed.totalDurationMs()).isEqualTo(4000 + 2000 + 4000 - 1000)
        // Trimmed below twice the minimum: the transition goes.
        val gone = p.trimVideo(ClipId("b"), 0, 500).assertValid()
        assertThat(gone.transitionOf("a")).isNull()
        // Shortening a photo clamps the same way.
        val photo = p.setPhotoDuration(ClipId("a"), 2400).assertValid()
        assertThat(photo.transitionOf("a")!!.durationMs).isEqualTo(1200)
    }

    @Test
    fun `NONE removes the transition and lengths outside 0_3 to 2 s are refused`() {
        val p = abc().setTransition(ClipId("a"), crossfade)
        assertThat(p.setTransition(ClipId("a"), Transition(TransitionKind.NONE)).transitionOf("a")).isNull()
        assertThat(p.setTransition(ClipId("a"), null).transitionOf("a")).isNull()
        assertThrows(IllegalArgumentException::class.java) { p.setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 200)) }
        assertThrows(IllegalArgumentException::class.java) { p.setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 2100)) }
        assertThat(Timeline.fit(Transition(TransitionKind.NONE), 5000, 5000)).isNull()
        assertThat(Timeline.fit(null, 5000, 5000)).isNull()
    }

    @Test
    fun `deleting a clip drops its own transition and keeps the previous clip's`() {
        val p =
            abc()
                .setTransition(ClipId("a"), crossfade)
                .setTransition(ClipId("b"), Transition(TransitionKind.SLIDE_UP, 600))
        val noB = p.deleteClip(ClipId("b")).assertValid()
        assertThat(noB.mainIds()).containsExactly("a", "c").inOrder()
        assertThat(noB.transitionOf("a")).isEqualTo(crossfade)
        // Deleting the last clip leaves the previous one leading into nothing, so its transition goes.
        val noC = p.deleteClip(ClipId("c")).assertValid()
        assertThat(noC.transitionOf("b")).isNull()
    }

    @Test
    fun `moving the leading clip removes its transition`() {
        val p = abc().setTransition(ClipId("a"), crossfade).setTransition(ClipId("b"), Transition(TransitionKind.CIRCLE, 800))
        val moved = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(moved.mainIds()).containsExactly("b", "c", "a").inOrder()
        assertThat(moved.transitionOf("a")).isNull()
        assertThat(moved.transitionOf("b")!!.kind).isEqualTo(TransitionKind.CIRCLE)
        // Moving b: a keeps its own transition, which now leads to c.
        val movedB = p.moveMainClip(ClipId("b"), 2).assertValid()
        assertThat(movedB.mainIds()).containsExactly("a", "c", "b").inOrder()
        assertThat(movedB.transitionOf("a")).isEqualTo(crossfade)
        assertThat(movedB.transitionOf("c")).isNull()
        assertThat(p.moveMainClip(ClipId("a"), 0)).isSameInstanceAs(p)
        assertThrows(IllegalArgumentException::class.java) { p.moveMainClip(ClipId("a"), 3) }
        assertThrows(IllegalArgumentException::class.java) { p.moveMainClip(ClipId("nope"), 0) }
    }

    @Test
    fun `split keeps the transition on the second half and none between the halves`() {
        val p = abc().setTransition(ClipId("b"), crossfade)
        val split = p.splitClip(ClipId("b"), 1500, ClipId("b2")).assertValid()
        assertThat(split.mainIds()).containsExactly("a", "b", "b2", "c").inOrder()
        assertThat(split.transitionOf("b")).isNull()
        assertThat(split.transitionOf("b2")).isEqualTo(crossfade)
        assertThat(split.totalDurationMs()).isEqualTo(p.totalDurationMs())
    }

    @Test
    fun `join removes the inner transition and keeps the outer one`() {
        val p = abc().setTransition(ClipId("b"), crossfade).splitClip(ClipId("b"), 2000, ClipId("b2"))
        val joined = p.joinClips(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(joined.mainIds()).containsExactly("a", "b", "c").inOrder()
        assertThat(joined.transitionOf("b")).isEqualTo(crossfade)
        assertThat((joined.requireClip(ClipId("b")) as VideoClip).trimEndMs).isEqualTo(4000)
    }

    @Test
    fun `Apply to all sets every junction in one undo step`() {
        val editor = ProjectEditor(Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a"), Fixtures.photo("b")), ids = ids), ids)
        assertThat(editor.edit { applyTransitionToAll(Transition(TransitionKind.FADE_BLACK, 800)) }).isTrue()
        assertThat(editor.undoDepth).isEqualTo(1)
        val clips = editor.project.mainClips
        // Intro to first, between the items, and last to ending.
        assertThat(clips.dropLast(1).map { it.transitionToNext?.kind }).containsExactly(
            TransitionKind.FADE_BLACK,
            TransitionKind.FADE_BLACK,
            TransitionKind.FADE_BLACK,
        )
        assertThat(clips.last().transitionToNext).isNull()
        editor.project.assertValid()

        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.mainClips.all { it.transitionToNext == null }).isTrue()
    }

    @Test
    fun `while Apply to all is set new junctions get the transition too`() {
        val all = Transition(TransitionKind.CROSSFADE, 600)
        val p = abc().applyTransitionToAll(all)
        val inserted = p.insertAt(p.main.id, ClipId("c"), Side.AFTER, Fixtures.photo("d"), ids).assertValid()
        assertThat(inserted.transitionOf("c")).isEqualTo(all)
        val moved = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(moved.transitionOf("c")).isEqualTo(all)
        assertThat(moved.transitionOf("a")).isNull()
        // Changing one junction ends Apply to all, and keeps the others as they are.
        val single = p.setTransition(ClipId("a"), crossfade)
        assertThat(single.transitionForAll).isNull()
        assertThat(single.transitionOf("b")).isEqualTo(all)
        // Setting the same transition again keeps it on.
        assertThat(p.setTransition(ClipId("a"), all).transitionForAll).isEqualTo(all)
        // Apply to all with None removes every transition.
        val none = p.applyTransitionToAll(Transition(TransitionKind.NONE))
        assertThat(none.transitionForAll).isNull()
        assertThat(none.mainClips.all { it.transitionToNext == null }).isTrue()
        assertThrows(IllegalArgumentException::class.java) { p.applyTransitionToAll(Transition(TransitionKind.CROSSFADE, 5000)) }
    }

    @Test
    fun `the frame follows the intro and the closing message follows the ending`() {
        val p = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a", 3000), Fixtures.photo("b", 3000)), ids = ids).assertValid()
        val frame = p.byRole(PresetRole.FRAME).clips.single()
        val music = p.byRole(PresetRole.FRAME_MUSIC).clips.single()
        val speech = p.byRole(PresetRole.CLOSING_MESSAGE).clips.single()
        // Intro 6.5 s, two photos, spoken ending 6 s: 18.5 s.
        assertThat(p.totalDurationMs()).isEqualTo(18_500)
        assertThat(frame.startMs).isEqualTo(6500)
        // A spoken ending: the frame covers it (coverEnding auto).
        assertThat(frame.endMs).isEqualTo(18_500)
        assertThat(music.startMs to music.endMs).isEqualTo(frame.startMs to frame.endMs)
        assertThat(speech.startMs to speech.endMs).isEqualTo(12_500L to 18_500L)

        // A transition into the ending moves the ending and the closing message earlier.
        val ending =
            p.main.clips
                .last()
                .id
        val beforeEnding = p.main.clips[p.main.clips.lastIndex - 1].id
        val t = p.setTransition(beforeEnding, crossfade).assertValid()
        assertThat(
            t
                .byRole(PresetRole.CLOSING_MESSAGE)
                .clips
                .single()
                .startMs,
        ).isEqualTo(11_500)
        assertThat(t.endingSlot()!!.clip.id).isEqualTo(ending)

        // Skipping the intro: the frame starts at 0.
        val noIntro =
            p
                .deleteClip(
                    p.main.clips
                        .first()
                        .id,
                ).assertValid()
        assertThat(
            noIntro
                .byRole(PresetRole.FRAME)
                .clips
                .single()
                .startMs,
        ).isEqualTo(0)
        // Skipping the ending removes the closing message and its row.
        val noEnding = p.deleteClip(ending).assertValid()
        assertThat(noEnding.tracks.none { it.presetRole == PresetRole.CLOSING_MESSAGE }).isTrue()
        assertThat(
            noEnding
                .byRole(PresetRole.FRAME)
                .clips
                .single()
                .endMs,
        ).isEqualTo(noEnding.totalDurationMs())
    }

    @Test
    fun `a video ending is not covered by the frame unless the preset says so`() {
        val preset = Fixtures.preset.copy(ending = EndingSpec(EndingKind.VIDEO))
        val p = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, ids).assertValid()
        val frame = p.byRole(PresetRole.FRAME).clips.single()
        assertThat(frame.endMs).isEqualTo(p.endingSlot()!!.startMs)
        assertThat(p.tracks.none { it.presetRole == PresetRole.CLOSING_MESSAGE }).isTrue()
        val covered = preset.copy(frame = preset.frame!!.copy(coverEnding = CoverEnding.YES))
        val q = ProjectFactory.fromPreset(Fixtures.PROJECT_ID, covered, Fixtures.presetMedia, ids)
        assertThat(
            q
                .byRole(PresetRole.FRAME)
                .clips
                .single()
                .endMs,
        ).isEqualTo(q.totalDurationMs())
        assertThat(CoverEnding.NO.resolve(EndingKind.AUDIO)).isFalse()
        assertThat(CoverEnding.AUTO.resolve(null)).isFalse()
    }

    @Test
    fun `a growing frame moves a clip it would cover to another row`() {
        // A short overlay put on the frame row before the frame starts...
        var p = Fixtures.presetProject()
        val frameRow = p.byRole(PresetRole.FRAME)
        p = p.addToTrack(frameRow.id, Fixtures.overlay("early", 0, 2000), 0, ids).assertValid()
        assertThat(p.trackOf(ClipId("early"))!!.id).isEqualTo(frameRow.id)
        // ...is moved off it when skipping the intro makes the frame start at 0.
        val noIntro =
            p
                .deleteClip(
                    p.main.clips
                        .first()
                        .id,
                ).assertValid()
        assertThat(noIntro.trackOf(ClipId("early"))!!.id).isNotEqualTo(frameRow.id)
        assertThat(noIntro.trackOf(ClipId("early"))!!.type).isEqualTo(TrackType.OVERLAY)
    }

    @Test
    fun `normalising twice changes nothing`() {
        val p =
            Fixtures
                .presetProject()
                .addToMain(listOf(Fixtures.photo("a"), Fixtures.video("b")), ids = ids)
                .applyTransitionToAll(crossfade)
                .setAnimation(ClipId("a"), AnimSlot.START, Anim(AnimKind.FADE_IN))
        assertThat(p.normalized(ids)).isEqualTo(p)
        assertThat(p.normalized(ids).normalized(ids)).isEqualTo(p)
    }
}
