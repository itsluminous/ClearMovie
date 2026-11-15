package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.contentStartMs
import com.itsluminous.clearmovie.render.RenderHarness.countNear
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.media3.FileMediaResolver
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Keying against a real preset frame, run by hand only: copy an intro
 * (`Intro.mp4`), a frame video (`Frame-White-20s.mp4`, at least 16 s) and a
 * spoken ending (`speech_last.mp3`) into the app's external files folder
 * `realmedia/`. Without them the test is skipped, so CI never runs it and
 * no real media is ever part of the repo.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealFrameKeyingTest {
    @Test
    fun realFrameKeysCleanly() {
        val src = File(checkNotNull(RenderHarness.context.getExternalFilesDir(null)), "realmedia")
        val names = listOf("Intro.mp4", "Frame-White-20s.mp4", "speech_last.mp3")
        assumeTrue("real preset media not provided", names.all { File(src, it).isFile })
        // The renderer only reads the app's own folders, so copy into the cache.
        val dir = File(RenderHarness.context.cacheDir, "realmedia").apply { mkdirs() }
        names.forEach { n -> File(src, n).copyTo(File(dir, n), overwrite = true) }
        val resolver =
            FileMediaResolver(
                mapOf(
                    PresetSlot.INTRO to File(dir, names[0]),
                    PresetSlot.FRAME to File(dir, names[1]),
                    PresetSlot.ENDING to File(dir, names[2]),
                ),
                RenderHarness.fixtureDir,
            )
        val ids = SequentialIds("real")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("real"), RenderHarness.preset, PresetMedia(introMs = 6508, frameMs = 20000, endingMs = 6086), ids)
                .addToMain(
                    listOf(
                        PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                        VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                        PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000),
                    ),
                    ids = ids,
                )
        val frameStartUs = project.contentStartMs() * 1000
        val out = RenderHarness.export(RenderPlanBuilder(RenderHarness.preset).build(project), "real-keying", resolver)
        val keyGreen = intArrayOf(0x00, 0xD9, 0x22)
        val window = RenderHarness.preset.frame!!.contentRect
        val failures = mutableListOf<String>()
        // Inside the window, (u, v); outside it, canvas points on the border and the strip.
        val inside = listOf(0.25 to 0.25, 0.75 to 0.25, 0.75 to 0.75, 0.5 to 0.5)
        val outside =
            listOf(
                "left border" to (0.004 to 0.4),
                "right border" to (0.996 to 0.4),
                "top border" to (0.5 to 0.01),
                "strip" to (0.6 to 0.9),
                "logo" to (0.08 to 0.9),
            )
        val expectWindow =
            mapOf(
                8.0 to listOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(255, 255, 255), null),
                11.0 to listOf(null, null, null, rgb(frameAt(File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000), 0.5, 0.5)),
                14.0 to listOf(null, null, null, intArrayOf(32, 64, 224)),
                18.0 to listOf(null, null, null, intArrayOf(32, 64, 224)),
            )
        for ((t, wants) in expectWindow) {
            val tu = (t * 1_000_000).toLong()
            val f = frameAt(out.file, tu)
            val label = "%04.1f".format(Locale.ROOT, t)
            savePng(f, "real-keying-t$label")
            val source = frameAt(File(dir, names[1]), tu - frameStartUs)
            savePng(source, "real-frame-source-t$label")
            inside.zip(wants).forEach { (uv, want) ->
                if (want == null) return@forEach
                val got = rgb(f, window.x + uv.first * window.w, window.y + uv.second * window.h)
                val ok = RenderHarness.close(got, want, 16)
                report("real t=$t window$uv = ${got.toList()} want ${want.toList()} ${if (ok) "ok" else "FAIL"}")
                if (!ok) failures += "t=$t window $uv ${got.toList()}"
            }
            for ((name, p) in outside) {
                val got = rgb(f, p.first, p.second, 2)
                val want = rgb(source, p.first, p.second, 2)
                val ok = RenderHarness.close(got, want, 20)
                report("real t=$t $name = ${got.toList()} frame source ${want.toList()} ${if (ok) "ok" else "FAIL"}")
                if (!ok) failures += "t=$t $name ${got.toList()} vs ${want.toList()}"
            }
            val near10 = RenderHarness.countNearKey(f, step = 1)
            val near20 = countNear(f, keyGreen, 20.0, step = 1)
            report(
                "real t=$t pixels within dE10 of either form of #00D922: $near10 (within dE20 of #00D922: $near20) of ${f.width * f.height}",
            )
            if (near10 != 0) failures += "t=$t $near10 key-green pixels"
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }
}
