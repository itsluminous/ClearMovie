package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.FrameMusic
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.setTalkLowering
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.setTrackMuted
import com.itsluminous.clearmovie.domain.model.setVolume
import com.itsluminous.clearmovie.domain.model.withVideoSound
import org.junit.Test

/**
 * A frame video's own sound (cm-frame-audio): a frame the user put on a
 * frame row plays its file's sound under the video like music, at its own
 * volume, in step with its picture, and lowered for talk as its row says;
 * the preset's frame keeps playing its sound as the frame music row only.
 * Only a frame with a measured loudness plays: one without sound has none.
 */
class FrameSoundPlanTest {
    private val ids = SequentialIds("f")

    /** The frame's sound measured at the music target, so it plays at its volume unchanged. */
    private val measured = LoudnessTable { if (it == Fixtures.media("fr")) RenderPlanBuilder.MUSIC_LUFS else null }

    /** Without a preset: a 3 s photo, a 3 s video with its own sound (3..6 s), a 4 s photo; a frame over 1..9 s. */
    private fun project(frame: OverlayClip = frame()): Project =
        Fixtures
            .blankWith(Fixtures.photo("p1"), Fixtures.video("v", 3000), Fixtures.photo("p2", 4000), ids = ids)
            .addTimedClip(frame, ids = ids)

    private fun frame(
        start: Long = 1000,
        ms: Long = 8000,
        sourceMs: Long = 8000,
        repeat: Boolean = false,
    ) = OverlayClip(ClipId("fr"), Fixtures.media("fr"), start, ms, sourceDurationMs = sourceMs, repeat = repeat)

    private val Project.frameRow: TrackId get() = tracks.single { it.type == TrackType.OVERLAY }.id

    private fun RenderPlan.frameSound(row: TrackId): List<AudioSegment.Sound> =
        audio.single { it.trackId == row }.segments.filterIsInstance<AudioSegment.Sound>()

    private fun List<AudioSegment.Sound>.dbAt(us: Long): Double {
        val s = single { us >= it.startUs && us < it.endUs }
        return s.gain.dbAt(us - s.startUs)
    }

    @Test
    fun `a frame on a frame row plays its own sound like music, lowered under a video's sound`() {
        val p = project()
        val plan = RenderPlanBuilder(null, measured).build(p)
        val layer = plan.audio.single { it.trackId == p.frameRow }
        assertThat(layer.role).isEqualTo(AudioLayerRole.MUSIC)
        val sound = plan.frameSound(p.frameRow).single()
        assertThat(sound.media).isEqualTo(Fixtures.media("fr"))
        assertThat(sound.startUs).isEqualTo(1_000_000)
        assertThat(sound.durationUs).isEqualTo(8_000_000)
        assertThat(sound.clipStartUs).isEqualTo(0)
        assertThat(sound.clipEndUs).isEqualTo(8_000_000)
        val base = FrameMusic.DEFAULT_GAIN_DB
        assertThat(plan.frameSound(p.frameRow).dbAt(2_000_000)).isWithin(1e-6).of(base)
        // Under the video's own sound (3..6 s): the project's level under a video, -12 dB.
        assertThat(plan.frameSound(p.frameRow).dbAt(4_500_000)).isWithin(1e-6).of(base - 12)
        assertThat(plan.frameSound(p.frameRow).dbAt(7_500_000)).isWithin(1e-6).of(base)
        plan.assertCoversAll()
    }

    @Test
    fun `the frame's volume sets its level, and its row's Lower for talk lowers it like a music row`() {
        val p = project().setVolume(ClipId("fr"), -2.0)
        val row = p.frameRow
        assertThat(RenderPlanBuilder(null, measured).build(p).frameSound(row).dbAt(2_000_000)).isWithin(1e-6).of(-2.0)
        val lowered = RenderPlanBuilder(null, measured).build(p.setTalkLowering(row, lower = true, levelDb = -30.0))
        assertThat(lowered.frameSound(row).dbAt(4_500_000)).isWithin(1e-6).of(-32.0)
        val off = RenderPlanBuilder(null, measured).build(p.setTalkLowering(row, lower = false, levelDb = null))
        assertThat(off.frameSound(row).dbAt(4_500_000)).isWithin(1e-6).of(-2.0)
    }

