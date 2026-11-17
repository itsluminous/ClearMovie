package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PartChoice
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetChoices
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.bringToTop
import com.itsluminous.clearmovie.domain.model.setSpeed
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.LaneEntry
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.LayerRef
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.MainContent
import org.junit.Test

/** The layered preview's schedule, from the same plan the export renders. */
class PreviewScheduleTest {
    private val preset = Fixtures.preset

    private fun presetProject(vararg items: MainClip) =
        ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds("x")).addToMain(items.toList())

    private fun schedule(project: Project) = PreviewSchedule.of(RenderPlanBuilder(preset).build(project, height = 720))

    private fun assertCovers(
        entries: List<Pair<Long, Long>>,
        total: Long,
    ) {
        var at = 0L
        for ((start, length) in entries) {
            assertThat(start).isEqualTo(at)
            assertThat(length).isGreaterThan(0L)
            at = start + length
        }
        assertThat(at).isEqualTo(total)
    }

    @Test
    fun `MAIN is one playlist with the intro full frame, the items in the window and the held last frame`() {
        val s = schedule(presetProject(Fixtures.photo("a"), Fixtures.video("b")))
        assertCovers(s.main.map { it.startUs to it.durationUs }, s.durationUs)
        assertThat(s.main).hasSize(4)
        val intro = s.main[0]
        assertThat(intro.placement).isEqualTo(Placement.FullFrame)
        assertThat((intro.content as MainContent.Video).media).isEqualTo(MediaRef.Preset(PresetSlot.INTRO))
        val photo = s.main[1]
        assertThat(photo.content).isEqualTo(MainContent.Still(Source.Photo(Fixtures.media("a"))))
        assertThat((photo.placement as Placement.Window).rect).isEqualTo(preset.frame!!.contentRect)
        assertThat(s.main[2].content).isInstanceOf(MainContent.Video::class.java)
        // The spoken ending holds the video's last frame, where the video was drawn.
        val held = (s.main[3].content as MainContent.Still).source as Source.VideoFrame
        assertThat(held.media).isEqualTo(Fixtures.media("b"))
        assertThat(s.mainIndexAt(0)).isEqualTo(0)
        assertThat(s.mainIndexAt(6_500_000)).isEqualTo(1)
        assertThat(s.mainIndexAt(s.durationUs + 5)).isEqualTo(3)
    }

    @Test
    fun `the frame is drawn over MAIN, keyed, and starts after the intro`() {
        val s = schedule(presetProject(Fixtures.photo("a")))
        assertThat(s.layerOrder.first()).isEqualTo(LayerRef.Main)
        assertThat(s.layerOrder).contains(LayerRef.Overlay(0))
        val lane = s.overlays.single()
        assertCovers(lane.entries.map { it.startUs to it.durationUs }, s.durationUs)
        assertThat(lane.entries.first()).isInstanceOf(LaneEntry.Gap::class.java)
        assertThat(lane.entries.first().durationUs).isEqualTo(6_500_000)
        val play = lane.entries[1] as LaneEntry.Play
        assertThat(play.media).isEqualTo(MediaRef.Preset(PresetSlot.FRAME))
        assertThat(play.key).isEqualTo(preset.frame!!.key)
        assertThat(lane.indexAt(0)).isEqualTo(0)
        assertThat(lane.indexAt(7_000_000)).isEqualTo(1)
    }

    @Test
    fun `sound rows play in their own lanes with the plan's ducking, and MAIN's own sound follows its envelope`() {
        val s = schedule(presetProject(Fixtures.video("v", ms = 5000)))
        val roles = s.sounds.map { it.role }
        assertThat(roles).containsExactly(AudioLayerRole.MUSIC, AudioLayerRole.CLOSING_MESSAGE)
        s.sounds.forEach { lane -> assertCovers(lane.entries.map { it.startUs to it.durationUs }, s.durationUs) }
        val music = s.sounds.first { it.role == AudioLayerRole.MUSIC }
        // During the intro there is no music (the frame starts after it).
        assertThat(music.gainDbAt(1_000_000)).isNull()
        // Under the video's sound the music is lower than over a quiet stretch would be.
        val underClip = music.gainDbAt(9_000_000)!!
        assertThat(underClip).isLessThan(-8.0)
        // The intro's own sound plays through the MAIN player.
        assertThat(s.mainGainDbAt(1_000_000)).isNotNull()
        // The closing message plays at the end, not before.
        val closing = s.sounds.first { it.role == AudioLayerRole.CLOSING_MESSAGE }
        assertThat(closing.gainDbAt(1_000_000)).isNull()
        assertThat(closing.gainDbAt(s.durationUs - 1_000_000)).isNotNull()
    }

    @Test
    fun `a transition becomes a cut where the incoming item starts, and the incoming item starts later in its file`() {
        val ids = SequentialIds("t")
        val project =
            Fixtures
                .blankWith(Fixtures.video("a", 4000), Fixtures.video("b", 4000), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
        val s = PreviewSchedule.of(RenderPlanBuilder().build(project))
        assertThat(s.durationUs).isEqualTo(7_000_000)
        assertCovers(s.main.map { it.startUs to it.durationUs }, s.durationUs)
        assertThat(s.main).hasSize(2)
        assertThat(s.main[0].durationUs).isEqualTo(3_000_000)
        assertThat((s.main[1].content as MainContent.Video).clipStartUs).isEqualTo(0)
    }

    @Test
    fun `a sped-up video plays the right part of its file`() {
        val project = Fixtures.blankWith(Fixtures.video("a", 4000)).setSpeed(ClipId("a"), Speed.X2)
        val s = PreviewSchedule.of(RenderPlanBuilder().build(project))
        val video = s.main.single().content as MainContent.Video
        assertThat(s.main.single().durationUs).isEqualTo(2_000_000)
        assertThat(video.speed).isEqualTo(Speed.X2)
        assertThat(video.clipEndUs(s.main.single().durationUs)).isEqualTo(4_000_000)
    }

    @Test
    fun `captions are listed with their boxes and times`() {
        val project = presetProject(Fixtures.photo("a")).addText("Hello", 6_500, ids = SequentialIds("c"))
        val s = schedule(project)
        assertThat(s.layerOrder.last()).isInstanceOf(LayerRef.Captions::class.java)
        val c = s.captions.single()
        assertThat(c.caption.text).isEqualTo("Hello")
        assertThat(s.captionsAt(7_000_000)).containsExactly(c)
        assertThat(s.captionsAt(1_000_000)).isEmpty()
    }

    @Test
    fun `a replaced intro plays the user's file`() {
        val mine = MediaRef.Store("content://media/external/video/media/9")
        val media = PresetChoices(intro = PartChoice.Replace(mine, 3000)).applyTo(Fixtures.presetMedia)
        val project =
            ProjectFactory
                .fromPreset(
                    Fixtures.PROJECT_ID,
                    preset,
                    media,
                    SequentialIds("y"),
                ).addToMain(listOf(Fixtures.photo("a")))
        val plan = RenderPlanBuilder(preset).build(project)
        val s = PreviewSchedule.of(plan)
        assertThat((s.main.first().content as MainContent.Video).media).isEqualTo(mine)
        assertThat(s.main.first().durationUs).isEqualTo(3_000_000)
        // The export plays the same file, and its sound.
        val introSound =
            plan.audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
                .first { it.startUs == 0L }
        assertThat(introSound.media).isEqualTo(mine)
    }

    @Test
    fun `nothing to play gives an empty MAIN`() {
        val s = PreviewSchedule.of(RenderPlanBuilder().build(ProjectFactory.blank(Fixtures.PROJECT_ID)))
        assertThat(s.main).isEmpty()
        assertThat(s.mainIndexAt(0)).isEqualTo(-1)
        assertThat(s.mainGainDbAt(0)).isNull()
    }

    @Test
    fun `picture-in-picture rows are drawn in stacking order, videos in their corner and photos as stills`() {
        val ids = SequentialIds("q")
        var p = presetProject(Fixtures.photo("a", 10_000))
        p = p.addTimedClip(Fixtures.video("pv", 3000, pip = PipPlacement.DEFAULT).withStartMs(1000), ids = ids)
        p = p.addTimedClip(Fixtures.photo("pp", 2000, pip = PipPlacement.DEFAULT).withStartMs(5000), ids = ids)
        val pipRow = p.trackOf(ClipId("pv"))!!.id
        val frameRow = p.tracks.first { it.presetRole == PresetRole.FRAME }.id
        val s = schedule(p)
        val pipIndex = s.overlays.indexOfFirst { it.trackId == pipRow }
        val frameIndex = s.overlays.indexOfFirst { it.trackId == frameRow }
        val lane = s.overlays[pipIndex]
        // Drawn above MAIN and under the frame, as the stacking says (a new picture-in-picture row's default).
        assertThat(s.layerOrder.first()).isEqualTo(LayerRef.Main)
        assertThat(s.layerOrder.indexOf(LayerRef.Overlay(pipIndex))).isLessThan(s.layerOrder.indexOf(LayerRef.Overlay(frameIndex)))
        val play = lane.entries.filterIsInstance<LaneEntry.Play>().single()
        // A timed item's start is the video's own time.
        assertThat(play.startUs).isEqualTo(1_000_000)
        assertThat(play.placement).isInstanceOf(Placement.Pip::class.java)
        assertThat(play.key).isNull()
        assertThat(lane.stills.single().source).isEqualTo(Source.Photo(Fixtures.media("pp")))
        assertThat(lane.stillAt(5_500_000)).isNotNull()
        assertThat(lane.stillAt(4_500_000)).isNull()
        // The video's own sound plays too, in a lane of its own.
        assertThat(s.sounds.map { it.trackId }).contains(pipRow)
        // Shown on top: now drawn after the frame.
        val top = schedule(p.bringToTop(pipRow))
        val pipTop = top.overlays.indexOfFirst { it.trackId == pipRow }
        val frameTop = top.overlays.indexOfFirst { it.trackId == frameRow }
        assertThat(top.layerOrder.indexOf(LayerRef.Overlay(pipTop))).isGreaterThan(top.layerOrder.indexOf(LayerRef.Overlay(frameTop)))
    }

    @Test
    fun `a video's sound row is not played twice`() {
        val s = schedule(presetProject(Fixtures.video("v")))
        assertThat(s.sounds.map { it.role }).doesNotContain(AudioLayerRole.CLIP)
        assertThat(
            (s.main[1].content as MainContent.Video).media,
        ).isEqualTo((presetProject(Fixtures.video("v")).mainClips[1] as VideoClip).media)
    }

    @Test
    fun `a sliver after a gap leaves no overlap and no extra time`() {
        val m = Fixtures.media("a")
        val sliver = VisualSegment.Picture(1_000, 400, Source.Photo(m), Placement.FullFrame)
        val next = VisualSegment.Picture(1_400, 2_000_000, Source.Photo(Fixtures.media("b")), Placement.FullFrame)
        val plan =
            RenderPlan(
                width = 1280,
                height = 720,
                fps = 30,
                durationUs = 2_001_400,
                visual =
                    listOf(
                        VisualLayer(
                            com.itsluminous.clearmovie.domain.model
                                .TrackId("m"),
                            LayerRole.MAIN,
                            listOf(VisualSegment.Fill(0, 1_000), sliver, next),
                        ),
                    ),
                audio = emptyList(),
            )
        val s = PreviewSchedule.of(plan)
        assertCovers(s.main.map { it.startUs to it.durationUs }, s.durationUs)
        assertThat(s.main.last().content).isEqualTo(MainContent.Still(Source.Photo(Fixtures.media("b"))))
    }
}
