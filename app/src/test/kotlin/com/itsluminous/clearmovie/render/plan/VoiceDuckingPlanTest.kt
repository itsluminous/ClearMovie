package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.FrameMusic
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.SoundRank
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setTalkLowering
import com.itsluminous.clearmovie.domain.model.setTrackMuted
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.domain.model.setVolume
import com.itsluminous.clearmovie.domain.model.validate
import org.junit.Test

/**
 * The ducking ladder (cm-voice-ducking, kupraki: "Lowest priority is Frame
 * sound, then music sound, then video sound, then voice sound"). While a
 * row's sound plays, every row below it in the ladder is lowered, unless
 * that row's Lower for talk says otherwise; a row is never lowered under a
 * row below it, and an item that makes no sound lowers nothing.
 *
 * The staircase, without a preset: MAIN is a 3 s photo, a 6 s video with
 * sound (3..9 s) and a 4 s photo; a frame of the user's with sound over
 * 0..12 s; music over 1.5..10.5 s; a voice over 5..7.5 s. Ramps are the
 * project's 300 ms.
 */
class VoiceDuckingPlanTest {
    private val ids = SequentialIds("vd")
    private val frameDb = FrameMusic.DEFAULT_GAIN_DB

    /** Every sound measured at its target, so each plays at its own volume before ducking. */
    private val measured =
        LoudnessTable {
            if (it == Fixtures.media("vo") || it == Fixtures.media("vo2")) RenderPlanBuilder.VOICE_LUFS else RenderPlanBuilder.MUSIC_LUFS
        }

    private fun staircase(
        video: VideoClip = Fixtures.video("v", 6000),
        musicDb: Double = 0.0,
        voiceDb: Double = 0.0,
    ): Project =
        Fixtures
            .blankWith(Fixtures.photo("p1"), video, Fixtures.photo("p2", 4000), ids = ids)
            .addTimedClip(OverlayClip(ClipId("fr"), Fixtures.media("fr"), 0, 12_000, sourceDurationMs = 12_000, repeat = false), ids = ids)
            .addTimedClip(
                AudioClip(ClipId("m"), Fixtures.media("m"), startMs = 1500, durationMs = 9000, sourceDurationMs = 9000, volumeDb = musicDb),
                role = AudioRole.MUSIC,
                ids = ids,
            ).addTimedClip(
                AudioClip(
                    ClipId("vo"),
                    Fixtures.media("vo"),
                    startMs = 5000,
                    durationMs = 2500,
                    sourceDurationMs = 2500,
                    volumeDb = voiceDb,
                ),
                role = AudioRole.VOICE,
                ids = ids,
            )

    private fun plan(p: Project) = RenderPlanBuilder(null, measured).build(p)

    private val Project.frameRow: TrackId get() = tracks.single { it.type == TrackType.OVERLAY }.id
    private val Project.musicRow: TrackId get() = tracks.single { it.audioRole == AudioRole.MUSIC }.id
    private val Project.voiceRow: TrackId get() = tracks.single { it.audioRole == AudioRole.VOICE }.id

    /** The level of [media]'s sound at [us] in the video, in dB. */
    private fun RenderPlan.dbOf(
        media: MediaRef,
        us: Long,
    ): Double {
        val s =
            audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
                .single { it.media == media && us >= it.startUs && us < it.endUs }
        return s.gain.dbAt(us - s.startUs)
    }

    private fun RenderPlan.frame(us: Long) = dbOf(Fixtures.media("fr"), us)

    private fun RenderPlan.music(us: Long) = dbOf(Fixtures.media("m"), us)

    private fun RenderPlan.video(us: Long) = dbOf(Fixtures.media("v"), us)

    private fun RenderPlan.voice(us: Long) = dbOf(Fixtures.media("vo"), us)

