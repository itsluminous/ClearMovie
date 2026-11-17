package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setAnimation
import com.itsluminous.clearmovie.domain.model.setSpeed
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.MainContent
import org.junit.Test
import kotlin.math.abs

/** cm-effects in the render plan and the preview schedule: one source for the export and the preview. */
class EffectsPlanTest {
    private val ids = SequentialIds("ep")

    private fun video(id: String) = VideoClip(ClipId(id), Fixtures.media(id), sourceDurationMs = 3000)

    private fun twoVideos(kind: TransitionKind) =
        ProjectFactory
            .blank(ProjectId("p"), ids)
            .addToMain(listOf(video("a"), video("b")), ids = ids)
            .setTransition(ClipId("a"), Transition(kind, 1000))

    private fun pictures(
        plan: RenderPlan,
        role: LayerRole,
    ) = plan.visual
        .single { it.role == role }
        .segments
        .filterIsInstance<VisualSegment.Picture>()

    @Test
    fun `every transition kind puts its parts on both pictures, the upper one in lane B`() {
        for (kind in TransitionKind.entries - TransitionKind.NONE) {
            val plan = RenderPlanBuilder().build(twoVideos(kind))
            val out = pictures(plan, LayerRole.MAIN).single()
            val into = pictures(plan, LayerRole.MAIN_B).single()
            assertThat(out.motion!!.transitionOut).isEqualTo(TransitionPart(kind, 2_000_000, 3_000_000, TransitionRole.OUT, upper = false))
            assertThat(into.motion!!.transitionIn).isEqualTo(TransitionPart(kind, 0, 1_000_000, TransitionRole.IN, upper = true))
            assertThat(plan.durationUs).isEqualTo(5_000_000)
        }
    }

    @Test
    fun `fade through a colour dips both sounds to silence at the midpoint`() {
        for (kind in listOf(TransitionKind.FADE_BLACK, TransitionKind.FADE_WHITE)) {
            val sounds =
                RenderPlanBuilder()
                    .build(twoVideos(kind))
                    .audio
                    .flatMap { it.segments }
                    .filterIsInstance<AudioSegment.Sound>()
            val a = sounds.single { it.media == Fixtures.media("a") }
            val b = sounds.single { it.media == Fixtures.media("b") }
            assertThat(a.gain.dbAt(2_000_000)).isWithin(1e-6).of(0.0)
            assertThat(a.gain.dbAt(2_500_000)).isEqualTo(GainEnvelope.SILENT_DB)
            assertThat(b.gain.dbAt(400_000)).isEqualTo(GainEnvelope.SILENT_DB)
            assertThat(b.gain.dbAt(1_000_000)).isWithin(1e-6).of(0.0)
            // Each half is equal-power on its own: the louder side carries the sound.
            assertThat(a.gain.gainAt(2_250_000).toDouble()).isWithin(0.01).of(kotlin.math.cos(Math.PI / 4))
        }
    }

    @Test
    fun `slides and wipes keep the equal-power crossfade for sound`() {
        val sounds =
            RenderPlanBuilder()
                .build(twoVideos(TransitionKind.SLIDE_LEFT))
                .audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
        val a = sounds.single { it.media == Fixtures.media("a") }
        val b = sounds.single { it.media == Fixtures.media("b") }
        for (t in (0..10).map { 2_000_000L + it * 100_000L }) {
            val ga = a.gain.gainAt(t - a.startUs).toDouble()
            val gb = b.gain.gainAt(t - b.startUs).toDouble()
            assertThat(abs(ga * ga + gb * gb - 1.0)).isLessThan(0.03)
        }
    }

