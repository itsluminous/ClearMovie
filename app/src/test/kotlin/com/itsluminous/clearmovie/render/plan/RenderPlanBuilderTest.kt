package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.EndingSpec
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.bringToTop
import com.itsluminous.clearmovie.domain.model.isValid
import com.itsluminous.clearmovie.domain.model.moveRow
import com.itsluminous.clearmovie.domain.model.sendBackward
import com.itsluminous.clearmovie.domain.model.setLowerWhenTalking
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.setTrackMuted
import com.itsluminous.clearmovie.domain.model.setTransition
import org.junit.Test
import kotlin.math.abs

class RenderPlanBuilderTest {
    private fun media(n: String) = Fixtures.media(n)

    private fun photo(
        id: String,
        ms: Long = 3000,
    ) = PhotoClip(ClipId(id), media(id), ms)

    private fun video(
        id: String,
        ms: Long = 3000,
    ) = VideoClip(ClipId(id), media(id), sourceDurationMs = ms)

    private val preset = Fixtures.preset

    private fun presetProject(
        vararg items: com.itsluminous.clearmovie.domain.model.MainClip,
        media: PresetMedia = PresetMedia(introMs = 2000, frameMs = 4000, endingMs = 3000),
    ): Project {
        val ids = SequentialIds("x")
        return ProjectFactory.fromPreset(ProjectId("p"), preset, media, ids).addToMain(items.toList(), ids = ids)
    }

    private fun RenderPlan.layer(role: LayerRole) = visual.single { it.role == role }

    private fun RenderPlan.audioOf(role: AudioLayerRole) = audio.filter { it.role == role }

    /** Every layer covers [0, duration) exactly, without gaps or overlaps. */
    private fun RenderPlan.assertGapFree() {
        for (layer in visual) {
            var t = 0L
            for (s in layer.segments) {
                assertThat(s.startUs).isEqualTo(t)
                assertThat(s.durationUs).isGreaterThan(0L)
                t = s.endUs
            }
            assertThat(t).isEqualTo(durationUs)
        }
        for (layer in audio) {
            var t = 0L
            for (s in layer.segments) {
                assertThat(s.startUs).isEqualTo(t)
                t = s.endUs
            }
            assertThat(t).isEqualTo(durationUs)
        }
    }

