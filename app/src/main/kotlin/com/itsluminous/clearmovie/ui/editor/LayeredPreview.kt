package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.graphics.scale
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Rotation
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.Mask
import com.itsluminous.clearmovie.render.plan.MotionFrame
import com.itsluminous.clearmovie.render.plan.MotionMath
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
 * fitted by the item's fit), or a still the preview draws itself; during a
 * transition the outgoing item's tail is drawn with it, under or over it as
 * the export's lanes are; each frame row is its keyed player over it;
 * captions are text in their boxes. Transitions and animations are drawn
 * from the plan's [com.itsluminous.clearmovie.render.plan.PictureMotion] with
 * the export's own curves ([MotionMath]), every frame while playing.
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
    // The players report their time ten times a second; while playing, the motion follows the
    // MAIN player's own clock on every frame. The clock is read only in the draw phase (graphics
    // layers and draw lambdas), so a frame redraws the moving layers without recomposing the
    // preview: recomposing every frame kept a slow phone's main thread busy enough to stop
    // answering touches.
    val clock = remember { mutableLongStateOf(state.positionUs) }
    LaunchedEffect(state.playing, state.loads, state.positionUs.takeUnless { state.playing }) {
        clock.longValue = state.positionUs
        if (!state.playing) return@LaunchedEffect
        while (true) {
            withInfiniteAnimationFrameNanos { clock.longValue = engine.positionUs }
        }
    }
    val now: () -> Long = remember(clock) { { clock.longValue } }
    BoxWithConstraints(modifier.clipToBounds().background(media.previewBackground).testTag(PREVIEW_TAG)) {
        val w = maxWidth
        val h = maxHeight
        val aspect = if (h.value > 0f) (w / h).toDouble() else CANVAS_ASPECT
        for (layer in schedule.layerOrder) {
            key(layer) {
                when (layer) {
                    LayerRef.Main -> {
                        MainLayer(engine, schedule, state, now, stills, resolver, w, h, aspect)
                    }

                    is LayerRef.Overlay -> {
                        // A new surface for each load: see PreviewState.loads.
                        key(state.loads) {
                            OverlayLayer(engine, layer.index, state.overlayVisible.getOrElse(layer.index) { false })
                        }
                    }

                    is LayerRef.Captions -> {
                        CaptionLayer(schedule, layer, state.positionUs, now, w, h, aspect)
                    }
                }
            }
        }
    }
}

/**
 * What one MAIN picture shows: the player's video on a surface, or a still.
 * [motionAt] gives its motion at a time of the video (read while drawing);
 * [showsAt] whether it shows then at all.
 */
private class Picture(
    val content: MainContent,
    val placement: Placement,
    val videoSize: VideoSize?,
    val motionAt: (Long) -> MotionFrame,
    val showsAt: (Long) -> Boolean,
)

