package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where an export writes. [path] is what Transformer is given; [muxerFactory]
 * (when set) writes somewhere else instead, such as a gallery entry's file
 * descriptor. [reset] empties the output before a second attempt.
 */
@UnstableApi
interface ExportOutput {
    val path: String
    val muxerFactory: Muxer.Factory? get() = null

    fun reset()
}

/**
 * One full export of a [Composition] with the phone's encoders, on a
 * looper thread of its own, so it never waits on the main thread and is not
 * tied to any screen. [onProgress] gets 0..100 every [POLL_MS].
 *
 * Hardware first, then software: when the first attempt fails in the video
 * or audio encoder (a hardware encoder that will not start, or stops
 * mid-way), the output is emptied and the export runs again once with
 * software encoders only. Any other failure is reported as it is.
 *
 * Cancelling the coroutine cancels Transformer and waits up to
 * [CANCEL_WAIT_MS] for it to release its codecs before returning.
 */
@UnstableApi
class VideoExport(
    private val context: Context,
    private val deviceColour: DeviceColour,
    private val baseEncoders: ((EncodeSpec, EncoderPreference) -> Codec.EncoderFactory)? = null,
) {
    sealed interface Outcome {
        data class Done(
            val result: ExportResult,
            val preference: EncoderPreference,
            /** The hardware attempt's error, when the software retry produced this file. */
            val hardwareFailure: ExportException?,
        ) : Outcome

        data class Failed(
            val error: ExportException,
        ) : Outcome
    }

    suspend fun run(
        composition: Composition,
        height: Int,
        spec: EncodeSpec,
        output: ExportOutput,
        onProgress: suspend (Int) -> Unit,
    ): Outcome {
        val first = attempt(composition, height, spec, output, EncoderPreference.HARDWARE_FIRST, onProgress)
        if (first !is Outcome.Failed || !isEncoderError(first.error)) return first
        Log.w(TAG, "The first encoder failed (${first.error.errorCodeName}); exporting again with software encoders", first.error)
        output.reset()
        onProgress(0)
        return when (val second = attempt(composition, height, spec, output, EncoderPreference.SOFTWARE_ONLY, onProgress)) {
            is Outcome.Done -> second.copy(hardwareFailure = first.error)
            is Outcome.Failed -> second
        }
    }

    private suspend fun attempt(
        composition: Composition,
        height: Int,
        spec: EncodeSpec,
        output: ExportOutput,
        preference: EncoderPreference,
        onProgress: suspend (Int) -> Unit,
    ): Outcome {
        val thread = HandlerThread("export").apply { start() }
        val handler = Handler(thread.looper)
        val exporter = RenderExporter(context, height, deviceColour, preference, baseEncoders, output.muxerFactory)
        var transformer: Transformer? = null
        try {
            return coroutineScope {
                val poll =
                    launch {
                        val holder = ProgressHolder()
                        while (true) {
                            delay(POLL_MS)
                            val percent = CompletableDeferred<Int?>()
                            handler.post {
                                val t = transformer
                                percent.complete(
                                    if (t != null &&
                                        t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                                    ) {
                                        holder.progress
                                    } else {
                                        null
                                    },
                                )
                            }
                            percent.await()?.let { onProgress(it) }
                        }
                    }
                try {
                    suspendCancellableCoroutine { cont ->
                        handler.post {
                            try {
                                transformer =
                                    exporter.start(
                                        composition,
                                        java.io.File(output.path),
                                        spec,
                                        object : RenderExporter.Listener {
                                            override fun onCompleted(result: ExportResult) {
                                                if (cont.isActive) cont.resume(Outcome.Done(result, preference, null))
                                            }

                                            override fun onError(error: ExportException) {
                                                if (cont.isActive) cont.resume(Outcome.Failed(error))
                                            }
                                        },
                                    )
                            } catch (e: RuntimeException) {
                                if (cont.isActive) cont.resume(Outcome.Failed(ExportException.createForUnexpected(e)))
                            }
                        }
                    }
                } finally {
                    poll.cancel()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val released = CompletableDeferred<Unit>()
                handler.post {
                    runCatching { transformer?.cancel() }
                    released.complete(Unit)
                }
                if (withTimeoutOrNull(CANCEL_WAIT_MS) { released.await() } == null) {
                    Log.w(TAG, "Cancelling the export did not return in time")
                }
            }
            throw e
        } finally {
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "VideoExport"
        const val POLL_MS = 500L
        const val CANCEL_WAIT_MS = 5_000L

        /** Failures a software encoder could get past. */
        fun isEncoderError(error: ExportException): Boolean =
            error.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
                error.errorCode == ExportException.ERROR_CODE_ENCODING_FAILED ||
                error.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
    }
}
