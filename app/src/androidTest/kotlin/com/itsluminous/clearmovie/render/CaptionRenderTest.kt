package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionFont
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextBackground
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.moveMainClip
import com.itsluminous.clearmovie.domain.model.sendBackward
import com.itsluminous.clearmovie.domain.model.setCaptionStyle
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.fixtureDir
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.media3.CaptionBitmapRenderer
import com.itsluminous.clearmovie.render.media3.CaptionTypefaces
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Captions in the exported video (ROADMAP cm-captions): in the preset's box
 * to within 2 px at 1080p, drawn over the frame, Hindi conjuncts and matras
 * shaped as a reference rasterised from the same font file, the export's
 * caption the same image the editor's preview shows, a caption that moves
 * with its photo when MAIN is reordered, and a second text row covering the
 * first where they overlap.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CaptionRenderTest {
    private val red = intArrayOf(255, 0, 0)
    private val green = intArrayOf(0, 200, 0)
    private val solidRed = TextBackground(ArgbColor(0xFFFF0000L), 0)

    private fun framed(ids: SequentialIds): Project =
        ProjectFactory
            .fromPreset(ProjectId("captions"), RenderHarness.preset, PresetMedia(frameMs = 12_000), ids)
            .addToMain(
                listOf(
                    PhotoClip(ClipId("blue"), fixture("photo_blue.png"), 2000),
                    PhotoClip(ClipId("quad"), fixture("photo_quadrants.png"), 2000),
                ),
                ids = ids,
            )

    private fun Project.captionId(text: String): ClipId = tracks.flatMap { it.clips }.first { (it as? TextClip)?.text == text }.id

    private fun export(
        project: Project,
        name: String,
    ): File = RenderHarness.export(RenderPlanBuilder(RenderHarness.preset).build(project, shortSide = 1080), name).file

    /** The caption's pure red band, not the frame's darker red border (200, 20, 20). */
    private fun isRed(c: Int) = Color.red(c) > 230 && Color.green(c) < 70 && Color.blue(c) < 70

    /**
     * The caption in the preset's box: its band's edges within 2 px of the
     * preset rectangle at 1080p, words inside it, and drawn over the frame
     * (a second caption over the frame's opaque strip shows there).
     */
    @Test
    fun captionSitsInThePresetBoxAboveTheFrame() {
        val ids = SequentialIds("a")
        var p = framed(ids).addCaptionFor(ClipId("blue"), "नमस्ते", ids)
        p = p.setCaptionStyle(p.captionId("नमस्ते"), CaptionStyleOverride(background = solidRed, clearOutline = true))
        // Over the blue strip below the window, where the frame itself is opaque.
        val overStrip = NormRect(0.90, 0.82, 0.09, 0.10)
        p =
            p.addTimedClip(
                TextClip(ids.clipId(), 0, 2000, "A", CaptionStyleOverride(box = overStrip, background = solidRed, clearOutline = true)),
                ids = ids,
            )
        val noCaption = framed(SequentialIds("a"))
        val f = frameAt(export(p, "caption-box"), 1_000_000)
        savePng(f, "caption-box-t01.0")
        val plain = frameAt(export(noCaption, "caption-box-none"), 1_000_000)
        savePng(plain, "caption-box-none-t01.0")
        assertThat(f.width to f.height).isEqualTo(1920 to 1080)

        val box = RenderHarness.preset.caption.box
        val expected = doubleArrayOf(box.x * 1920, box.y * 1080, (box.x + box.w) * 1920, (box.y + box.h) * 1080)
        // Edges of the red band, read along lines a few pixels inside it (clear of the words).
        val rowY = (expected[1] + 6).roundToInt()
        val colX = (expected[0] + 6).roundToInt()
        val left = (0 until f.width).first { isRed(f.getPixel(it, rowY)) }
        val right = (f.width - 1 downTo 0).first { isRed(f.getPixel(it, rowY)) } + 1
        val top = (0 until f.height).first { isRed(f.getPixel(colX, it)) }
        val bottom = (f.height - 1 downTo 0).first { isRed(f.getPixel(colX, it)) } + 1
        report("caption box measured=[$left,$top,$right,$bottom] expected=${expected.toList()}")
        assertWithMessage("left").that(abs(left - expected[0])).isAtMost(2.0)
        assertWithMessage("top").that(abs(top - expected[1])).isAtMost(2.0)
        assertWithMessage("right").that(abs(right - expected[2])).isAtMost(2.0)
        assertWithMessage("bottom").that(abs(bottom - expected[3])).isAtMost(2.0)
        // The words: white pixels inside the box.
        var white = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val c = f.getPixel(x, y)
                if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) white++
            }
        }
        report("caption box white pixels=$white")
        assertThat(white).isGreaterThan(2000)
        // Over the frame: red where the frame's own strip is (blue without it), sampled clear of the "A".
        val strip = rgb(f, 0.91, 0.835, 2)
        val stripPlain = rgb(plain, 0.91, 0.835, 2)
        report("over the frame: with caption=${strip.toList()} without=${stripPlain.toList()}")
        assertWithMessage("caption over the strip").that(RenderHarness.close(strip, red, 40)).isTrue()
        assertWithMessage("strip without a caption").that(RenderHarness.close(stripPlain, red, 40)).isFalse()
    }

    /**
     * "नमस्ते हिन्दी" (the स्त and न्द conjuncts, and the ि matra drawn
     * before its consonant) as the renderer draws it, compared with a
     * reference rasterised by HarfBuzz from the same font file
     * (scripts/make-caption-reference.sh). Two wrong forms of the same
     * letters, with an explicit virama and drawn letter by letter, are far
     * further from the reference, so the comparison would catch broken
     * shaping.
     */
    @Test
    fun hindiConjunctsAndMatrasMatchAReferenceFromTheSameFont() {
        val renderer = CaptionBitmapRenderer(1080, CaptionTypefaces.get(RenderHarness.context))
        val style =
            CaptionStyle(
                box = NormRect(0.0, 0.0, 1.0, 0.3),
                font = CaptionFont.SANS_BOLD.id,
                sizePx = 120,
                color = ArgbColor.BLACK,
                outline = null,
                background = TextBackground(ArgbColor.WHITE, 0),
            )
        val words = "नमस्ते हिन्दी"
        val shaped = renderer.caption(words, style, 1920, 320)
        savePng(shaped, "caption-hindi-device")
        val reference = BitmapFactory.decodeFile(File(fixtureDir, "caption_ref_namaste_hindi.png").path)
        val ref = Ink.of(reference)
        val dev = Ink.of(shaped)
        val good = Ink.iou(dev, ref)
        // An explicit virama (ZWNJ) instead of the conjuncts.
        val virama = Ink.of(renderer.caption("नमस्\u200Cते हिन्\u200Cदी", style, 1920, 320))
        // Each letter on its own: no conjuncts, the ि matra after its consonant.
        val loose = Ink.of(unshaped(words, renderer, style))
        val badVirama = Ink.iou(virama, ref)
        val badLoose = Ink.iou(loose, ref)
        report(
            "hindi shaping IoU: device=$good explicit-virama=$badVirama letter-by-letter=$badLoose aspect dev=${dev.aspect} ref=${ref.aspect}",
        )
        assertWithMessage("shaped like the reference").that(good).isAtLeast(0.85)
        assertWithMessage("same proportions as the reference").that(abs(dev.aspect - ref.aspect) / ref.aspect).isAtMost(0.04)
        assertWithMessage("explicit virama is far from the reference").that(badVirama).isLessThan(good - 0.1)
        assertWithMessage("letter by letter is far from the reference").that(badLoose).isLessThan(good - 0.1)
    }

    /** [text] drawn one code point at a time with the caption font: what broken shaping would look like. */
    private fun unshaped(
        text: String,
        renderer: CaptionBitmapRenderer,
        style: CaptionStyle,
    ): Bitmap {
        val b = Bitmap.createBitmap(1920, 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = renderer.typeface(style.font)
                textSize = 120f
                color = Color.BLACK
            }
        var x = 40f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            c.drawText(s, x, 200f, paint)
            x += paint.measureText(s)
            i += Character.charCount(cp)
        }
        return b
    }

    /**
     * The exported caption is the image the editor's preview shows: the
     * renderer's own bitmap at the box's 1080p size, matching the export's
     * pixels inside the box closely.
     */
    @Test
    fun theExportedCaptionIsThePreviewsImage() {
        val ids = SequentialIds("v")
        var p = framed(ids).addCaptionFor(ClipId("blue"), "Hello नमस्ते", ids)
        p = p.setCaptionStyle(p.captionId("Hello नमस्ते"), CaptionStyleOverride(background = TextBackground(ArgbColor(0xFF202020L), 0)))
        val f = frameAt(export(p, "caption-parity"), 1_000_000)
        val style = (p.clip(p.captionId("Hello नमस्ते")) as TextClip).style.resolve(p.captionDefaults)
        val w = (style.box.w * 1920).roundToInt()
        val h = (style.box.h * 1080).roundToInt()
        val preview = CaptionBitmapRenderer(1080, CaptionTypefaces.get(RenderHarness.context)).caption("Hello नमस्ते", style, w, h)
        val x0 = (style.box.x * 1920).roundToInt()
        val y0 = (style.box.y * 1080).roundToInt()
        val crop = Bitmap.createBitmap(f, x0, y0, w, h)
        savePng(crop, "caption-parity-export-crop")
        savePng(preview, "caption-parity-preview-image")
        var sum = 0L
        var n = 0
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val a = crop.getPixel(x, y)
                val b = preview.getPixel(x, y)
                sum += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                n += 3
            }
        }
        val mean = sum.toDouble() / n
        val inkIou = Ink.iou(Ink.of(crop, light = true), Ink.of(preview, light = true))
        report("caption parity: mean abs diff=$mean per channel, ink IoU=$inkIou")
        assertThat(mean).isLessThan(12.0)
        assertThat(inkIou).isAtLeast(0.85)
    }

    /** A caption attached to a photo plays with that photo, before and after the photo is moved later. */
    @Test
    fun aCaptionFollowsItsPhotoWhenMainIsReordered() {
        val ids = SequentialIds("r")
        var p = framed(ids).addCaptionFor(ClipId("blue"), "Blue", ids)
        p = p.setCaptionStyle(p.captionId("Blue"), CaptionStyleOverride(background = solidRed, clearOutline = true))
        val box = RenderHarness.preset.caption.box
        val nx = box.x + 0.02
        val ny = box.y + box.h / 2

        val before = export(p, "caption-follow-before")
        val b1 = frameAt(before, 1_000_000)
        val b3 = frameAt(before, 3_000_000)
        val moved = p.moveMainClip(ClipId("blue"), 1)
        assertThat((moved.clip(p.captionId("Blue")) as TextClip).startMs).isEqualTo(2000)
        val after = export(moved, "caption-follow-after")
        val a1 = frameAt(after, 1_000_000)
        val a3 = frameAt(after, 3_000_000)
        listOf(b1 to "before-t01.0", b3 to "before-t03.0", a1 to "after-t01.0", a3 to "after-t03.0").forEach { (f, n) ->
            savePng(f, "caption-follow-$n")
        }
        report("caption follow: before 1s=${rgb(b1, nx, ny).toList()} 3s=${rgb(b3, nx, ny).toList()}")
        report("caption follow: after 1s=${rgb(a1, nx, ny).toList()} 3s=${rgb(a3, nx, ny).toList()}")
        assertWithMessage("before, with the blue photo").that(RenderHarness.close(rgb(b1, nx, ny), red, 40)).isTrue()
        assertWithMessage("before, with the other photo").that(RenderHarness.close(rgb(b3, nx, ny), red, 40)).isFalse()
        assertWithMessage("after, the other photo first").that(RenderHarness.close(rgb(a1, nx, ny), red, 40)).isFalse()
        assertWithMessage("after, with the blue photo").that(RenderHarness.close(rgb(a3, nx, ny), red, 40)).isTrue()
    }

    /** Two captions at once on two text rows: the one stacked higher covers the other, until it is sent backward. */
    @Test
    fun aSecondTextRowStackedAboveCoversTheFirst() {
        val ids = SequentialIds("s")
        val shared = NormRect(0.30, 0.30, 0.30, 0.15)
        var p = framed(ids)
        p =
            p.addTimedClip(
                TextClip(ids.clipId(), 0, 2000, "low", CaptionStyleOverride(box = shared, background = solidRed, clearOutline = true)),
                ids = ids,
            )
        val greenBand = TextBackground(ArgbColor(0xFF00C800L), 0)
        p =
            p.addTimedClip(
                TextClip(
                    ids.clipId(),
                    0,
                    2000,
                    "high",
                    CaptionStyleOverride(box = shared.copy(x = 0.45), background = greenBand, clearOutline = true),
                ),
                ids = ids,
            )
        val high = p.trackOf(p.captionId("high"))!!.id
        val low = p.trackOf(p.captionId("low"))!!.id
        assertThat(high).isNotEqualTo(low)
        assertThat(p.stackIndex(high)).isGreaterThan(p.stackIndex(low))
        // The overlap is x 0.45..0.60; sampled near its top edge, clear of the words.
        val over = frameAt(export(p, "caption-rows"), 1_000_000)
        val under = frameAt(export(p.sendBackward(high), "caption-rows-sent-backward"), 1_000_000)
        savePng(over, "caption-rows-t01.0")
        savePng(under, "caption-rows-sent-backward-t01.0")
        val atOverlap = { f: Bitmap -> rgb(f, 0.47, 0.31, 2) }
        report("caption rows overlap: high on top=${atOverlap(over).toList()} sent backward=${atOverlap(under).toList()}")
        assertWithMessage("higher row covers").that(RenderHarness.close(atOverlap(over), green, 50)).isTrue()
        assertWithMessage("after send backward").that(RenderHarness.close(atOverlap(under), red, 50)).isTrue()
    }
}

