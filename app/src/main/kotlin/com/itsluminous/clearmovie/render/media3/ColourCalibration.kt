package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Measures how this device decodes and encodes BT.709 video ([DeviceColour]),
 * once per build of the system: a 1 s, 320x192 export of a flat BT.709 clip
 * (`assets/render/colour-calibration.mp4`, YUV [CALIBRATION_YUV]) through the
 * same encoder settings as an export. A GL readback where the first effect
 * sits gives the RGB the GPU made of the clip, so the decode matrix; the
 * output's raw YUV (a `MediaCodec` decode to buffers, with no colour
 * conversion) against that RGB gives the encoder's matrix.
 *
 * The result is kept in memory and in `noBackupFilesDir/render`, keyed by
 * [Build.FINGERPRINT], so a system update measures again. A calibration that
 * fails or is ambiguous changes nothing ([DeviceColour.TRUSTED]); a failed one
 * is not stored, so the next export tries again. [of] blocks for the
 * calibration (about a second) and must not run on the main thread.
 */
@UnstableApi
object ColourCalibration {
    /** The calibration clip's measured Y, Cb, Cr (scripts/make-colour-calibration.sh). */
    val CALIBRATION_YUV = doubleArrayOf(97.0, 191.0, 201.0)

    const val CLIP_WIDTH = 320
    const val CLIP_HEIGHT = 192
    private const val VERSION = 1
    private const val TAG = "ColourCalibration"
    private const val ASSET = "asset:///render/colour-calibration.mp4"

    @Volatile private var cached: DeviceColour? = null
    private val lock = Any()

    fun of(context: Context): DeviceColour {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            check(!Looper.getMainLooper().isCurrentThread) { "Calibrate colour off the main thread" }
            val app = context.applicationContext
            val file = File(File(app.noBackupFilesDir, "render"), "colour-calibration.txt")
            val key = "v$VERSION|${Build.FINGERPRINT}"
            val stored = runCatching { DeviceColour.parse(file.readText(), key) }.getOrNull()
            val result =
                stored ?: measure(app)?.also { measured ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(measured.serialize(key))
                    }
                } ?: DeviceColour.TRUSTED
            cached = result
            return result
        }
    }

    /** Runs the calibration export; null when it failed. */
    fun measure(context: Context): DeviceColour? {
        val dir = File(context.noBackupFilesDir, "render").apply { mkdirs() }
        val out = File(dir, "colour-calibration.mp4").apply { delete() }
        val probe = GlColourProbe(listOf(CLIP_WIDTH / 2 to CLIP_HEIGHT / 2))
        val item =
            EditedMediaItem
                .Builder(MediaItem.fromUri(Uri.parse(ASSET)))
                .setRemoveAudio(true)
                .setEffects(Effects(listOf(), listOf(probe)))
                .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()
        val thread = HandlerThread("colour-calibration").apply { start() }
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val encoder = AtomicReference<String>()
        try {
            Handler(thread.looper).post {
                try {
                    Transformer
                        .Builder(context)
                        .setLooper(thread.looper)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(RenderExporter.encoderFactory(context, EncodeSpec(2_000_000), DeviceColour.TRUSTED))
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    encoder.set(exportResult.videoEncoderName)
                                    latch.countDown()
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    error.set(exportException)
                                    latch.countDown()
                                }
                            },
                        ).build()
                        .start(composition, out.absolutePath)
                } catch (e: RuntimeException) {
                    error.set(e)
                    latch.countDown()
                }
            }
            if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS) || error.get() != null) {
                Log.w(TAG, "Colour calibration failed", error.get())
                return null
            }
        } finally {
            thread.quitSafely()
        }
        val gl = probe.mean(fromUs = 300_000)?.copyOfRange(0, 3) ?: return null
        val yuv = RawYuv.meanAt(out, 500_000, CLIP_WIDTH / 2, CLIP_HEIGHT / 2) ?: return null
        out.delete()
        val result = classify(gl, yuv)
        Log.i(
            TAG,
            "GL sees ${gl.format()} for YUV ${CALIBRATION_YUV.format()}; the encoder (${encoder.get()}) wrote YUV ${yuv.format()}: $result",
        )
        return result
    }

    /** [gl]: the RGB the GPU made of [CALIBRATION_YUV]; [encoded]: the YUV the encoder wrote for [gl]. */
    fun classify(
        gl: DoubleArray,
        encoded: DoubleArray,
    ): DeviceColour {
        val decode =
            ColourMath.nearest(
                gl,
                listOf(
                    ColourMath.toRgb(CALIBRATION_YUV, ColourMath.Matrix.BT709),
                    ColourMath.toRgb(CALIBRATION_YUV, ColourMath.Matrix.BT601),
                ),
            )
        val encode =
            ColourMath.nearest(
                encoded,
                listOf(ColourMath.toYuv(gl, ColourMath.Matrix.BT709), ColourMath.toYuv(gl, ColourMath.Matrix.BT601)),
            )
        if (decode == null || encode == null) return DeviceColour.TRUSTED
        return DeviceColour(gpuDecodesBt709AsBt601 = decode == 1, encoderWritesBt601 = encode == 1)
    }

    private fun DoubleArray.format() = joinToString(prefix = "(", postfix = ")") { "%.1f".format(Locale.ROOT, it) }

    private const val TIMEOUT_S = 60L
}