    @Test
    fun `each row is lowered only under the rows above it, step by step`() {
        val p = staircase()
        assertThat(p.validate()).isEmpty()
        val plan = plan(p)
        // Frame alone.
        assertThat(plan.frame(1_000_000)).isWithin(1e-6).of(frameDb)
        // Music starts: the frame goes under it, the music stays.
        assertThat(plan.frame(2_500_000)).isWithin(1e-6).of(frameDb - 12)
        assertThat(plan.music(2_500_000)).isWithin(1e-6).of(0.0)
        // The video's sound starts: the music and the frame go under it, the video stays.
        assertThat(plan.frame(4_000_000)).isWithin(1e-6).of(frameDb - 12)
        assertThat(plan.music(4_000_000)).isWithin(1e-6).of(-12.0)
        assertThat(plan.video(4_000_000)).isWithin(1e-6).of(0.0)
        // The voice starts: everything below it goes to the level under a voice, the voice stays.
        assertThat(plan.frame(6_000_000)).isWithin(1e-6).of(frameDb - 20)
        assertThat(plan.music(6_000_000)).isWithin(1e-6).of(-20.0)
        assertThat(plan.video(6_000_000)).isWithin(1e-6).of(-20.0)
        assertThat(plan.voice(6_000_000)).isWithin(1e-6).of(0.0)
        // And back down the stairs.
        assertThat(plan.video(8_500_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.music(8_500_000)).isWithin(1e-6).of(-12.0)
        assertThat(plan.frame(8_500_000)).isWithin(1e-6).of(frameDb - 12)
        assertThat(plan.music(9_800_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.frame(9_800_000)).isWithin(1e-6).of(frameDb - 12)
        assertThat(plan.frame(11_000_000)).isWithin(1e-6).of(frameDb)
        // The voice is never lowered by anything below it.
        assertThat(
            plan.audio
                .single { it.trackId == p.voiceRow }
                .segments
                .filterIsInstance<AudioSegment.Sound>()
                .single()
                .gain.isUnity,
        ).isTrue()
    }

    @Test
    fun `the ramps into and out of a lowering are straight lines in dB, with no jump`() {
        val plan = plan(staircase())
        // Halfway through the 300 ms attack before the voice (5 s) and the release after it (7.5 s).
        assertThat(plan.video(4_850_000)).isWithin(1e-6).of(-10.0)
        assertThat(plan.video(7_650_000)).isWithin(1e-6).of(-10.0)
        assertThat(plan.video(4_700_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.video(7_800_000)).isWithin(1e-6).of(0.0)
        // Sampled every millisecond across the whole video, no layer's level moves by more than the ramp's slope allows.
        val slopePerMs = 20.0 / 300
        for (media in listOf("fr", "m", "v")) {
            var last: Double? = null
            for (ms in 0L until 12_000L) {
                val db = runCatching { plan.dbOf(Fixtures.media(media), ms * 1000) }.getOrNull()
                if (db != null && last != null) {
                    assertThat(kotlin.math.abs(db - last)).isAtMost(slopePerMs + 1e-6)
                }
                last = db
            }
        }
    }

    @Test
    fun `an item that makes no sound lowers nothing`() {
        // The voice and the music turned all the way down, and a video whose file has no sound track.
        val silent =
            staircase(
                video = Fixtures.video("v", 6000).copy(hasSound = false),
                musicDb = Limits.MIN_VOLUME_DB,
                voiceDb = Limits.MIN_VOLUME_DB,
            )
        val plan = plan(silent)
        for (us in listOf(1_000_000L, 2_500_000L, 4_000_000L, 6_000_000L, 8_500_000L, 9_800_000L, 11_000_000L)) {
            assertThat(plan.frame(us)).isWithin(1e-6).of(frameDb)
        }
        assertThat(plan.video(6_000_000)).isWithin(1e-6).of(0.0)
        // A muted voice row lowers nothing either.
        val muted = staircase().let { it.setTrackMuted(it.voiceRow, true) }
        val plan2 = plan(muted)
        assertThat(plan2.video(6_000_000)).isWithin(1e-6).of(0.0)
        assertThat(plan2.music(6_000_000)).isWithin(1e-6).of(-12.0)
        assertThat(plan2.frame(6_000_000)).isWithin(1e-6).of(frameDb - 12)
    }

    @Test
    fun `the preset's intro is lowered under a voice, and a silent picture-in-picture video lowers nothing`() {
        val presetIds = SequentialIds("vi")
        val p =
            Fixtures
                .presetProject(presetIds)
                .addToMain(listOf(Fixtures.photo("pp", 6000)), ids = presetIds)
                .addTimedClip(
                    AudioClip(ClipId("vo"), Fixtures.media("vo"), startMs = 2000, durationMs = 2000, sourceDurationMs = 2000),
                    role = AudioRole.VOICE,
                    ids = presetIds,
                )
        val intro = p.main.clips.first()
        val plan = RenderPlanBuilder(Fixtures.preset, measured).build(p)
        val introSound =
            plan.audio
                .flatMap { it.segments }
                .filterIsInstance<AudioSegment.Sound>()
                .first { it.startUs == 0L }
        assertThat(introSound.gain.dbAt(3_000_000) - introSound.gain.dbAt(500_000)).isWithin(1e-6).of(-20.0)
        assertThat(intro.durationMs).isGreaterThan(4000)
        // A picture-in-picture video known to have no sound track, over music: the music stays up.
        val silentPip = Fixtures.video("pv", 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)).copy(startMs = 1000, hasSound = false)
        val s = staircase().addTimedClip(silentPip, ids = ids)
        assertThat(plan(s).music(2_500_000)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `a video is lowered under a voice even when the video itself is silent, and lowers nothing then`() {
        val plan = plan(staircase(video = Fixtures.video("v", 6000).copy(hasSound = false)))
        assertThat(plan.video(6_000_000)).isWithin(1e-6).of(-20.0)
        assertThat(plan.music(4_000_000)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `Lower for talk on a row overrides the ladder for that row only`() {
        val p = staircase()
        // Photos and videos off: the video keeps its level under the voice; the rest still go down.
        val mainOff = plan(p.setTalkLowering(p.main.id, lower = false, levelDb = null))
        assertThat(mainOff.video(6_000_000)).isWithin(1e-6).of(0.0)
        assertThat(mainOff.music(6_000_000)).isWithin(1e-6).of(-20.0)
        // Photos and videos to their own level: that level under the voice.
        val mainLevel = plan(p.setTalkLowering(p.main.id, lower = true, levelDb = -6.0))
        assertThat(mainLevel.video(6_000_000)).isWithin(1e-6).of(-6.0)
        // A frame row's own level applies under music as under the others.
        val frameLevel = plan(p.setTalkLowering(p.frameRow, lower = true, levelDb = -3.0))
        assertThat(frameLevel.frame(2_500_000)).isWithin(1e-6).of(frameDb - 3)
        assertThat(frameLevel.frame(6_000_000)).isWithin(1e-6).of(frameDb - 3)
        // A frame row turned off is not lowered under music, videos or voices.
        val frameOff = plan(p.setTalkLowering(p.frameRow, lower = false, levelDb = null))
        for (us in listOf(2_500_000L, 4_000_000L, 6_000_000L)) assertThat(frameOff.frame(us)).isWithin(1e-6).of(frameDb)
        // A music row turned off is not lowered, and still lowers the frame.
        val musicOff = plan(p.setTalkLowering(p.musicRow, lower = false, levelDb = null))
        assertThat(musicOff.music(6_000_000)).isWithin(1e-6).of(0.0)
        assertThat(musicOff.frame(2_500_000)).isWithin(1e-6).of(frameDb - 12)
    }

    @Test
    fun `a picture-in-picture video's sound is lowered under a voice like MAIN's`() {
        val pip = Fixtures.video("pv", 4000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)).copy(startMs = 4000)
        val p = staircase().addTimedClip(pip, ids = ids)
        val row = p.tracks.single { it.type == TrackType.VISUAL }
        val plan = plan(p)
        assertThat(plan.dbOf(Fixtures.media("pv"), 4_500_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.dbOf(Fixtures.media("pv"), 6_000_000)).isWithin(1e-6).of(-20.0)
        val off = plan(p.setTalkLowering(row.id, lower = false, levelDb = null))
        assertThat(off.dbOf(Fixtures.media("pv"), 6_000_000)).isWithin(1e-6).of(0.0)
        // Videos never lower each other.
        assertThat(plan.video(4_500_000)).isWithin(1e-6).of(0.0)
        // The preview's picture-in-picture sound lane carries the same lowering.
        val lane = PreviewSchedule.of(plan).sounds.single { it.trackId == row.id }
        assertThat(lane.gainDbAt(6_000_000)).isWithin(1e-6).of(-20.0)
        assertThat(lane.gainDbAt(4_500_000)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `the preset's frame music is frame sound, under the user's music, and the closing message still lowers everything`() {
        val presetIds = SequentialIds("vp")
        val base =
            Fixtures
                .presetProject(presetIds)
                .addToMain(listOf(Fixtures.photo("pp", 9000)), ids = presetIds)
        val frameMusic = base.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }
        assertThat(frameMusic.soundRank).isEqualTo(SoundRank.FRAME)
        val p =
            base
                .setTrackMuted(frameMusic.id, false)
                .addTimedClip(
                    OverlayClip(ClipId("fr"), Fixtures.media("fr"), 7000, 3000, sourceDurationMs = 3000, repeat = false),
                    ids = presetIds,
                ).addTimedClip(
                    AudioClip(ClipId("m"), Fixtures.media("m"), startMs = 9000, durationMs = 3000, sourceDurationMs = 3000),
                    role = AudioRole.MUSIC,
                    ids = presetIds,
                )
        val musicDb = (frameMusic.clips.single() as AudioClip).volumeDb
        val fm = (frameMusic.clips.single() as AudioClip).media
        val plan = RenderPlanBuilder(Fixtures.preset, measured).build(p)
        // The intro (0..6.5 s) has sound; the photo is 6.5..15.5 s. The user's frame (7..10 s) and the frame music
        // are both frame sound: neither lowers the other.
        assertThat(plan.frame(8_000_000)).isWithin(1e-6).of(frameDb)
        assertThat(plan.dbOf(fm, 8_000_000)).isWithin(1e-6).of(musicDb)
        // The user's music (9..12 s) lowers both, and is not lowered by them.
        assertThat(plan.frame(9_700_000)).isWithin(1e-6).of(frameDb - 12)
        assertThat(plan.dbOf(fm, 10_500_000)).isWithin(1e-6).of(musicDb - 12)
        assertThat(plan.music(10_500_000)).isWithin(1e-6).of(0.0)
        // Under the closing message every row is lowered, the frame music among them, and the closing message is not.
        val closing =
            p.tracks
                .single { it.presetRole == PresetRole.CLOSING_MESSAGE }
                .clips
                .single() as AudioClip
        val mid = (closing.startMs + closing.durationMs / 2) * 1000
        assertThat(plan.dbOf(fm, mid)).isWithin(1e-6).of(musicDb - 20)
        assertThat(plan.dbOf(closing.media, mid)).isWithin(1e-6).of(RenderPlanBuilder.VOICE_LUFS - RenderPlanBuilder.MUSIC_LUFS)
    }

    @Test
    fun `the closing message lowers at its own level, to a row's own level when it has one, and not when turned down`() {
        val presetIds = SequentialIds("vc")
        val base0 = Fixtures.presetProject(presetIds).addToMain(listOf(Fixtures.photo("pp", 3000)), ids = presetIds)
        val closing =
            base0.tracks
                .single { it.presetRole == PresetRole.CLOSING_MESSAGE }
                .clips
                .single() as AudioClip
        // The user's music runs under the whole closing message.
        val base =
            base0.addTimedClip(
                AudioClip(
                    ClipId("m"),
                    Fixtures.media("m"),
                    startMs = closing.startMs - 1000,
                    durationMs = closing.durationMs + 1000,
                    sourceDurationMs = 20_000,
                ),
                role = AudioRole.MUSIC,
                ids = presetIds,
            )
        val musicRow = base.tracks.single { it.audioRole == AudioRole.MUSIC && it.presetRole == null }.id
        val under = (closing.startMs + closing.durationMs / 2) * 1000

        fun musicUnder(p: Project) = RenderPlanBuilder(Fixtures.preset, measured).build(p).music(under)
        assertThat(musicUnder(base)).isWithin(1e-6).of(base.ducking.speechDuckDb)
        // A preset whose closing message lowers less than a voice does: its own level, not the voice's.
        assertThat(musicUnder(base.copy(ducking = base.ducking.copy(speechDuckDb = -10.0)))).isWithin(1e-6).of(-10.0)
        // A row with its own level goes to that level under the closing message too; turned off, to the closing level.
        assertThat(musicUnder(base.setTalkLowering(musicRow, true, -30.0))).isWithin(1e-6).of(-30.0)
        assertThat(musicUnder(base.setTalkLowering(musicRow, false, null))).isWithin(1e-6).of(base.ducking.speechDuckDb)
        // A closing message turned all the way down lowers nothing.
        assertThat(musicUnder(base.setVolume(closing.id, Limits.MIN_VOLUME_DB))).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `rows of one rank never lower each other, and sound effects stay out of the ladder`() {
        val p =
            staircase()
                .addTimedClip(
                    AudioClip(ClipId("m2"), Fixtures.media("m2"), startMs = 2000, durationMs = 2000, sourceDurationMs = 2000),
                    role = AudioRole.MUSIC,
                    ids = ids,
                ).addTimedClip(
                    AudioClip(ClipId("vo2"), Fixtures.media("vo2"), startMs = 5500, durationMs = 1000, sourceDurationMs = 1000),
                    role = AudioRole.VOICE,
                    ids = ids,
                ).addTimedClip(
                    OverlayClip(ClipId("fr2"), Fixtures.media("fr2"), 1000, 2000, sourceDurationMs = 2000, repeat = false),
                    ids = ids,
                )
        // A second music row starts at 2 s: the first is not lowered by it, nor it by the first.
        val withEffect =
            p.copy(
                tracks =
                    p.tracks +
                        com.itsluminous.clearmovie.domain.model.Track(
                            com.itsluminous.clearmovie.domain.model
                                .TrackId("fx"),
                            TrackType.AUDIO,
                            listOf(
                                AudioClip(ClipId("fx1"), Fixtures.media("fx1"), startMs = 5500, durationMs = 1000, sourceDurationMs = 1000),
                            ),
                            audioRole = AudioRole.EFFECT,
                        ),
            )
        assertThat(withEffect.validate()).isEmpty()
        val plan = plan(withEffect)
        assertThat(plan.music(2_500_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.dbOf(Fixtures.media("m2"), 2_500_000)).isWithin(1e-6).of(0.0)
        // Two voices: neither lowered.
        assertThat(plan.voice(6_000_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.dbOf(Fixtures.media("vo2"), 6_000_000)).isWithin(1e-6).of(0.0)
        // Two frame rows (1..3 s): neither lowers the other; both go under the music.
        assertThat(plan.frame(1_100_000)).isWithin(1e-6).of(frameDb)
        assertThat(plan.dbOf(Fixtures.media("fr2"), 1_100_000)).isWithin(1e-6).of(frameDb)
        assertThat(plan.dbOf(Fixtures.media("fr2"), 2_500_000)).isWithin(1e-6).of(frameDb - 12)
        // The effect plays under the voices unlowered and lowers nothing: the levels at 6 s are the voice's, as without it.
        assertThat(plan.dbOf(Fixtures.media("fx1"), 6_000_000)).isWithin(1e-6).of(0.0)
        assertThat(plan.video(6_000_000)).isWithin(1e-6).of(-20.0)
    }

    @Test
    fun `a looped, sped-up video crossfading into the next is lowered under a voice in every copy and lane`() {
        val looped = Fixtures.video("v", 2000).copy(loops = 2, speed = com.itsluminous.clearmovie.domain.model.Speed.X2)
        val next = Fixtures.video("v2", 4000)
        val p0 =
            Fixtures
                .blankWith(Fixtures.photo("p1"), looped, next, ids = ids)
                .let {
                    it.setTransition(
                        looped.id,
                        com.itsluminous.clearmovie.domain.model
                            .Transition(TransitionKind.CROSSFADE, 1000),
                    )
                }
        val voiced =
            p0.addTimedClip(
                AudioClip(ClipId("vo"), Fixtures.media("vo"), startMs = 2000, durationMs = 4000, sourceDurationMs = 4000),
                role = AudioRole.VOICE,
                ids = ids,
            )
        val quiet = plan(p0)
        val loud = plan(voiced)
        // 3..5 s is the looped video (two 1 s copies at 2x) and the crossfade; every sound of it, in both lanes, is 20 dB down.
        for (ms in listOf(3_200L, 3_700L, 4_300L, 4_800L)) {
            for (media in listOf("v", "v2")) {
                val a = runCatching { quiet.dbOf(Fixtures.media(media), ms * 1000) }.getOrNull() ?: continue
                assertThat(loud.dbOf(Fixtures.media(media), ms * 1000)).isWithin(1e-6).of(maxOf(a - 20, GainEnvelope.SILENT_DB))
            }
        }
        // And the preview's MAIN and tail players carry it.
        val qs = PreviewSchedule.of(quiet)
        val ls = PreviewSchedule.of(loud)
        for (ms in 3_100L until 5_900L step 100) {
            val us = ms * 1000
            qs.mainGainDbAt(us)?.let { assertThat(ls.mainGainDbAt(us)!!).isWithin(1e-6).of(maxOf(it - 20, GainEnvelope.SILENT_DB)) }
            qs.tailGainDbAt(us)?.let { assertThat(ls.tailGainDbAt(us)!!).isWithin(1e-6).of(maxOf(it - 20, GainEnvelope.SILENT_DB)) }
        }
    }

    @Test
    fun `the preview plays the same levels as the export`() {
        val p = staircase()
        val plan = plan(p)
        val schedule = PreviewSchedule.of(plan)
        var checked = 0
        for (ms in 0L until 12_000L step 50) {
            val us = ms * 1000
            val main = schedule.mainGainDbAt(us)
            val v = runCatching { plan.video(us) }.getOrNull()
            if (v != null && main != null) {
                assertThat(main).isWithin(1e-6).of(v)
                checked++
            }
            for ((row, media) in listOf(p.musicRow to "m", p.frameRow to "fr", p.voiceRow to "vo")) {
                val lane = schedule.sounds.single { it.trackId == row }
                val want = runCatching { plan.dbOf(Fixtures.media(media), us) }.getOrNull() ?: continue
                assertThat(lane.gainDbAt(us)).isWithin(1e-6).of(want)
                checked++
            }
        }
        // Every step of the staircase was looked at: the video's 6 s, the music's 9 s, the frame's 12 s, the voice's 2.5 s.
        assertThat(checked).isAtLeast((6_000 + 9_000 + 12_000 + 2_500) / 50 - 4)
    }

    @Test
    fun `only rows below videos ask whether a video has sound`() {
        // Voices lower videos whatever their files hold, so a project with only photos, videos and a voice reads nothing.
        val p =
            Fixtures
                .blankWith(Fixtures.photo("p1"), Fixtures.video("v", 6000), ids = ids)
                .addTimedClip(
                    AudioClip(ClipId("vo"), Fixtures.media("vo"), startMs = 1000, durationMs = 2000, sourceDurationMs = 2000),
                    role = AudioRole.VOICE,
                    ids = ids,
                )
        assertThat(duckingMedia(p)).isEmpty()
        assertThat(duckingMedia(staircase())).containsExactly(Fixtures.media("v"))
        val noFrameLowering = staircase().let { s -> s.setTalkLowering(s.frameRow, false, null).setTalkLowering(s.musicRow, false, null) }
        assertThat(duckingMedia(noFrameLowering)).isEmpty()
    }

    @Test
    fun `the ladder ranks every kind of row`() {
        val p = Fixtures.presetProject()
        assertThat(p.main.soundRank).isEqualTo(SoundRank.VIDEO)
        assertThat(p.tracks.single { it.presetRole == PresetRole.FRAME }.soundRank).isNull()
        assertThat(p.tracks.single { it.presetRole == PresetRole.CLOSING_MESSAGE }.soundRank).isEqualTo(SoundRank.VOICE)
        assertThat(p.tracks.first { it.type == TrackType.TEXT }.soundRank).isNull()
        assertThat(p.main.isLoweredForTalk).isTrue()
        assertThat(p.tracks.single { it.presetRole == PresetRole.CLOSING_MESSAGE }.isLoweredForTalk).isFalse()
        val s = staircase()
        assertThat(s.tracks.single { it.id == s.frameRow }.soundRank).isEqualTo(SoundRank.FRAME)
        assertThat(s.tracks.single { it.id == s.voiceRow }.isLoweredForTalk).isFalse()
        // The volume of a voice item sets its level, never another row's.
        val louder = plan(s.setVolume(ClipId("vo"), -6.0))
        assertThat(louder.voice(6_000_000)).isWithin(1e-6).of(-6.0)
        assertThat(louder.video(6_000_000)).isWithin(1e-6).of(-20.0)
    }
}
