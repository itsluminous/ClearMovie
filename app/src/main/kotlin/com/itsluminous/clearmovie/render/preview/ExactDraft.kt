package com.itsluminous.clearmovie.render.preview

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.itsluminous.clearmovie.render.media3.ColourCalibration
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.media3.RenderExporter
import com.itsluminous.clearmovie.render.plan.RenderPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * "Play exactly as exported" (render decision D1): renders about
 * [WINDOW_US] of the real export around the playhead, at a low height, with
 * Transformer and the same `CompositionFactory` as the export, so what plays
 * is what the export will contain (the keyed frame, captions, crossfades).
 * The file goes to the app's cache and is replaced by the next draft.
 */
@UnstableApi
class ExactDraft(
    private val context: Context,
) {
    sealed interface Result {
        data class Done(
            val file: File,
        ) : Result

        data object Failed : Result
    }

    /** The part of [plan] the draft covers when asked at [atUs]: from a little before it, [WINDOW_US] long. */
    fun window(
        plan: RenderPlan,
        atUs: Long,
    ): LongRange {
        val length = minOf(WINDOW_US, plan.durationUs)
        val start = (atUs - LEAD_US).coerceIn(0, plan.durationUs - length)
        return start until start + length
    }

    /**
     * Renders [plan] (built at [RenderPlan.height], which should be low) from
     * [atUs]'s window. [onProgress] gets 0..100. Cancelling stops the render
     * and deletes the partial file.
     */
    suspend fun render(
        plan: RenderPlan,
        atUs: Long,
        resolver: MediaResolver,
        onProgress: (Int) -> Unit,
    ): Result {
        val w = window(plan, atUs)
        val slice = plan.slice(w.first, w.last + 1)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val out = File(dir, FILE)
        out.delete()
        // The device's measured colour handling, the same for the composition and the
        // encoder. Interruptible, so cancelling the draft stops a calibration in flight.
        val colour = runInterruptible(Dispatchers.IO) { ColourCalibration.of(context) }
        val composition =
            try {
                withContext(Dispatchers.IO) { CompositionFactory(context, resolver) { colour }.build(slice) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.Failed
            }
        return withContext(Dispatchers.Main) {
            coroutineScope {
                var transformer: Transformer? = null
                val poll =
                    launch {
                        val holder = ProgressHolder()
                        while (true) {
                            delay(POLL_MS)
                            val t = transformer ?: continue
                            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        }
                    }
                try {
                    suspendCancellableCoroutine { cont ->
                        transformer =
                            RenderExporter(context, maxOf(plan.width, plan.height), colour).start(
                                composition,
                                out,
                                EncodeSpec.forHeight(plan.shortSide),
                                object : RenderExporter.Listener {
                                    override fun onCompleted(result: ExportResult) {
                                        if (cont.isActive) cont.resume(Result.Done(out))
                                    }

                                    override fun onError(error: ExportException) {
                                        out.delete()
                                        if (cont.isActive) cont.resume(Result.Failed)
                                    }
                                },
                            )
                        cont.invokeOnCancellation { /* cancelled below, on the main thread */ }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        transformer?.cancel()
                        out.delete()
                    }
                    throw e
                } finally {
                    poll.cancel()
                }
            }
        }
    }

    companion object {
        const val WINDOW_US = 10_000_000L
        const val LEAD_US = 2_000_000L

        /** The draft's picture height: quick to render, enough to judge the frame and captions. */
        const val HEIGHT = 360
        private const val POLL_MS = 250L
        private const val DIR = "draft"
        private const val FILE = "draft.mp4"
    }
}
