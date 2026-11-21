package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.metrics.LogSessionId
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File

/** Output settings per quality (ARCHITECTURE section 7, Export). */
data class EncodeSpec(
    val videoBitrate: Int,
    val audioBitrate: Int = 192_000,
) {
    companion object {
        /** High 1080p, Standard 720p, Small 480p. */
        fun forHeight(height: Int): EncodeSpec =
            when {
                height >= 1080 -> EncodeSpec(12_000_000)
                height >= 720 -> EncodeSpec(6_000_000)
                else -> EncodeSpec(2_500_000)
            }
    }
}

/**
 * Which encoders an export may use. [HARDWARE_FIRST] tries the phone's
 * hardware encoders before its software ones (the normal export);
 * [SOFTWARE_ONLY] is the retry after a hardware encoder failed mid-export.
 */
enum class EncoderPreference { HARDWARE_FIRST, SOFTWARE_ONLY }

/**
 * Starts a Transformer export of a [Composition] to an MP4 file: H.264 and
 * AAC, the encoder factory falling back to a supported configuration when
 * the phone refuses the requested one. The encoder names are logged, so a
 * report from a phone shows whether hardware encoding was used.
 *
 * [preference] picks hardware or software encoders. [baseEncoders] replaces
 * the default encoder factory (a test injects a failing one there).
 * [muxerFactory] replaces the default MP4 muxer (the export's direct path
 * writes into a gallery entry's file descriptor). Decoders fall back to the
 * next one the phone has when the first refuses to start, which covers a
 * phone out of hardware decoder instances (risk 5).
 *
 * Photos are decoded no larger than [maxImageDimension] on their long side,
 * so a 48 MP photo never reaches a 2 GB phone's memory at full size (review
 * F8). Android decodes at a power-of-two reduction until it fits, so the
 * limit is twice the canvas's long side (at most 4096, the usual GL texture
 * limit): a phone photo then keeps at least canvas resolution instead of
 * being halved below it.
 *
 * [deviceColour] is the device's measured colour handling
 * ([ColourCalibration]): when its encoder writes BT.601 YUV under a BT.709
 * tag (the Android 10 emulator's `OMX.google.h264.encoder` does), the export
 * asks for BT.601, so the file's tag matches what the encoder wrote.
 *
 * [start] must be called on a thread with a [Looper]; the listener is called
 * on it. Build the composition off the main thread first. cm-export wraps
 * this in the foreground worker.
 */
