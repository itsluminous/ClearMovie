package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.containedRect
import com.itsluminous.clearmovie.domain.model.normalized
import com.itsluminous.clearmovie.domain.model.setShape
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.within
import com.itsluminous.clearmovie.render.media3.PlacementGeometry
import org.junit.Test

/** The plan on each video shape (cm-aspect-ratio): sizes, windows, the preset's parts and frame, PiP. */
class ShapePlanTest {
    private val preset = Fixtures.preset

    private fun photo(
        id: String,
        fit: Fit = Fit.FILL_CROP,
    ) = PhotoClip(ClipId(id), Fixtures.media(id), 3000, fit = fit)

    private fun video(id: String) = VideoClip(ClipId(id), Fixtures.media(id), sourceDurationMs = 3000)

    private fun blank(shape: CanvasShape): Project {
        val ids = SequentialIds("b")
        return ProjectFactory
            .blank(Fixtures.PROJECT_ID, ids)
            .addToMain(listOf(photo("p", Fit.FIT_BLUR), video("v")), ids = ids)
            .addText("Hello", 0, ids = ids)
            .setShape(shape)
            .normalized()
    }

    private fun presetProject(ids: SequentialIds = SequentialIds("x")): Project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, preset, PresetMedia(introMs = 2000, frameMs = 4000, endingMs = 3000), ids)
            .addToMain(listOf(photo("p")), ids = ids)

    private fun RenderPlan.pictures(role: LayerRole): List<VisualSegment.Picture> =
        visual.filter { it.role == role }.flatMap { it.segments }.filterIsInstance<VisualSegment.Picture>()

    @Test
    fun `each export quality gives the shape's size, its short side the quality`() {
        for (shape in CanvasShape.entries) {
            for (quality in ExportQuality.entries) {
                val plan = RenderPlanBuilder().build(blank(shape), shortSide = quality.shortSide)
                assertThat(plan.width to plan.height).isEqualTo(shape.size(quality.shortSide))
                assertThat(plan.shortSide).isEqualTo(quality.shortSide)
                assertThat(plan.shape).isEqualTo(shape)
            }
        }
        assertThat(RenderPlanBuilder().build(blank(CanvasShape.PORTRAIT), shortSide = 720).let { it.width to it.height })
            .isEqualTo(720 to 1280)
        assertThat(RenderPlanBuilder().build(blank(CanvasShape.SQUARE), shortSide = 1080).let { it.width to it.height })
            .isEqualTo(1080 to 1080)
    }

    @Test
    fun `without a preset, photos and videos fill the whole canvas by their own fit, on every shape`() {
        for (shape in CanvasShape.entries) {
            val main = RenderPlanBuilder().build(blank(shape)).pictures(LayerRole.MAIN)
            assertThat(main.map { it.placement }).containsExactly(
                Placement.Window(NormRect.FULL, Fit.FIT_BLUR),
                Placement.Window(NormRect.FULL, Fit.FILL_CROP),
            )
        }
    }

    @Test
    fun `a landscape photo and an upright one are each fitted, both ways, by the shader maths`() {
        // An upright canvas: a landscape photo cropped to fill, or fitted whole with a blurred surround.
        val (pw, ph) = CanvasShape.PORTRAIT.size(1080)
        val crop = PlacementGeometry.of(Placement.Window(NormRect.FULL, Fit.FILL_CROP), 1920, 1080, pw, ph)
        assertThat(crop.source.h).isWithin(1e-9).of(1.0)
        assertThat(crop.source.w).isWithin(1e-6).of((1080.0 / 1920) / (1920.0 / 1080))
        val blur = PlacementGeometry.of(Placement.Window(NormRect.FULL, Fit.FIT_BLUR), 1920, 1080, pw, ph)
        assertThat(blur.content.w).isWithin(1e-9).of(1.0)
        assertThat(blur.content.h).isWithin(1e-6).of(1080.0 * 1080 / 1920 / 1920)
        assertThat(blur.mode).isEqualTo(PlacementGeometry.Mode.BLUR)
        // A landscape canvas: an upright video fitted with bars at the full height.
        val bars = PlacementGeometry.of(Placement.Window(NormRect.FULL, Fit.FIT_BARS), 1080, 1920, 1920, 1080)
        assertThat(bars.content.h).isWithin(1e-9).of(1.0)
        assertThat(bars.content.w).isWithin(1e-6).of(1080.0 * 1080 / 1920 / 1920)
        // A square canvas: an upright photo cropped top and bottom.
        val square = PlacementGeometry.of(Placement.Window(NormRect.FULL, Fit.FILL_CROP), 1080, 1920, 1080, 1080)
        assertThat(square.source.w).isWithin(1e-9).of(1.0)
        assertThat(square.source.h).isWithin(1e-6).of(1080.0 / 1920)
    }

    @Test
    fun `the preset on its own shape draws its intro and frame over the whole canvas, as before`() {
        val plan = RenderPlanBuilder(preset).build(presetProject())
        val intro = plan.pictures(LayerRole.MAIN).first()
        assertThat(intro.placement).isEqualTo(Placement.FullFrame)
        assertThat(plan.pictures(LayerRole.OVERLAY).map { it.placement }.distinct()).containsExactly(Placement.FullFrame)
        assertThat(frameWindow(presetProject(), preset)).isEqualTo(preset.frame!!.contentRect)
    }

    @Test
    fun `an upright video from a landscape preset switches the frame off and fits the intro over a blur`() {
        val upright = presetProject().setShape(CanvasShape.PORTRAIT, frameShape = preset.shape).normalized()
        val plan = RenderPlanBuilder(preset).build(upright)
        assertThat(plan.width to plan.height).isEqualTo(1080 to 1920)
        assertThat(plan.visual.none { it.role == LayerRole.OVERLAY }).isTrue()
        val main = plan.pictures(LayerRole.MAIN)
        assertThat(main.first().placement).isEqualTo(Placement.Window(NormRect.FULL, Fit.FIT_BLUR))
        // The user's photo fills the whole upright canvas, no frame window.
        assertThat(main.map { it.placement }).contains(Placement.Window(NormRect.FULL, Fit.FILL_CROP))
        // The spoken ending holds the photo where it was.
        assertThat(main.last().placement).isEqualTo(Placement.Window(NormRect.FULL, Fit.FILL_CROP))
    }

    @Test
    fun `a frame switched back on in another shape keeps its own shape, centred, with its window inside`() {
        val upright = presetProject().setShape(CanvasShape.PORTRAIT, frameShape = preset.shape).normalized()
        val frameRow = upright.tracks.single { it.presetRole == PresetRole.FRAME }.id
        val on = upright.setTrackEnabled(frameRow, true).normalized()
        val box = containedRect(CanvasShape.LANDSCAPE, CanvasShape.PORTRAIT)
        val plan = RenderPlanBuilder(preset).build(on)
        assertThat(plan.pictures(LayerRole.OVERLAY).map { it.placement }.distinct()).containsExactly(Placement.Box(box))
        val window = preset.frame!!.contentRect.within(box)
        assertThat(frameWindow(on, preset)).isEqualTo(window)
        assertThat(plan.pictures(LayerRole.MAIN).map { it.placement }).contains(Placement.Window(window, Fit.FILL_CROP))
        assertThat(frameBox(on, preset)).isEqualTo(box)
        assertThat(frameBox(presetProject(), preset)).isEqualTo(NormRect.FULL)
    }

    @Test
    fun `picture in picture keeps a 16 by 9 box in its corner on every shape`() {
        for (shape in CanvasShape.entries) {
            val (w, h) = shape.size(1080)
            val rect = RenderPlanBuilder.pipRect(PipPlacement(Corner.TOP_LEFT), NormRect.FULL, w.toDouble() / h)
            assertThat(rect.w * w / (rect.h * h)).isWithin(1e-6).of(16.0 / 9)
            assertThat(rect.isValid).isTrue()
            // Its border is as thin on an upright canvas as on a landscape one: 4 px on the 1080 short side.
            val g = PlacementGeometry.of(Placement.Pip(rect), 1920, 1080, w, h)
            assertThat(g.borderX * w).isWithin(1e-9).of(4.0)
            assertThat(g.borderY * h).isWithin(1e-9).of(4.0)
        }
    }
}
