package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.metrics.LogSessionId
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
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
 * Starts a Transformer export of a [Composition] to an MP4 file: H.264 and
 * AAC, the encoder factory falling back to a supported configuration when
 * the phone refuses the requested one. The encoder names are logged, so a
 * report from a phone shows whether hardware encoding was used.
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
    canvasHeight: Int,
    private val deviceColour: DeviceColour,
) {
    val maxImageDimension: Int = minOf(MAX_TEXTURE, 2 * maxOf(canvasHeight, (canvasHeight * 16 + 8) / 9))

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
        val encoders = encoderFactory(context, spec, deviceColour)
        val bitmapLoader =
            DataSourceBitmapLoader
                .Builder(context)
                .setMaximumOutputDimension(maxImageDimension)
                .build()
        val transformer =
            Transformer
                .Builder(context)
                .setLooper(looper)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, bitmapLoader))
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            Log.i(
                                TAG,
                                "Export done: video encoder ${exportResult.videoEncoderName}, audio encoder ${exportResult.audioEncoderName}",
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
            base: () -> Codec.EncoderFactory = {
                DefaultEncoderFactory
                    .Builder(context)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(spec.audioBitrate).build())
                    .build()
            },
        ): Codec.EncoderFactory {
            val encoders = base()
            return if (deviceColour.encoderWritesBt601) Bt601TaggedEncoders(encoders, deviceColour.encoderName) else encoders
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
        private val measuredEncoder: String?,
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
            val tagged = delegate.createForVideoEncoding(format.buildUpon().setColorInfo(SDR_BT601_LIMITED).build(), logSessionId)
            if (measuredEncoder == null || tagged.name == measuredEncoder) return tagged
            Log.i(TAG, "Encoder ${tagged.name} is not the calibrated $measuredEncoder; keeping its own tag")
            tagged.release()
            return delegate.createForVideoEncoding(format, logSessionId)
        }

        override fun isVideoFormatSupported(format: Format): Boolean = delegate.isVideoFormatSupported(format)

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
    }
}