/**
 * The colour correction for BT.709 video on a GPU that decodes it with the
 * BT.601 matrix ([ColourMath.bt601DecodedToBt709]). It must be the first
 * effect on the item, so every later effect (keying among them) sees the
 * right colours.
 */
@UnstableApi
object Bt709Correction : RgbMatrix {
    private val matrix = ColourMath.bt601DecodedToBt709()

    override fun getMatrix(
        presentationTimeUs: Long,
        useHdr: Boolean,
    ): FloatArray = matrix
}

/** A pass-through effect that reads back the mean RGBA of a 9x9 patch around each canvas point (y down). */
@UnstableApi
internal class GlColourProbe(
    private val points: List<Pair<Int, Int>>,
) : GlEffect {
    private val samples: MutableList<Pair<Long, List<DoubleArray>>> = Collections.synchronizedList(mutableListOf())

    /** The mean of each channel at the first point over the frames from [fromUs] on (0..255), or null. */
    fun mean(fromUs: Long = 0): DoubleArray? = means(fromUs)?.firstOrNull()

    fun means(fromUs: Long = 0): List<DoubleArray>? {
        val late = synchronized(samples) { samples.filter { it.first >= fromUs } }
        if (late.isEmpty()) return null
        return points.indices.map { p -> DoubleArray(4) { c -> late.sumOf { it.second[p][c] } / late.size } }
    }

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram =
        object : BaseGlShaderProgram(false, 1) {
            private val program =
                GlProgram(
                    "attribute vec4 aFramePosition; varying vec2 vUv; void main() { gl_Position = aFramePosition; vUv = aFramePosition.xy * 0.5 + 0.5; }",
                    "precision highp float; uniform sampler2D uTexSampler; varying vec2 vUv; void main() { gl_FragColor = texture2D(uTexSampler, vUv); }",
                ).apply {
                    setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                }
            private var height = 0
            private var width = 0

            override fun configure(
                inputWidth: Int,
                inputHeight: Int,
            ): Size {
                width = inputWidth
                height = inputHeight
                return Size(inputWidth, inputHeight)
            }

            override fun drawFrame(
                inputTexId: Int,
                presentationTimeUs: Long,
            ) {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                val means =
                    points.map { (x, y) ->
                        val px = (x - 4).coerceIn(0, maxOf(0, width - 9))
                        val py = (height - 1 - y - 4).coerceIn(0, maxOf(0, height - 9))
                        val buf = ByteBuffer.allocateDirect(9 * 9 * 4).order(ByteOrder.nativeOrder())
                        GLES20.glReadPixels(px, py, 9, 9, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                        val sum = DoubleArray(4)
                        for (i in 0 until 81) for (c in 0 until 4) sum[c] += (buf.get(i * 4 + c).toInt() and 0xFF).toDouble()
                        DoubleArray(4) { sum[it] / 81 }
                    }
                samples += presentationTimeUs to means
            }

            override fun release() {
                super.release()
                program.delete()
            }
        }
}

/** Raw YUV of a video frame, decoded by `MediaCodec` to buffers (no colour conversion). */
internal object RawYuv {
    /** Mean Y, Cb, Cr of an 8x8 patch at (x, y) of the first frame at or after [timeUs]; null when it can't be read. */
    fun meanAt(
        file: File,
        timeUs: Long,
        x: Int,
        y: Int,
    ): DoubleArray? {
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
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            repeat(MAX_POLLS) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
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
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && (info.presentationTimeUs >= timeUs || last)) {
                        val mean = decoder.getOutputImage(out)?.use { image -> patch(image, x, y) }
                        decoder.releaseOutputBuffer(out, false)
                        return mean
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (last) return null
                }
            }
            return null
        } catch (e: RuntimeException) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun patch(
        image: android.media.Image,
        cx: Int,
        cy: Int,
    ): DoubleArray {
        val crop = image.cropRect
        val (yp, up, vp) = image.planes
        val sum = DoubleArray(3)
        var n = 0
        for (dy in -4 until 4) {
            for (dx in -4 until 4) {
                val x = (crop.left + cx + dx).coerceIn(crop.left, crop.right - 1)
                val y = (crop.top + cy + dy).coerceIn(crop.top, crop.bottom - 1)
                sum[0] += (yp.buffer.get(y * yp.rowStride + x * yp.pixelStride).toInt() and 0xFF).toDouble()
                sum[1] += (up.buffer.get((y / 2) * up.rowStride + (x / 2) * up.pixelStride).toInt() and 0xFF).toDouble()
                sum[2] += (vp.buffer.get((y / 2) * vp.rowStride + (x / 2) * vp.pixelStride).toInt() and 0xFF).toDouble()
                n++
            }
        }
        return DoubleArray(3) { sum[it] / n }
    }

    private const val MAX_POLLS = 2000
}
