package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.render.media3.CaptionBitmapRenderer
import com.itsluminous.clearmovie.render.media3.CaptionTypefaces
import kotlin.math.roundToInt

// Captions and cards in the editor are the export's own images: drawn by
// CaptionBitmapRenderer on the 1080-line canvas the export uses, then scaled
// to the preview, so the words wrap, shrink and look exactly as they will in
// the video.

/** The export canvas a caption image is drawn for: 1920 x 1080. */
internal const val CAPTION_CANVAS_W = 1920
internal const val CAPTION_CANVAS_H = 1080

/** The renderer the editor draws captions with. */
@Composable
internal fun rememberCaptionRenderer(): CaptionBitmapRenderer {
    val context = LocalContext.current.applicationContext
    return remember(context) { CaptionBitmapRenderer(CAPTION_CANVAS_H, CaptionTypefaces.get(context)) }
}

/** [rect]'s size in pixels on the export canvas. */
internal fun canvasPixels(rect: NormRect): Pair<Int, Int> =
    (rect.w * CAPTION_CANVAS_W).roundToInt().coerceIn(1, CAPTION_CANVAS_W) to
        (rect.h * CAPTION_CANVAS_H).roundToInt().coerceIn(1, CAPTION_CANVAS_H)

/** A caption exactly as the export draws it, filling its box. */
@Composable
internal fun CaptionImage(
    text: String,
    style: CaptionStyle,
    modifier: Modifier = Modifier,
) {
    val renderer = rememberCaptionRenderer()
    val (w, h) = canvasPixels(style.box)
    val bitmap: Bitmap = remember(renderer, text, style, w, h) { renderer.caption(text, style, w, h) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
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
    val (w, h) = canvasPixels(window)
    val bitmap: Bitmap = remember(renderer, color, text, style, w, h) { renderer.card(color, text, style, w, h) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.High,
        modifier = modifier.fillMaxSize(),
    )
}
