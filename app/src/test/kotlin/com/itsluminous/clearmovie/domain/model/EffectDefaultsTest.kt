package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.trackOfType
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * cm-effects on the model: the preset's effect defaults on new items, apply,
 * apply to all, remove, and the length clamps (cm-model D7).
 */
class EffectDefaultsTest {
    private val ids = SequentialIds("fx")
    private val defaults = Fixtures.preset.effects

    /** The preset project with [items] added the way the picker adds them. */
    private fun withNew(vararg items: MainClip): Project {
        val p = Fixtures.presetProject(SequentialIds("x")).addToMain(items.toList(), ids = ids)
        return p.withEffectDefaultsFor(items.map { it.id }).assertValid()
    }

    private fun Project.transitionOf(id: String) = (requireClip(ClipId(id)) as MainClip).transitionToNext

    private fun Project.anims(id: String) = requireClip(ClipId(id)).animations

    @Test
    fun `new photos get the gentle zoom, each panning its own way`() {
        val p = withNew(Fixtures.photo("a"), Fixtures.photo("b"), Fixtures.video("v"))
        assertThat(p.anims("a").during!!.kind).isEqualTo(AnimKind.KEN_BURNS_IN)
        assertThat(p.anims("a").during!!.seed).isEqualTo(kenBurnsSeed(ClipId("a")))
        assertThat(p.anims("b").during!!.seed).isNotEqualTo(p.anims("a").during!!.seed)
        // A video has no "during" animation: Ken Burns is for photos.
        assertThat(p.anims("v").isEmpty).isTrue()
    }

    @Test
    fun `every junction a new item makes gets the default transition, the intro's and the ending's included`() {
        val p = withNew(Fixtures.photo("a"), Fixtures.video("b"))
        val main = p.mainClips
        assertThat(main.first()).isInstanceOf(IntroClip::class.java)
        assertThat(main.dropLast(1).map { it.transitionToNext }).containsExactly(defaults.mainTransition, defaults.mainTransition, defaults.mainTransition)
        assertThat(main.last().transitionToNext).isNull()
        // Three 0.6 s overlaps come off the total.
        assertThat(p.totalDurationMs()).isEqualTo(6500 + 3000 + 4000 + 6000 - 3 * 600)
    }

