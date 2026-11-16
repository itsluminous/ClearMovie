package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import org.junit.Assert.assertThrows
import org.junit.Test

/** The MAIN operations the timeline adds in cm-timeline: duplicate, replace, replace a preset part, card colour. */
class TimelineEditsTest {
    private val ids = SequentialIds("n")
    private val base = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a"), Fixtures.video("b", 4000), Fixtures.photo("c")))
    private val intro = base.mainClips.first() as IntroClip
    private val ending = base.mainClips.last() as EndingClip

    @Test
    fun `duplicate puts a copy right after, which takes over the transition to the next item`() {
        val t = Transition(TransitionKind.CROSSFADE, 500)
        val withTransition = base.setTransition(ClipId("b"), t)
        val p = withTransition.duplicateClip(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(p.mainIds()).containsExactly(intro.id.value, "a", "b", "b2", "c", ending.id.value).inOrder()
        assertThat(p.mainClips.first { it.id == ClipId("b") }.transitionToNext).isNull()
        assertThat(p.mainClips.first { it.id == ClipId("b2") }.transitionToNext).isEqualTo(t)
        assertThat(p.totalDurationMs()).isEqualTo(withTransition.totalDurationMs() + 4000)
    }

    @Test
    fun `a duplicate of a split half belongs to no split, so it can never be joined`() {
        val split = base.splitClip(ClipId("b"), 2000, ClipId("b-2"))
        val p = split.duplicateClip(ClipId("b"), ClipId("copy")).assertValid()
        assertThat((p.clip(ClipId("copy")) as VideoClip).splitGroup).isNull()
        assertThat(p.canJoin(ClipId("b"), ClipId("copy"))).isFalse()
    }

    @Test
    fun `the intro and ending can't be duplicated`() {
        assertThrows(IllegalArgumentException::class.java) { base.duplicateClip(intro.id, ClipId("x")) }
        assertThrows(IllegalArgumentException::class.java) { base.duplicateClip(ending.id, ClipId("x")) }
    }

    @Test
    fun `replace keeps the place, the id, the transition and the fit`() {
        val t = Transition(TransitionKind.CROSSFADE, 500)
        val before = base.setTransition(ClipId("a"), t).setFit(ClipId("a"), Fit.FIT_BLUR)
        val video = VideoClip(ClipId("new"), Fixtures.media("new"), sourceDurationMs = 5000)
        val p = before.replaceMainItem(ClipId("a"), video).assertValid()
        assertThat(p.mainIds()).isEqualTo(before.mainIds())
        val replaced = p.clip(ClipId("a")) as VideoClip
        assertThat(replaced.media).isEqualTo(Fixtures.media("new"))
        assertThat(replaced.transitionToNext).isEqualTo(t)
        assertThat(replaced.fit).isEqualTo(Fit.FIT_BLUR)
        assertThat(replaced.durationMs).isEqualTo(5000)
    }

    @Test
    fun `replace keeps the animations only for an item of the same kind`() {
        val kenBurns = Anim(AnimKind.KEN_BURNS_IN, 500)
        val before = base.setAnimation(ClipId("a"), AnimSlot.DURING, kenBurns)
        val photo = before.replaceMainItem(ClipId("a"), Fixtures.photo("p2")).assertValid()
        assertThat(photo.clip(ClipId("a"))!!.animations.during).isEqualTo(kenBurns)
        val video = before.replaceMainItem(ClipId("a"), Fixtures.video("v2")).assertValid()
        assertThat(video.clip(ClipId("a"))!!.animations).isEqualTo(Animations.NONE)
    }

    @Test
    fun `replacing the intro plays the new video for its own length, and the frame follows`() {
        val mine = MediaRef.Store("content://media/external/video/media/9")
        val p = base.replacePresetPart(intro.id, mine, 3000).assertValid()
        val newIntro = p.mainClips.first() as IntroClip
        assertThat(newIntro.media).isEqualTo(mine)
        assertThat(newIntro.durationMs).isEqualTo(3000)
        assertThat(
            p
                .byRole(PresetRole.FRAME)
                .clips
                .single()
                .startMs,
        ).isEqualTo(3000)
    }

    @Test
    fun `replacing a spoken ending changes the held frame and the closing message together`() {
        val sound = MediaRef.Store("content://media/external/audio/media/4")
        val p = base.replacePresetPart(ending.id, sound, 8000).assertValid()
        val newEnding = p.mainClips.last() as EndingClip
        assertThat(newEnding.durationMs).isEqualTo(8000)
        val speech = p.byRole(PresetRole.CLOSING_MESSAGE).clips.single() as AudioClip
        assertThat(speech.media).isEqualTo(sound)
        assertThat(speech.durationMs).isEqualTo(8000)
        assertThat(speech.startMs).isEqualTo(p.endingSlot()!!.startMs)
    }

    @Test
    fun `only the intro and ending are replaced that way, and only by the user's own file`() {
        val mine = MediaRef.Store("content://media/external/video/media/9")
        assertThrows(IllegalArgumentException::class.java) { base.replacePresetPart(ClipId("a"), mine, 3000) }
        assertThrows(IllegalArgumentException::class.java) { base.replacePresetPart(intro.id, MediaRef.Preset(PresetSlot.INTRO), 3000) }
        assertThrows(IllegalArgumentException::class.java) { base.replacePresetPart(intro.id, mine, 10) }
    }

    @Test
    fun `a card's colour changes, and must be opaque`() {
        val card = CardClip(ClipId("card"), 3000, ArgbColor(0xFF000000), "Hello")
        val p = base.insertAt(base.main.id, ClipId("a"), Side.AFTER, card, ids)
        val red = p.setCardColor(ClipId("card"), ArgbColor(0xFFB71C1C)).assertValid()
        assertThat((red.clip(ClipId("card")) as CardClip).color).isEqualTo(ArgbColor(0xFFB71C1C))
        assertThrows(IllegalArgumentException::class.java) { p.setCardColor(ClipId("card"), ArgbColor(0x80FFFFFF)) }
        assertThrows(IllegalArgumentException::class.java) { p.setCardColor(ClipId("a"), ArgbColor(0xFFFFFFFF)) }
    }
}
