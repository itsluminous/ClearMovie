package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.photo
import com.itsluminous.clearmovie.domain.model.Fixtures.trackOfType
import org.junit.Test

/** The video's shape (cm-aspect-ratio): sizes, the shape a picture suggests, and changing it. */
class CanvasShapeTest {
    @Test
    fun `every quality has an even size on each shape, the short side named by the quality`() {
        assertThat(CanvasShape.LANDSCAPE.size(1080)).isEqualTo(1920 to 1080)
        assertThat(CanvasShape.LANDSCAPE.size(720)).isEqualTo(1280 to 720)
        assertThat(CanvasShape.LANDSCAPE.size(480)).isEqualTo(854 to 480)
        assertThat(CanvasShape.PORTRAIT.size(1080)).isEqualTo(1080 to 1920)
        assertThat(CanvasShape.PORTRAIT.size(720)).isEqualTo(720 to 1280)
        assertThat(CanvasShape.PORTRAIT.size(480)).isEqualTo(480 to 854)
        assertThat(CanvasShape.SQUARE.size(1080)).isEqualTo(1080 to 1080)
        assertThat(CanvasShape.SQUARE.size(720)).isEqualTo(720 to 720)
        assertThat(CanvasShape.SQUARE.size(480)).isEqualTo(480 to 480)
        // The preview's low drafts too, and an odd request is made even.
        assertThat(CanvasShape.PORTRAIT.size(360)).isEqualTo(360 to 640)
        assertThat(CanvasShape.SQUARE.size(361)).isEqualTo(362 to 362)
        for (shape in CanvasShape.entries) {
            for (q in listOf(144, 360, 480, 720, 1080, 2160)) {
                val (w, h) = shape.size(q)
                assertThat(w % 2 to h % 2).isEqualTo(0 to 0)
                assertThat(minOf(w, h)).isEqualTo(q)
            }
        }
    }

    @Test
    fun `a picture suggests the shape it has, with near squares counted as square`() {
        assertThat(CanvasShape.suiting(1920, 1080)).isEqualTo(CanvasShape.LANDSCAPE)
        assertThat(CanvasShape.suiting(1080, 1920)).isEqualTo(CanvasShape.PORTRAIT)
        assertThat(CanvasShape.suiting(3000, 4000)).isEqualTo(CanvasShape.PORTRAIT)
        assertThat(CanvasShape.suiting(1000, 1050)).isEqualTo(CanvasShape.SQUARE)
        assertThat(CanvasShape.suiting(0, 100)).isNull()
    }

