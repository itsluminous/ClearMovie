package com.itsluminous.clearmovie.render

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.FrameSpec
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setFrameLook
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cm-frame-area: a frame the user added whose screen is magenta, keyed with
 * the picked colour and with its picture area set, exported. The window
 * shows the photo placed in the area (its green quarter right of the
 * canvas's middle, where a full-canvas photo would show red), the frame's
 * white and its green swatch stay. The same video with the green key the
 * app used before leaves the window magenta: the red-first control.
 * 2 s at 360p, so it stays short on the API 29 runner.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FrameColourRenderTest {
    private val magenta = KeySpec(ArgbColor(0xFFFF00FF), FrameSpec.DEFAULT_TOLERANCE, FrameSpec.DEFAULT_SOFTNESS)
    private val green = KeySpec(ArgbColor(0xFF00D922), FrameSpec.DEFAULT_TOLERANCE, FrameSpec.DEFAULT_SOFTNESS)

    /** The window of frame_magenta.mp4, in units of its picture. */
    private val window = NormRect(0.1, 0.1, 0.5, 0.6)

    private fun win(
        u: Double,
        v: Double,
    ) = (window.x + u * window.w) to (window.y + v * window.h)

    @Test
    fun aPickedMagentaKeyAndPictureAreaPlaceThePhotoInTheWindow() {
        val ids = SequentialIds("m")
        val frameId = ClipId("magenta-frame")
        val base =
            ProjectFactory
                .blank(ProjectId("frame-colour"), ids)
                .addToMain(listOf(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 2000)), ids = ids)
                .addTimedClip(OverlayClip(frameId, fixture("frame_magenta.mp4"), 0, 2000, 2000, key = green, repeat = false), ids = ids)
        val failures = mutableListOf<String>()

        fun expect(
            what: String,
            got: IntArray,
            want: IntArray,
            tolerance: Int = 24,
        ) {
            val ok = RenderHarness.close(got, want, tolerance)
            report("frame-colour $what = ${got.toList()} want ${want.toList()} +-$tolerance ${if (ok) "ok" else "FAIL"}")
            if (!ok) failures += "$what was ${got.toList()}, want ${want.toList()}"
        }

        // Before: the green key the app always used, and no picture area.
        val before = RenderHarness.export(RenderPlanBuilder().build(base, shortSide = 360), "frame-colour-before")
        frameAt(before.file, 1_000_000).let { f ->
            savePng(f, "frame-colour-before-t1.0")
            val (x, y) = win(0.5, 0.5)
            // Magenta, not the photo: red and blue high, green low. The export's encode and the GPU's BT.601
            // decode of a BT.709 file lift green to about 37 here (observed on the Android 16 emulator), so the
            // check is by kind, not by an exact value.
            val c = rgb(f, x, y)
            val magentaLeft = c[0] > 200 && c[2] > 200 && c[1] < 80
            report("frame-colour before: window left magenta = ${c.toList()} ${if (magentaLeft) "ok" else "FAIL"}")
            if (!magentaLeft) failures += "before: window was ${c.toList()}, want magenta (R,B > 200, G < 80)"
        }

        // After: the picked magenta and the window as the picture area.
        val after = base.setFrameLook(frameId, magenta, window)
        val out = RenderHarness.export(RenderPlanBuilder().build(after, shortSide = 360), "frame-colour-after")
        frameAt(out.file, 1_000_000).let { f ->
            savePng(f, "frame-colour-after-t1.0")
            win(0.25, 0.25).let { (x, y) -> expect("window top-left shows the photo's red", rgb(f, x, y), intArrayOf(255, 0, 0)) }
            // Right of the canvas's middle (x 0.475): a full-canvas photo would be red here.
            win(0.75, 0.25).let { (x, y) -> expect("window top-right shows the photo's green", rgb(f, x, y), intArrayOf(0, 255, 0)) }
            win(0.25, 0.75).let { (x, y) -> expect("window bottom-left shows the photo's blue", rgb(f, x, y), intArrayOf(0, 0, 255)) }
            expect("frame white kept", rgb(f, 0.8, 0.85), intArrayOf(255, 255, 255))
            expect("green swatch kept by a magenta key", rgb(f, 0.81, 0.28), intArrayOf(0, 217, 34), 30)
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }
}
