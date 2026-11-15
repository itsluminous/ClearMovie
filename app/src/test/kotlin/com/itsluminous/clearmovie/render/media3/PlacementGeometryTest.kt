package com.itsluminous.clearmovie.render.media3

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.render.plan.Placement
import org.junit.Test

class PlacementGeometryTest {
    private val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)

    @Test
    fun `fill crop covers the window and crops the picture's long side, centred`() {
        // A 16:9 picture into the 2.25:1 window loses height (ARCHITECTURE section 3: about 21%).
        val g = PlacementGeometry.of(Placement.Window(window, Fit.FILL_CROP), 1920, 1080, 1920, 1080)
        assertThat(g.content).isEqualTo(window)
        assertThat(g.source.w).isWithin(1e-9).of(1.0)
        assertThat(1 - g.source.h).isWithin(0.01).of(0.21)
        assertThat(g.source.y).isWithin(1e-9).of((1 - g.source.h) / 2)
        assertThat(g.mode).isEqualTo(PlacementGeometry.Mode.PLAIN)
    }

    @Test
    fun `fit with bars keeps the whole picture, centred, with bars around it`() {
        val g = PlacementGeometry.of(Placement.Window(window, Fit.FIT_BARS), 1080, 1920, 1920, 1080)
        assertThat(g.source).isEqualTo(NormRect.FULL)
        assertThat(g.content.h).isWithin(1e-9).of(window.h)
        // Portrait 9:16 at window height: width = h * 1080 * 9/16 / 1920 of the canvas.
        assertThat(g.content.w).isWithin(1e-9).of(window.h * 1080 * 9.0 / 16 / 1920)
        assertThat(g.content.x + g.content.w / 2).isWithin(1e-9).of(window.x + window.w / 2)
        assertThat(g.window).isEqualTo(window)
        assertThat(g.mode).isEqualTo(PlacementGeometry.Mode.BARS)
    }

    @Test
    fun `fit with blur samples a cover crop for the background`() {
        val g = PlacementGeometry.of(Placement.Window(window, Fit.FIT_BLUR), 1080, 1920, 1920, 1080)
        assertThat(g.mode).isEqualTo(PlacementGeometry.Mode.BLUR)
        assertThat(g.background.w).isWithin(1e-9).of(1.0)
        assertThat(g.background.h).isLessThan(1.0)
    }

    @Test
    fun `a quarter turn swaps the picture's sides`() {
        val g = PlacementGeometry.of(Placement.Window(window, Fit.FIT_BARS, Rotation.R90), 1920, 1080, 1920, 1080)
        assertThat(g.quarterTurns).isEqualTo(1)
        // Rotated, the 16:9 landscape becomes portrait, so it fits by height.
        assertThat(g.content.h).isWithin(1e-9).of(window.h)
        assertThat(g.content.w).isLessThan(window.w / 2)
    }

    @Test
    fun `picture in picture has a border scaled to the output`() {
        val rect = NormRect(0.6, 0.05, 0.35, 0.35)
        val g1080 = PlacementGeometry.of(Placement.Pip(rect), 800, 450, 1920, 1080)
        val g480 = PlacementGeometry.of(Placement.Pip(rect), 800, 450, 854, 480)
        assertThat(g1080.borderX * 1920).isWithin(1e-9).of(4.0)
        assertThat(g480.borderY * 480).isWithin(1e-9).of(2.0)
        assertThat(g1080.content).isEqualTo(rect)
    }

    @Test
    fun `full frame and caption boxes draw the whole picture`() {
        assertThat(PlacementGeometry.of(Placement.FullFrame, 10, 10, 1920, 1080).content).isEqualTo(NormRect.FULL)
        val box = NormRect(0.16, 0.62, 0.81, 0.14)
        val g = PlacementGeometry.of(Placement.Box(box), 1555, 151, 1920, 1080)
        assertThat(g.content).isEqualTo(box)
        assertThat(g.source).isEqualTo(NormRect.FULL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero sizes are refused`() {
        PlacementGeometry.of(Placement.FullFrame, 0, 10, 1920, 1080)
    }
}
