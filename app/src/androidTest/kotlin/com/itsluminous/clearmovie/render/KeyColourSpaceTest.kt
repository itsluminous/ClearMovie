package com.itsluminous.clearmovie.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.media3.ChromaKeyEffect
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.RenderExporter
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Which colour space the keyer works in, and that it keys both forms of the
 * key. cm-storage measures the key colour from the frame decoded with its
 * stream's own BT.709 matrix. A pass-through probe placed where
 * `ChromaKeyEffect` sits reads pixels back from GL. On the API 36 emulator
 * the GPU sampler decodes the BT.709 stream as BT.601 (Delta E about 16 from
 * the stored key), so the keyer matches both forms of the key.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class KeyColourSpaceTest {
    /** Copies its input and reads back a 9x9 RGBA mean around each canvas point, y down. */
    private class Probe(
        val points: List<Pair<Int, Int>>,
    ) : GlEffect {
        /** Per frame: presentation time and one RGBA mean (0..255) per point. */
        val samples: MutableList<Pair<Long, List<IntArray>>> = Collections.synchronizedList(mutableListOf())

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
                        setBufferAttribute(
                            "aFramePosition",
                            GlUtil.getNormalizedCoordinateBounds(),
                            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                        )
                    }
                private var height = 0

                override fun configure(
                    inputWidth: Int,
                    inputHeight: Int,
                ): Size {
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
                            val buf = ByteBuffer.allocateDirect(9 * 9 * 4).order(ByteOrder.nativeOrder())
                            GLES20.glReadPixels(x - 4, height - 1 - y - 4, 9, 9, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                            val sum = IntArray(4)
                            for (i in 0 until 81) for (c in 0 until 4) sum[c] += buf.get(i * 4 + c).toInt() and 0xFF
                            IntArray(4) { sum[it] / 81 }
                        }
                    samples += presentationTimeUs to means
                }

                override fun release() {
                    super.release()
                    program.delete()
                }
            }
    }

    /** Exports [item] through the app's own exporter and waits. */
    private fun export(
        item: EditedMediaItem,
        name: String,
    ) {
        val out = File(RenderHarness.outDir, "$name.mp4").apply { delete() }
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()
        Handler(Looper.getMainLooper()).post {
            try {
                RenderExporter(RenderHarness.context, 1080).start(
                    composition,
                    out,
                    EncodeSpec.forHeight(1080),
                    object : RenderExporter.Listener {
                        override fun onCompleted(result: ExportResult) = latch.countDown()

                        override fun onError(error2: ExportException) {
                            error.set(error2)
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                error.set(e)
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.MINUTES)) { "Probe export $name timed out" }
        error.get()?.let { throw AssertionError("Probe export $name failed", it) }
    }

    /** The mean of each point over the frames from [fromUs] on. */
    private fun Probe.means(fromUs: Long = 0): List<IntArray> {
        val late = synchronized(samples) { samples.filter { it.first >= fromUs } }
        check(late.isNotEmpty()) { "The probe saw no frames" }
        return points.indices.map { p -> IntArray(4) { c -> late.sumOf { it.second[p][c] } / late.size } }
    }

    private fun gpuWindowColour(file: File): IntArray {
        // The window centre of the reference geometry, and the key the preset ships.
        val probe = Probe(listOf(960 to 400))
        val key = RenderHarness.preset.frame!!.key
        val item =
            EditedMediaItem
                .Builder(
                    MediaItem
                        .Builder()
                        .setUri(Uri.fromFile(file))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration
                                .Builder()
                                .setEndPositionMs(2000)
                                .build(),
                        ).build(),
                ).setRemoveAudio(true)
                .setEffects(Effects(listOf(), listOf(probe, ChromaKeyEffect(key))))
                .build()
        export(item, "keycolour-${file.nameWithoutExtension}")
        return probe.means(500_000)[0].copyOf(3)
    }

    private fun rgbColor(c: IntArray) = ArgbColor(0xFF000000L or (c[0].toLong() shl 16) or (c[1].toLong() shl 8) or c[2].toLong())

    private fun chromaDistance(
        a: ArgbColor,
        b: ArgbColor,
    ): Double {
        val (cb1, cr1) = ChromaKeyEffect.chroma(a)
        val (cb2, cr2) = ChromaKeyEffect.chroma(b)
        return hypot((cb1 - cb2).toDouble(), (cr1 - cr2).toDouble())
    }

    private fun check(
        name: String,
        file: File,
        bt709: IntArray,
        bt601: IntArray,
    ): List<String> {
        val gpu = gpuWindowColour(file)
        val key = RenderHarness.preset.frame!!.key
        val to709 = RenderHarness.deltaE(gpu, bt709)
        val to601 = RenderHarness.deltaE(gpu, bt601)
        val keyDistance =
            min(chromaDistance(rgbColor(gpu), key.color), chromaDistance(rgbColor(gpu), ChromaKeyEffect.misdecodedAsBt601(key.color)))
        val matrix = if (to709 < to601) "BT.709" else "BT.601"
        report(
            "keycolour $name: GL sees ${gpu.toList()} at the window centre; ffmpeg BT.709 ${bt709.toList()} (dE %.1f), BT.601 ${bt601.toList()} (dE %.1f); GL decoded as %s; keyer distance %.3f (tolerance %.2f)"
                .format(Locale.ROOT, to709, to601, matrix, keyDistance, key.tolerance),
        )
        return buildList {
            if (min(to709, to601) > 4.0) {
                add("$name (${file.name}): GL colour ${gpu.toList()} matches neither ffmpeg decode of that file (dE $to709 / $to601)")
            }
            if (keyDistance > key.tolerance / 4) add("$name: keyer distance $keyDistance to the key")
        }
    }

    @Test
    fun reportsTheDecoderMatrixAndKeysTheWindow() {
        val failures = mutableListOf<String>()
        // Window colours of each file at (960, 400), from
        // ffmpeg -ss 1 -i <file> -vf scale=in_color_matrix=bt709|bt601:in_range=tv:out_range=pc,format=rgb24.
        failures += check("synthetic frame", File(RenderHarness.fixtureDir, "frame.mp4"), intArrayOf(0, 214, 30), intArrayOf(13, 250, 35))
        val real = File(checkNotNull(RenderHarness.context.getExternalFilesDir(null)), "realmedia/Frame-White-20s.mp4")
        if (real.isFile) {
            // These values are for the reference frame Frame-White-20s.mp4 only.
            val copy = File(RenderHarness.context.cacheDir, "realmedia-frame.mp4").also { real.copyTo(it, overwrite = true) }
            failures += check("real frame", copy, intArrayOf(0, 218, 34), intArrayOf(16, 253, 41))
        } else {
            report("keycolour real frame: not provided, skipped")
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }

    /** The keyer's alpha for [c] when it keys only the given forms of [key] (the shader's own formula). */
    private fun alpha(
        c: ArgbColor,
        key: KeySpec,
        forms: List<ArgbColor>,
    ): Double {
        val d = forms.minOf { chromaDistance(c, it) }
        val t = ((d - key.tolerance) / key.softness).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * The shader itself keys both forms: exact sRGB patches (a PNG, so no
     * YUV decode is involved) go through `ChromaKeyEffect`, and probes before
     * and after it read the colour it sees and the alpha it writes. Each alpha
     * must match the two-form formula for that colour. Two patches would come
     * out clearly different if either form were dropped: one sits inside the
     * soft band of the BT.601 form only, one inside the soft band of the
     * stored form only.
     */
    @Test
    fun shaderKeysBothFormsOfTheKey() {
        val key = RenderHarness.preset.frame!!.key
        val second = ChromaKeyEffect.misdecodedAsBt601(key.color)
        val patches =
            listOf(
                "stored key" to key.color,
                "BT.601 form" to second,
                "near the BT.601 form only" to ArgbColor.parse("#64FF00")!!,
                "near the stored form only" to ArgbColor.parse("#21BE68")!!,
                "far red" to ArgbColor.parse("#C81414")!!,
            )
        val w = 128
        val bitmap = Bitmap.createBitmap(w * patches.size, 128, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            patches.forEachIndexed { i, (_, c) ->
                drawRect((i * w).toFloat(), 0f, ((i + 1) * w).toFloat(), 128f, Paint().apply { color = c.argb.toInt() })
            }
        }
        val png = File(RenderHarness.context.cacheDir, "key-patches.png")
        FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val points = patches.indices.map { i -> (i * w + w / 2) to 64 }
        val before = Probe(points)
        val after = Probe(points)
        val item =
            EditedMediaItem
                .Builder(
                    MediaItem
                        .Builder()
                        .setUri(Uri.fromFile(png))
                        .setMimeType(MimeTypes.IMAGE_PNG)
                        .setImageDurationMs(500)
                        .build(),
                ).setFrameRate(10)
                .setEffects(Effects(listOf(), listOf(before, ChromaKeyEffect(key), after)))
                .build()
        export(item, "keycolour-patches")
        val seen = before.means()
        val written = after.means()
        val failures = mutableListOf<String>()
        patches.forEachIndexed { i, (name, _) ->
            val c = rgbColor(seen[i])
            val got = written[i][3] / 255.0
            val both = alpha(c, key, listOf(key.color, second))
            val storedOnly = alpha(c, key, listOf(key.color))
            val secondOnly = alpha(c, key, listOf(second))
            report(
                "keycolour patch $name: keyer sees ${seen[i].copyOf(
                    3,
                ).toList()}, alpha %.3f; both forms %.3f, stored only %.3f, BT.601 only %.3f"
                    .format(Locale.ROOT, got, both, storedOnly, secondOnly),
            )
            if (abs(got - both) > 0.06) failures += "$name: alpha %.3f, both forms give %.3f".format(Locale.ROOT, got, both)
            // The two discriminating patches must still discriminate as the device shows them.
            if (i == 2 && abs(storedOnly - both) < 0.1) failures += "$name no longer tells the forms apart"
            if (i == 3 && abs(secondOnly - both) < 0.5) failures += "$name no longer tells the forms apart"
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }
}
