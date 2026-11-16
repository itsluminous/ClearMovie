package com.itsluminous.clearmovie.data.preset

import android.content.Context
import androidx.core.net.toUri
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.FrameSpec
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The key-colour check for a frame the user chose instead of the preset's,
 * for one video (preset review): the same measurement `PresetStore` runs at
 * setup, on the user's own file. Null when the file has no flat green in the
 * preset's window (it is not a green-screen frame with its window there), so
 * the review can say so instead of keying the wrong colour.
 */
fun interface FrameKeyCheck {
    suspend fun green(
        project: ProjectId,
        ref: MediaRef,
        durationMs: Long,
        frame: FrameSpec,
    ): ArgbColor?
}

/** [FrameKeyCheck] with the platform decoder ([YuvFrameDecoder], the stream's own colour matrix). */
class AndroidFrameKeyCheck(
    private val context: Context,
    private val files: AppFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FrameKeyCheck {
    override suspend fun green(
        project: ProjectId,
        ref: MediaRef,
        durationMs: Long,
        frame: FrameSpec,
    ): ArgbColor? =
        withContext(dispatcher) {
            val times = PresetStore.FRAME_SAMPLES.map { (durationMs * 1000 * it).toLong() }
            val frames =
                try {
                    when (ref) {
                        is MediaRef.Store -> {
                            YuvFrameDecoder.frames(
                                { it.setDataSource(context, ref.uri.toUri(), null) },
                                times,
                                AndroidMediaProbe.FRAME_WIDTH,
                            )
                        }

                        is MediaRef.Copied -> {
                            YuvFrameDecoder.frames(files.copiedFile(project, ref), times, AndroidMediaProbe.FRAME_WIDTH)
                        }

                        is MediaRef.Preset -> {
                            emptyList()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            KeyColorCheck.measure(frames, frame.contentRect, frame.key.color)?.measured
        }
}
