package com.itsluminous.clearmovie.render

import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.normalized
import com.itsluminous.clearmovie.domain.model.setShape
import com.itsluminous.clearmovie.domain.model.setTrackEnabled
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.luma
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * One short export per new video shape (cm-aspect-ratio), at a 360-pixel
 * short side so each costs a few seconds: the file is the shape's size as
 * players show it, a landscape photo fills an upright canvas cropped at its
 * sides, and a landscape preset frame turned on in a square video sits at
 * its own shape with black above and below.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ShapeRenderTest {
    /** The size a player shows (the rotation the file asks for applied), and that rotation. */
    private fun shownSize(file: File): Triple<Int, Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            return if (rotation % 180 != 0) Triple(h, w, rotation) else Triple(w, h, rotation)
        } finally {
            r.release()
        }
    }

    @Test
    fun anUprightVideoIsUprightAndALandscapePhotoFillsItCroppedAtTheSides() {
        val ids = SequentialIds("u")
        val project =
            ProjectFactory
                .blank(ProjectId("shape-portrait"), ids)
                .addToMain(listOf(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 1000, fit = Fit.FILL_CROP)), ids = ids)
                .setShape(CanvasShape.PORTRAIT)
                .normalized()
        val plan = RenderPlanBuilder().build(project, shortSide = 360)
        assertThat(plan.width to plan.height).isEqualTo(360 to 640)
        val out = RenderHarness.export(plan, "shape-portrait")
        val (w, h, rotation) = shownSize(out.file)
        report("shape portrait: shown ${w}x$h, rotation $rotation, ${out.wallMs} ms")
        assertThat(w to h).isEqualTo(360 to 640)
        val f = frameAt(out.file, 500_000)
        savePng(f, "shape-portrait")
        assertThat(f.width to f.height).isEqualTo(360 to 640)
        // The photo's middle strip covers the canvas, no bars: red and blue on the left, green and white on the right.
        val topLeft = rgb(f, 0.2, 0.2)
        val bottomRight = rgb(f, 0.8, 0.8)
        assertWithMessage("top left ${topLeft.toList()}").that(topLeft[0]).isGreaterThan(150)
        assertWithMessage("top left ${topLeft.toList()}").that(topLeft[1]).isLessThan(100)
        assertWithMessage("bottom right ${bottomRight.toList()}").that(luma(bottomRight)).isGreaterThan(200.0)
        // Every corner is one of the photo's colours (a bar would be black there; blue is dark in luma, so the brightest channel).
        for ((x, y) in listOf(0.03 to 0.03, 0.97 to 0.03, 0.03 to 0.97, 0.97 to 0.97)) {
            assertWithMessage("corner $x,$y").that(rgb(f, x, y, r = 1).max()).isGreaterThan(150)
        }
    }

    @Test
    fun aLandscapeFrameTurnedOnInASquareVideoKeepsItsShapeWithBlackAboveAndBelow() {
        val ids = SequentialIds("q")
        val preset = RenderHarness.preset
        val made =
            ProjectFactory
                .fromPreset(ProjectId("shape-square"), preset, PresetMedia(introMs = null, frameMs = 12_000, endingMs = null), ids)
                .addToMain(listOf(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 1000, fit = Fit.FILL_CROP)), ids = ids)
        // Square switches the landscape frame off; the user turns it on again.
        val square = made.setShape(CanvasShape.SQUARE, frameShape = preset.shape).normalized()
        val frameRow = square.tracks.single { it.presetRole == PresetRole.FRAME }
        assertThat(frameRow.enabled).isFalse()
        val project = square.setTrackEnabled(frameRow.id, true).normalized()
        val plan = RenderPlanBuilder(preset).build(project, shortSide = 360)
        assertThat(plan.width to plan.height).isEqualTo(360 to 360)
        val out = RenderHarness.export(plan, "shape-square", RenderHarness.resolver(intro = null, ending = null))
        val (w, h, rotation) = shownSize(out.file)
        report("shape square: shown ${w}x$h, rotation $rotation, ${out.wallMs} ms")
        assertThat(w to h).isEqualTo(360 to 360)
        val f = frameAt(out.file, 500_000)
        savePng(f, "shape-square")
        // The frame band is 9/16 of the height, centred: black above and below it, the photo in its window.
        assertWithMessage("above").that(luma(rgb(f, 0.5, 0.08))).isLessThan(24.0)
        assertWithMessage("below").that(luma(rgb(f, 0.5, 0.92))).isLessThan(24.0)
        val inWindow = rgb(f, 0.25, 0.35)
        assertWithMessage("window ${inWindow.toList()}").that(inWindow[0]).isGreaterThan(150)
    }
}
