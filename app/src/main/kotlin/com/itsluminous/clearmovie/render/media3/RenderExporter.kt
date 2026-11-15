package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.transformer.AudioEncoderSettings
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
 * the phone refuses the requested one. Photos are decoded no larger than
 * [maxImageDimension] on their long side, so a 48 MP photo never reaches a
 * 2 GB phone's memory at full size (review F8).
 *
 * Must be called on a thread with a [Looper]; the listener is called on it.
 * cm-export wraps this in the foreground worker.
 */
@UnstableApi
class RenderExporter(
    private val context: Context,
    private val maxImageDimension: Int,
) {
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
        val encoders =
            DefaultEncoderFactory
                .Builder(context)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(spec.audioBitrate).build())
                .build()
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
                        ) = listener.onCompleted(exportResult)

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
}
