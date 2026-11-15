package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.render.plan.AudioSegment
import com.itsluminous.clearmovie.render.plan.Placement
import com.itsluminous.clearmovie.render.plan.RenderPlan
import com.itsluminous.clearmovie.render.plan.Source
import com.itsluminous.clearmovie.render.plan.VisualSegment
import kotlin.math.roundToInt

/**
 * Maps a [RenderPlan] to one Media3 [Composition] (ARCHITECTURE section 7)
 * for `Transformer`: the export, and the short exact draft behind "Play
 * exactly as exported" (a [RenderPlan.slice] at a low height).
 *
 * The live editor preview does NOT use this composition: the cm-render spike
 * found that Media3's multi-input `CompositionPlayer` (1.11.1 and
 * 1.12.0-beta01) shows no frame after a seek and freezes at a video-to-photo
 * boundary in any lower layer, so the editor uses the layered preview
 * (risk 2 fallback, chosen by the owner).
 *
 * Layer order: Media3's compositor draws the FIRST sequence on top and each
 * later one under it (DefaultVideoCompositor, verified by the render spike),
 * so the visual layers are emitted top first, i.e. the plan's bottom-first
 * list reversed. The top sequence is the compositor's primary stream and sets
 * the output timestamps; the plan makes every layer exactly as long as the
 * video, so whichever row is on top can lead. Audio-only sequences follow the
 * visual ones and are mixed.
 *
 * Every visual item ends in a [PlacementEffect], so every layer is a
 * canvas-sized frame that is transparent where it draws nothing.
 *
 * @throws MediaUnavailableException from [build] when a clip's media is gone.
 */