    @Test
    fun `animations reach pictures in the window and captions in their box`() {
        val photo = PhotoClip(ClipId("ph"), Fixtures.media("ph"), 3000)
        val project =
            ProjectFactory
                .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("x"))
                .addToMain(listOf(photo), ids = ids)
                .withEffectDefaultsFor(listOf(photo.id))
                .addTimedClip(TextClip(ClipId("tx"), 7000, 2000, "Hi"), ids = ids)
                .let { it.setAnimation(ClipId("tx"), AnimSlot.START, Anim(AnimKind.POP, 400)) }
        val plan = RenderPlanBuilder(Fixtures.preset).build(project, height = 720)
        val ph =
            plan.visual
                .flatMap { it.segments }
                .filterIsInstance<VisualSegment.Picture>()
                .filter { it.source is Source.Photo }
                .minBy { it.startUs }
        assertThat(ph.motion!!.during!!.kind).isEqualTo(AnimKind.KEN_BURNS_IN)
        assertThat(ph.motion!!.rect).isEqualTo(Fixtures.preset.frame!!.contentRect)
        assertThat(ph.motion!!.clipToRect).isTrue()
        val caption =
            plan.visual
                .flatMap { it.segments }
                .filterIsInstance<VisualSegment.Picture>()
                .single { it.source is Source.Caption }
        assertThat(caption.motion!!.start!!.kind).isEqualTo(AnimKind.POP)
        assertThat(caption.motion!!.clipToRect).isFalse()
        assertThat(caption.motionAt(0).scale).isWithin(1e-6f).of(0f)
        // The preview's caption entries carry the same motion.
        val schedule = PreviewSchedule.of(plan)
        assertThat(schedule.captions.map { it.motion }).containsExactly(caption.motion)
    }

    @Test
    fun `the preview plays the outgoing item's tail during the overlap, with its sound`() {
        val project =
            ProjectFactory
                .blank(ProjectId("p"), ids)
                .addToMain(listOf(video("a"), video("b"), video("c")), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
                .setSpeed(ClipId("b"), Speed.X1)
        val s = PreviewSchedule.of(RenderPlanBuilder().build(project))
        assertThat(s.tail).hasSize(1)
        val tail = s.tail.single()
        assertThat(tail.startUs to tail.endUs).isEqualTo(2_000_000L to 3_000_000L)
        assertThat((tail.content as MainContent.Video).clipStartUs).isEqualTo(2_000_000)
        assertThat(tail.clipOffsetUs).isEqualTo(2_000_000)
        assertThat(tail.upper).isFalse()
        assertThat(tail.motion!!.transitionOut!!.kind).isEqualTo(TransitionKind.CROSSFADE)
        // The incoming item is MAIN's entry from the overlap's start, in lane B, fading in.
        val inEntry = s.main[s.mainIndexAt(2_500_000)]
        assertThat(inEntry.upper).isTrue()
        assertThat(inEntry.motion!!.transitionIn).isNotNull()
        assertThat(inEntry.clipUsAt(2_500_000)).isEqualTo(500_000)
        // Equal-power sound on both players across the overlap.
        for (t in listOf(2_100_000L, 2_500_000L, 2_900_000L)) {
            val ga = PreviewEngineGain.of(s.tailGainDbAt(t))
            val gb = PreviewEngineGain.of(s.mainGainDbAt(t))
            assertThat(abs(ga * ga + gb * gb - 1.0)).isLessThan(0.03)
        }
        assertThat(s.tailGainDbAt(1_000_000)).isNull()
        // The tail player's playlist covers the whole video, playing only in the overlap.
        val lane = s.tailLane!!
        // It starts half a second early, hidden and silent, so its decoder is running when the overlap begins.
        val play = lane.entries.filterIsInstance<PreviewSchedule.LaneEntry.Play>().single()
        assertThat(play.startUs).isEqualTo(2_000_000L - PreviewSchedule.TAIL_PREROLL_US)
        assertThat(play.clipStartUs).isEqualTo(2_000_000L - PreviewSchedule.TAIL_PREROLL_US)
        assertThat(s.tailGainDbAt(1_700_000)).isNull()
        assertThat(lane.entries.last().endUs).isEqualTo(s.durationUs)
    }

    @Test
    fun `no transition, no tail`() {
        val s =
            PreviewSchedule.of(
                RenderPlanBuilder().build(ProjectFactory.blank(ProjectId("p"), ids).addToMain(listOf(video("a"), video("b")), ids = ids)),
            )
        assertThat(s.tail).isEmpty()
        assertThat(s.tailLane).isNull()
        assertThat(s.mainGainDbAt(1_000_000)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `a spoken ending holds the last photo's final gentle-zoom pose`() {
        val photo = PhotoClip(ClipId("ph"), Fixtures.media("ph"), 3000)
        val project =
            ProjectFactory
                .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("x"))
                .addToMain(listOf(photo), ids = ids)
                .withEffectDefaultsFor(listOf(photo.id))
        val plan = RenderPlanBuilder(Fixtures.preset).build(project, height = 720)
        val photos =
            plan.visual
                .flatMap { it.segments }
                .filterIsInstance<VisualSegment.Picture>()
                .filter { it.source is Source.Photo }
                .sortedBy { it.startUs }
        val (item, held) = photos
        val endPose = item.motionAt(item.durationUs)
        val heldStart = held.motionAt(held.clipOffsetUs)
        val heldLater = held.motionAt(held.clipOffsetUs + 2_000_000)
        assertThat(heldStart.scale).isWithin(1e-5f).of(endPose.scale)
        assertThat(heldStart.dx).isWithin(1e-6f).of(endPose.dx)
        assertThat(heldLater.scale).isWithin(1e-5f).of(endPose.scale)
        assertThat(heldLater.dy).isWithin(1e-6f).of(endPose.dy)
    }

    /** dB to the linear gain the preview players get. */
    private object PreviewEngineGain {
        fun of(db: Double?): Double = if (db == null) 0.0 else GainEnvelope.dbToGain(db).toDouble()
    }
}
