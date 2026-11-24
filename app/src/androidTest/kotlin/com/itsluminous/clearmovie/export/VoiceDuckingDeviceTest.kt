package com.itsluminous.clearmovie.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.domain.model.setTalkLowering
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.work.ExportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs

/**
 * The ducking ladder through the whole export (cm-voice-ducking), measured
 * on the saved file. Each layer is a pure tone, so its level can be read
 * out of the mix: a frame of the user's with sound (frame.mp4, 1000 Hz)
 * over 0..12 s, music (seconds.mp4, 440 Hz) over 1.5..10.5 s, a video with
 * sound on MAIN (clip_light.mp4 twice, 550 Hz) over 3.5..9.5 s and a voice
 * (speech.m4a, 300 Hz) over 5.5..8.5 s, between a 3.5 s and a 4.5 s photo.
 * Each layer is lowered only while a higher one plays: frame under music
 * (-12 dB), both under the video (-12 dB), all three under the voice
 * (-20 dB). An item that makes no sound lowers nothing.
 */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class VoiceDuckingDeviceTest {
    private val h = ExportHarness()

    @Before
    fun setUp() = h.setUp()

    @After
    fun tearDown() = h.cleanUp()

    /** The windows between the steps, clear of the 300 ms ramps, in seconds. */
    private val windows =
        listOf(
            "1 frame alone" to (0.2 to 1.1),
            "2 frame+music" to (1.9 to 3.1),
            "3 frame+music+video" to (3.9 to 5.1),
            "4 frame+music+video+voice" to (5.9 to 8.1),
            "5 frame+music+video" to (8.85 to 9.15),
            "6 frame+music" to (9.85 to 10.15),
            "7 frame alone" to (10.85 to 11.9),
        )
    private val tones = listOf("frame" to 1000.0, "music" to 440.0, "video" to 550.0, "voice" to 300.0)

    private fun staircase(
        name: String,
        silent: Boolean = false,
    ): Project {
        val ids = RandomIds
        val video =
            if (silent) {
                // frame_neargreen.mp4 has no sound track; 3 s of it twice, as the sounding video below.
                VideoClip(
                    ClipId(ids.next()),
                    store("fixtures/frame_neargreen.mp4", "video/mp4"),
                    sourceDurationMs = 4000,
                    trimEndMs = 3000,
                    loops = 2,
                )
            } else {
                VideoClip(ClipId(ids.next()), store("fixtures/clip_light.mp4", "video/mp4"), sourceDurationMs = 3000, loops = 2)
            }
        val items = listOf(h.photo(3500), video, h.photo(4500, "fixtures/photo_blue.png"))
        val itemDb = if (silent) Limits.MIN_VOLUME_DB else 0.0
        val project =
            ProjectFactory
                .blank(ProjectId(RandomIds.next()), RandomIds)
                .addToMain(items)
                .addTimedClip(
                    OverlayClip(
                        ClipId(ids.next()),
                        store("fixtures/frame.mp4", "video/mp4"),
                        startMs = 0,
                        durationMs = 12_000,
                        sourceDurationMs = 12_000,
                        key = RenderHarness.preset.frame!!.key,
                        repeat = false,
                    ),
                    ids = RandomIds,
                ).addTimedClip(
                    AudioClip(
                        ClipId(ids.next()),
                        store("fixtures/seconds.mp4", "video/mp4"),
                        startMs = 1500,
                        durationMs = 9000,
                        sourceDurationMs = 10_000,
                        volumeDb = itemDb,
                    ),
                    role = AudioRole.MUSIC,
                    ids = RandomIds,
                ).addTimedClip(
                    AudioClip(
                        ClipId(ids.next()),
                        store("fixtures/speech.m4a", "audio/mp4"),
                        startMs = 5500,
                        durationMs = 3000,
                        sourceDurationMs = 3000,
                        volumeDb = itemDb,
                    ),
                    role = AudioRole.VOICE,
                    ids = RandomIds,
                ).rename(name)
        return project
    }

    private fun store(
        asset: String,
        mime: String,
    ) = MediaRef.Store(h.insert(asset, mime).toString())

    /** Exports [project] in full and returns each tone's level per window, in dBFS. */
    private fun levels(
        project: Project,
        file: String,
    ): Map<String, Map<String, Double>> {
        check(runBlocking { h.app.projectRepository.createDraft(project, inEditor = false) })
        val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, project.name ?: file) {} }
        val done = (outcome as ExportOutcome.Done).done
        h.exported(done.uri)
        val pcm = RenderHarness.decodeAudio(h.keep(done.uri, file))
        return windows
            .associate { (w, span) ->
                w to tones.associate { (t, f) -> t to RenderHarness.toneDb(pcm, f, span.first, span.second) }
            }.also { table ->
                table.forEach { (w, row) ->
                    RenderHarness.report(
                        "voice-ducking %s %s: %s".format(
                            Locale.ROOT,
                            file,
                            w,
                            row.entries.joinToString { (t, db) -> "%s %.2f".format(Locale.ROOT, t, db) },
                        ),
                    )
                }
            }
    }

    private class Checks {
        val failures = mutableListOf<String>()

        fun near(
            what: String,
            got: Double,
            want: Double,
            tol: Double = 2.5,
        ) {
            RenderHarness.report("voice-ducking check %s: %.2f dB, want %.2f +-%.1f".format(Locale.ROOT, what, got, want, tol))
            if (abs(got - want) > tol) failures += "$what was %.2f dB, want %.2f".format(Locale.ROOT, got, want)
        }
    }

    @Test
    fun eachLayerIsLoweredOnlyUnderTheLayersAboveIt() {
        val l = levels(staircase("Voice ducking staircase"), "voice-ducking-staircase.mp4")

        fun drop(
            tone: String,
            window: Int,
            from: Int,
        ) = l.getValue(windows[window - 1].first).getValue(tone) - l.getValue(windows[from - 1].first).getValue(tone)
        val c = Checks()
        c.near("frame under music", drop("frame", 2, 1), -12.0)
        c.near("frame under music and video", drop("frame", 3, 1), -12.0)
        c.near("frame under the voice", drop("frame", 4, 1), -20.0)
        c.near("frame back under music and video", drop("frame", 5, 1), -12.0)
        c.near("frame back under music", drop("frame", 6, 1), -12.0)
        c.near("frame alone again", drop("frame", 7, 1), 0.0)
        c.near("music under the video", drop("music", 3, 2), -12.0)
        c.near("music under the voice", drop("music", 4, 2), -20.0)
        c.near("music back under the video", drop("music", 5, 2), -12.0)
        c.near("music after the video", drop("music", 6, 2), 0.0)
        c.near("video under the voice", drop("video", 4, 3), -20.0)
        c.near("video after the voice", drop("video", 5, 3), 0.0)
        // The voice plays at its own, loudness-normalised level (about -13 dBFS here): nothing below it lowers it.
        val voice = l.getValue(windows[3].first).getValue("voice")
        RenderHarness.report("voice-ducking check voice at its own level: %.2f dBFS, want above -16".format(Locale.ROOT, voice))
        if (voice < -16.0) c.failures += "the voice was lowered to %.2f dBFS".format(Locale.ROOT, voice)
        assertWithMessage(c.failures.joinToString("; ")).that(c.failures).isEmpty()
    }

    @Test
    fun itemsThatMakeNoSoundLowerNothing() {
        val l = levels(staircase("Voice ducking silent", silent = true), "voice-ducking-silent.mp4")
        val c = Checks()
        val alone = l.getValue(windows[0].first).getValue("frame")
        for (w in windows.drop(1)) c.near("frame in ${w.first}", l.getValue(w.first).getValue("frame") - alone, 0.0)
        assertWithMessage(c.failures.joinToString("; ")).that(c.failures).isEmpty()
    }

    @Test
    fun thePresetFrameMusicGoesUnderTheUsersMusic() {
        // The preset bound to the fixtures: intro.mp4 (660 Hz) 0..2 s, frame.mp4's sound (1000 Hz) as the frame music,
        // a 9 s photo 2..11 s, and the user's music (seconds.mp4, 440 Hz) over 4.5..7.5 s.
        h.bindPreset()
        val preset = requireNotNull(runBlocking { h.app.presetStore.effectivePreset(h.presetId) })
        val project =
            ProjectFactory
                .fromPreset(ProjectId(RandomIds.next()), preset, runBlocking { h.app.presetStore.presetMedia(h.presetId) })
                .addToMain(listOf(h.photo(9000)))
                .addTimedClip(
                    AudioClip(ClipId(RandomIds.next()), store("fixtures/seconds.mp4", "video/mp4"), 4500, 3000, 10_000),
                    role = AudioRole.MUSIC,
                    ids = RandomIds,
                ).rename("Voice ducking frame music")
        check(runBlocking { h.app.projectRepository.createDraft(project, inEditor = false) })
        val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, "Voice ducking frame music") {} }
        val done = (outcome as ExportOutcome.Done).done
        h.exported(done.uri)
        val pcm = RenderHarness.decodeAudio(h.keep(done.uri, "voice-ducking-frame-music.mp4"))
        val alone = RenderHarness.toneDb(pcm, 1000.0, 2.6, 4.0)
        val under = RenderHarness.toneDb(pcm, 1000.0, 5.0, 7.0)
        val after = RenderHarness.toneDb(pcm, 1000.0, 8.0, 10.0)
        val music = RenderHarness.toneDb(pcm, 440.0, 5.0, 7.0)
        RenderHarness.report(
            "voice-ducking frame-music: frame music %.2f alone (2.6-4.0 s), %.2f under music (5.0-7.0 s), %.2f after (8.0-10.0 s); music %.2f"
                .format(Locale.ROOT, alone, under, after, music),
        )
        val c = Checks()
        c.near("frame music under the user's music", under - alone, -12.0)
        c.near("frame music after the user's music", after - alone, 0.0)
        assertWithMessage(c.failures.joinToString("; ")).that(c.failures).isEmpty()
    }

    @Test
    fun lowerForTalkOnARowOverridesTheLadder() {
        // Photos and videos with Lower for talk off; the frame row to its own level, 6 dB down, under everything above it.
        val p = staircase("Voice ducking override")
        val frameRow = p.tracks.single { it.type == TrackType.OVERLAY }.id
        val project = p.setTalkLowering(p.main.id, lower = false, levelDb = null).setTalkLowering(frameRow, lower = true, levelDb = -6.0)
        val l = levels(project, "voice-ducking-override.mp4")

        fun drop(
            tone: String,
            window: Int,
            from: Int,
        ) = l.getValue(windows[window - 1].first).getValue(tone) - l.getValue(windows[from - 1].first).getValue(tone)
        val c = Checks()
        c.near("video not lowered under the voice", drop("video", 4, 3), 0.0)
        c.near("music still lowered under the voice", drop("music", 4, 2), -20.0)
        c.near("frame at its own level under music", drop("frame", 2, 1), -6.0)
        c.near("frame at its own level under the voice", drop("frame", 4, 1), -6.0)
        assertWithMessage(c.failures.joinToString("; ")).that(c.failures).isEmpty()
    }
}