@OptIn(ExperimentalApi::class)
@UnstableApi
class CompositionFactory(
    private val context: Context,
    private val resolver: MediaResolver,
) {
    private val assets = RenderAssets(context)
    private val guard = UriGuard(context)

    fun build(plan: RenderPlan): Composition {
        require(plan.durationUs > 0) { "Nothing to render" }
        require(plan.visual.isNotEmpty()) { "Nothing to draw" }
        val captions = CaptionBitmapRenderer(plan.height)
        val sequences = mutableListOf<EditedMediaItemSequence>()
        for (layer in plan.visual.asReversed()) {
            val items = layer.segments.map { visualItem(plan, it, captions) }
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItems(items).build()
        }
        // Always at least two visual sequences, so every export runs the same
        // multi-input graph and compositor (Transformer refuses compositor
        // settings for a single sequence): a lone layer gets a transparent one
        // under it.
        if (plan.visual.size == 1) {
            val fill = visualItem(plan, VisualSegment.Fill(0, plan.durationUs), captions)
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(fill).build()
        }
        for (layer in plan.audio) {
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            for (segment in layer.segments) {
                when (segment) {
                    is AudioSegment.Silence -> builder.addGap(segment.durationUs)
                    is AudioSegment.Sound -> builder.addItem(soundItem(segment))
                }
            }
            sequences += builder.build()
        }
        return Composition
            .Builder(sequences)
            .setVideoCompositorSettings(CanvasCompositorSettings(plan.width, plan.height))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .setEffects(Effects(listOf(outputRate()), listOf()))
            .build()
    }

    /** The mix is resampled to the export's 48 kHz (section 7, Export). */
    private fun outputRate(): SonicAudioProcessor = SonicAudioProcessor().apply { setOutputSampleRateHz(OUTPUT_SAMPLE_RATE) }

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 48_000
    }

    private fun uri(ref: com.itsluminous.clearmovie.domain.model.MediaRef): Uri = guard.check(resolver.resolve(ref))

    private fun visualItem(
        plan: RenderPlan,
        segment: VisualSegment,
        captions: CaptionBitmapRenderer,
    ): EditedMediaItem =
        when (segment) {
            is VisualSegment.Fill -> {
                image(
                    assets.transparent(),
                    segment.durationUs,
                    plan.fps,
                    listOf(PlacementEffect(Placement.FullFrame, plan.width, plan.height)),
                )
            }

            is VisualSegment.Picture -> {
                picture(plan, segment, captions)
            }
        }

    private fun picture(
        plan: RenderPlan,
        segment: VisualSegment.Picture,
        captions: CaptionBitmapRenderer,
    ): EditedMediaItem {
        val effects = mutableListOf<Effect>()
        segment.key?.let { effects += ChromaKeyEffect(it) }
        effects += PlacementEffect(segment.placement, plan.width, plan.height)
        if (segment.alpha.isNotEmpty()) effects += TransitionEffect(segment.alpha, segment.startUs, segment.clipOffsetUs)
        return when (val source = segment.source) {
            is Source.Video -> {
                val item =
                    MediaItem
                        .Builder()
                        .setUri(uri(source.media))
                        .setClippingConfiguration(clipping(source.clipStartUs, source.clipEndUs, source.speed, segment.durationUs))
                        .build()
                EditedMediaItem
                    .Builder(item)
                    .setDurationUs(source.sourceDurationUs)
                    .setFrameRate(plan.fps)
                    .setEffects(Effects(listOf(), effects))
                    .apply { speedParameters(source.speed)?.let { setSpeed(it) } }
                    .build()
            }

            is Source.Photo -> {
                image(uri(source.media), segment.durationUs, plan.fps, effects)
            }

            is Source.VideoFrame -> {
                image(
                    assets.videoFrame(uri(source.media), source.atUs, maxOf(plan.width, plan.height)),
                    segment.durationUs,
                    plan.fps,
                    effects,
                )
            }

            is Source.Caption -> {
                val (w, h) = boxPixels(segment.placement, plan)
                val png = assets.png("caption|${source.text}|${source.style}|${w}x$h") { captions.caption(source.text, source.style, w, h) }
                image(png, segment.durationUs, plan.fps, effects)
            }

            is Source.Card -> {
                val (w, h) = boxPixels(segment.placement, plan)
                val png =
                    assets.png("card|${source.color}|${source.text}|${source.style}|${w}x$h") {
                        captions.card(source.color, source.text, source.style, w, h)
                    }
                image(png, segment.durationUs, plan.fps, effects)
            }
        }
    }

    private fun boxPixels(
        placement: Placement,
        plan: RenderPlan,
    ): Pair<Int, Int> {
        val rect =
            when (placement) {
                is Placement.Box -> placement.rect
                is Placement.Window -> placement.rect
                is Placement.Pip -> placement.rect
                Placement.FullFrame -> com.itsluminous.clearmovie.domain.model.NormRect.FULL
            }
        return (rect.w * plan.width).roundToInt().coerceAtLeast(1) to (rect.h * plan.height).roundToInt().coerceAtLeast(1)
    }

    private fun image(
        uri: Uri,
        durationUs: Long,
        fps: Int,
        effects: List<Effect>,
    ): EditedMediaItem {
        val item =
            MediaItem
                .Builder()
                .setUri(uri)
                .setImageDurationMs(((durationUs + 999) / 1000).coerceAtLeast(1))
                .build()
        return EditedMediaItem
            .Builder(item)
            .setDurationUs(durationUs)
            .setFrameRate(fps)
            .setEffects(Effects(listOf(), effects))
            .build()
    }

    private fun soundItem(segment: AudioSegment.Sound): EditedMediaItem {
        val item =
            MediaItem
                .Builder()
                .setUri(uri(segment.media))
                .setClippingConfiguration(clipping(segment.clipStartUs, segment.clipEndUs, segment.speed, segment.durationUs))
                .build()
        return EditedMediaItem
            .Builder(item)
            .setDurationUs(segment.sourceDurationUs)
            .setEffects(Effects(listOf(GainEnvelopeAudioProcessor(segment.gain)), listOf()))
            .apply { speedParameters(segment.speed)?.let { setSpeed(it) } }
            .build()
    }

    /**
     * Plays from [startUs] for exactly [lengthUs] of timeline at [speed], and
     * never past [endUs]: an item is never longer than its segment, so every
     * sequence stays exactly as long as the plan says.
     */
    private fun clipping(
        startUs: Long,
        endUs: Long,
        speed: Speed,
        lengthUs: Long,
    ): MediaItem.ClippingConfiguration =
        MediaItem.ClippingConfiguration
            .Builder()
            .setStartPositionUs(startUs)
            .setEndPositionUs(minOf(endUs, startUs + lengthUs * speed.numerator / speed.denominator))
            .build()

    private fun speedParameters(speed: Speed): SpeedParameters? {
        if (speed == Speed.X1) return null
        val value = speed.numerator.toFloat() / speed.denominator
        val provider =
            object : SpeedProvider {
                override fun getSpeed(timeUs: Long) = value

                override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
            }
        // Speech keeps its pitch (section 6.3).
        return SpeedParameters(provider, true)
    }
}

/** Every layer is drawn full size on a fixed canvas. */
@UnstableApi
internal class CanvasCompositorSettings(
    private val width: Int,
    private val height: Int,
) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

    override fun getOverlaySettings(
        inputId: Int,
        presentationTimeUs: Long,
    ): OverlaySettings = DEFAULT_OVERLAY

    private companion object {
        val DEFAULT_OVERLAY = object : OverlaySettings {}
    }
}
