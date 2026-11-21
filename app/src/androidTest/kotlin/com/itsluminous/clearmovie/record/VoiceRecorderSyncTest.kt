package com.itsluminous.clearmovie.record

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.data.record.VoiceRecorder
import com.itsluminous.clearmovie.render.RenderHarness
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * A voice recording starts with the sound captured as the first picture
 * showed (cm-record-voice): the microphone runs from before that moment,
 * the frames before it are dropped, and the AAC encoder's own delay is
 * taken off. A click "said" exactly 1 s after the picture started must be
 * 1 s into the file as a player decodes it.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderSyncTest {
    @Test
    fun theFileStartsWithTheSoundCapturedAsThePictureStarted() {
        val mic = FakeMic()
        val file = File(RenderHarness.outDir, "record-sync.m4a").apply { delete() }
        val recorder = VoiceRecorder(mic, file)
        assertThat(recorder.start()).isTrue()
        // The countdown: the microphone is running before the picture moves.
        Thread.sleep(600)
        val pictureNs = System.nanoTime()
        mic.clicksAtNs += pictureNs + CLICK_AFTER_MS * 1_000_000
        recorder.startAt(pictureNs)
        Thread.sleep(2_000)
        val result = recorder.finish(wallNs = pictureNs + 2_000L * 1_000_000)
        assertThat(result.ok).isTrue()
        assertThat(result.timestamps).isEqualTo("microphone")
        assertThat(mic.released).isTrue()
        // What was recorded up to the stop (the file plays it after the encoder's priming), and nothing after it.
        assertThat(abs(result.durationMs - 2_000 - VoiceRecorder.ENCODER_DELAY_FRAMES * 1000 / 44_100)).isAtMost(30)

        val pcm = RenderHarness.decodeAudio(file)
        val onset = pcm.mono.indexOfFirst { abs(it) > 0.3f }
        assertWithMessage("no click in the file").that(onset).isAtLeast(0)
        val onsetMs = onset * 1000.0 / pcm.sampleRate
        val offsetMs = onsetMs - CLICK_AFTER_MS
        RenderHarness.report("record-sync: click at %.1f ms, wanted %d ms, offset %.1f ms".format(onsetMs, CLICK_AFTER_MS, offsetMs))
        assertWithMessage("click at $onsetMs ms").that(abs(offsetMs)).isAtMost(MAX_OFFSET_MS)
    }

    private companion object {
        const val CLICK_AFTER_MS = 1_000L

        /** About one video frame at 30 per second. */
        const val MAX_OFFSET_MS = 15.0
    }
}
