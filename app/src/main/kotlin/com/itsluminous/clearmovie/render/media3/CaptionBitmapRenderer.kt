package com.itsluminous.clearmovie.render.media3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.TextAlign
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws captions and cards as bitmaps (ARCHITECTURE section 7, TEXT rows).
 * The export and the editor's preview both draw with this, so a caption
 * looks the same in both. A caption is drawn at its box's pixel size: the
 * rounded background, then the text outline, then the text, wrapped to the
 * box and centred vertically. Sizes in [CaptionStyle] are pixels on a
 * 1080-line canvas: the text is laid out in those units (with linear,
 * sub-pixel metrics) and the drawing is scaled to [canvasHeight], so it
 * wraps and shrinks the same way at every export size and in the preview.
 *
 * Text is shaped by the platform with the bundled Noto fonts
 * ([CaptionTypefaces]): Devanagari conjuncts and matras form as in any
 * Android text, and the line height makes room for the Devanagari font's
 * marks above and below the line. Text that does not fit the box at its
 * size is drawn smaller until it fits (down to [MIN_FIT] of its size), and
 * only then cut at the box.
 */
class CaptionBitmapRenderer(
    private val shortSide: Int,
    private val typefaces: CaptionTypefaces?,
    shape: CanvasShape = CanvasShape.LANDSCAPE,
) {
    private val scale = shortSide / CanvasShape.REFERENCE_SHORT_SIDE.toFloat()

    /** The canvas's width in layout units: 1920 for landscape, 1080 upright or square. */
    private val logicalCanvasWidth = shape.size(CanvasShape.REFERENCE_SHORT_SIDE).first.toDouble()

    /** The canvas's height in layout units: 1080 for landscape, 1920 upright. */
    private val logicalCanvasHeight = shape.size(CanvasShape.REFERENCE_SHORT_SIDE).second.toDouble()

    fun caption(
        text: String,
        style: CaptionStyle,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val bitmap = createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        style.background?.let { bg ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.color.toAndroid() }
            val r = bg.cornerPx * scale
            canvas.drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), r, r, paint)
        }
        // Laid out on the caption's box in 1080-line units, not on the rounded bitmap, so every export size wraps alike.
        drawText(canvas, text, style, bitmap.width, bitmap.height, logicalWidth = (style.box.w * logicalCanvasWidth).toFloat())
        return bitmap
    }

    fun card(
        color: ArgbColor,
        text: String?,
        style: CaptionStyle,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val bitmap = createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.drawColor(color.toAndroid() or OPAQUE)
        if (!text.isNullOrBlank()) drawText(canvas, text, style.copy(sizePx = style.sizePx * 2), bitmap.width, bitmap.height)
        return bitmap
    }

    /** The typeface a style's font id draws with. */
    fun typeface(fontId: String): Typeface = typefaces?.of(fontId) ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /**
     * Which font files a style's font id really draws with: the bundled ones,
     * or the system's when they could not be loaded. Part of a cached image's
     * name, so an image drawn with the system font is never reused once the
     * bundled one works.
     */
    fun fontKey(fontId: String): String = if (typefaces?.isBundled(fontId) == true) "noto" else "system"

    /**
     * The height (in units of the canvas height) a caption box [w] wide (in
     * units of the canvas width) needs to show [text] in [style] at its own
     * size, every line, with the padding and outline room [caption] leaves,
     * and a little to spare so that rounding to a smaller export's pixels
     * never makes it shrink there. The editor keeps a box at least this tall
     * while it is resized, so the words keep their size (cm-caption-box).
     */
    fun boxHeightFor(
        text: String,
        style: CaptionStyle,
        w: Double,
    ): Double {
        val layoutWidth = max(1, (w * logicalCanvasWidth - 2 * PADDING_PX).toInt())
        val paint = textPaint(style).apply { textSize = style.sizePx.toFloat() }
        val outline = style.outline?.takeIf { it.widthPx > 0 }?.widthPx ?: 0
        val needed = layout(text, paint, layoutWidth, style).height + 2 * PADDING_PX + 2 * outline + FIT_SLACK_PX
        return needed / logicalCanvasHeight
    }

    private fun textPaint(style: CaptionStyle) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = typeface(style.font)
            color = style.color.toAndroid()
        }

    private fun layout(
        text: String,
        p: TextPaint,
        layoutWidth: Int,
        style: CaptionStyle,
    ): StaticLayout {
        val align =
            when (style.align) {
                TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, p, layoutWidth)
            .setAlignment(align)
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(true)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        style: CaptionStyle,
        width: Int,
        height: Int,
        logicalWidth: Float? = null,
    ) {
        // Everything is laid out in 1080-line units, then drawn scaled.
        val logicalW = logicalWidth ?: (width / scale)
        val logicalH = height / scale
        val pad = PADDING_PX.toFloat()
        val layoutWidth = max(1, (logicalW - 2 * pad).toInt())
        val outline = style.outline?.takeIf { it.widthPx > 0 }
        // The outline is stroked around the glyphs, so it needs its width of room at the box's edges too.
        val room = (logicalH - 2 * pad - 2 * (outline?.widthPx ?: 0)).coerceAtLeast(1f)
        val paint = textPaint(style)
        val wanted = style.sizePx.toFloat()
        var size = wanted
        paint.textSize = size
        var fill = layout(text, paint, layoutWidth, style)
        while (fill.height > room && size > wanted * MIN_FIT) {
            size = max(wanted * MIN_FIT, size * FIT_STEP)
            paint.textSize = size
            fill = layout(text, paint, layoutWidth, style)
        }
        val top = ((logicalH - fill.height) / 2f).coerceAtLeast(0f)
        canvas.withClip(0, 0, width, height) {
            scale(scale, scale)
            translate(pad, top)
            outline?.let { o ->
                val stroke =
                    TextPaint(paint).apply {
                        this.style = Paint.Style.STROKE
                        strokeWidth = o.widthPx * 2 * (size / wanted)
                        strokeJoin = Paint.Join.ROUND
                        color = o.color.toAndroid()
                    }
                layout(text, stroke, layoutWidth, style).draw(this)
            }
            fill.draw(this)
        }
    }

    companion object {
        /** The room kept between a caption's box and its words, on every side, in 1080-line pixels. */
        const val PADDING_PX = 16

        /** Room [boxHeightFor] adds for rounding: a 480-line export rounds a box down by up to about 2 of these. */
        private const val FIT_SLACK_PX = 4
        private const val OPAQUE = Color.BLACK

        /** Text is drawn no smaller than this share of its size to fit its box. */
        const val MIN_FIT = 0.5f
        private const val FIT_STEP = 0.92f

        /**
         * Part of every cached caption and card image's name: bump it when the
         * drawing changes, so an export never reuses an image drawn the old way.
         */
        const val VERSION = 3

        /** The canvas width in 1080-line units (the canvas is always 16:9). */

        private fun ArgbColor.toAndroid(): Int = argb.toInt()
    }
}
