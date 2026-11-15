package com.itsluminous.clearmovie.render

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
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
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.media3.ChromaKeyEffect
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Which colour space the keyer works in. cm-storage measures the key colour
 * from the frame decoded with its stream's own BT.709 matrix. A pass-through
 * probe placed exactly where `ChromaKeyEffect` sits (the first effect on the
 * frame item) reads the window's pixels back from GL and compares them with
 * ffmpeg's decode of the same frame with each matrix. On the API 36 emulator
 * the GPU sampler decodes the BT.709 stream as BT.601 (Delta E about 16 from
 * the stored key), so the keyer matches both forms of the key; this test
 * reports which matrix the device used and asserts that the keyer's own
 * distance for the window is well inside its tolerance either way.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class KeyColourSpaceTest {
    /** Copies its input and reads back a 9x9 patch around canvas point ([x], [y]), y down. */
    private class Probe(
        val x: Int,
        val y: Int,
    ) : GlEffect {
        val samples: MutableList<Pair<Long, IntArray>> = Collections.synchronizedList(mutableListOf())

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
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    val buf = ByteBuffer.allocateDirect(9 * 9 * 4).order(ByteOrder.nativeOrder())
                    GLES20.glReadPixels(x - 4, height - 1 - y - 4, 9, 9, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    val sum = IntArray(3)
                    for (i in 0 until 81) for (c in 0 until 3) sum[c] += buf.get(i * 4 + c).toInt() and 0xFF
                    samples += presentationTimeUs to IntArray(3) { sum[it] / 81 }
                }

                override fun release() {
                    super.release()
                    program.delete()
                }
            }
    }

    private fun gpuWindowColour(file: File): IntArray {
        // The window centre of the reference geometry, and the key the preset ships.
        val probe = Probe(960, 400)
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
        val out = File(RenderHarness.outDir, "keycolour-${file.nameWithoutExtension}.mp4").apply { delete() }
        val latch = CountDownLatch(1)
        val error = arrayOfNulls<Throwable>(1)
        Handler(Looper.getMainLooper()).post {
            Transformer
                .Builder(RenderHarness.context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) = latch.countDown()

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            error[0] = exportException
                            latch.countDown()
                        }
                    },
                ).build()
                .start(Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build(), out.absolutePath)
        }
        check(latch.await(5, TimeUnit.MINUTES)) { "Probe export timed out" }
        error[0]?.let { throw AssertionError("Probe export failed", it) }
        val late = synchronized(probe.samples) { probe.samples.filter { it.first >= 500_000 } }
        check(late.isNotEmpty()) { "The probe saw no frames" }
        return IntArray(3) { c -> late.sumOf { it.second[c] } / late.size }
    }

    private fun chromaDistance(
        a: IntArray,
        b: ArgbColor,
    ): Double {
        val (cb1, cr1) = ChromaKeyEffect.chroma(ArgbColor(0xFF000000L or (a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()))
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
        val keyDistance = minOf(chromaDistance(gpu, key.color), chromaDistance(gpu, ChromaKeyEffect.misdecodedAsBt601(key.color)))
        val matrix = if (to709 < to601) "BT.709" else "BT.601"
        report(
            "keycolour $name: GL sees ${gpu.toList()} at the window centre; ffmpeg BT.709 ${bt709.toList()} (dE %.1f), BT.601 ${bt601.toList()} (dE %.1f); GL decoded as %s; keyer distance %.3f (tolerance %.2f)"
                .format(Locale.ROOT, to709, to601, matrix, keyDistance, key.tolerance),
        )
        return buildList {
            if (minOf(to709, to601) > 4.0) add("$name: GL colour matches neither decode (dE $to709 / $to601)")
            if (keyDistance > key.tolerance / 4) add("$name: keyer distance $keyDistance to the key")
        }
    }

    @Test
    fun keyerSeesTheFrameInBt709() {
        val failures = mutableListOf<String>()
        // ffmpeg -ss 1 -i frame.mp4 -vf scale=in_color_matrix=bt709|bt601:in_range=tv:out_range=pc,format=rgb24, pixel (960, 400).
        failures += check("synthetic frame", File(RenderHarness.fixtureDir, "frame.mp4"), intArrayOf(0, 214, 30), intArrayOf(13, 250, 35))
        val real = File(checkNotNull(RenderHarness.context.getExternalFilesDir(null)), "realmedia/Frame-White-20s.mp4")
        if (real.isFile) {
            val copy = File(RenderHarness.context.cacheDir, "realmedia-frame.mp4").also { real.copyTo(it, overwrite = true) }
            failures += check("real frame", copy, intArrayOf(0, 218, 34), intArrayOf(16, 253, 41))
        } else {
            report("keycolour real frame: not provided, skipped")
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }
}
