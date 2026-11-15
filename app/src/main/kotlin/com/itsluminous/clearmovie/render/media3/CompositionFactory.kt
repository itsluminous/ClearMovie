package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.render.plan.AudioSegment
import com.itsluminous.clearmovie.render.plan.Placement
import com.itsluminous.clearmovie.render.plan.RenderPlan
import com.itsluminous.clearmovie.render.plan.Source
import com.itsluminous.clearmovie.render.plan.VisualSegment
import kotlin.math.roundToInt

/**
 * What the factory reads from a media file before it uses it: its real
 * length, and whether it has a sound track. A sound item from a file
 * without one (a silent video, or a frame video with no music) fails the
 * whole export in Media3, so the factory plays silence for it instead.
 */
data class SourceInfo(
    val durationUs: Long,
    val hasAudio: Boolean,
)

/** Reads a [SourceInfo], or null when the file can't be read. */
fun interface SourceProbe {
    fun probe(uri: Uri): SourceInfo?
}

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
 * list reversed. Above them all goes one transparent full-length sequence:
 * the top sequence is the compositor's primary stream and sets the output
 * frame rate, so every export comes out at the plan's rate whatever the
 * sources are, and even a one-layer plan runs the same multi-input graph.
 * Audio-only sequences follow and are mixed. A sound from a file without a
 * sound track becomes silence of the same length, and a sound row with no
 * sound left is left out.
 *
 * Every layer must last exactly as long as the plan says, or the items after
 * a short one start early. So every video and sound item is checked against
 * the file's real length ([probe]), and a clip that asks for more than
 * the file has (a file replaced or edited since it was added) fails the
 * build instead of drifting.
 *
 * Every visual item ends in a [PlacementEffect], so every layer is a
 * canvas-sized frame that is transparent where it draws nothing.
 *
 * [build] reads files and draws bitmaps, so it must not run on the main
 * thread. It throws [MediaUnavailableException] when media is missing,
 * unreadable or shorter than planned, [IllegalArgumentException] for a plan
 * that cannot be rendered, and [IllegalStateException] when a render image
 * can't be stored. Exception messages are for logs only, never for the user.
 */
