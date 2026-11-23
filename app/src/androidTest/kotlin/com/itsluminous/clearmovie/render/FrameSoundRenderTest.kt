package com.itsluminous.clearmovie.render

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.data.preset.AndroidSoundLoudness
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setTalkLowering
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.toneDb
import com.itsluminous.clearmovie.render.media3.readSoundTracks
import com.itsluminous.clearmovie.render.media3.soundPresenceOf
import com.itsluminous.clearmovie.render.plan.LoudnessTable
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.duckingMedia
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * A frame video's own sound in the export (cm-frame-audio), measured on the
 * exported AAC track. No preset: MAIN is a 3 s photo, the 3 s clip_dark
 * video (440 Hz, -20 dBFS) and a 4 s photo; a frame of the user's on a frame
 * row from 1 to 9 s is frame.mp4, whose sound is a 1000 Hz tone at
 * -20 dBFS. The frame's sound plays at its volume (the frame music's -8 dB),
 * lowered 12 dB under the video's sound; a frame without sound
 * (frame_magenta.mp4) exports as before, in silence.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FrameSoundRenderTest {
    private val key = RenderHarness.preset.frame!!.key

    /** frame.mp4's sound taken as at the music target, so the levels below are its volume and its ducking only. */
    private val measured = LoudnessTable { if (it == fixture("frame.mp4")) RenderPlanBuilder.MUSIC_LUFS else null }

    private fun project(
        frameFile: String,
        frameMs: Long,
        frameKey: KeySpec = key,
    ) = SequentialIds("fs").let { ids ->
        ProjectFactory
            .blank(ProjectId("frame-sound"), ids)
            .addToMain(
                listOf(
                    PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                    VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                    PhotoClip(ids.clipId(), fixture("photo_blue.png"), 4000),
                ),
                ids = ids,
            ).addTimedClip(
                OverlayClip(ClipId("fr"), fixture(frameFile), 1000, minOf(8000, frameMs), frameMs, key = frameKey, repeat = false),
                ids = ids,
            )
    }

    @Test
    fun aFrameOnAFrameRowPlaysItsOwnSoundDuckedUnderTheVideo() {
        val project = project("frame.mp4", 12_000)
        val out = RenderHarness.export(RenderPlanBuilder(null, measured).build(project), "frame-sound")
        val pcm = RenderHarness.decodeAudio(out.file)
        val frameAlone = toneDb(pcm, 1000.0, 1.5, 2.6)
        val frameUnderClip = toneDb(pcm, 1000.0, 3.5, 5.6)
        val frameAfter = toneDb(pcm, 1000.0, 6.5, 8.6)
        val beforeFrame = toneDb(pcm, 1000.0, 0.1, 0.8)
        val afterFrame = toneDb(pcm, 1000.0, 9.3, 9.9)
        val clip = toneDb(pcm, 440.0, 3.5, 5.6)
        val rows =
            listOf(
                "frame sound alone (1.5-2.6 s)" to frameAlone,
                "frame sound under the video's sound (3.5-5.6 s)" to frameUnderClip,
                "frame sound after the video (6.5-8.6 s)" to frameAfter,
                "1000 Hz before the frame (0.1-0.8 s)" to beforeFrame,
                "1000 Hz after the frame (9.3-9.9 s)" to afterFrame,
                "video's own sound 440 Hz (3.5-5.6 s)" to clip,
            )
        rows.forEach { (k, v) -> report("frame-sound %s: %.2f dBFS".format(Locale.ROOT, k, v)) }
        val failures = mutableListOf<String>()

        fun near(
            what: String,
            got: Double,
            want: Double,
        ) {
            report("frame-sound check %s: %.2f dB, want %.2f +-2".format(Locale.ROOT, what, got, want))
            if (abs(got - want) > 2.0) failures += "$what was $got dB, want $want"
        }
        // The frame's tone is -20 dBFS in its file; a new frame plays at -8 dB.
        near("frame sound alone", frameAlone, -28.0)
        near("frame sound under the video", frameUnderClip - frameAlone, -12.0)
        near("frame sound after the video recovers", frameAfter - frameAlone, 0.0)
        near("the video's sound is not lowered by the frame", clip, -20.0)
        if (beforeFrame > -60.0) failures += "1000 Hz before the frame was $beforeFrame dB"
        if (afterFrame > -60.0) failures += "1000 Hz after the frame was $afterFrame dB"
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()

        // Lower for talk on the frame row, to its own level: -30 dB under the video.
        val row = project.tracks.single { it.type == TrackType.OVERLAY }.id
        val lowered = project.setTalkLowering(row, lower = true, levelDb = -30.0)
        val pcm2 =
            RenderHarness.decodeAudio(
                RenderHarness.export(RenderPlanBuilder(null, measured).build(lowered), "frame-sound-lowered").file,
            )
        val loweredUnder = toneDb(pcm2, 1000.0, 3.5, 5.6) - toneDb(pcm2, 1000.0, 1.5, 2.6)
        report("frame-sound check lowered to -30 dB under the video: %.2f dB".format(Locale.ROOT, loweredUnder))
        assertWithMessage("lowered frame sound").that(abs(loweredUnder + 30.0)).isLessThan(2.5)
    }

    @Test
    fun theLoudnessMeterFindsTheFrameWithSoundAndNotTheOneWithout() {
        val meter = AndroidSoundLoudness(RenderHarness.context)
        val withSound = runBlocking { meter.lufs(Uri.fromFile(File(RenderHarness.fixtureDir, "frame.mp4"))) }
        val without = runBlocking { meter.lufs(Uri.fromFile(File(RenderHarness.fixtureDir, "frame_magenta.mp4"))) }
        report("frame-sound loudness: frame.mp4 %s LUFS, frame_magenta.mp4 %s".format(Locale.ROOT, withSound, without))
        // A -20 dBFS 1 kHz tone on both channels is about -17 LUFS.
        assertWithMessage("frame.mp4 has sound, at a real loudness").that(withSound!!).isWithin(4.0).of(-17.0)
        assertWithMessage("frame_magenta.mp4 has none").that(without).isNull()
    }

    @Test
    fun aMainVideoWithoutSoundLowersNoFrameSound() {
        // kupraki's call: lower only under videos with sound. The MAIN video is frame_magenta.mp4, which has none.
        val ids = SequentialIds("fq")
        val project =
            ProjectFactory
                .blank(ProjectId("frame-sound-silent-video"), ids)
                .addToMain(
                    listOf(
                        PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                        VideoClip(ids.clipId(), fixture("frame_magenta.mp4"), sourceDurationMs = 2000),
                        PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000),
                    ),
                    ids = ids,
                ).addTimedClip(OverlayClip(ClipId("fr"), fixture("frame.mp4"), 0, 8000, 12_000, key = key, repeat = false), ids = ids)
        val sound = soundPresenceOf(runBlocking { readSoundTracks(RenderHarness.context, duckingMedia(project), RenderHarness.resolver()) })
        assertWithMessage("frame_magenta.mp4 read as without sound").that(sound.hasSound(fixture("frame_magenta.mp4"))).isFalse()
        val pcm =
            RenderHarness.decodeAudio(
                RenderHarness.export(RenderPlanBuilder(null, measured, sound).build(project), "frame-sound-silent-video").file,
            )
        val before = toneDb(pcm, 1000.0, 1.0, 2.6)
        val under = toneDb(pcm, 1000.0, 3.4, 4.6)
        report("frame-sound check over a video without sound: %.2f dB against before it, want 0 +-2".format(Locale.ROOT, under - before))
        assertWithMessage("frame sound over a silent video").that(abs(under - before)).isLessThan(2.0)
    }

    @Test
    fun aFrameWithoutSoundExportsInSilenceAsBefore() {
        val project =
            project(
                "frame_magenta.mp4",
                2000,
                key.copy(
                    color =
                        com.itsluminous.clearmovie.domain.model
                            .ArgbColor(0xFFFF00FF),
                ),
            )
        val out = RenderHarness.export(RenderPlanBuilder(null, measured).build(project), "frame-silent")
        val pcm = RenderHarness.decodeAudio(out.file)
        val clip = toneDb(pcm, 440.0, 3.5, 5.6)
        val frameTone = toneDb(pcm, 1000.0, 1.2, 2.8)
        report("frame-silent: video's sound %.2f dBFS, 1000 Hz under the frame %.2f dBFS".format(Locale.ROOT, clip, frameTone))
        assertWithMessage("video sound").that(abs(clip + 20.0)).isLessThan(2.0)
        assertWithMessage("no frame tone").that(frameTone).isLessThan(-60.0)
        assertWithMessage("export length").that(RenderHarness.durationUs(out.file)).isAtLeast(9_900_000L)
    }
}