@OptIn(UnstableApi::class)
@Composable
private fun MainLayer(
    engine: LayeredPreviewEngine,
    schedule: PreviewSchedule,
    state: PreviewState,
    now: () -> Long,
    stills: StillLoader,
    resolver: MediaResolver?,
    w: Dp,
    h: Dp,
    aspect: Double,
) {
    val entry = schedule.main.getOrNull(state.mainIndex) ?: schedule.main.firstOrNull() ?: return
    // The tail playing now, else the next one (composed a little early, so it is ready when its overlap starts).
    val positionUs = state.positionUs
    val tail = schedule.tailAt(positionUs) ?: schedule.tail.firstOrNull { it.startUs >= positionUs } ?: schedule.tail.lastOrNull()
    Box(Modifier.fillMaxSize()) {
        // The tail's player surface is always there while the video has one (a surface that is never
        // drawn stalls its player), shown only while a tail plays.
        if (tail != null) {
            Box(Modifier.fillMaxSize().zIndex(if (tail.upper) 2f else 0f)) {
                PlacedPicture(
                    Picture(
                        tail.content,
                        tail.placement,
                        state.tailVideoSize,
                        motionAt = { t -> tail.motion?.let { MotionMath.frameAt(it, tail.clipUsAt(t), aspect) } ?: MotionFrame.STILL },
                        showsAt = { t -> t >= tail.startUs && t < tail.endUs },
                    ),
                    stills = stills,
                    resolver = resolver,
                    w = w,
                    h = h,
                    now = now,
                    surface =
                        if (schedule.tailLane != null) {
                            { modifier -> TailSurface(engine, modifier) }
                        } else {
                            null
                        },
                )
            }
        }
        Box(Modifier.fillMaxSize().zIndex(1f)) {
            PlacedPicture(
                Picture(
                    entry.content,
                    entry.placement,
                    state.mainVideoSize,
                    motionAt = { t -> entry.motion?.let { MotionMath.frameAt(it, entry.clipUsAt(t), aspect) } ?: MotionFrame.STILL },
                    showsAt = { true },
                ),
                stills = stills,
                resolver = resolver,
                w = w,
                h = h,
                now = now,
                surface = { modifier -> MainSurface(engine, modifier) },
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MainSurface(
    engine: LayeredPreviewEngine,
    modifier: Modifier,
) {
    AndroidView(
        factory = { TextureView(it) },
        // On every update, not only when the view is made: the engine may have new players.
        update = engine::setMainSurface,
        onRelease = { if (engine.schedule != null) engine.releaseMainSurface(it) },
        modifier = modifier,
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun TailSurface(
    engine: LayeredPreviewEngine,
    modifier: Modifier,
) {
    AndroidView(
        factory = { TextureView(it) },
        update = engine::setTailSurface,
        onRelease = { if (engine.schedule != null) engine.releaseTailSurface(it) },
        modifier = modifier,
    )
}

/**
 * One MAIN picture where its placement puts it, moved, faded, tinted and cut
 * as [Picture.motionAt] says for [now]: the same order as the export's shader
 * (the picture moves inside its window, then the window clips it, then the
 * mask cuts the canvas). Everything that changes with time is read in the
 * draw phase.
 */
@Composable
private fun PlacedPicture(
    picture: Picture,
    stills: StillLoader,
    resolver: MediaResolver?,
    w: Dp,
    h: Dp,
    now: () -> Long,
    surface: (@Composable (Modifier) -> Unit)?,
) {
    val placement = picture.placement
    val rect = (placement as? Placement.Window)?.rect ?: NormRect.FULL
    val fit = (placement as? Placement.Window)?.fit
    val rotation = (placement as? Placement.Window)?.rotation ?: Rotation.R0
    val isVideo = picture.content is MainContent.Video
    val winW = w * rect.w.toFloat()
    val winH = h * rect.h.toFloat()
    // The video's own shape decides the crop or the bars; until it is known, it fills the window.
    val vs = picture.videoSize
    val videoAspect = if (vs != null && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else null
    val (cw, ch) = if (fit == null || videoAspect == null) winW to winH else fitted(videoAspect, rotation, winW, winH, fit)
    Box(
        Modifier.fillMaxSize().drawWithContent {
            val mask = picture.motionAt(now()).mask
            if (mask ==
                Mask.None
            ) {
                drawContent()
            } else {
                clipPath(maskPath(mask, size.width, size.height)) { this@drawWithContent.drawContent() }
            }
        },
    ) {
        Box(
            Modifier
                .offset(w * rect.x.toFloat(), h * rect.y.toFloat())
                .size(winW, winH)
                .clipToBounds(),
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val t = now()
                    val f = picture.motionAt(t)
                    scaleX = f.scale
                    scaleY = f.scale
                    translationX = (w * f.dx).toPx()
                    translationY = (h * f.dy).toPx()
                    alpha = if (picture.showsAt(t)) f.alpha else 0f
                },
            ) {
                if (fit == Fit.FIT_BLUR) {
                    val video = picture.content as? MainContent.Video
                    val source =
                        (picture.content as? MainContent.Still)?.source ?: video?.let { Source.VideoFrame(it.media, it.clipStartUs) }
                    if (source != null) SoftBackdrop(source, rotation, stills, resolver)
                }
                surface?.invoke(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(cw, ch)
                        .graphicsLayer {
                            // A hidden surface is still drawn, faintly: see HIDDEN_ALPHA.
                            alpha = if (isVideo && picture.showsAt(now())) 1f else HIDDEN_ALPHA
                            rotationZ = rotation.degrees.toFloat()
                        },
                )
                val still = picture.content as? MainContent.Still
                if (still != null) Still(still.source, fit ?: Fit.FILL_CROP, rotation, stills, resolver, h)
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val f = picture.motionAt(now())
                        if (f.tintAmount > 0f) drawRect(f.tint.toColor(), alpha = f.tintAmount)
                    },
                )
            }
        }
    }
}

/** [mask] as a path over a canvas [width] x [height] pixels. */
internal fun maskPath(
    mask: Mask,
    width: Float,
    height: Float,
): Path =
    Path().apply {
        when (mask) {
            Mask.None -> {
                addRect(Rect(0f, 0f, width, height))
            }

            is Mask.Wipe -> {
                val edge = if (mask.vertical) mask.edge * height else mask.edge * width
                val r =
                    when {
                        !mask.vertical && mask.keepAfter -> Rect(edge, 0f, width, height)
                        !mask.vertical -> Rect(0f, 0f, edge, height)
                        mask.keepAfter -> Rect(0f, edge, width, height)
                        else -> Rect(0f, 0f, width, edge)
                    }
                addRect(r)
            }

            is Mask.Circle -> {
                val radius = mask.radius * height
                val circle = Rect(Offset(mask.cx * width, mask.cy * height), radius)
                if (mask.inside) {
                    addOval(circle)
                } else {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, width, height))
                    addOval(circle)
                }
            }
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
    now: () -> Long,
    w: Dp,
    h: Dp,
    aspect: Double,
) {
    for (c in schedule.captionsAt(positionUs).filter { it.trackId == layer.trackId }) {
        Box(
            Modifier
                .offset(w * c.rect.x.toFloat(), h * c.rect.y.toFloat())
                .size(w * c.rect.w.toFloat(), h * c.rect.h.toFloat())
                .graphicsLayer {
                    val f = c.motion?.let { MotionMath.frameAt(it, c.clipUsAt(now()), aspect) } ?: MotionFrame.STILL
                    scaleX = f.scale
                    scaleY = f.scale
                    translationX = (w * f.dx).toPx()
                    translationY = (h * f.dy).toPx()
                    alpha = f.alpha
                },
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
private const val CANVAS_ASPECT = 16.0 / 9.0

/** The width the preview's blurred background is shrunk to before it is stretched back. */
private const val BACKDROP_PX = 24

/**
 * A hidden player surface is drawn almost transparent rather than not at all: a TextureView
 * takes in new pictures only when it is drawn, and one that is never drawn fills up and stalls
 * the player feeding it (the keyed frame then never shows again).
 */
private const val HIDDEN_ALPHA = 0.004f