@OptIn(ExperimentalApi::class)
@UnstableApi
class CompositionFactory(
    private val context: Context,
    private val resolver: MediaResolver,
    private val probe: SourceProbe = RetrieverProbe(context),
) {
    private val assets = RenderAssets(context)
    private val guard = UriGuard(context)

    fun build(plan: RenderPlan): Composition {
        check(!Looper.getMainLooper().isCurrentThread) { "Build the composition off the main thread" }
        require(plan.durationUs > 0) { "Nothing to render" }
        require(plan.visual.isNotEmpty()) { "Nothing to draw" }
        val build = Build(plan)
        val sequences = mutableListOf<EditedMediaItemSequence>()
        val top = build.visualItem(VisualSegment.Fill(0, plan.durationUs))
        sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(top).build()
        for (layer in plan.visual.asReversed()) {
            val items = layer.segments.map { build.visualItem(it) }
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItems(items).build()
        }
        for (layer in plan.audio) {
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var sounds = 0
            for (segment in layer.segments) {
                val item = (segment as? AudioSegment.Sound)?.let { build.soundItem(it) }
                if (item != null) {
                    builder.addItem(item)
                    sounds++
                } else {
                    builder.addGap(segment.durationUs)
                }
            }
            if (sounds > 0) sequences += builder.build()
        }
        assets.trim(keep = build.images)
        return Composition
            .Builder(sequences)
            .setVideoCompositorSettings(CanvasCompositorSettings(plan.width, plan.height))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .setEffects(Effects(listOf(outputRate()), listOf()))
            .build()
    }

    /** The mix is resampled to the export's 48 kHz (section 7, Export). */
    private fun outputRate(): SonicAudioProcessor = SonicAudioProcessor().apply { setOutputSampleRateHz(OUTPUT_SAMPLE_RATE) }

    /** State for one [build]: resolved uris, probed files, and the images used. */
    private inner class Build(
        private val plan: RenderPlan,
    ) {
        private val captions = CaptionBitmapRenderer(plan.height)
        private val uris = mutableMapOf<MediaRef, Uri>()
        private val infos = mutableMapOf<Uri, SourceInfo?>()
        val images = mutableSetOf<Uri>()

        private fun uri(ref: MediaRef): Uri = uris.getOrPut(ref) { guard.check(resolver.resolve(ref)) }

        /** [ref]'s uri and what its file holds, after checking it holds [endUs] of media. */
        private fun timedSource(
            ref: MediaRef,
            endUs: Long,
        ): Pair<Uri, SourceInfo> {
            val u = uri(ref)
            val info = infos.getOrPut(u) { probe.probe(u) } ?: throw MediaUnavailableException("Could not read the media's length")
            if (endUs > info.durationUs + LENGTH_TOLERANCE_US) throw MediaUnavailableException("A clip is longer than its file")
            return u to info
        }

        /** [ref]'s uri, after checking the file holds [endUs] of media. */
        private fun timedUri(
            ref: MediaRef,
            endUs: Long,
        ): Uri = timedSource(ref, endUs).first

        private fun image(
            png: Uri,
            durationUs: Long,
            effects: List<Effect>,
            mime: String,
        ): EditedMediaItem {
            if (png.scheme == "file") images += png
            val item =
                MediaItem
                    .Builder()
                    .setUri(png)
                    // A hint so Media3 uses its image loader; BitmapFactory reads the real format.
                    .setMimeType(mime)
                    .setImageDurationMs(((durationUs + 999) / 1000).coerceAtLeast(1))
                    .build()
            return EditedMediaItem
                .Builder(item)
                .setDurationUs(durationUs)
                .setFrameRate(plan.fps)
                .setEffects(Effects(listOf(), effects))
                .build()
        }

        fun visualItem(segment: VisualSegment): EditedMediaItem =
            when (segment) {
                is VisualSegment.Fill -> {
                    val effects = listOf<Effect>(PlacementEffect(Placement.FullFrame, plan.width, plan.height, fromBitmap = true))
                    image(assets.transparent(), segment.durationUs, effects, MimeTypes.IMAGE_PNG)
                }

                is VisualSegment.Picture -> {
                    picture(segment)
                }
            }

        private fun picture(segment: VisualSegment.Picture): EditedMediaItem {
            val source = segment.source
            val effects = mutableListOf<Effect>()
            segment.key?.let { effects += ChromaKeyEffect(it) }
            effects += PlacementEffect(segment.placement, plan.width, plan.height, fromBitmap = source !is Source.Video)
            if (segment.alpha.isNotEmpty()) effects += TransitionEffect(segment.alpha, segment.startUs, segment.clipOffsetUs)
            return when (source) {
                is Source.Video -> {
                    val item =
                        MediaItem
                            .Builder()
                            .setUri(timedUri(source.media, source.clipEndUs))
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
                    image(uri(source.media), segment.durationUs, effects, MimeTypes.IMAGE_JPEG)
                }

                is Source.VideoFrame -> {
                    val frame = assets.videoFrame(uri(source.media), source.atUs, plan.width, plan.height)
                    image(frame, segment.durationUs, effects, MimeTypes.IMAGE_PNG)
                }

                is Source.Caption -> {
                    val (w, h) = boxPixels(segment.placement)
                    val png =
                        assets.png("caption|${plan.height}|${source.text}|${source.style}|${w}x$h") {
                            captions.caption(source.text, source.style, w, h)
                        }
                    image(png, segment.durationUs, effects, MimeTypes.IMAGE_PNG)
                }

                is Source.Card -> {
                    val (w, h) = boxPixels(segment.placement)
                    val png =
                        assets.png("card|${plan.height}|${source.color}|${source.text}|${source.style}|${w}x$h") {
                            captions.card(source.color, source.text, source.style, w, h)
                        }
                    image(png, segment.durationUs, effects, MimeTypes.IMAGE_PNG)
                }
            }
        }

        /** A box's size in output pixels, never larger than the canvas. */
        private fun boxPixels(placement: Placement): Pair<Int, Int> {
            val rect =
                when (placement) {
                    is Placement.Box -> placement.rect
                    is Placement.Window -> placement.rect
                    is Placement.Pip -> placement.rect
                    Placement.FullFrame -> NormRect.FULL
                }
            require(rect.isValid) { "A box is outside the picture" }
            val w = (rect.w * plan.width).roundToInt().coerceIn(1, plan.width)
            val h = (rect.h * plan.height).roundToInt().coerceIn(1, plan.height)
            return w to h
        }

        /** [segment] as an item, or null when its file has no sound track (it plays as silence). */
        fun soundItem(segment: AudioSegment.Sound): EditedMediaItem? {
            val (u, info) = timedSource(segment.media, segment.clipEndUs)
            if (!info.hasAudio) return null
            val item =
                MediaItem
                    .Builder()
                    .setUri(u)
                    .setClippingConfiguration(clipping(segment.clipStartUs, segment.clipEndUs, segment.speed, segment.durationUs))
                    .build()
            return EditedMediaItem
                .Builder(item)
                .setDurationUs(segment.sourceDurationUs)
                .setEffects(Effects(listOf(GainEnvelopeAudioProcessor(segment.gain)), listOf()))
                .apply { speedParameters(segment.speed)?.let { setSpeed(it) } }
                .build()
        }
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

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 48_000

        /** About one frame: container lengths are rounded, and AAC adds padding. */
        const val LENGTH_TOLERANCE_US = 34_000L
    }
}

/** Reads a file's length and tracks with one [MediaMetadataRetriever] pass. */
internal class RetrieverProbe(
    private val context: Context,
) : SourceProbe {
    override fun probe(uri: Uri): SourceInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val durationUs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000) ?: return null
            // The key is present ("yes") only when the file has a sound track.
            SourceInfo(durationUs, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } catch (e: RuntimeException) {
            null
        } finally {
            r.release()
        }
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
