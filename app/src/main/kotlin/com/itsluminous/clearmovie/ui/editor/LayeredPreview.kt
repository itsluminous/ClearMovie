package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.Placement
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.LayerRef
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.MainContent
import com.itsluminous.clearmovie.render.plan.Source
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import com.itsluminous.clearmovie.render.preview.PreviewState
import com.itsluminous.clearmovie.render.preview.StillLoader
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors
import com.itsluminous.clearmovie.domain.model.TextAlign as CaptionAlign

/** Test tag of the preview's 16:9 canvas. */
const val PREVIEW_TAG = "editor_preview"

/**
 * The layered preview (render decision D1): a 16:9 canvas on which the rows
 * are drawn in stacking order, bottom first, as the export draws them. MAIN
 * is the plain player's picture placed where the plan puts it (full frame
 * for the intro, into the frame's window for the user's items, cropped or
 * fitted by the item's fit), or a still the preview draws itself; each frame
 * row is its keyed player over it; captions are text in their boxes.
 */
@OptIn(UnstableApi::class)
@Composable
fun LayeredPreview(
    engine: LayeredPreviewEngine,
    schedule: PreviewSchedule,
    state: PreviewState,
    stills: StillLoader,
    resolver: MediaResolver?,
    modifier: Modifier = Modifier,
) {
    val media = LocalMediaColors.current
    BoxWithConstraints(modifier.clipToBounds().background(media.previewBackground).testTag(PREVIEW_TAG)) {
        val w = maxWidth
        val h = maxHeight
        for (layer in schedule.layerOrder) {
            key(layer) {
                when (layer) {
                    LayerRef.Main -> {
                        MainLayer(engine, schedule, state, stills, resolver, w, h)
                    }

                    is LayerRef.Overlay -> {
                        // A new surface for each load: see PreviewState.loads.
                        key(state.loads) {
                            OverlayLayer(engine, layer.index, state.overlayVisible.getOrElse(layer.index) { false })
                        }
                    }

                    is LayerRef.Captions -> {
                        CaptionLayer(schedule, layer, state.positionUs, w, h)
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MainLayer(
    engine: LayeredPreviewEngine,
    schedule: PreviewSchedule,
    state: PreviewState,
    stills: StillLoader,
    resolver: MediaResolver?,
    w: Dp,
    h: Dp,
) {
    val entry = schedule.main.getOrNull(state.mainIndex) ?: schedule.main.firstOrNull() ?: return
    val placement = entry.placement
    val rect = (placement as? Placement.Window)?.rect ?: NormRect.FULL
    val fit = (placement as? Placement.Window)?.fit
    val rotation = (placement as? Placement.Window)?.rotation ?: Rotation.R0
    val isVideo = entry.content is MainContent.Video
    val winW = w * rect.w.toFloat()
    val winH = h * rect.h.toFloat()
    // The video's own shape decides the crop or the bars; until it is known, it fills the window.
    val size = state.mainVideoSize
    val aspect = if (size != null && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else null
    val (cw, ch) = if (fit == null || aspect == null) winW to winH else fitted(aspect, rotation, winW, winH, fit)
    Box(
        Modifier
            .offset(w * rect.x.toFloat(), h * rect.y.toFloat())
            .size(winW, winH)
            .clipToBounds(),
    ) {
        if (fit == Fit.FIT_BLUR) {
            val video = entry.content as? MainContent.Video
            val source = (entry.content as? MainContent.Still)?.source ?: video?.let { Source.VideoFrame(it.media, it.clipStartUs) }
            if (source != null) SoftBackdrop(source, rotation, stills, resolver)
        }
        AndroidView(
            factory = { TextureView(it) },
            // On every update, not only when the view is made: the engine may have new players.
            update = engine::setMainSurface,
            onRelease = { if (engine.schedule != null) engine.releaseMainSurface(it) },
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(cw, ch)
                    .graphicsLayer {
                        alpha = if (isVideo) 1f else HIDDEN_ALPHA
                        rotationZ = rotation.degrees.toFloat()
                    },
        )
        val still = entry.content as? MainContent.Still
        if (still != null) Still(still.source, fit ?: Fit.FILL_CROP, rotation, stills, resolver, h)
    }
}

/** The picture size for a video of [aspect] (width over height) in a [winW] x [winH] window. */
private fun fitted(
    aspect: Float,
    rotation: Rotation,
    winW: Dp,
    winH: Dp,
    fit: Fit,
): Pair<Dp, Dp> {
    val turned = rotation == Rotation.R90 || rotation == Rotation.R270
    val shown = if (turned) 1f / aspect else aspect
    val windowAspect = winW / winH
    val byWidth = (shown >= windowAspect) == (fit != Fit.FILL_CROP)
    val (sw, sh) = if (byWidth) winW to winW / shown else winH * shown to winH
    // The view is laid out unrotated, then turned.
    return if (turned) sh to sw else sw to sh
}

@Composable
private fun Still(
    source: Source,
    fit: Fit,
    rotation: Rotation,
    stills: StillLoader,
    resolver: MediaResolver?,
    canvasHeight: Dp,
) {
    when (source) {
        is Source.Card -> {
            Box(Modifier.fillMaxSize().background(source.color.toColor()), contentAlignment = Alignment.Center) {
                source.text?.let { CaptionText(it, source.style, canvasHeight, Modifier.padding(8.dp)) }
            }
        }

        is Source.Photo, is Source.VideoFrame -> {
            val bitmap by produceState<Bitmap?>(null, source, resolver) { value = resolver?.let { stills.load(source, it) } }
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = if (fit == Fit.FILL_CROP) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.degrees.toFloat() },
                )
            }
        }

        else -> {
            Unit
        }
    }
}

/**
 * The fit-with-blur background in the preview: the picture shrunk to a few
 * dozen pixels and stretched back over the window, which blurs it on every
 * Android version, darkened as the export darkens it. For a video it is the
 * item's first frame, held (the export blurs every frame; the preview keeps
 * one decoder per row).
 */
@Composable
private fun SoftBackdrop(
    source: Source,
    rotation: Rotation,
    stills: StillLoader,
    resolver: MediaResolver?,
) {
    val bitmap by produceState<Bitmap?>(null, source, resolver, rotation) {
        value =
            resolver?.let { stills.load(source, it) }?.let { b ->
                // Turned first (so a rotated picture covers the window as the export's does), then
                // halved step by step: each step averages pixels, where one big step would skip most.
                var out = if (rotation == Rotation.R0) b else b.rotated(rotation.degrees)
                while (out.width > BACKDROP_PX * 2) out = out.scale(out.width / 2, (out.height / 2).coerceAtLeast(1))
                out.scale(BACKDROP_PX, (BACKDROP_PX * out.height / out.width.coerceAtLeast(1)).coerceAtLeast(1))
            }
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            colorFilter = ColorFilter.tint(Color(0xFFCCCCCC), BlendMode.Multiply),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap =
    Bitmap.createBitmap(this, 0, 0, width, height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true)

@OptIn(UnstableApi::class)
@Composable
private fun OverlayLayer(
    engine: LayeredPreviewEngine,
    index: Int,
    visible: Boolean,
) {
    AndroidView(
        // Not opaque: the keyed window shows the rows below through it.
        factory = { context -> TextureView(context).apply { isOpaque = false } },
        update = {
            engine.setOverlaySurface(index, it)
        },
        onRelease = { view -> if (engine.schedule != null) engine.releaseOverlaySurface(index, view) },
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (visible) 1f else HIDDEN_ALPHA },
    )
}

@Composable
private fun CaptionLayer(
    schedule: PreviewSchedule,
    layer: LayerRef.Captions,
    positionUs: Long,
    w: Dp,
    h: Dp,
) {
    for (c in schedule.captionsAt(positionUs).filter { it.trackId == layer.trackId }) {
        Box(
            Modifier
                .offset(w * c.rect.x.toFloat(), h * c.rect.y.toFloat())
                .size(w * c.rect.w.toFloat(), h * c.rect.h.toFloat()),
            contentAlignment = Alignment.Center,
        ) {
            CaptionText(c.caption.text, c.caption.style, h)
        }
    }
}

/** A caption as the export draws it, scaled from the 1080-line canvas to this preview. */
@Composable
private fun CaptionText(
    text: String,
    style: CaptionStyle,
    canvasHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val scale = canvasHeight.value / CANVAS_LINES
    val sizeSp = with(LocalDensity.current) { (style.sizePx * scale).dp.toSp() }
    val bg = style.background
    Text(
        text = text,
        color = style.color.toColor(),
        fontSize = sizeSp,
        lineHeight = sizeSp * 1.2f,
        textAlign =
            when (style.align) {
                CaptionAlign.START -> TextAlign.Start
                CaptionAlign.END -> TextAlign.End
                else -> TextAlign.Center
            },
        modifier =
            modifier
                .then(
                    if (bg != null) {
                        Modifier.background(bg.color.toColor(), RoundedCornerShape((bg.cornerPx * scale).dp))
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 4.dp),
    )
}

private fun ArgbColor.toColor(): Color = Color(argb.toInt())

private const val CANVAS_LINES = 1080f

/** The width the preview's blurred background is shrunk to before it is stretched back. */
private const val BACKDROP_PX = 24

/**
 * A hidden player surface is drawn almost transparent rather than not at all: a TextureView
 * takes in new pictures only when it is drawn, and one that is never drawn fills up and stalls
 * the player feeding it (the keyed frame then never shows again).
 */
private const val HIDDEN_ALPHA = 0.004f
