package com.itsluminous.clearmovie.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetParseResult
import com.itsluminous.clearmovie.domain.model.PresetParser
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.render.media3.ChromaKeyEffect
import com.itsluminous.clearmovie.render.media3.ColourCalibration
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.FileMediaResolver
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.media3.RenderExporter
import com.itsluminous.clearmovie.render.plan.RenderPlan
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Shared plumbing for the render tests: synthetic fixtures copied into the
 * app's cache (the only place the renderer reads files from), export of a
 * plan through the real factory and Transformer, frame and audio extraction,
 * and colour and level measurements. Outputs and extracted frames go to the
 * app's external files folder (`render-tests/`), so they can be pulled as
 * evidence; nothing there is ever committed.
 */
@UnstableApi
object RenderHarness {
    const val TAG = "RenderTest"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    val fixtureDir: File by lazy {
        File(context.cacheDir, "fixtures").apply {
            mkdirs()
            for (name in testContext.assets.list("fixtures").orEmpty()) {
                testContext.assets.open("fixtures/$name").use { input -> FileOutputStream(File(this, name)).use { input.copyTo(it) } }
            }
        }
    }

    val outDir: File by lazy { File(checkNotNull(context.getExternalFilesDir(null)), "render-tests").apply { mkdirs() } }

    /** The bundled generic preset, read the way the app reads it. */
    val preset: Preset by lazy {
        val text =
            context.assets
                .open("presets/event-frame/preset.json")
                .bufferedReader()
                .use { it.readText() }
        (PresetParser.parse(text) as PresetParseResult.Ok).preset
    }

    fun fixture(name: String) = MediaRef.Copied(name)

    fun resolver(
        intro: String? = "intro.mp4",
        frame: String? = "frame.mp4",
        ending: String? = "speech.m4a",
        root: File = fixtureDir,
    ): MediaResolver =
        FileMediaResolver(
            buildMap {
                intro?.let { put(PresetSlot.INTRO, File(root, it)) }
                frame?.let { put(PresetSlot.FRAME, File(root, it)) }
                ending?.let { put(PresetSlot.ENDING, File(root, it)) }
            },
            fixtureDir,
        )

    data class Exported(
        val file: File,
        val result: ExportResult,
        val wallMs: Long,
    )

    /** Exports [plan] to `render-tests/<name>.mp4` and waits for it. */
    fun export(
        plan: RenderPlan,
        name: String,
        resolver: MediaResolver = resolver(),
        timeoutMin: Long = 15,
    ): Exported {
        // The device's colour handling, measured once (and cached) off the main thread.
        val colour = ColourCalibration.of(context)
        // The composition is built off the main thread (it reads files and
        // draws bitmaps), as the factory requires; Transformer runs on main.
        val composition = CompositionFactory(context, resolver, deviceColour = { colour }).build(plan)
        return exportComposition(
            composition,
            name,
            RenderExporter(context, maxOf(plan.width, plan.height), colour),
            EncodeSpec.forHeight(plan.shortSide),
            timeoutMin,
        )
    }

    /** Exports a built [composition] with [exporter] to `render-tests/<name>.mp4` and waits for it. */
    fun exportComposition(
        composition: Composition,
        name: String,
        exporter: RenderExporter,
        spec: EncodeSpec,
        timeoutMin: Long = 15,
    ): Exported {
        val out = File(outDir, "$name.mp4").apply { delete() }
        val latch = CountDownLatch(1)
        val done = AtomicReference<ExportResult>()
        val failure = AtomicReference<Throwable>()
        val started = System.currentTimeMillis()
        Handler(Looper.getMainLooper()).post {
            try {
                exporter.start(
                    composition,
                    out,
                    spec,
                    object : RenderExporter.Listener {
                        override fun onCompleted(result: ExportResult) {
                            done.set(result)
                            latch.countDown()
                        }

                        override fun onError(error: ExportException) {
                            failure.set(error)
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                failure.set(e)
                latch.countDown()
            }
        }
        check(latch.await(timeoutMin, TimeUnit.MINUTES)) { "Export of $name timed out" }
        failure.get()?.let { throw AssertionError("Export of $name failed: ${(it as? ExportException)?.errorCodeName ?: it}", it) }
        val r = checkNotNull(done.get()) { "No export result" }
        val wall = System.currentTimeMillis() - started
        report("export $name: ${wall}ms wall, videoEncoder=${r.videoEncoderName}, audioEncoder=${r.audioEncoderName}")
        return Exported(out, r, wall)
    }

    /** Appends a line to `render-tests/report.txt`, the spike's raw measurements. */
    fun report(line: String) {
        Log.i(TAG, line)
        File(outDir, "report.txt").appendText(line + "\n")
    }

    /** The frame closest to [timeUs], as an ARGB bitmap. */
    fun frameAt(
        file: File,
        timeUs: Long,
    ): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            return checkNotNull(r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)) { "No frame at $timeUs" }
                .copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            r.release()
        }
    }

