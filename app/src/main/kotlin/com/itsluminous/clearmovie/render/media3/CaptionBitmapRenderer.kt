package com.itsluminous.clearmovie.render.media3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.TextAlign
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws captions and cards as bitmaps (ARCHITECTURE section 7, TEXT rows).
 * A caption is drawn at its box's pixel size: the rounded background, then
 * the text outline, then the text, wrapped to the box and centred
 * vertically. Sizes in [CaptionStyle] are pixels on a 1080-line canvas and
 * scale with the output height. Text that does not fit is cut at the box.
 *
 * Only Latin text is checked here; the bundled Noto fonts and Devanagari
 * shaping arrive with cm-captions, so every font id falls back to the
 * system's bold sans-serif for now.
 */
class CaptionBitmapRenderer(
    private val canvasHeight: Int,
) {
    private val scale = canvasHeight / 1080f

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
        drawText(canvas, text, style, bitmap.width, bitmap.height)
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

    private fun drawText(
        canvas: Canvas,
        text: String,
        style: CaptionStyle,
        width: Int,
        height: Int,
    ) {
        val pad = (PADDING_PX * scale).roundToInt()
        val layoutWidth = max(1, width - 2 * pad)
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.sizePx * scale
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        val align =
            when (style.align) {
                TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            }

        fun layout(p: TextPaint) =
            StaticLayout.Builder
                .obtain(text, 0, text.length, p, layoutWidth)
                .setAlignment(align)
                .setIncludePad(false)
                .build()
        val fill = layout(paint.apply { color = style.color.toAndroid() })
        val top = ((height - fill.height) / 2f).coerceAtLeast(0f)
        canvas.withClip(0, 0, width, height) {
            translate(pad.toFloat(), top)
            drawLayers(this, paint, style, fill, ::layout)
        }
    }

    private fun drawLayers(
        canvas: Canvas,
        paint: TextPaint,
        style: CaptionStyle,
        fill: StaticLayout,
        layout: (TextPaint) -> StaticLayout,
    ) {
        style.outline?.takeIf { it.widthPx > 0 }?.let { o ->
            val stroke =
                TextPaint(paint).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = o.widthPx * scale * 2
                    strokeJoin = Paint.Join.ROUND
                    color = o.color.toAndroid()
                }
            layout(stroke).draw(canvas)
        }
        fill.draw(canvas)
    }

    private companion object {
        const val PADDING_PX = 16
        const val OPAQUE = Color.BLACK

        fun ArgbColor.toAndroid(): Int = argb.toInt()
    }
}
