package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.render.media3.CaptionBitmapRenderer
import com.itsluminous.clearmovie.render.media3.CaptionTypefaces
import kotlin.math.roundToInt

// Captions and cards in the editor are the export's own images: drawn by
// CaptionBitmapRenderer on the canvas the Best export uses (its short side
// 1080 pixels, in the video's shape), then scaled to the preview, so the
// words wrap, shrink and look exactly as they will in the video.

/**
 * The shape of the video the captions on screen belong to (cm-aspect-ratio):
 * the editor and the preset editor provide it, so a caption image is drawn
 * for that canvas.
 */
val LocalCanvasShape = staticCompositionLocalOf { CanvasShape.LANDSCAPE }

/** The renderer the editor draws captions with, for the [LocalCanvasShape] canvas. */
@Composable
internal fun rememberCaptionRenderer(): CaptionBitmapRenderer {
    val context = LocalContext.current.applicationContext
    val shape = LocalCanvasShape.current
    return remember(context, shape) { CaptionBitmapRenderer(CanvasShape.REFERENCE_SHORT_SIDE, CaptionTypefaces.get(context), shape) }
}

/** [rect]'s size in pixels on the Best export's canvas of [shape]: 1920 x 1080 for landscape. */
internal fun canvasPixels(
    rect: NormRect,
    shape: CanvasShape = CanvasShape.LANDSCAPE,
): Pair<Int, Int> {
    val (cw, ch) = shape.size(CanvasShape.REFERENCE_SHORT_SIDE)
    return (rect.w * cw).roundToInt().coerceIn(1, cw) to (rect.h * ch).roundToInt().coerceIn(1, ch)
}

/** A caption exactly as the export draws it, filling its box. */
@Composable
internal fun CaptionImage(
    text: String,
    style: CaptionStyle,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillBounds,
) {
    val renderer = rememberCaptionRenderer()
    val (w, h) = canvasPixels(style.box, LocalCanvasShape.current)
    val bitmap: Bitmap = remember(renderer, text, style, w, h) { renderer.caption(text, style, w, h) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = contentScale,
        filterQuality = FilterQuality.High,
        modifier = modifier.fillMaxSize(),
    )
}

/** A text or colour card exactly as the export draws it, for a window of [window] on the canvas. */
@Composable
internal fun CardImage(
    color: ArgbColor,
    text: String?,
    style: CaptionStyle,
    window: NormRect,
    modifier: Modifier = Modifier,
) {
    val renderer = rememberCaptionRenderer()
    val (w, h) = canvasPixels(window, LocalCanvasShape.current)
    val bitmap: Bitmap = remember(renderer, color, text, style, w, h) { renderer.card(color, text, style, w, h) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.High,
        modifier = modifier.fillMaxSize(),
    )
}
