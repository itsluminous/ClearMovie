package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.FrameSpec
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.domain.model.containedRect
import com.itsluminous.clearmovie.domain.model.setFrameLook
import com.itsluminous.clearmovie.domain.model.setShape
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.domain.model.within
import org.junit.Test

/** cm-frame-area: photos and videos go into the picture area of the frame shown over them, on every shape. */
class FrameAreaPlanTest {
    private val magenta = KeySpec(ArgbColor(0xFFFF00FF), FrameSpec.DEFAULT_TOLERANCE, FrameSpec.DEFAULT_SOFTNESS)
    private val area = NormRect(0.25, 0.1, 0.5, 0.6)

    private fun photo(id: String) = PhotoClip(ClipId(id), Fixtures.media(id), 3000, fit = Fit.FILL_CROP)

    /** Two photos (0-3 s, 3-6 s) and a frame the user added over the first. */
    private fun withFrame(
        shape: CanvasShape,
        frameArea: NormRect? = area,
    ): Project {
        val ids = SequentialIds("f")
        var p =
            ProjectFactory
                .blank(Fixtures.PROJECT_ID, ids)
                .addToMain(listOf(photo("a"), photo("b")), ids = ids)
                .setShape(shape)
                .addTimedClip(Fixtures.overlay("o", 0, 3000), ids = ids)
        if (frameArea != null) p = p.setFrameLook(ClipId("o"), magenta, frameArea)
        return p
    }

    private fun RenderPlan.mainRects(): List<NormRect> =
        visual
            .filter { it.role == LayerRole.MAIN }
            .flatMap { it.segments }
            .filterIsInstance<VisualSegment.Picture>()
            .map { (it.placement as Placement.Window).rect }

    @Test
    fun `a frame the user added puts the photos it covers into its area, the rest fill the canvas, on every shape`() {
        for (shape in CanvasShape.entries) {
            for (short in listOf(480, 720, 1080)) {
                val plan = RenderPlanBuilder().build(withFrame(shape), shortSide = short)
                assertThat(plan.mainRects()).containsExactly(area, NormRect.FULL).inOrder()
                val frame =
                    plan.visual
                        .single { it.role == LayerRole.OVERLAY }
                        .segments
                        .filterIsInstance<VisualSegment.Picture>()
                        .single()
                assertThat(frame.key).isEqualTo(magenta)
                assertThat(frame.placement).isEqualTo(Placement.FullFrame)
            }
        }
    }

    @Test
    fun `a frame without an area leaves the photos on the whole canvas, as before`() {
        assertThat(RenderPlanBuilder().build(withFrame(CanvasShape.LANDSCAPE, frameArea = null)).mainRects())
            .containsExactly(NormRect.FULL, NormRect.FULL)
    }

    @Test
    fun `a frame row switched off gives its area up`() {
        val p = withFrame(CanvasShape.PORTRAIT)
        val row = p.trackOf(ClipId("o"))!!.id
        assertThat(RenderPlanBuilder().build(p.setTrackEnabled(row, false)).mainRects()).containsExactly(NormRect.FULL, NormRect.FULL)
    }

    @Test
    fun `the preset frame's own area for this video replaces the preset's window, placed in the frame's box`() {
        val ids = SequentialIds("x")
        val p0 =
            ProjectFactory
                .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, PresetMedia(introMs = 2000, frameMs = 4000, endingMs = 3000), ids)
                .addToMain(listOf(photo("p")), ids = ids)
        val frameClip = p0.byRole(PresetRole.FRAME).clips.single() as OverlayClip
        // As made, the preset's window.
        assertThat(frameWindow(p0, Fixtures.preset)).isEqualTo(Fixtures.preset.frame!!.contentRect)
        val p1 = p0.setFrameLook(frameClip.id, magenta, area)
        assertThat(frameWindow(p1, Fixtures.preset)).isEqualTo(area)
        // The photo, and the spoken ending's held picture of it, both go into the area.
        val photoRects =
            RenderPlanBuilder(Fixtures.preset)
                .build(p1)
                .visual
                .filter { it.role == LayerRole.MAIN || it.role == LayerRole.MAIN_B }
                .flatMap { it.segments }
                .filterIsInstance<VisualSegment.Picture>()
                .filter { it.source is Source.Photo }
                .map { (it.placement as Placement.Window).rect }
        assertThat(photoRects).isNotEmpty()
        assertThat(photoRects.toSet()).containsExactly(area)
        // On a square video the landscape frame is drawn centred at its own shape, and its area with it.
        val square = p1.setShape(CanvasShape.SQUARE).let { it.setTrackEnabled(it.byRole(PresetRole.FRAME).id, true) }
        val box = containedRect(CanvasShape.LANDSCAPE, CanvasShape.SQUARE)
        assertThat(frameWindow(square, Fixtures.preset)).isEqualTo(area.within(box))
        assertThat(pictureWindow(square, Fixtures.preset, 2000, 5000)).isEqualTo(area.within(box))
    }

    @Test
    fun `where two frames cover an item, the one shown longer decides`() {
        val ids = SequentialIds("g")
        val other = NormRect(0.0, 0.0, 0.5, 0.5)
        val p =
            ProjectFactory
                .blank(Fixtures.PROJECT_ID, ids)
                .addToMain(listOf(photo("a")), ids = ids)
                .addTimedClip(Fixtures.overlay("o1", 0, 1000), ids = ids)
                .addTimedClip(Fixtures.overlay("o2", 1000, 2000), ids = ids)
                .setFrameLook(ClipId("o1"), magenta, area)
                .setFrameLook(ClipId("o2"), magenta, other)
        assertThat(pictureWindow(p, null, 0, 3000)).isEqualTo(other)
        assertThat(pictureWindow(p, null, 0, 900)).isEqualTo(area)
    }

    @Test
    fun `frames keyed with different colours on one row get a preview player each`() {
        val ids = SequentialIds("h")
        val p =
            ProjectFactory
                .blank(Fixtures.PROJECT_ID, ids)
                .addToMain(listOf(photo("a"), photo("b")), ids = ids)
                .addTimedClip(Fixtures.overlay("o1", 0, 1000), ids = ids)
        val row = p.trackOf(ClipId("o1"))!!.id
        val p2 =
            p
                .addToTrack(row, Fixtures.overlay("o2", 2000, 1000), 2000, ids = ids)
                .setFrameLook(ClipId("o1"), magenta, area)
        val schedule = PreviewSchedule.of(RenderPlanBuilder().build(p2))
        val keys =
            schedule.overlays.map { lane ->
                lane.entries
                    .filterIsInstance<PreviewSchedule.LaneEntry.Play>()
                    .map { it.key }
                    .distinct()
            }
        // One row, two keys (the picked magenta, and none): two players, each with one key, never both.
        assertThat(keys).hasSize(2)
        assertThat(keys.map { it.size }).containsExactly(1, 1)
        assertThat(keys.flatten().toSet()).containsExactly(magenta, null)
        assertThat(schedule.overlays.map { it.trackId }.toSet()).containsExactly(row)
    }
}
