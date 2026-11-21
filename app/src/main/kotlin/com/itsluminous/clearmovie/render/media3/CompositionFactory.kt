package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray
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
    /** The picture is BT.709: tagged so (container, else stream), or untagged and sized so by Android's rule. */
    val bt709: Boolean = true,
)

/** Reads a [SourceInfo], or null when the file can't be read. */
fun interface SourceProbe {
    fun probe(uri: Uri): SourceInfo?
}

/**
 * Maps a [RenderPlan] to one Media3 [Composition] (ARCHITECTURE section 7)
 * for `Transformer`: the export (and, in render tests, a [RenderPlan.slice]).
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
 * On a GPU that decodes BT.709 video with the BT.601 matrix
 * ([DeviceColour.gpuDecodesBt709AsBt601], measured by [ColourCalibration]),
 * every BT.709 video item starts with [Bt709Correction], so keying and
 * everything after it sees the colours the file holds.
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
    private val deviceColour: () -> DeviceColour = { ColourCalibration.of(context) },
) {
    private val assets = RenderAssets(context)
    private val guard = UriGuard(context)

    fun build(plan: RenderPlan): Composition {
        check(!Looper.getMainLooper().isCurrentThread) { "Build the composition off the main thread" }
        require(plan.durationUs > 0) { "Nothing to render" }
        require(plan.visual.isNotEmpty()) { "Nothing to draw" }
        val build = Build(plan, deviceColour())
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
        private val colour: DeviceColour,
    ) {
        private val captions = CaptionBitmapRenderer(plan.shortSide, CaptionTypefaces.get(context), plan.shape)
        private val uris = mutableMapOf<MediaRef, Uri>()
        private val infos = mutableMapOf<Uri, SourceInfo>()
        val images = mutableSetOf<Uri>()

        private fun uri(ref: MediaRef): Uri = uris.getOrPut(ref) { guard.check(resolver.resolve(ref)) }

        /** [ref]'s uri and what its file holds, after checking it holds [endUs] of media. */
        private fun timedSource(
            ref: MediaRef,
            endUs: Long,
        ): Pair<Uri, SourceInfo> {
            val u = uri(ref)
            val info = infos.getOrPut(u) { probe.probe(u) ?: throw MediaUnavailableException("Could not read the media's length") }
            if (endUs > info.durationUs + LENGTH_TOLERANCE_US) throw MediaUnavailableException("A clip is longer than its file")
            return u to info
        }

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
            val video = (source as? Source.Video)?.let { timedSource(it.media, it.clipEndUs) }
            // First, so every later effect sees the file's colours.
            if (video != null && video.second.bt709 && colour.gpuDecodesBt709AsBt601) effects += Bt709Correction
            segment.key?.let { effects += ChromaKeyEffect(it) }
            effects += PlacementEffect(segment.placement, plan.width, plan.height, fromBitmap = source !is Source.Video)
            segment.motion?.let { effects += MotionEffect(it, segment.startUs, segment.clipOffsetUs, plan.width, plan.height) }
            return when (source) {
                is Source.Video -> {
                    val item =
                        MediaItem
                            .Builder()
                            .setUri(checkNotNull(video).first)
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
                    val font = captions.fontKey(source.style.font)
                    val key =
                        "caption|v${CaptionBitmapRenderer.VERSION}|$font|${plan.width}x${plan.height}|${source.text}|${source.style}|${w}x$h"
                    val png =
                        assets.png(key) {
                            captions.caption(source.text, source.style, w, h)
                        }
                    image(png, segment.durationUs, effects, MimeTypes.IMAGE_PNG)
                }

                is Source.Card -> {
                    val (w, h) = boxPixels(segment.placement)
                    val font = captions.fontKey(source.style.font)
                    val key =
                        "card|v${CaptionBitmapRenderer.VERSION}|$font|${plan.width}x${plan.height}|${source.color}|${source.text}|${source.style}|${w}x$h"
                    val png =
                        assets.png(key) {
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

/** Reads a file's length and tracks with [MediaMetadataRetriever], and a video's colour standard with [MediaExtractor]. */
internal class RetrieverProbe(
    private val context: Context,
) : SourceProbe {
    override fun probe(uri: Uri): SourceInfo? {
        val r = MediaMetadataRetriever()
        val info =
            try {
                r.setDataSource(context, uri)
                val durationUs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000) ?: return null
                // The keys are present ("yes") only when the file has that track.
                val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                SourceInfo(durationUs, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") to hasVideo
            } catch (e: RuntimeException) {
                return null
            } finally {
                // release() declares IOException on newer APIs; nothing to do about it here.
                runCatching { r.release() }
            }
        return if (info.second) info.first.copy(bt709 = isBt709(uri)) else info.first
    }

    /**
     * From an H.264 stream's own tag (SPS VUI), else the container's colour
     * keys, else by size, as the decoder decides ([classify]).
     */
    private fun isBt709(uri: Uri): Boolean {
        val e = MediaExtractor()
        return try {
            e.setDataSource(context, uri, null)
            val format =
                (0 until e.trackCount).map { e.getTrackFormat(it) }.firstOrNull {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") ==
                        true
                }
                    ?: return true

            fun int(key: String) = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
            val vui =
                if (format.getString(MediaFormat.KEY_MIME) == MimeTypes.VIDEO_H264) {
                    avcVui(
                        runCatching { format.getByteBuffer("csd-0") }.getOrNull()?.takeIf { it.remaining() <= MAX_CSD_BYTES }?.let { b ->
                            ByteArray(b.remaining()).also { b.duplicate().get(it) }
                        },
                    )
                } else {
                    Vui.NONE
                }
            classify(
                Vui(standardOf(int(MediaFormat.KEY_COLOR_STANDARD)), int(MediaFormat.KEY_COLOR_TRANSFER)),
                vui,
                int(MediaFormat.KEY_WIDTH),
                int(MediaFormat.KEY_HEIGHT),
            )
        } catch (ex: RuntimeException) {
            true
        } catch (ex: java.io.IOException) {
            true
        } finally {
            runCatching { e.release() }
        }
    }

    companion object {
        /**
         * A colour standard and transfer as [MediaFormat] constants, null when
         * not stated. For a stream, [standard] follows its matrix (what the
         * decoder applies) and [configured] its primaries (what Media3
         * configures the decoder with, the decoder's default when the matrix is
         * unstated).
         */
        data class Vui(
            val standard: Int?,
            val transfer: Int?,
            val configured: Int? = null,
        ) {
            companion object {
                val NONE = Vui(null, null)
            }
        }

        /**
         * The public standard (BT.709, BT.601 or BT.2020) Media3 configures the
         * decoder with for a container's `KEY_COLOR_STANDARD`, null for none.
         * Media3 reads a `colr` box's primaries only, for every codec, so the
         * code's primaries decide. Android reports its own codes there too
         * (ColorUtils): the non-public 3, 5, 7, 8, 9, 10, the extended
         * 64 + primaries + 7 * matrix (its enum order), and vendor codes from
         * 0x10000 (P3 and other primaries, which Media3 does not configure).
         */
        fun standardOf(standard: Int?): Int? {
            val bt709 = MediaFormat.COLOR_STANDARD_BT709
            val bt601 = MediaFormat.COLOR_STANDARD_BT601_NTSC
            val bt2020 = MediaFormat.COLOR_STANDARD_BT2020
            if (standard == null) return null
            if (standard in EXTENDED_STANDARD until EXTENDED_STANDARD + 7 * 7) {
                // Primaries: unstated, BT.709, BT.470M, BT.601 625 and 525, film, BT.2020.
                return when ((standard - EXTENDED_STANDARD) % 7) {
                    1 -> bt709
                    2, 3, 4 -> bt601
                    6 -> bt2020
                    else -> null
                }
            }
            return when (standard) {
                bt709, bt2020 -> standard

                // BT.601 625 and 525, the latter two with other matrices (Android's 3 and 5), and BT.470M (8).
                MediaFormat.COLOR_STANDARD_BT601_PAL, bt601, 3, 5, 8 -> bt601

                // BT.2020 constant luminance.
                7 -> bt2020

                // Film (9) and Display P3 (10) primaries, vendor codes and the rest: Media3 configures none.
                else -> null
            }
        }

        private const val EXTENDED_STANDARD = 64

        /** Larger than any real SPS and PPS; a bigger `csd-0` is not read. */
        private const val MAX_CSD_BYTES = 64 * 1024

        /**
         * The stream's own tag (SPS VUI matrix, else its primaries as Media3
         * configures them), else [container]'s (its colour keys,
         * the standard already through [standardOf]), each of standard and transfer on its
         * own, else by size. This is the decoder's order: Codec2 hands the
         * configured (container) aspects to the decoder as defaults, the
         * decoder keeps the coded ones where the stream states them, and the
         * size default fills what is left. A format with no size is taken as
         * 1280x720, as Codec2 does.
         */
        fun classify(
            container: Vui,
            stream: Vui,
            width: Int?,
            height: Int?,
        ): Boolean =
            videoIsBt709(
                stream.standard ?: stream.configured ?: container.standard,
                width ?: 1280,
                height ?: 720,
                stream.transfer ?: container.transfer,
            )

        /**
         * The colour an H.264 `csd-0` (Annex B, holding the SPS) declares in
         * its VUI. The standard follows the matrix coefficients, which decide
         * how the decoder turns YUV into RGB (Media3's own SPS parser reads only
         * the primaries), and [Vui.configured] the primaries Media3 configures
         * the decoder with; [classify] falls back from one to the other, then
         * to the container and the size rule.
         */
        @OptIn(UnstableApi::class)
        fun avcVui(csd0: ByteArray?): Vui {
            if (csd0 == null) return Vui.NONE
            val found = BooleanArray(3)
            var start = NalUnitUtil.findNalUnit(csd0, 0, csd0.size, found)
            while (start + 3 < csd0.size) {
                val header = start + 3
                val next = NalUnitUtil.findNalUnit(csd0, header, csd0.size, found)
                if (csd0[header].toInt() and 0x1F == NalUnitUtil.H264_NAL_UNIT_TYPE_SPS) {
                    // A truncated or malformed SPS reads past its end: no tag.
                    return try {
                        spsColour(ParsableNalUnitBitArray(csd0, header + 1, next))
                    } catch (e: RuntimeException) {
                        Vui.NONE
                    }
                }
                start = next
            }
            return Vui.NONE
        }

        /** Profiles whose SPS carries chroma format, bit depths and scaling lists (H.264 7.3.2.1.1). */
        private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

        @OptIn(UnstableApi::class)
        private fun spsColour(a: ParsableNalUnitBitArray): Vui {
            val profile = a.readBits(8)
            a.skipBits(16) // constraint flags, level
            a.readUnsignedExpGolombCodedInt() // seq_parameter_set_id
            if (profile in HIGH_PROFILES) {
                val chroma = a.readUnsignedExpGolombCodedInt()
                if (chroma == 3) a.skipBit()
                a.readUnsignedExpGolombCodedInt() // bit_depth_luma_minus8
                a.readUnsignedExpGolombCodedInt() // bit_depth_chroma_minus8
                a.skipBit() // qpprime_y_zero_transform_bypass_flag
                if (a.readBit()) {
                    for (i in 0 until if (chroma != 3) 8 else 12) if (a.readBit()) skipScalingList(a, if (i < 6) 16 else 64)
                }
            }
            a.readUnsignedExpGolombCodedInt() // log2_max_frame_num_minus4
            when (a.readUnsignedExpGolombCodedInt()) {
                0 -> {
                    a.readUnsignedExpGolombCodedInt()
                }

                1 -> {
                    a.skipBit()
                    a.readSignedExpGolombCodedInt()
                    a.readSignedExpGolombCodedInt()
                    val cycle = a.readUnsignedExpGolombCodedInt()
                    if (cycle > 255) return Vui.NONE
                    repeat(cycle) { a.readSignedExpGolombCodedInt() }
                }
            }
            a.readUnsignedExpGolombCodedInt() // max_num_ref_frames
            a.skipBit() // gaps_in_frame_num_value_allowed_flag
            a.readUnsignedExpGolombCodedInt() // pic_width_in_mbs_minus1
            a.readUnsignedExpGolombCodedInt() // pic_height_in_map_units_minus1
            if (!a.readBit()) a.skipBit() // frame_mbs_only_flag, mb_adaptive_frame_field_flag
            a.skipBit() // direct_8x8_inference_flag
            if (a.readBit()) repeat(4) { a.readUnsignedExpGolombCodedInt() } // frame cropping
            if (!a.readBit()) return Vui.NONE // vui_parameters_present_flag
            if (a.readBit() && a.readBits(8) == 255) a.skipBits(32) // aspect ratio, extended SAR
            if (a.readBit()) a.skipBit() // overscan
            if (!a.readBit()) return Vui.NONE // video_signal_type_present_flag
            a.skipBits(4) // video_format, video_full_range_flag
            if (!a.readBit()) return Vui.NONE // colour_description_present_flag
            // Media3's own mapping (isoColorPrimariesToColorSpace).
            val configured =
                when (a.readBits(8)) {
                    1 -> MediaFormat.COLOR_STANDARD_BT709
                    4, 5, 6, 7 -> MediaFormat.COLOR_STANDARD_BT601_NTSC
                    9 -> MediaFormat.COLOR_STANDARD_BT2020
                    else -> null
                }
            val transfer =
                when (a.readBits(8)) {
                    16 -> MediaFormat.COLOR_TRANSFER_ST2084
                    18 -> MediaFormat.COLOR_TRANSFER_HLG
                    1, 6, 14, 15 -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    else -> null
                }
            val standard =
                when (a.readBits(8)) {
                    // SMPTE 240M is close to BT.709, FCC to BT.601.
                    1, 7 -> MediaFormat.COLOR_STANDARD_BT709

                    5 -> MediaFormat.COLOR_STANDARD_BT601_PAL

                    4, 6 -> MediaFormat.COLOR_STANDARD_BT601_NTSC

                    9, 10 -> MediaFormat.COLOR_STANDARD_BT2020

                    else -> null
                }
            return Vui(standard, transfer, configured)
        }

        @OptIn(UnstableApi::class)
        private fun skipScalingList(
            a: ParsableNalUnitBitArray,
            size: Int,
        ) {
            var last = 8
            var next = 8
            repeat(size) {
                if (next != 0) next = (last + a.readSignedExpGolombCodedInt() + 256) % 256
                if (next != 0) last = next
            }
        }

        /**
         * BT.709 when tagged so; BT.601 or BT.2020 when tagged so; otherwise by
         * size, as Android's codecs default untagged video
         * (`ColorUtils::setDefaultCodecColorAspectsIfNeeded`): BT.2020 from 4K,
         * BT.601 up to PAL or NTSC size, BT.709 between. HDR (PQ or HLG) never
         * is: Media3 converts HDR video itself.
         */
        fun videoIsBt709(
            standard: Int?,
            width: Int,
            height: Int,
            transfer: Int? = null,
        ): Boolean =
            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
                false
            } else {
                when (standard) {
                    MediaFormat.COLOR_STANDARD_BT709 -> true
                    MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT2020 -> false
                    else -> sizeDefaultIsBt709(width, height)
                }
            }

        private fun sizeDefaultIsBt709(
            w: Int,
            h: Int,
        ): Boolean =
            when {
                w >= 3840 || h >= 3840 || w.toLong() * h >= 3840L * 1634 -> false
                (w <= 720 && h in 481..576) || (h <= 720 && w in 481..576) -> false
                (w <= 720 && h <= 480) || (h <= 720 && w <= 480) -> false
                else -> true
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