    @Test
    fun `what an item already has is kept, and applying twice changes nothing`() {
        val p = withNew(Fixtures.photo("a"), Fixtures.photo("b"))
        val custom =
            p
                .setTransition(ClipId("a"), Transition(TransitionKind.SLIDE_LEFT, 800))
                .setAnimation(ClipId("a"), AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_OUT))
        val again = custom.withEffectDefaultsFor(listOf(ClipId("a"), ClipId("b")))
        assertThat(again).isEqualTo(custom)
        assertThat(again.transitionOf("a")).isEqualTo(Transition(TransitionKind.SLIDE_LEFT, 800))
        assertThat(again.anims("a").during!!.kind).isEqualTo(AnimKind.KEN_BURNS_OUT)
    }

    @Test
    fun `an item inserted later only fills the junctions it makes`() {
        val p = withNew(Fixtures.photo("a"), Fixtures.photo("b"))
        // The user takes the transition out between a and b, then puts c between them.
        val cleared = p.setTransition(ClipId("a"), null)
        val c = Fixtures.photo("c")
        val inserted = cleared.insertAt(cleared.main.id, ClipId("a"), Side.AFTER, c).withEffectDefaultsFor(listOf(c.id)).assertValid()
        assertThat(inserted.transitionOf("a")).isEqualTo(defaults.mainTransition)
        assertThat(inserted.transitionOf("c")).isEqualTo(defaults.mainTransition)
        // The intro's own transition is untouched.
        assertThat(inserted.mainClips.first().transitionToNext).isEqualTo(defaults.mainTransition)
    }

    @Test
    fun `a new caption gets the start animation, and unknown ids are ignored`() {
        val p = Fixtures.presetProject(SequentialIds("x")).addText("Hello", 7000, ids = ids)
        val caption = p.trackOfType(TrackType.TEXT).clips.single()
        val q = p.withEffectDefaultsFor(listOf(caption.id, ClipId("not-there"))).assertValid()
        assertThat(q.anims(caption.id.value).start).isEqualTo(Anim(AnimKind.FADE_IN))
        assertThat(p.withEffectDefaultsFor(emptyList())).isEqualTo(p)
    }

    @Test
    fun `no defaults without a preset, none under apply to all, none inside a split`() {
        val blank = Fixtures.blankWith(Fixtures.photo("a"), Fixtures.photo("b"), ids = ids)
        assertThat(blank.withEffectDefaultsFor(listOf(ClipId("a"), ClipId("b")))).isEqualTo(blank)

        val wipe = Transition(TransitionKind.WIPE_UP, 500)
        val forAll = withNew(Fixtures.photo("a")).applyTransitionToAll(wipe)
        val d = Fixtures.photo("d")
        val added = forAll.addToMain(listOf(d), ids = ids).withEffectDefaultsFor(listOf(d.id)).assertValid()
        assertThat(added.transitionOf("d")).isEqualTo(wipe)
        assertThat(added.transitionOf("a")).isEqualTo(wipe)

        val v = Fixtures.video("v", 8000)
        val split = withNew(v).splitClip(v.id, 4000, ClipId("v2"))
        val again = split.withEffectDefaultsFor(listOf(ClipId("v"), ClipId("v2")))
        assertThat(again.transitionOf("v")).isNull()
    }

    @Test
    fun `a default that does not fit is shortened, or dropped`() {
        // 0.6 s fits half of a 1.2 s photo exactly; a 0.5 s photo is too short for even 0.3 s.
        val p = withNew(Fixtures.photo("a", 1200), Fixtures.photo("b", 500), Fixtures.photo("c", 4000))
        assertThat(p.transitionOf("a")).isNull()
        assertThat(p.transitionOf("b")).isNull()
        val q = withNew(Fixtures.photo("a", 1000), Fixtures.photo("c", 4000))
        assertThat(q.transitionOf("a")).isEqualTo(Transition(TransitionKind.CROSSFADE, 500))
    }

    @Test
    fun `trimming a neighbour shorter shortens the default, then drops it`() {
        val v = Fixtures.video("v", 4000)
        val p = withNew(Fixtures.photo("a"), v)
        assertThat(p.transitionOf("a")!!.durationMs).isEqualTo(600)
        val shorter = p.trimVideo(v.id, 0, 1000).assertValid()
        assertThat(shorter.transitionOf("a")!!.durationMs).isEqualTo(500)
        val tiny = p.trimVideo(v.id, 0, 500).assertValid()
        assertThat(tiny.transitionOf("a")).isNull()
        // cm-model D7: lengthening it again does not bring the dropped one back.
        assertThat(tiny.trimVideo(v.id, 0, 4000).transitionOf("a")).isNull()
    }

    @Test
    fun `apply, apply to all and remove`() {
        val p = withNew(Fixtures.photo("a"), Fixtures.photo("b"), Fixtures.photo("c"))
        val slide = Transition(TransitionKind.SLIDE_RIGHT, 1000)
        val one = p.setTransition(ClipId("b"), slide).assertValid()
        assertThat(one.transitionOf("b")).isEqualTo(slide)
        assertThat(one.transitionOf("a")).isEqualTo(defaults.mainTransition)
        val all = one.applyTransitionToAll(Transition(TransitionKind.CIRCLE, 700)).assertValid()
        assertThat(all.mainClips.dropLast(1).map { it.transitionToNext!!.kind }.toSet()).containsExactly(TransitionKind.CIRCLE)
        assertThat(all.transitionForAll).isEqualTo(Transition(TransitionKind.CIRCLE, 700))
        // Changing one junction ends "for all"; the others keep what they had.
        val changed = all.setTransition(ClipId("a"), null).assertValid()
        assertThat(changed.transitionForAll).isNull()
        assertThat(changed.transitionOf("a")).isNull()
        assertThat(changed.transitionOf("b")!!.kind).isEqualTo(TransitionKind.CIRCLE)
        val none = all.applyTransitionToAll(Transition(TransitionKind.NONE)).assertValid()
        assertThat(none.mainClips.all { it.transitionToNext == null }).isTrue()
        assertThrows(IllegalArgumentException::class.java) { p.setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 100)) }
    }

    @Test
    fun `text animations go on cards too, never on photos or sounds`() {
        val card = CardClip(ClipId("k"), 3000, ArgbColor.BLACK, "Hi")
        val p = withNew(card, Fixtures.photo("a"))
        val popped = p.setAnimation(card.id, AnimSlot.START, Anim(AnimKind.POP)).assertValid()
        assertThat(popped.anims("k").start!!.kind).isEqualTo(AnimKind.POP)
        assertThat(card.animationChoices(AnimSlot.DURING)).containsExactly(AnimKind.PULSE)
        assertThat(Fixtures.photo("z").animationChoices(AnimSlot.DURING)).containsExactly(AnimKind.KEN_BURNS_IN, AnimKind.KEN_BURNS_OUT)
        assertThat(Fixtures.video("z").animationChoices(AnimSlot.DURING)).isEmpty()
        assertThat(Fixtures.photo("z").animationChoices(AnimSlot.START)).doesNotContain(AnimKind.POP)
        assertThrows(IllegalArgumentException::class.java) { p.setAnimation(ClipId("a"), AnimSlot.START, Anim(AnimKind.POP)) }
        assertThat(AnimKind.FADE_IN.allowedOn(Fixtures.audio("s", 0))).isFalse()
    }

    @Test
    fun `start and end animations shrink to fit a shortened item`() {
        val p =
            withNew(Fixtures.photo("a", 3000))
                .setAnimation(ClipId("a"), AnimSlot.START, Anim(AnimKind.FADE_IN, 1500))
                .setAnimation(ClipId("a"), AnimSlot.END, Anim(AnimKind.FADE_OUT, 1200))
        val short = p.setPhotoDuration(ClipId("a"), 2000).assertValid()
        val a = short.anims("a")
        assertThat(a.start!!.durationMs + a.end!!.durationMs).isAtMost(2000)
        assertThat(a.end!!.durationMs).isEqualTo(1200)
    }
}