    @Test
    fun `a frame of another shape sits centred at its own shape`() {
        assertThat(containedRect(CanvasShape.LANDSCAPE, CanvasShape.LANDSCAPE)).isEqualTo(NormRect.FULL)
        val inPortrait = containedRect(CanvasShape.LANDSCAPE, CanvasShape.PORTRAIT)
        // A 16:9 picture across a 9:16 canvas: the full width, (9/16)^2 of the height, centred.
        assertThat(inPortrait.w).isWithin(1e-9).of(1.0)
        assertThat(inPortrait.h).isWithin(1e-9).of(81.0 / 256)
        assertThat(inPortrait.y).isWithin(1e-9).of((1 - 81.0 / 256) / 2)
        val inSquare = containedRect(CanvasShape.PORTRAIT, CanvasShape.SQUARE)
        assertThat(inSquare.h).isWithin(1e-9).of(1.0)
        assertThat(inSquare.w).isWithin(1e-9).of(9.0 / 16)
        assertThat(inSquare.x).isWithin(1e-9).of((1 - 9.0 / 16) / 2)
        val window = NormRect(0.1, 0.1, 0.8, 0.5).within(inSquare)
        assertThat(window.x).isWithin(1e-9).of(inSquare.x + 0.1 * inSquare.w)
        assertThat(window.h).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `a caption keeps its width share and middle, and its height follows the lines its words need`() {
        val box = NormRect(0.15, 0.7981, 0.83, 0.1222)
        // 16:9 to 9:16: the same share of a canvas as wide as the short side, so the same box.
        val upright = refitBox(box, CanvasShape.LANDSCAPE, CanvasShape.PORTRAIT)
        assertThat(upright).isEqualTo(box)
        // 16:9 to square: the box is 9/16 as wide in text units, so 16/9 as tall, around the same middle.
        val square = refitBox(box, CanvasShape.LANDSCAPE, CanvasShape.SQUARE)
        assertThat(square.x).isEqualTo(box.x)
        assertThat(square.w).isEqualTo(box.w)
        assertThat(square.h).isWithin(1e-4).of(box.h * 16 / 9)
        assertThat(square.y + square.h).isAtMost(1.0)
        assertThat(square.isValid).isTrue()
        // And back: the original, to the stored precision.
        val back = refitBox(square, CanvasShape.SQUARE, CanvasShape.LANDSCAPE)
        assertThat(back.h).isWithin(2e-4).of(box.h)
        // A tall box never grows past the canvas.
        val tall = refitBox(NormRect(0.0, 0.1, 1.0, 0.8), CanvasShape.PORTRAIT, CanvasShape.SQUARE)
        assertThat(tall.isValid).isTrue()
        assertThat(tall.h).isAtMost(0.96 + 1e-9)
    }

    @Test
    fun `changing the shape refits every caption box and the default, as one change`() {
        val ids = SequentialIds("s")
        val base = Fixtures.blankWith(photo("a"), ids = ids)
        val own = NormRect(0.1, 0.1, 0.5, 0.1)
        val project =
            base
                .addText("free", 0, ids = ids)
                .let { p ->
                    val caption =
                        p
                            .trackOfType(TrackType.TEXT)
                            .clips
                            .single()
                            .id
                    p.setCaptionStyle(caption, CaptionStyleOverride(box = own))
                }.normalized()
        val square = project.setShape(CanvasShape.SQUARE).normalized().assertValid()
        assertThat(square.shape).isEqualTo(CanvasShape.SQUARE)
        val caption = square.trackOfType(TrackType.TEXT).clips.single() as TextClip
        assertThat(caption.style.box).isEqualTo(refitBox(own, CanvasShape.LANDSCAPE, CanvasShape.SQUARE))
        assertThat(square.captionDefaults.box).isEqualTo(refitBox(project.captionDefaults.box, CanvasShape.LANDSCAPE, CanvasShape.SQUARE))
        // The same shape again changes nothing.
        assertThat(square.setShape(CanvasShape.SQUARE)).isSameInstanceAs(square)
    }

    @Test
    fun `a preset frame of another shape is switched off, and on again with its own shape`() {
        val project = Fixtures.presetProject()
        assertThat(project.shape).isEqualTo(CanvasShape.LANDSCAPE)
        val upright = project.setShape(CanvasShape.PORTRAIT, frameShape = CanvasShape.LANDSCAPE).normalized().assertValid()
        assertThat(upright.byRole(PresetRole.FRAME).enabled).isFalse()
        // The frame's music plays on: only the picture does not fit.
        assertThat(upright.byRole(PresetRole.FRAME_MUSIC).muted).isEqualTo(project.byRole(PresetRole.FRAME_MUSIC).muted)
        val square = upright.setShape(CanvasShape.SQUARE, frameShape = CanvasShape.LANDSCAPE)
        assertThat(square.byRole(PresetRole.FRAME).enabled).isFalse()
        val back = square.setShape(CanvasShape.LANDSCAPE, frameShape = CanvasShape.LANDSCAPE).normalized().assertValid()
        assertThat(back.byRole(PresetRole.FRAME).enabled).isTrue()
        // Without the frame's shape (no preset), rows are left as they are.
        assertThat(project.setShape(CanvasShape.PORTRAIT).byRole(PresetRole.FRAME).enabled).isTrue()
    }

    @Test
    fun `a new video without a preset asks once, and choosing stops it asking, even for landscape`() {
        val blank = ProjectFactory.blank(Fixtures.PROJECT_ID, SequentialIds("b"), askShape = true)
        assertThat(blank.askShape).isTrue()
        assertThat(blank.shape).isEqualTo(CanvasShape.LANDSCAPE)
        val kept = blank.chooseShape(CanvasShape.LANDSCAPE)
        assertThat(kept.askShape).isFalse()
        assertThat(kept.shape).isEqualTo(CanvasShape.LANDSCAPE)
        val upright = blank.chooseShape(CanvasShape.PORTRAIT)
        assertThat(upright.askShape).isFalse()
        assertThat(upright.shape).isEqualTo(CanvasShape.PORTRAIT)
        // A preset's video takes the preset's shape and never asks.
        val fromPreset =
            ProjectFactory.fromPreset(
                Fixtures.PROJECT_ID,
                Fixtures.preset.copy(shape = CanvasShape.SQUARE),
                Fixtures.presetMedia,
            )
        assertThat(fromPreset.shape).isEqualTo(CanvasShape.SQUARE)
        assertThat(fromPreset.askShape).isFalse()
    }
}
