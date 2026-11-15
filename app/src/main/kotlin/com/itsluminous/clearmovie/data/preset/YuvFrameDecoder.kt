package com.itsluminous.clearmovie.data.preset

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Frames for the key-colour check, decoded to YUV with `MediaCodec` and turned
 * into RGB here, with the matrix and range the stream declares (BT.709 for
 * HD video, BT.601 for SD, limited range unless it says full).
 *
 * Why not `MediaMetadataRetriever.getFrameAtTime`: on the Android 16
 * emulator it converts HD BT.709 video with the BT.601 matrix. The synthetic
 * frame fixture's `#00D81F` window came out as `#10FB25` (Delta E 16 from the
 * preset's `#00D922`), and decoding the real frame file the same wrong way
 * gives Delta E 16.8, so every untouched frame would have been "adjusted" to
 * a wrong green. The preset's key colour was measured on correctly decoded
 * BT.709 pixels, so the check decodes the same way.
 */
internal object YuvFrameDecoder {
    /** Frames at [timesUs], subsampled to at most [maxWidth] pixels across. */
    suspend fun frames(
        file: File,
        timesUs: List<Long>,
        maxWidth: Int,
    ): List<FrameSample> {
        if (timesUs.isEmpty()) return emptyList()
        // A fresh decoder per frame: flushing and seeking one decoder is not
        // reliable on every device, and three short decodes from a key frame are cheap.
        return timesUs.sorted().mapNotNull { frameAt(file, it, maxWidth) }
    }

    private suspend fun frameAt(
        file: File,
        timeUs: Long,
        maxWidth: Int,
    ): FrameSample? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return decodeAt(decoder, extractor, format, timeUs, maxWidth)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun decodeAt(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        targetUs: Long,
        maxWidth: Int,
    ): FrameSample? {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var polls = 0
        while (polls++ < MAX_POLLS) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (out < 0) continue
            val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0 && (info.presentationTimeUs >= targetUs || last)) {
                val frame =
                    decoder.getOutputImage(out)?.use { image ->
                        toRgb(image, Colorimetry.of(decoder.outputFormat, inputFormat, image.height), maxWidth)
                    }
                decoder.releaseOutputBuffer(out, false)
                return frame
            }
            decoder.releaseOutputBuffer(out, false)
            if (last) return null
        }
        return null
    }

    private fun toRgb(
        image: Image,
        colors: Colorimetry,
        maxWidth: Int,
    ): FrameSample {
        val crop = image.cropRect
        val step = maxOf(1, (crop.width() + maxWidth - 1) / maxWidth)
        val w = crop.width() / step
        val h = crop.height() / step
        val (yPlane, uPlane, vPlane) = image.planes
        val pixels = IntArray(w * h)
        for (j in 0 until h) {
            val y = crop.top + j * step
            for (i in 0 until w) {
                val x = crop.left + i * step
                val luma = yPlane.buffer.get(y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 0xFF
                val cIndexU = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
                val cIndexV = (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride
                val cb = uPlane.buffer.get(cIndexU).toInt() and 0xFF
                val cr = vPlane.buffer.get(cIndexV).toInt() and 0xFF
                pixels[j * w + i] = colors.toArgb(luma, cb, cr)
            }
        }
        return FrameSample(w, h, pixels)
    }

    private const val TIMEOUT_US = 10_000L

    /** About 20 s per frame at most, so a broken decoder cannot hang a setup. */
    private const val MAX_POLLS = 1000
}

/** How a stream's YUV maps to RGB. */
internal data class Colorimetry(
    val kr: Double,
    val kb: Double,
    val fullRange: Boolean,
) {
    fun toArgb(
        y: Int,
        cb: Int,
        cr: Int,
    ): Int {
        val yf = if (fullRange) y / 255.0 else (y - 16) / 219.0
        val pb = if (fullRange) (cb - 128) / 255.0 else (cb - 128) / 224.0
        val pr = if (fullRange) (cr - 128) / 255.0 else (cr - 128) / 224.0
        val kg = 1 - kr - kb
        val r = yf + 2 * (1 - kr) * pr
        val b = yf + 2 * (1 - kb) * pb
        val g = (yf - kr * r - kb * b) / kg
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun channel(v: Double): Int = Math.round(v * 255).toInt().coerceIn(0, 255)

    companion object {
        val BT709 = Colorimetry(0.2126, 0.0722, fullRange = false)
        val BT601 = Colorimetry(0.299, 0.114, fullRange = false)
        val BT2020 = Colorimetry(0.2627, 0.0593, fullRange = false)

        /**
         * From the decoder's output format, then the container's, then by size
         * (HD means BT.709). Decoders may report values outside the public
         * constants (the emulator's says 71), which fall back to the size rule.
         */
        fun of(
            output: MediaFormat,
            input: MediaFormat,
            height: Int,
        ): Colorimetry {
            fun int(key: String): Int? =
                listOf(output, input).firstNotNullOfOrNull { f ->
                    if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrNull() else null
                }
            val base =
                when (int(MediaFormat.KEY_COLOR_STANDARD)) {
                    MediaFormat.COLOR_STANDARD_BT709 -> BT709
                    MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> BT601
                    MediaFormat.COLOR_STANDARD_BT2020 -> BT2020
                    else -> if (height >= 720) BT709 else BT601
                }
            return base.copy(fullRange = int(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL)
        }
    }
}