    @Test
    fun `a frame is lowered under a voice, and its sound never lowers anything`() {
        val p =
            project().addTimedClip(
                AudioClip(ClipId("voice"), Fixtures.media("voice"), startMs = 7000, durationMs = 1500, sourceDurationMs = 1500),
                role = AudioRole.VOICE,
                ids = ids,
            )
        val plan = RenderPlanBuilder(null, measured).build(p)
        assertThat(plan.frameSound(p.frameRow).dbAt(7_700_000)).isWithin(1e-6).of(FrameMusic.DEFAULT_GAIN_DB - 20)
        val voice = plan.audio.single { it.role == AudioLayerRole.VOICE }
        assertThat(
            voice.segments
                .filterIsInstance<AudioSegment.Sound>()
                .single()
                .gain.isUnity,
        ).isTrue()
        val clip =
            plan.audio
                .filter { it.role == AudioLayerRole.CLIP }
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
        assertThat(clip.single { it.media == Fixtures.media("v") }.gain.isUnity).isTrue()
    }

    @Test
    fun `a repeating frame's sound starts again with each copy of its picture, dipping at the joins`() {
        val p = project(frame(start = 0, ms = 10_000, sourceMs = 4000, repeat = true))
        val plan = RenderPlanBuilder(null, measured).build(p)
        val pictures =
            plan.visual
                .single { it.trackId == p.frameRow }
                .segments
                .filterIsInstance<VisualSegment.Picture>()
        val sounds = plan.frameSound(p.frameRow)
        assertThat(sounds.map { it.startUs }).isEqualTo(pictures.map { it.startUs })
        assertThat(sounds.map { it.durationUs }).isEqualTo(pictures.map { it.durationUs })
        assertThat(sounds.map { it.startUs }).containsExactly(0L, 4_000_000L, 8_000_000L).inOrder()
        assertThat(sounds.all { it.clipStartUs == 0L }).isTrue()
        assertThat(sounds.last().clipEndUs).isEqualTo(2_000_000)
        assertThat(sounds[0].gain.dbAt(sounds[0].durationUs)).isEqualTo(GainEnvelope.SILENT_DB)
        assertThat(sounds[1].gain.dbAt(0)).isEqualTo(GainEnvelope.SILENT_DB)
        plan.assertCoversAll()
    }

