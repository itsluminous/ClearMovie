package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.toneDb
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Ducking, measured on the exported AAC track. Each source is a pure tone,
 * so its level can be read out of the mix: music 1000 Hz (the frame's
 * soundtrack), clip sound 440 Hz, a voice row 550 Hz, the closing message
 * 300 Hz. Timeline: intro 0-2, photo 2-5, video 5-8, photo 8-11, held last
 * frame 11-14 under the closing message; music 2-14; voice 9.5-12.5.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class DuckingRenderTest {
    @Test
    fun musicDucksUnderClipAndVoiceAndEverythingUnderTheClosingMessage() {
        val ids = SequentialIds("d")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("duck"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000), ids)
                .addToMain(
                    listOf(
                        PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                        VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                        PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000),
                    ),
                    ids = ids,
                ).addTimedClip(
                    AudioClip(ids.clipId(), fixture("clip_light.mp4"), startMs = 9500, durationMs = 3000, sourceDurationMs = 3000),
                    role = AudioRole.VOICE,
                    ids = ids,
                )
        val out = RenderHarness.export(RenderPlanBuilder(RenderHarness.preset).build(project), "ducking")
        val pcm = RenderHarness.decodeAudio(out.file)

        fun level(
            freq: Double,
            from: Double,
            to: Double,
        ) = toneDb(pcm, freq, from, to)

        val musicBase = level(1000.0, 2.5, 4.6)
        val musicBase2 = level(1000.0, 8.4, 9.1)
        val musicUnderClip = level(1000.0, 5.4, 7.6)
        val musicUnderVoice = level(1000.0, 9.8, 10.6)
        val musicUnderSpeech = level(1000.0, 12.9, 13.8)
        val clip = level(440.0, 5.4, 7.6)
        val voice = level(550.0, 9.8, 10.6)
        val voiceUnderSpeech = level(550.0, 11.4, 12.3)
        val speech = level(300.0, 12.9, 13.8)
        val rows =
            listOf(
                "music alone (2.5-4.6 s)" to musicBase,
                "music alone (8.4-9.1 s)" to musicBase2,
                "music under clip sound (5.4-7.6 s)" to musicUnderClip,
                "music under voice (9.8-10.6 s)" to musicUnderVoice,
                "music under closing message (12.9-13.8 s)" to musicUnderSpeech,
                "clip sound 440 Hz (5.4-7.6 s)" to clip,
                "voice 550 Hz (9.8-10.6 s)" to voice,
                "voice under closing message (11.4-12.3 s)" to voiceUnderSpeech,
                "closing message 300 Hz (12.9-13.8 s)" to speech,
            )
        rows.forEach { (k, v) -> report("ducking %s: %.2f dBFS".format(Locale.ROOT, k, v)) }

        val failures = mutableListOf<String>()

        fun near(
            what: String,
            got: Double,
            want: Double,
        ) {
            report("ducking check %s: %.2f dB, want %.2f +-2".format(Locale.ROOT, what, got, want))
            if (kotlin.math.abs(got - want) > 2.0) failures += "$what was $got dB, want $want"
        }
        // Source tones are -20 dBFS; the frame music plays at its preset gain (-8 dB).
        near("music alone", musicBase, -28.0)  // -20 dBFS tone at the preset's -8 dB
        near("music after the clip recovers", musicBase2 - musicBase, 0.0)
        near("music under clip sound", musicUnderClip - musicBase, -12.0)
        near("music under voice", musicUnderVoice - musicBase, -20.0)
        near("music under the closing message", musicUnderSpeech - musicBase, -20.0)
        near("clip sound is not lowered by music", clip, -20.0)
        near("voice is not lowered by music", voice, -20.0)
        near("voice under the closing message", voiceUnderSpeech - voice, -20.0)
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }
}