    fun durationUs(file: File): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            return checkNotNull(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() * 1000
        } finally {
            r.release()
        }
    }

    fun savePng(
        bitmap: Bitmap,
        name: String,
    ): File = File(outDir, "$name.png").also { f -> FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    /** Mean RGB of a (2r+1)^2 patch centred on normalised (nx, ny). */
    fun rgb(
        bitmap: Bitmap,
        nx: Double,
        ny: Double,
        r: Int = 3,
    ): IntArray {
        val cx = (nx * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val cy = (ny * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        var sr = 0
        var sg = 0
        var sb = 0
        var n = 0
        for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(bitmap.height - 1)) {
            for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(bitmap.width - 1)) {
                val c = bitmap.getPixel(x, y)
                sr += Color.red(c)
                sg += Color.green(c)
                sb += Color.blue(c)
                n++
            }
        }
        return intArrayOf(sr / n, sg / n, sb / n)
    }

    fun luma(c: IntArray): Double = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    fun close(
        actual: IntArray,
        expected: IntArray,
        tolerance: Int,
    ): Boolean = actual.indices.all { abs(actual[it] - expected[it]) <= tolerance }

    /** CIE76 colour difference between two sRGB colours. */
    fun deltaE(
        a: IntArray,
        b: IntArray,
    ): Double {
        val la = lab(a)
        val lb = lab(b)
        return sqrt((la[0] - lb[0]).pow(2) + (la[1] - lb[1]).pow(2) + (la[2] - lb[2]).pow(2))
    }

    private fun lab(c: IntArray): DoubleArray {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(c[0])
        val g = lin(c[1])
        val b = lin(c[2])
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    /**
     * Pixels within Delta E 10 of either form of the preset's key colour (as
     * stored, and as a BT.601 decoder shows a BT.709 file of it), so leftover
     * key green is counted whichever matrix decoded the export. A pure green
     * (0, 255, 0) picture is within Delta E 6 of the BT.601 form whichever
     * matrix decodes it, so areas where the content itself is green go in
     * [exclude] (normalised canvas rects: x, y, w, h).
     */
    fun countNearKey(
        bitmap: Bitmap,
        step: Int = 2,
        exclude: List<DoubleArray> = emptyList(),
    ): Int {
        val key = preset.frame!!.key.color
        val forms =
            listOf(key, ChromaKeyEffect.misdecodedAsBt601(key)).map {
                intArrayOf(
                    ((it.argb shr 16) and 0xFF).toInt(),
                    (
                        (
                            it.argb shr
                                8
                        ) and
                            0xFF
                    ).toInt(),
                    (it.argb and 0xFF).toInt(),
                )
            }
        var n = 0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val c = bitmap.getPixel(x, y)
                if (Color.green(c) < 120 || Color.red(c) > 120) continue
                val nx = x.toDouble() / bitmap.width
                val ny = y.toDouble() / bitmap.height
                if (exclude.any { r -> nx >= r[0] && nx < r[0] + r[2] && ny >= r[1] && ny < r[1] + r[3] }) continue
                val rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
                if (forms.any { deltaE(rgb, it) <= 10.0 }) n++
            }
        }
        return n
    }

    /** Pixels within [maxDeltaE] of [key], scanning every [step] px. */
    fun countNear(
        bitmap: Bitmap,
        key: IntArray,
        maxDeltaE: Double,
        step: Int = 2,
    ): Int {
        var n = 0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val c = bitmap.getPixel(x, y)
                // Cheap pre-filter: key green is strongly green.
                if (Color.green(c) < 120 || Color.red(c) > 120) continue
                if (deltaE(intArrayOf(Color.red(c), Color.green(c), Color.blue(c)), key) <= maxDeltaE) n++
            }
        }
        return n
    }

    class Pcm(
        val sampleRate: Int,
        val mono: FloatArray,
    )

    /** Decodes the first audio track of [file] to mono float PCM. */
    fun decodeAudio(file: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track =
            (0 until extractor.trackCount).first {
                extractor
                    .getTrackFormat(
                        it,
                    ).getString(MediaFormat.KEY_MIME)!!
                    .startsWith("audio/")
            }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ArrayList<Float>()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var done = false
        try {
            while (!done) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.asShortBuffer()
                    while (shorts.remaining() >= channels) {
                        var sum = 0f
                        repeat(channels) { sum += shorts.get() / 32768f }
                        out += sum / channels
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return Pcm(rate, out.toFloatArray())
    }

    /**
     * The level of a pure tone at [freq] between [fromS] and [toS], in dBFS of
     * a full-scale sine (Goertzel, Hann window).
     */
    fun toneDb(
        pcm: Pcm,
        freq: Double,
        fromS: Double,
        toS: Double,
    ): Double {
        val a = (fromS * pcm.sampleRate).toInt().coerceIn(0, pcm.mono.size)
        val b = (toS * pcm.sampleRate).toInt().coerceIn(a, pcm.mono.size)
        val n = b - a
        require(n > 1000) { "Window too short" }
        val w = 2 * PI * freq / pcm.sampleRate
        val coeff = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        var wsum = 0.0
        for (i in 0 until n) {
            val hann = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            wsum += hann
            val s = pcm.mono[a + i] * hann + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        val amplitude = 2 * sqrt(power) / wsum
        return 20 * log10(amplitude.coerceAtLeast(1e-9))
    }
}