    @Test
    fun `the preset's frame plays its sound once, as the frame music row`() {
        val p = Fixtures.presetProject().let { it.addTimedClip(frame(start = 8000, ms = 3000), ids = SequentialIds("u")) }
        val plan = RenderPlanBuilder(Fixtures.preset, measured).build(p)
        val presetFrame = p.tracks.single { it.presetRole == PresetRole.FRAME }.id
        assertThat(plan.audio.none { it.trackId == presetFrame }).isTrue()
        val music = plan.audio.filter { it.role == AudioLayerRole.MUSIC }
        val userRow = p.tracks.single { it.type == TrackType.OVERLAY && it.presetRole == null }.id
        assertThat(music.map { it.trackId })
            .containsExactly(p.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }.id, userRow)
    }

    @Test
    fun `a frame's sound is brought to the music's loudness, as a music row's is`() {
        val p = project()
        val plan = RenderPlanBuilder(null, LoudnessTable { if (it == Fixtures.media("fr")) -13.0 else null }).build(p)
        // -13 LUFS to the -23 LUFS music target: 10 dB down, then the frame's own -8 dB.
        assertThat(plan.frameSound(p.frameRow).dbAt(2_000_000)).isWithin(1e-6).of(FrameMusic.DEFAULT_GAIN_DB - 10)
    }

    @Test
    fun `a frame whose sound was never measured (no sound track, or unreadable) plays none`() {
        val p = project()
        assertThat(RenderPlanBuilder(null).build(p).audio.none { it.trackId == p.frameRow }).isTrue()
    }

    @Test
    fun `a frame's own kept loudness is used before the table's, so preview and export agree`() {
        val p = project(frame().copy(soundLufs = -13.0))
        // No table at all (the editor before it measured anything): the frame's own value.
        assertThat(
            RenderPlanBuilder(null).build(p).frameSound(p.frameRow).dbAt(2_000_000),
        ).isWithin(1e-6).of(FrameMusic.DEFAULT_GAIN_DB - 10)
        // A table that says otherwise does not change it.
        val other = LoudnessTable { -40.0 }
        assertThat(
            RenderPlanBuilder(null, other).build(p).frameSound(p.frameRow).dbAt(2_000_000),
        ).isWithin(1e-6).of(FrameMusic.DEFAULT_GAIN_DB - 10)
    }

    @Test
    fun `a switched-off or muted frame row plays no sound`() {
        val p = project()
        assertThat(
            RenderPlanBuilder(null, measured).build(p.setTrackEnabled(p.frameRow, false)).audio.none { it.trackId == p.frameRow },
        ).isTrue()
        assertThat(
            RenderPlanBuilder(null, measured).build(p.setTrackMuted(p.frameRow, true)).audio.none { it.trackId == p.frameRow },
        ).isTrue()
    }

    @Test
    fun `the preview plays the frame's sound as a sound row, at the export's levels`() {
        val p = project()
        val plan = RenderPlanBuilder(null, measured).build(p)
        val lane = PreviewSchedule.of(plan).sounds.single { it.trackId == p.frameRow }
        assertThat(lane.role).isEqualTo(AudioLayerRole.MUSIC)
        for (us in listOf(2_000_000L, 4_500_000L, 7_500_000L)) {
            assertThat(lane.gainDbAt(us)!!).isWithin(1e-6).of(plan.frameSound(p.frameRow).dbAt(us))
        }
        // The frame's own picture player stays muted: its sound comes from the sound row only.
        assertThat(PreviewSchedule.of(plan).overlays.map { it.trackId }).contains(p.frameRow)
    }

    @Test
    fun `a video whose file has no sound lowers neither the frame nor music, one with sound or unknown does`() {
        // kupraki's call: lower only under videos with sound. A file not known either way lowers them, as before.
        val p = project()
        val music =
            p.addTimedClip(
                AudioClip(ClipId("song"), Fixtures.media("song"), startMs = 0, durationMs = 9000, sourceDurationMs = 9000),
                role = AudioRole.MUSIC,
                ids = ids,
            )
        val silent = SoundPresence { if (it == Fixtures.media("v")) false else null }
        val loud = SoundPresence { if (it == Fixtures.media("v")) true else null }
        val base = FrameMusic.DEFAULT_GAIN_DB
        assertThat(RenderPlanBuilder(null, measured, silent).build(p).frameSound(p.frameRow).dbAt(4_500_000)).isWithin(1e-6).of(base)
        assertThat(RenderPlanBuilder(null, measured, loud).build(p).frameSound(p.frameRow).dbAt(4_500_000)).isWithin(1e-6).of(base - 12)
        assertThat(RenderPlanBuilder(null, measured).build(p).frameSound(p.frameRow).dbAt(4_500_000)).isWithin(1e-6).of(base - 12)
        val songRow = music.tracks.single { it.audioRole == AudioRole.MUSIC }.id

        fun songAt(plan: RenderPlan) = plan.frameSound(songRow).dbAt(4_500_000)
        assertThat(songAt(RenderPlanBuilder(null, measured, silent).build(music))).isWithin(1e-6).of(0.0)
        assertThat(songAt(RenderPlanBuilder(null, measured, loud).build(music))).isWithin(1e-6).of(-12.0)
    }

    @Test
    fun `a silent intro lowers nothing either, and only what would lower something is looked at`() {
        val preset = Fixtures.presetProject()
        val intro = MediaRef.Preset(com.itsluminous.clearmovie.domain.model.PresetSlot.INTRO)
        val p = preset.addTimedClip(frame(start = 0, ms = 5000), ids = SequentialIds("u"))
        val row = p.tracks.single { it.type == TrackType.OVERLAY && it.presetRole == null }.id
        val silentIntro = SoundPresence { if (it == intro) false else null }
        val base = FrameMusic.DEFAULT_GAIN_DB
        assertThat(
            RenderPlanBuilder(Fixtures.preset, measured, silentIntro).build(p).frameSound(row).dbAt(2_000_000),
        ).isWithin(1e-6).of(base)
        assertThat(RenderPlanBuilder(Fixtures.preset, measured).build(p).frameSound(row).dbAt(2_000_000)).isWithin(1e-6).of(base - 12)
        assertThat(duckingMedia(p)).contains(intro)
        // Nothing to lower (no music, no frame of the user's): nothing to look at.
        val bare = Fixtures.blankWith(Fixtures.video("v1"), ids = SequentialIds("b"))
        assertThat(duckingMedia(bare)).isEmpty()
        assertThat(
            duckingMedia(bare.addTimedClip(frame(start = 0, ms = 2000), ids = SequentialIds("c"))),
        ).containsExactly(Fixtures.media("v1"))
        // A video that keeps its answer is not looked at, and its answer wins over the reader's.
        val kept = withFrame0(bare).withVideoSound(mapOf(Fixtures.media("v1") to false))
        assertThat(duckingMedia(kept)).isEmpty()
        val keptRow = kept.tracks.single { it.type == TrackType.OVERLAY }.id
        val says = SoundPresence { true }
        assertThat(RenderPlanBuilder(null, measured, says).build(kept).frameSound(keptRow).dbAt(1_000_000)).isWithin(1e-6).of(base)
        // Lower for talk off on the only row that could be lowered: nothing to look at.
        assertThat(
            duckingMedia(
                withFrame0(bare).let {
                    it.setTalkLowering(
                        it.tracks
                            .single { t ->
                                t.type == TrackType.OVERLAY
                            }.id,
                        false,
                        null,
                    )
                },
            ),
        ).isEmpty()
        // A muted frame row lowers nothing, so nothing is looked at for it.
        val withFrame = bare.addTimedClip(frame(start = 0, ms = 2000), ids = SequentialIds("d"))
        val muted = withFrame.setTrackMuted(withFrame.frameRow, true)
        assertThat(duckingMedia(muted)).isEmpty()
    }

    @Test
    fun `a picture-in-picture video without sound lowers nothing either`() {
        val pip =
            VideoClip(
                ClipId("pv"),
                Fixtures.media("pv"),
                sourceDurationMs = 3000,
                startMs = 3000,
                pip = com.itsluminous.clearmovie.domain.model.PipPlacement.DEFAULT,
            )
        val p = Fixtures.blankWith(Fixtures.photo("p1", 10_000), ids = ids).addTimedClip(frame(), ids = ids).addTimedClip(pip, ids = ids)
        val silent = SoundPresence { if (it == Fixtures.media("pv")) false else null }
        val base = FrameMusic.DEFAULT_GAIN_DB
        assertThat(RenderPlanBuilder(null, measured, silent).build(p).frameSound(p.frameRow).dbAt(4_500_000)).isWithin(1e-6).of(base)
        assertThat(RenderPlanBuilder(null, measured).build(p).frameSound(p.frameRow).dbAt(4_500_000)).isWithin(1e-6).of(base - 12)
    }

    @Test
    fun `a video on MAIN with its sound turned down does not lower the frame`() {
        val p =
            Fixtures
                .blankWith(
                    Fixtures.photo("p1"),
                    VideoClip(ClipId("v"), Fixtures.media("v"), sourceDurationMs = 3000, volumeDb = -50.0),
                    Fixtures.photo("p2", 4000),
                    ids = ids,
                ).addTimedClip(frame(), ids = ids)
        assertThat(
            RenderPlanBuilder(null, measured).build(p).frameSound(p.frameRow).dbAt(4_500_000),
        ).isWithin(1e-6).of(FrameMusic.DEFAULT_GAIN_DB)
    }

    private fun withFrame0(p: Project) = p.addTimedClip(frame(start = 0, ms = 2000), ids = SequentialIds("e"))

    private fun RenderPlan.assertCoversAll() {
        for (layer in audio) {
            var t = 0L
            for (s in layer.segments) {
                assertThat(s.startUs).isEqualTo(t)
                t = s.endUs
            }
            assertThat(t).isEqualTo(durationUs)
        }
    }
}
