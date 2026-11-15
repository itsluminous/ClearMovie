package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.totalDurationMs
import com.itsluminous.clearmovie.render.RenderHarness.countNear
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Risk 1 and the keying acceptance (ROADMAP cm-render): the preset frame is
 * keyed in a multi-sequence composition whose frame row starts with a
 * transparent fill (it begins after the intro) and repeats a 12 s frame
 * video over 13 s (the second copy, cut to 1 s, plays from 14 s). Window pixels show the clip, border pixels stay the frame,
 * the logo that overlaps the window stays, no key green is left, and the
 * intro is not hidden at t = 1 s.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class KeyingRenderTest {
    private val keyGreen = intArrayOf(0x00, 0xD9, 0x22)

    /** A point inside the frame window, by window-relative (u, v). */
    private fun win(
        u: Double,
        v: Double,
    ) = (0.0094 + u * 0.9813) to (0.0222 + v * 0.7741)

    @Test
    fun keyedFrameShowsTheClipKeepsTheBorderAndNeverHidesTheIntro() {
        val ids = SequentialIds("k")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("keying"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000), ids)
                .addToMain(
                    listOf(
                        PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                        VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                        PhotoClip(ids.clipId(), fixture("photo_blue.png"), 4000),
                    ),
                    ids = ids,
                )
        assertThat(project.totalDurationMs()).isEqualTo(15_000)
        val plan = RenderPlanBuilder(RenderHarness.preset).build(project)
        val out = RenderHarness.export(plan, "keying")

        val failures = mutableListOf<String>()

        fun expect(
            what: String,
            got: IntArray,
            want: IntArray,
            tolerance: Int,
        ) {
            val ok = close(got, want, tolerance)
            report("keying $what = ${got.toList()} want ${want.toList()} +-$tolerance ${if (ok) "ok" else "FAIL"}")
            if (!ok) failures += "$what was ${got.toList()}, want ${want.toList()}"
        }

        // Expected colours come from the same decoder reading the source files,
        // so only what the renderer does is measured.
        val introSrc = frameAt(java.io.File(RenderHarness.fixtureDir, "intro.mp4"), 1_000_000)
        val darkSrc = frameAt(java.io.File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000)
        val frameSrc = frameAt(java.io.File(RenderHarness.fixtureDir, "frame.mp4"), 1_500_000)
        val border = rgb(frameSrc, 0.003, 0.5)
        val top = rgb(frameSrc, 0.5, 0.009)
        val strip = rgb(frameSrc, 0.95, 0.85)
        val logo = rgb(frameSrc, 0.1, 0.769)

        // t = 1 s: the intro, full frame, while the frame row is still a transparent fill.
        frameAt(out.file, 1_000_000).let { f ->
            savePng(f, "keying-t01.0-intro")
            val (x, y) = win(0.5, 0.6)
            expect("t=1.0 intro in window", rgb(f, x, y), rgb(introSrc, x, y), 12)
            expect("t=1.0 intro at border", rgb(f, 0.003, 0.5), rgb(introSrc, 0.003, 0.5), 12)
            val green = countNear(f, keyGreen, 10.0)
            report("keying t=1.0 pixels within dE10 of #00D922: $green")
            if (green != 0) failures += "t=1.0 has $green key-green pixels"
        }

        data class Check(
            val timeS: Double,
            val label: String,
            val u: Double,
            val v: Double,
            val expect: IntArray,
        )
        val darkCentre = rgb(darkSrc, 0.5, 0.5)
        val checks =
            listOf(
                Check(3.5, "photo-quadrants", 0.25, 0.25, intArrayOf(255, 0, 0)),
                Check(3.5, "photo-quadrants", 0.75, 0.25, intArrayOf(0, 255, 0)),
                Check(3.5, "photo-quadrants", 0.25, 0.75, intArrayOf(0, 0, 255)),
                Check(3.5, "photo-quadrants", 0.75, 0.75, intArrayOf(255, 255, 255)),
                Check(6.5, "video-dark", 0.5, 0.5, darkCentre),
                Check(10.0, "photo-blue", 0.5, 0.5, intArrayOf(32, 64, 224)),
                Check(13.5, "held-last-under-closing", 0.5, 0.5, intArrayOf(32, 64, 224)),
                Check(14.5, "second-frame-copy", 0.5, 0.5, intArrayOf(32, 64, 224)),
            )
        for ((timeS, group) in checks.groupBy { it.timeS }) {
            val f = frameAt(out.file, (timeS * 1_000_000).toLong())
            savePng(f, "keying-t%04.1f-%s".format(java.util.Locale.ROOT, timeS, group.first().label))
            for (c in group) {
                val (x, y) = win(c.u, c.v)
                expect("t=$timeS ${c.label} window(${c.u},${c.v})", rgb(f, x, y), c.expect, 14)
            }
            expect("t=$timeS left border", rgb(f, 0.003, 0.5), border, 14)
            expect("t=$timeS top border", rgb(f, 0.5, 0.009), top, 14)
            expect("t=$timeS strip", rgb(f, 0.95, 0.85), strip, 14)
            expect("t=$timeS logo over the window", rgb(f, 0.1, 0.769), logo, 14)
            val green = countNear(f, keyGreen, 10.0)
            report("keying t=$timeS pixels within dE10 of #00D922: $green")
            if (green != 0) failures += "t=$timeS has $green key-green pixels"
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
        assertThat(RenderHarness.durationUs(out.file)).isIn(
            com.google.common.collect.Range
                .closed(14_950_000L, 15_050_000L),
        )
    }

    private fun close(
        a: IntArray,
        b: IntArray,
        t: Int,
    ) = RenderHarness.close(a, b, t)
}