/** Ink (dark pixels, or light ones with [light]) cropped to its bounds and sampled on a fixed grid, for shape comparison. */
private class Ink(
    val cells: BooleanArray,
    val aspect: Double,
) {
    companion object {
        private const val W = 240
        private const val H = 60

        fun of(
            b: Bitmap,
            light: Boolean = false,
        ): Ink {
            fun ink(c: Int): Boolean {
                val l = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
                return if (light) l > 140 else Color.alpha(c) > 128 && l < 128
            }
            var x0 = b.width
            var y0 = b.height
            var x1 = -1
            var y1 = -1
            for (y in 0 until b.height) {
                for (x in 0 until b.width) {
                    if (ink(b.getPixel(x, y))) {
                        if (x < x0) x0 = x
                        if (y < y0) y0 = y
                        if (x > x1) x1 = x
                        if (y > y1) y1 = y
                    }
                }
            }
            check(x1 >= x0) { "No ink" }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            val cells =
                BooleanArray(W * H) { i ->
                    val gx = i % W
                    val gy = i / W
                    ink(b.getPixel(x0 + (gx + 0.5) * bw / W, y0 + (gy + 0.5) * bh / H))
                }
            return Ink(cells, bw.toDouble() / bh)
        }

        private fun Bitmap.getPixel(
            x: Double,
            y: Double,
        ): Int = getPixel(x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1))

        fun iou(
            a: Ink,
            b: Ink,
        ): Double {
            var both = 0
            var either = 0
            for (i in a.cells.indices) {
                if (a.cells[i] && b.cells[i]) both++
                if (a.cells[i] || b.cells[i]) either++
            }
            return if (either == 0) 0.0 else both.toDouble() / either
        }
    }
}
