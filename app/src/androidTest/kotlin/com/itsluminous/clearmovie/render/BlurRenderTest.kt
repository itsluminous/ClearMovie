package com.itsluminous.clearmovie.render

import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Fit with blur (cm-timeline, the Fit action): the background beside a
 * fitted picture is a smooth blur of it, with no ghost copies of its sharp
 * edges. The four-colour fixture has hard edges in the middle; a portrait
 * turn leaves wide bands of blurred background at the sides.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BlurRenderTest {
    @Test
    fun fitWithBlurBackgroundIsSmoothAndFollowsThePicture() {
        val ids = SequentialIds("b")
        val project =
            ProjectFactory
                .blank(ProjectId("blur"), ids)
                .addToMain(
                    listOf(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 2000, rotation = Rotation.R90, fit = Fit.FIT_BLUR)),
                    ids = ids,
                )
        val out = RenderHarness.export(RenderPlanBuilder().build(project), "fit-blur")
        val f = frameAt(out.file, 1_000_000)
        savePng(f, "fit-blur-t1.0")
        // A column through the left band of background, across the blurred edge between the colours.
        val x = (0.15 * f.width).toInt()

        fun luma(y: Int): Double {
            val c = f.getPixel(x, y)
            return 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
        }
        val l = DoubleArray(f.height) { luma(it) }
        val top = l.take(f.height / 4).average()
        val bottom = l.takeLast(f.height / 4).average()
        val change = abs(top - bottom)
        // Ghost copies are bands with hard steps between them; a blur changes a little on each row.
        // Averaged over 2 px pairs so the encoder's noise does not count as a step.
        var jump = 0.0
        for (y in 2 until f.height - 2) jump = maxOf(jump, abs((l[y + 1] + l[y + 2]) - (l[y - 1] + l[y])) / 4)
        val lo = minOf(top, bottom) + 0.1 * change
        val hi = maxOf(top, bottom) - 0.1 * change
        val within = l.count { it in lo..hi }
        report(
            "fit-blur background: colour change %.0f levels, largest step %.1f (%.1f%% of the change), change spans %d px (%.1f%% of the height)"
                .format(change, jump, 100 * jump / change, within, 100.0 * within / f.height),
        )
        // The background follows the picture: two different colours above and below.
        assertThat(change).isGreaterThan(30.0)
        // No bands: no single step is more than 2% of the whole change (the old single pass of
        // spaced taps measured 3.0% here, in seven bands; the two-pass blur 1.1%).
        assertThat(jump / change).isLessThan(0.02)
        // A soft background, not a hard edge: the change takes several percent of the height.
        assertThat(within.toDouble() / f.height).isAtLeast(0.04)
    }
}