@UnstableApi
class RenderExporter(
    private val context: Context,
    canvasLongSide: Int,
    private val deviceColour: DeviceColour,
    private val preference: EncoderPreference = EncoderPreference.HARDWARE_FIRST,
    private val baseEncoders: ((EncodeSpec, EncoderPreference) -> Codec.EncoderFactory)? = null,
    private val muxerFactory: Muxer.Factory? = null,
) {
    val maxImageDimension: Int = minOf(MAX_TEXTURE, 2 * canvasLongSide)

    interface Listener {
        fun onCompleted(result: ExportResult)

        fun onError(error: ExportException)
    }

    fun start(
        composition: Composition,
        output: File,
        spec: EncodeSpec,
        listener: Listener,
    ): Transformer {
        val looper = checkNotNull(Looper.myLooper()) { "Export needs a Looper thread" }
        val base = baseEncoders
        val encoders =
            if (base != null) {
                encoderFactory(context, spec, deviceColour) { base(spec, preference) }
            } else {
                encoderFactory(context, spec, deviceColour, preference)
            }
        val bitmapLoader =
            DataSourceBitmapLoader
                .Builder(context)
                .setMaximumOutputDimension(maxImageDimension)
                .build()
        val decoders = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()
        val transformer =
            Transformer
                .Builder(context)
                .setLooper(looper)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoders, Clock.DEFAULT, null, bitmapLoader))
                .apply { muxerFactory?.let(::setMuxerFactory) }
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            Log.i(
                                TAG,
                                "Export done: video encoder ${exportResult.videoEncoderName}, " +
                                    "audio encoder ${exportResult.audioEncoderName} ($preference)",
                            )
                            listener.onCompleted(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) = listener.onError(exportException)
                    },
                ).build()
        transformer.start(composition, output.absolutePath)
        return transformer
    }

    companion object {
        private const val TAG = "RenderExporter"
        private const val MAX_TEXTURE = 4096

        /** BT.601 SDR, limited range: the tag for an encoder that writes BT.601 YUV. */
        val SDR_BT601_LIMITED: ColorInfo =
            ColorInfo
                .Builder()
                .setColorSpace(C.COLOR_SPACE_BT601)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .setColorTransfer(C.COLOR_TRANSFER_SDR)
                .build()

        /** The export's encoders; the calibration uses the same ones. */
        fun encoderFactory(
            context: Context,
            spec: EncodeSpec,
            deviceColour: DeviceColour,
            preference: EncoderPreference = EncoderPreference.HARDWARE_FIRST,
            base: () -> Codec.EncoderFactory = { defaultEncoders(context, spec, preference) },
        ): Codec.EncoderFactory {
            val encoders = base()
            val measured = deviceColour.encoderName
            return if (deviceColour.encoderWritesBt601 && measured != null) Bt601TaggedEncoders(encoders, measured) else encoders
        }

        /** Media3's encoder factory, with fallback to a supported size and rate, choosing encoders by [preference]. */
        fun defaultEncoders(
            context: Context,
            spec: EncodeSpec,
            preference: EncoderPreference,
        ): Codec.EncoderFactory =
            DefaultEncoderFactory
                .Builder(context)
                .setEnableFallback(true)
                .setVideoEncoderSelector(selector(preference))
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(spec.audioBitrate).build())
                .build()

        /** Hardware encoders before software ones (in the phone's own order otherwise), or software only. */
        fun selector(preference: EncoderPreference): EncoderSelector =
            EncoderSelector { mime ->
                val all = EncoderUtil.getSupportedEncoders(mime)
                val (hardware, software) = all.partition { EncoderUtil.isHardwareAccelerated(it, mime) }
                when (preference) {
                    EncoderPreference.HARDWARE_FIRST -> ImmutableList.copyOf(hardware + software)
                    EncoderPreference.SOFTWARE_ONLY -> ImmutableList.copyOf(software)
                }
            }
    }

    /**
     * Asks [delegate] for BT.601 SDR video instead of BT.709, but only when it
     * picks the encoder the calibration measured ([measuredEncoder]; another
     * one, chosen for a different size, is opened again untagged). HDR and
     * audio are passed through unchanged. Every method is forwarded: Kotlin's
     * `by` does not forward a Java interface's default methods.
     *
     * BT.601 here is Media3's `COLOR_SPACE_BT601`, which also writes BT.470BG
     * primaries; for SDR video that changes nothing a player shows.
     */
    private class Bt601TaggedEncoders(
        private val delegate: Codec.EncoderFactory,
        private val measuredEncoder: String,
    ) : Codec.EncoderFactory {
        override fun createForAudioEncoding(
            format: Format,
            logSessionId: LogSessionId?,
        ): Codec = delegate.createForAudioEncoding(format, logSessionId)

        override fun createForVideoEncoding(
            format: Format,
            logSessionId: LogSessionId?,
        ): Codec {
            val sdr = format.colorInfo == null || !ColorInfo.isTransferHdr(format.colorInfo)
            if (!sdr) return delegate.createForVideoEncoding(format, logSessionId)
            val tagged =
                try {
                    delegate.createForVideoEncoding(format.buildUpon().setColorInfo(SDR_BT601_LIMITED).build(), logSessionId)
                } catch (e: ExportException) {
                    Log.w(TAG, "The encoder refused the BT.601 tag; keeping its own", e)
                    return delegate.createForVideoEncoding(format, logSessionId)
                }
            if (tagged.name == measuredEncoder) return tagged
            Log.i(TAG, "Encoder ${tagged.name} is not the calibrated $measuredEncoder; keeping its own tag")
            tagged.release()
            return delegate.createForVideoEncoding(format, logSessionId)
        }

        @OptIn(ExperimentalApi::class)
        override fun isVideoFormatSupported(format: Format): Boolean = delegate.isVideoFormatSupported(format)

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
    }
}
