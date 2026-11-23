package com.itsluminous.clearmovie.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.mainLayout
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
 * The whole export (cm-frame-audio, kupraki's call "lower only under videos
 * with sound"): the preset's frame music, a 1 kHz tone, is not lowered under
 * a video whose file has no sound track, and is lowered 12 dB under one with
 * sound, measured on the saved file.
 */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class SilentVideoDuckingDeviceTest {
    private val h = ExportHarness()

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
    }

    @After
    fun tearDown() = h.cleanUp()

    private fun musicDropUnder(
        asset: String,
        ms: Long,
    ): Double {
        // As a video picked before this change: no answer kept on it, so the export reads the file itself.
        val video = VideoClip(ClipId(RandomIds.next()), MediaRef.Store(h.insert(asset, "video/mp4").toString()), sourceDurationMs = ms)
        val items = listOf(h.photo(3000), video, h.photo(3000, "fixtures/photo_blue.png"))
        val project = h.project(items, "Ducking ${asset.substringAfterLast('/')}", caption = null)
        val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, "Ducking") {} }
        val done = (outcome as ExportOutcome.Done).done
        h.exported(done.uri)
        val file = h.keep(done.uri, "ducking-${asset.substringAfterLast('/')}")
        val pcm = RenderHarness.decodeAudio(file)
        // The slots as the export lays them out. "After" is the second photo, well clear of the 300 ms release
        // after the video and of the closing message's lowering at the end; "under" is the video's middle.
        val slots = project.mainLayout()
        val photo = slots.single { it.clip.id == items[2].id }
        val clip = slots.single { it.clip.id == video.id }
        val before = RenderHarness.toneDb(pcm, 1000.0, (photo.startMs + 1300) / 1000.0, (photo.endMs - 1000) / 1000.0)
        val under = RenderHarness.toneDb(pcm, 1000.0, (clip.startMs + 700) / 1000.0, (clip.endMs - 700) / 1000.0)
        RenderHarness.report("silent-video ducking %s: music %.2f dB after, %.2f dB under".format(Locale.ROOT, asset, before, under))
        return under - before
    }

    @Test
    fun theFrameMusicIsLoweredOnlyUnderAVideoWithSound() {
        val silent = musicDropUnder("fixtures/frame_magenta.mp4", 2000)
        val loud = musicDropUnder("fixtures/clip_dark.mp4", 3000)
        RenderHarness.report(
            "silent-video ducking check: %.2f dB under no sound (want 0), %.2f dB under sound (want -12)".format(Locale.ROOT, silent, loud),
        )
        assertWithMessage("under a video without sound").that(abs(silent)).isLessThan(2.0)
        assertWithMessage("under a video with sound").that(abs(loud + 12.0)).isLessThan(2.5)
    }
}