    @Test
    fun `segment times follow the time mapping, with overlaps taken off`() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("p"), ids)
                .addToMain(listOf(video("a"), video("b"), photo("c", 2000)), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
        val plan = RenderPlanBuilder().build(project)
        assertThat(plan.durationUs).isEqualTo(7_000_000)
        plan.assertGapFree()
        val a = plan.layer(LayerRole.MAIN).segments
        val b = plan.layer(LayerRole.MAIN_B).segments
        assertThat(a.map { it::class.simpleName to (it.startUs to it.endUs) })
            .containsExactly("Picture" to (0L to 3_000_000L), "Fill" to (3_000_000L to 7_000_000L))
            .inOrder()
        assertThat(b.map { it::class.simpleName to (it.startUs to it.endUs) })
            .containsExactly("Fill" to (0L to 2_000_000L), "Picture" to (2_000_000L to 5_000_000L), "Picture" to (5_000_000L to 7_000_000L))
            .inOrder()
        // B is drawn over A and fades in across the overlap.
        val incoming = b[1] as VisualSegment.Picture
        assertThat(incoming.alphaAt(0)).isEqualTo(0f)
        assertThat(incoming.alphaAt(500_000)).isWithin(1e-6f).of(0.5f)
        assertThat(incoming.alphaAt(1_000_000)).isEqualTo(1f)
        assertThat((b[2] as VisualSegment.Picture).alpha).isEmpty()
        assertThat((a[0] as VisualSegment.Picture).alpha).isEmpty()
    }

    @Test
    fun `a clip that leads out of lane B fades out over lane A`() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("p"), ids)
                .addToMain(listOf(video("a"), video("b"), video("c")), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
                .setTransition(ClipId("b"), Transition(TransitionKind.CROSSFADE, 1000))
        val plan = RenderPlanBuilder().build(project)
        assertThat(plan.durationUs).isEqualTo(7_000_000)
        val b =
            plan
                .layer(LayerRole.MAIN_B)
                .segments
                .filterIsInstance<VisualSegment.Picture>()
                .single()
        assertThat(b.startUs to b.endUs).isEqualTo(2_000_000L to 5_000_000L)
        assertThat(b.alphaAt(0)).isEqualTo(0f)
        assertThat(b.alphaAt(1_500_000)).isEqualTo(1f)
        assertThat(b.alphaAt(2_500_000)).isWithin(1e-6f).of(0.5f)
        assertThat(b.alphaAt(3_000_000)).isEqualTo(0f)
        val a = plan.layer(LayerRole.MAIN).segments.filterIsInstance<VisualSegment.Picture>()
        assertThat(a.map { it.startUs }).containsExactly(0L, 4_000_000L).inOrder()
        assertThat(plan.videoDecodersAt(4_500_000)).isEqualTo(2)
        assertThat(plan.videoDecodersAt(3_000_000)).isEqualTo(1)
    }

    @Test
    fun `without a transition MAIN is one lane`() {
        val plan = RenderPlanBuilder().build(Fixtures.blankWith(photo("a"), photo("b")))
        assertThat(plan.visual.map { it.role }).containsExactly(LayerRole.MAIN)
    }

    @Test
    fun `layer order is the stacking order, not the screen order`() {
        val ids = SequentialIds("s")
        var project =
            ProjectFactory
                .fromPreset(ProjectId("p"), preset, PresetMedia(introMs = 2000, frameMs = 4000, endingMs = 3000), ids)
                .addToMain(listOf(video("a")), ids = ids)
                .addTimedClip(PhotoClip(ClipId("pip"), media("pip"), 2000, startMs = 2000, pip = PipPlacement.DEFAULT), ids = ids)
                .addTimedClip(TextClip(ClipId("cap"), 2000, 2000, "Hi"), ids = ids)
        val roles = { p: Project -> RenderPlanBuilder(preset).build(p).visual.map { it.role } }
        assertThat(roles(project)).containsExactly(LayerRole.MAIN, LayerRole.VISUAL, LayerRole.OVERLAY, LayerRole.TEXT).inOrder()
        val text = project.tracks.first { it.type == TrackType.TEXT && it.clips.isNotEmpty() }.id
        // Moving rows on screen changes nothing.
        val moved = project.moveRow(text, project.tracks.lastIndex)
        assertThat(roles(moved)).isEqualTo(roles(project))
        project = project.sendBackward(text)
        assertThat(roles(project)).containsExactly(LayerRole.MAIN, LayerRole.VISUAL, LayerRole.TEXT, LayerRole.OVERLAY).inOrder()
        val pipRow = project.tracks.single { it.type == TrackType.VISUAL }.id
        assertThat(
            roles(project.bringToTop(pipRow)),
        ).containsExactly(LayerRole.MAIN, LayerRole.TEXT, LayerRole.OVERLAY, LayerRole.VISUAL).inOrder()
    }

    @Test
    fun `transparent fills replace every gap on timed rows, and empty rows are left out`() {
        val ids = SequentialIds("g")
        val project =
            Fixtures
                .blankWith(photo("a", 10_000), ids = ids)
                .addTimedClip(TextClip(ClipId("t1"), 1000, 1000, "one"), ids = ids)
                .addTimedClip(TextClip(ClipId("t2"), 5000, 2000, "two"), ids = ids)
        val plan = RenderPlanBuilder().build(project)
        plan.assertGapFree()
        val text = plan.layer(LayerRole.TEXT).segments
        assertThat(text.map { it::class.simpleName }).containsExactly("Fill", "Picture", "Fill", "Picture", "Fill").inOrder()
        assertThat(text.map { it.startUs }).containsExactly(0L, 1_000_000L, 2_000_000L, 5_000_000L, 7_000_000L).inOrder()
        // The blank project's empty Text row draws nothing, so it is not a layer.
        assertThat(plan.visual.count { it.role == LayerRole.TEXT }).isEqualTo(1)
    }

    @Test
    fun `a frame row that starts after the intro begins with a fill, so the intro shows`() {
        val plan = RenderPlanBuilder(preset).build(presetProject(photo("a")))
        val overlay = plan.layer(LayerRole.OVERLAY).segments
        assertThat(overlay.first()).isEqualTo(VisualSegment.Fill(0, 2_000_000))
        val main = plan.layer(LayerRole.MAIN).segments
        val intro = main.first() as VisualSegment.Picture
        assertThat(intro.placement).isEqualTo(Placement.FullFrame)
        assertThat((intro.source as Source.Video).media).isEqualTo(MediaRef.Preset(PresetSlot.INTRO))
    }

    @Test
    fun `the frame repeats and its last copy is cut`() {
        // Intro 2 s, 3 photos of 3 s, ending 3 s: frame from 2 s to 14 s over a 4 s source.
        val plan = RenderPlanBuilder(preset).build(presetProject(photo("a"), photo("b"), photo("c")))
        assertThat(plan.durationUs).isEqualTo(14_000_000)
        val copies = plan.layer(LayerRole.OVERLAY).segments.filterIsInstance<VisualSegment.Picture>()
        assertThat(copies.map { it.startUs to it.durationUs })
            .containsExactly(2_000_000L to 4_000_000L, 6_000_000L to 4_000_000L, 10_000_000L to 4_000_000L)
            .inOrder()
        assertThat(copies.map { it.clipOffsetUs }).containsExactly(0L, 4_000_000L, 8_000_000L).inOrder()
        assertThat(copies.all { it.key == preset.frame!!.key && it.placement == Placement.FullFrame }).isTrue()
        val ids = SequentialIds("x")
        val odd =
            ProjectFactory
                .fromPreset(ProjectId("p"), preset, PresetMedia(introMs = 2000, frameMs = 5000, endingMs = 3000), ids)
                .addToMain(listOf(photo("a"), photo("b"), photo("c")), ids = ids)
        val last =
            RenderPlanBuilder(preset)
                .build(odd)
                .layer(LayerRole.OVERLAY)
                .segments
                .filterIsInstance<VisualSegment.Picture>()
                .last()
        assertThat(last.durationUs).isEqualTo(2_000_000)
        assertThat((last.source as Source.Video).clipEndUs).isEqualTo(2_000_000)
    }

    @Test
    fun `photos go into the frame window while the frame row is on, else the whole canvas`() {
        val project = presetProject(PhotoClip(ClipId("a"), media("a"), 3000, fit = Fit.FIT_BLUR))
        val window = RenderPlanBuilder(preset).build(project).layer(LayerRole.MAIN).segments[1] as VisualSegment.Picture
        assertThat(window.placement).isEqualTo(Placement.Window(preset.frame!!.contentRect, Fit.FIT_BLUR))
        val frameRow = project.tracks.single { it.presetRole == PresetRole.FRAME }.id
        val off = RenderPlanBuilder(preset).build(project.setTrackEnabled(frameRow, false))
        assertThat(off.visual.none { it.role == LayerRole.OVERLAY }).isTrue()
        assertThat(
            (off.layer(LayerRole.MAIN).segments[1] as VisualSegment.Picture).placement,
        ).isEqualTo(Placement.Window(NormRect.FULL, Fit.FIT_BLUR))
    }

    @Test
    fun `a spoken ending holds the last item, and the frame stays over it`() {
        val plan =
            RenderPlanBuilder(
                preset,
            ).build(presetProject(photo("a"), VideoClip(ClipId("v"), media("v"), sourceDurationMs = 4000, trimEndMs = 3000)))
        val held = plan.layer(LayerRole.MAIN).segments.last() as VisualSegment.Picture
        val src = held.source as Source.VideoFrame
        assertThat(src.media).isEqualTo(media("v"))
        assertThat(src.atUs).isLessThan(3_000_000L)
        assertThat(src.atUs).isAtLeast(2_900_000L)
        assertThat(held.startUs to held.endUs).isEqualTo(8_000_000L to 11_000_000L)
        assertThat(
            plan
                .layer(LayerRole.OVERLAY)
                .segments
                .last()
                .endUs,
        ).isEqualTo(11_000_000L)
        assertThat(plan.layer(LayerRole.OVERLAY).segments.last()).isInstanceOf(VisualSegment.Picture::class.java)
    }

    @Test
    fun `a video ending is drawn full frame and the frame stops before it (coverEnding auto)`() {
        val videoEnding = preset.copy(ending = EndingSpec(EndingKind.VIDEO))
        val ids = SequentialIds("x")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("p"), videoEnding, PresetMedia(introMs = 2000, frameMs = 4000, endingMs = 3000), ids)
                .addToMain(listOf(photo("a")), ids = ids)
        val plan = RenderPlanBuilder(videoEnding).build(project)
        val ending = plan.layer(LayerRole.MAIN).segments.last() as VisualSegment.Picture
        assertThat(ending.placement).isEqualTo(Placement.FullFrame)
        assertThat((ending.source as Source.Video).media).isEqualTo(MediaRef.Preset(PresetSlot.ENDING))
        val overlay = plan.layer(LayerRole.OVERLAY).segments
        assertThat(overlay.last()).isEqualTo(VisualSegment.Fill(5_000_000, 3_000_000))
        // The ending's own sound is clip sound.
        assertThat(
            plan
                .audioOf(AudioLayerRole.CLIP)
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
                .map { it.media },
        ).contains(MediaRef.Preset(PresetSlot.ENDING))
    }

    @Test
    fun `loops become copies whose clip offsets continue, and speed sets the length`() {
        val clip =
            VideoClip(ClipId("v"), media("v"), sourceDurationMs = 3000, trimStartMs = 500, trimEndMs = 2000, loops = 3, speed = Speed.X2)
        val plan = RenderPlanBuilder().build(Fixtures.blankWith(clip))
        assertThat(plan.durationUs).isEqualTo(2_250_000)
        val copies = plan.layer(LayerRole.MAIN).segments.filterIsInstance<VisualSegment.Picture>()
        assertThat(copies.map { it.durationUs }).containsExactly(750_000L, 750_000L, 750_000L)
        assertThat(copies.map { it.clipOffsetUs }).containsExactly(0L, 750_000L, 1_500_000L).inOrder()
        val src = copies.first().source as Source.Video
        assertThat(src.clipStartUs to src.clipEndUs).isEqualTo(500_000L to 2_000_000L)
        assertThat(src.speed).isEqualTo(Speed.X2)
        val sounds = plan.audioOf(AudioLayerRole.CLIP).flatMap { it.segments }.filterIsInstance<AudioSegment.Sound>()
        assertThat(sounds).hasSize(3)
        assertThat(sounds.all { it.speed == Speed.X2 && it.clipStartUs == 500_000L }).isTrue()
    }

    @Test
    fun `picture in picture goes in its corner inside the window`() {
        val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)
        val tr = RenderPlanBuilder.pipRect(PipPlacement(Corner.TOP_RIGHT, 0.35), window, 16.0 / 9)
        assertThat(tr.w).isWithin(1e-9).of(window.w * 0.35)
        assertThat(tr.h).isWithin(1e-9).of(tr.w)
        assertThat(tr.x + tr.w).isLessThan(window.x + window.w)
        assertThat(tr.y).isGreaterThan(window.y)
        val bl = RenderPlanBuilder.pipRect(PipPlacement(Corner.BOTTOM_LEFT, 0.35), window, 16.0 / 9)
        assertThat(bl.x).isGreaterThan(window.x)
        assertThat(bl.y + bl.h).isLessThan(window.y + window.h)
        val huge = RenderPlanBuilder.pipRect(PipPlacement(Corner.TOP_LEFT, 0.9), window, 16.0 / 9)
        assertThat(huge.isValid).isTrue()
        assertThat(huge.y + huge.h).isAtMost(window.y + window.h + 1e-9)
    }

    @Test
    fun `caption windows follow the text clips, and blank captions draw nothing`() {
        val ids = SequentialIds("c")
        val project =
            Fixtures
                .blankWith(photo("a", 6000), ids = ids)
                .addTimedClip(TextClip(ClipId("t1"), 1000, 2000, "Hello"), ids = ids)
        val plan = RenderPlanBuilder().build(project)
        val caption =
            plan
                .layer(LayerRole.TEXT)
                .segments
                .filterIsInstance<VisualSegment.Picture>()
                .single()
        assertThat(caption.startUs to caption.endUs).isEqualTo(1_000_000L to 3_000_000L)
        assertThat(caption.placement).isEqualTo(Placement.Box(project.captionDefaults.box))
        assertThat((caption.source as Source.Caption).text).isEqualTo("Hello")
    }

    @Test
    fun `output size is 16 by 9 with an even width`() {
        assertThat(RenderPlanBuilder.evenWidthFor(1080)).isEqualTo(1920)
        assertThat(RenderPlanBuilder.evenWidthFor(720)).isEqualTo(1280)
        assertThat(RenderPlanBuilder.evenWidthFor(480)).isEqualTo(854)
        val plan = RenderPlanBuilder().build(Fixtures.blankWith(photo("a")), height = 720)
        assertThat(plan.width to plan.height).isEqualTo(1280 to 720)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an absurd output height is refused`() {
        RenderPlanBuilder().build(Fixtures.blankWith(photo("a")), height = 100_000)
    }

    @Test
    fun `a slice starts later in its media and keeps transitions in step`() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("p"), ids)
                .addToMain(listOf(video("a"), video("b")), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
        val full = RenderPlanBuilder().build(project)
        val slice = full.slice(2_500_000, 4_000_000)
        assertThat(slice.durationUs).isEqualTo(1_500_000)
        slice.assertGapFree()
        val b = slice.layer(LayerRole.MAIN_B).segments.first() as VisualSegment.Picture
        assertThat(b.clipOffsetUs).isEqualTo(500_000)
        assertThat(b.alphaAt(b.clipOffsetUs)).isWithin(1e-6f).of(0.5f)
        assertThat((b.source as Source.Video).clipStartUs).isEqualTo(500_000)
        val a = slice.layer(LayerRole.MAIN).segments.first() as VisualSegment.Picture
        assertThat((a.source as Source.Video).clipStartUs).isEqualTo(2_500_000)
        val sound =
            slice.audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
                .first { it.media == media("b") }
        assertThat(sound.clipStartUs).isEqualTo(500_000)
        // The equal-power envelope is at its midpoint (-3 dB) at the slice start.
        assertThat(sound.gain.dbAt(0)).isWithin(0.05).of(-3.01)
    }

    // ---- Audio and ducking -----------------------------------------------------

    /** Intro 0-2, photo 2-5, video 5-8, photo 8-11, held frame 11-14; music 2-14; closing message 11-14. */
    private fun duckingProject(): Project {
        val ids = SequentialIds("d")
        return ProjectFactory
            .fromPreset(ProjectId("d"), preset, PresetMedia(introMs = 2000, frameMs = 20_000, endingMs = 3000), ids)
            .addToMain(listOf(photo("p1"), video("v"), photo("p2")), ids = ids)
    }

    private fun RenderPlan.musicGainAt(timeUs: Long): Double {
        val seg =
            audioOf(AudioLayerRole.MUSIC).single().segments.filterIsInstance<AudioSegment.Sound>().single {
                timeUs >= it.startUs &&
                    timeUs < it.endUs
            }
        return seg.gain.dbAt(timeUs - seg.startUs)
    }

    @Test
    fun `music ducks under clip sound and under the closing message, with attack and release`() {
        val plan = RenderPlanBuilder(preset).build(duckingProject())
        val base = preset.frame!!.music.gainDb
        assertThat(plan.musicGainAt(3_500_000)).isWithin(1e-6).of(base)
        assertThat(plan.musicGainAt(6_500_000)).isWithin(1e-6).of(base - 12)
        // Attack starts 300 ms before the video; release ends 300 ms after it.
        assertThat(plan.musicGainAt(4_700_000)).isWithin(1e-6).of(base)
        assertThat(plan.musicGainAt(4_850_000)).isWithin(1e-6).of(base - 6)
        assertThat(plan.musicGainAt(8_150_000)).isWithin(1e-6).of(base - 6)
        assertThat(plan.musicGainAt(8_300_000)).isWithin(1e-6).of(base)
        assertThat(plan.musicGainAt(12_500_000)).isWithin(1e-6).of(base - 20)
    }

    @Test
    fun `music ducks deeper under a voice row, and the lowest duck wins`() {
        val ids = SequentialIds("vv")
        val project =
            duckingProject().addTimedClip(
                AudioClip(ClipId("voice"), media("voice"), startMs = 6000, durationMs = 3000, sourceDurationMs = 3000),
                role = AudioRole.VOICE,
                ids = ids,
            )
        val plan = RenderPlanBuilder(preset).build(project)
        val base = preset.frame!!.music.gainDb
        assertThat(plan.musicGainAt(5_500_000)).isWithin(1e-6).of(base - 12)
        assertThat(plan.musicGainAt(7_000_000)).isWithin(1e-6).of(base - 20)
        assertThat(plan.musicGainAt(8_600_000)).isWithin(1e-6).of(base - 20)
    }

    @Test
    fun `clip sound and voice are never lowered by music, only by the closing message`() {
        val ids = SequentialIds("vv")
        val project =
            duckingProject().addTimedClip(
                AudioClip(ClipId("voice"), media("voice"), startMs = 9000, durationMs = 4000, sourceDurationMs = 4000),
                role = AudioRole.VOICE,
                ids = ids,
            )
        val plan = RenderPlanBuilder(preset).build(project)
        val clip =
            plan.audioOf(AudioLayerRole.CLIP).flatMap { it.segments }.filterIsInstance<AudioSegment.Sound>().single {
                it.media ==
                    media("v")
            }
        assertThat(clip.gain.isUnity).isTrue()
        val voice =
            plan
                .audioOf(AudioLayerRole.VOICE)
                .single()
                .segments
                .filterIsInstance<AudioSegment.Sound>()
                .single()
        assertThat(voice.gain.dbAt(500_000)).isWithin(1e-6).of(0.0)
        assertThat(voice.gain.dbAt(3_000_000)).isWithin(1e-6).of(-20.0)
        val closing =
            plan
                .audioOf(AudioLayerRole.CLOSING_MESSAGE)
                .single()
                .segments
                .filterIsInstance<AudioSegment.Sound>()
                .single()
        assertThat(closing.gain.isUnity).isTrue()
        assertThat(closing.startUs).isEqualTo(11_000_000)
    }

    @Test
    fun `lower music when people talk off keeps only the closing-message duck`() {
        val project = duckingProject()
        val music = project.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }.id
        val plan = RenderPlanBuilder(preset).build(project.setLowerWhenTalking(music, false))
        val base = preset.frame!!.music.gainDb
        assertThat(plan.musicGainAt(6_500_000)).isWithin(1e-6).of(base)
        assertThat(plan.musicGainAt(12_500_000)).isWithin(1e-6).of(base - 20)
    }

    @Test
    fun `quiet or muted clip sound does not lower the music`() {
        val ids = SequentialIds("d")
        val quiet =
            ProjectFactory
                .fromPreset(ProjectId("d"), preset, PresetMedia(introMs = 2000, frameMs = 20_000, endingMs = 3000), ids)
                .addToMain(
                    listOf(photo("p1"), VideoClip(ClipId("v"), media("v"), sourceDurationMs = 3000, volumeDb = -50.0), photo("p2")),
                    ids = ids,
                )
        assertThat(RenderPlanBuilder(preset).build(quiet).musicGainAt(6_500_000)).isWithin(1e-6).of(preset.frame!!.music.gainDb)
        val muted = duckingProject().let { it.setTrackMuted(it.main.id, true) }
        val plan = RenderPlanBuilder(preset).build(muted)
        assertThat(plan.musicGainAt(6_500_000)).isWithin(1e-6).of(preset.frame!!.music.gainDb)
        assertThat(plan.audioOf(AudioLayerRole.CLIP)).isEmpty()
    }

    @Test
    fun `a muted music row is left out`() {
        val project = duckingProject()
        val music = project.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }.id
        assertThat(RenderPlanBuilder(preset).build(project.setTrackMuted(music, true)).audioOf(AudioLayerRole.MUSIC)).isEmpty()
    }

    @Test
    fun `loudness is normalised to the targets, within limits`() {
        val table =
            LoudnessTable { ref ->
                if (ref ==
                    MediaRef.Preset(PresetSlot.FRAME)
                ) {
                    -11.0
                } else if (ref == MediaRef.Preset(PresetSlot.ENDING)) {
                    -27.0
                } else {
                    null
                }
            }
        val plan = RenderPlanBuilder(preset, table).build(duckingProject())
        // Music measured at -11 LUFS needs -12 dB to reach -23; voice at -27 needs +11.
        assertThat(plan.musicGainAt(3_500_000)).isWithin(1e-6).of(preset.frame!!.music.gainDb - 12)
        val closing =
            plan
                .audioOf(AudioLayerRole.CLOSING_MESSAGE)
                .single()
                .segments
                .filterIsInstance<AudioSegment.Sound>()
                .single()
        assertThat(closing.gain.dbAt(1_000_000)).isWithin(1e-6).of(11.0)
        val wild = LoudnessTable { -90.0 }
        val loud = RenderPlanBuilder(preset, wild).build(duckingProject())
        assertThat(loud.musicGainAt(3_500_000)).isWithin(1e-6).of(preset.frame!!.music.gainDb + RenderPlanBuilder.MAX_NORM_DB)
    }

    @Test
    fun `a repeating soundtrack dips to silence at each join`() {
        val ids = SequentialIds("r")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("r"), preset, PresetMedia(introMs = 2000, frameMs = 4000, endingMs = 3000), ids)
                .addToMain(listOf(photo("a", 5000), photo("b", 5000)), ids = ids)
        val plan = RenderPlanBuilder(preset).build(project)
        val music =
            plan
                .audioOf(AudioLayerRole.MUSIC)
                .single()
                .segments
                .filterIsInstance<AudioSegment.Sound>()
        assertThat(music.size).isAtLeast(3)
        // The first copy ends silent and the second starts silent.
        val first = music[0]
        assertThat(first.gain.dbAt(first.durationUs)).isEqualTo(GainEnvelope.SILENT_DB)
        assertThat(music[1].gain.dbAt(0)).isEqualTo(GainEnvelope.SILENT_DB)
        assertThat(music[1].gain.dbAt(music[1].durationUs / 2)).isGreaterThan(-30.0)
    }

    @Test
    fun `a crossfade gives equal-power audio, constant in sum of power`() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("p"), ids)
                .addToMain(listOf(video("a"), video("b")), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
        val sounds =
            RenderPlanBuilder()
                .build(project)
                .audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
        val a = sounds.single { it.media == media("a") }
        val b = sounds.single { it.media == media("b") }
        for (t in listOf(2_000_000L, 2_250_000L, 2_500_000L, 2_750_000L, 3_000_000L)) {
            val ga = a.gain.gainAt(t - a.startUs).toDouble()
            val gb = b.gain.gainAt(t - b.startUs).toDouble()
            assertThat(abs(ga * ga + gb * gb - 1.0)).isLessThan(0.03)
        }
    }

    @Test
    fun `every plan of the preset project is gap free`() {
        val ids = SequentialIds("z")
        val project =
            presetProject(photo("a"), video("b"), photo("c"))
                .addTimedClip(PhotoClip(ClipId("pp"), media("pp"), 2000, startMs = 5000, pip = PipPlacement.DEFAULT), ids = ids)
                .addTimedClip(TextClip(ClipId("tt"), 4000, 2500, "x"), ids = ids)
        RenderPlanBuilder(preset).build(project).assertGapFree()
        RenderPlanBuilder(preset).build(project).slice(1_234_567, 9_876_543).assertGapFree()
    }

    @Test
    fun `an invalid project is refused`() {
        val bad = Fixtures.blankWith(photo("a")).let { it.copy(stacking = it.stacking.drop(1)) }
        val threw =
            try {
                RenderPlanBuilder().build(bad)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
        assertThat(threw).isTrue()
    }

    @Test
    fun `a tiny repeated overlay over a long video is refused instead of making thousands of items`() {
        val ids = SequentialIds("o")
        val project =
            Fixtures
                .blankWith(video("long", 600_000), ids = ids)
                .addTimedClip(
                    com.itsluminous.clearmovie.domain.model.OverlayClip(
                        ClipId("ov"),
                        media("ov"),
                        startMs = 0,
                        durationMs = 600_000,
                        sourceDurationMs = 200,
                        repeat = true,
                    ),
                    ids = ids,
                )
        assertThat(project.isValid).isTrue()
        val message =
            try {
                RenderPlanBuilder().build(project)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        assertThat(message).contains("too many pieces")
    }

    @Test
    fun `a spoken ending straight after the intro holds the intro's last frame`() {
        val plan = RenderPlanBuilder(preset).build(presetProject())
        val held = plan.layer(LayerRole.MAIN).segments.last() as VisualSegment.Picture
        val src = held.source as Source.VideoFrame
        assertThat(src.media).isEqualTo(MediaRef.Preset(PresetSlot.INTRO))
        assertThat(src.atUs).isIn(
            com.google.common.collect.Range
                .closed(1_900_000L, 2_000_000L),
        )
        assertThat(held.placement).isEqualTo(Placement.FullFrame)
    }

    @Test
    fun `ducking follows the lower of two crossing ramps exactly`() {
        // A clip ends at 1 s (release to 0 over 300 ms from -12); a voice starts at
        // 1.2 s (attack from 0 over 300 ms to -20). The ramps cross at 1.05 s, -10 dB.
        val env =
            RenderPlanBuilder.duckEnvelope(
                listOf(DuckInterval(0, 1_000_000, -12.0), DuckInterval(1_200_000, 3_000_000, -20.0)),
                300_000,
                300_000,
            )
        assertThat(env.dbAt(1_050_000)).isWithin(1e-6).of(-10.0)
        assertThat(env.dbAt(1_000_000)).isWithin(1e-6).of(-12.0)
        assertThat(env.dbAt(1_100_000)).isWithin(1e-6).of(-13.333333)
    }

    @Test
    fun `a crossfade keeps constant power between its breakpoints too`() {
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("p"), ids)
                .addToMain(listOf(video("a"), video("b")), ids = ids)
                .setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
        val sounds =
            RenderPlanBuilder()
                .build(project)
                .audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
        val a = sounds.single { it.media == media("a") }
        val b = sounds.single { it.media == media("b") }
        // Off the 32 construction points, after the first 1/32 (where the incoming side rises from silence).
        for (k in 2 until 64 step 3) {
            val t = 2_000_000L + 1_000_000L * k / 64 + 7_000
            val ga = a.gain.gainAt(t - a.startUs).toDouble()
            val gb = b.gain.gainAt(t - b.startUs).toDouble()
            assertThat(abs(ga * ga + gb * gb - 1.0)).isLessThan(0.05)
        }
    }
}
