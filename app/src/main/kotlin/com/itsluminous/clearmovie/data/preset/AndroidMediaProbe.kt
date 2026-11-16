package com.itsluminous.clearmovie.data.preset

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * [MediaProbe] on the platform: `MediaMetadataRetriever` for the facts,
 * [YuvFrameDecoder] for frames, `MediaExtractor` and `MediaCodec` to decode the audio for loudness
 * (at most [MAX_LOUDNESS_US] of it). Any failure gives null or leaves the
 * value out; nothing here throws for a bad file.
 */
class AndroidMediaProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaProbe {
    override suspend fun probe(
        file: File,
        frameAt: List<Double>,
        measureLoudness: Boolean,
    ): ProbeResult? =
        withContext(dispatcher) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                // Not getFrameAtTime: it ignores the BT.709 tag on some devices (see YuvFrameDecoder).
                val frames =
                    if (hasVideo && frameAt.isNotEmpty()) {
                        orNullUnlessCancelled {
                            YuvFrameDecoder.frames(file, frameAt.map { (durationMs * 1000 * it.coerceIn(0.0, 1.0)).toLong() }, FRAME_WIDTH)
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }
                val lufs = if (measureLoudness && hasAudio) orNullUnlessCancelled { loudness(file) } else null
                ProbeResult(durationMs * 1000, mime, hasVideo, hasAudio, frames, lufs)
            } catch (e: CancellationException) {
                // A CancellationException is a RuntimeException: let it cancel.
                throw e
            } catch (e: RuntimeException) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    /**
     * Integrated loudness of [uri]'s first audio track (a replaced ending, or
     * a sound the user added, measured when the video is exported), or null
     * without audio or when it cannot be read.
     */
    suspend fun loudnessOf(
        context: Context,
        uri: Uri,
    ): Double? =
        withContext(dispatcher) {
            orNullUnlessCancelled { loudness { it.setDataSource(context, uri, null) } }
        }

    private suspend fun loudness(file: File): Double? = loudness { it.setDataSource(file.path) }

    private suspend fun loudness(open: (MediaExtractor) -> Unit): Double? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(extractor)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") ==
                        true
                }
                    ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var meter: LoudnessMeter? = null
            var floatPcm = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var buffer = FloatArray(0)
            var idleAfterInput = 0
            while (idleAfterInput < MAX_IDLE_POLLS) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0 || extractor.sampleTime > MAX_LOUDNESS_US) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (inputDone && out == MediaCodec.INFO_TRY_AGAIN_LATER) idleAfterInput++
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm =
                            f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        meter = null
                    }

                    out >= 0 -> {
                        val data = decoder.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        val m = meter ?: LoudnessMeter(rate, channels).also { meter = it }
                        val count = if (floatPcm) info.size / 4 else info.size / 2
                        if (buffer.size < count) buffer = FloatArray(count)
                        if (floatPcm) {
                            data.asFloatBuffer().get(buffer, 0, count)
                        } else {
                            val shorts = data.asShortBuffer()
                            for (i in 0 until count) buffer[i] = shorts.get(i) / 32768f
                        }
                        m.add(buffer, count)
                        decoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return m.integratedLufs()
                    }
                }
            }
            // A decoder that never signals the end: use what was decoded.
            return meter?.integratedLufs()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** [block]'s result, or null when it fails; a cancellation still cancels. */
    private inline fun <T> orNullUnlessCancelled(block: () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        const val FRAME_WIDTH = 480
        const val MAX_LOUDNESS_US = 10L * 60 * 1_000_000
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_POLLS = 300
    }
}
