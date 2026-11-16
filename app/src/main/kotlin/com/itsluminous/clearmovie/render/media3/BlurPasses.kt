package com.itsluminous.clearmovie.render.media3

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * The fit-with-blur background, at an eighth of the frame window's size, in
 * three passes: the first shrinks the picture's cover crop (rotated as the
 * item is) to the small size, averaging each small pixel's 8x8 picture
 * pixels; the second and third are the two halves of a separable Gaussian,
 * across and then down. The taps are one small pixel apart (taps further
 * apart turn a sharp edge into a staircase of copies), [TAPS] each side with
 * a sigma of [SIGMA] small pixels, about 2.5% of the window's width; the
 * final pass scales the result up with bilinear filtering.
 *
 * Each shader is one short, flat loop of texture reads. A single shader doing
 * the shrink inside the blur (31 x 16 reads a pixel, through nested function
 * calls) drew only black on CI's emulator GL (SwiftShader 4.0), though it
 * worked on newer GL. Must be made, drawn and released on the GL thread.
 */
@UnstableApi
internal class BlurPasses(
    private val width: Int,
    private val height: Int,
) {
    private val shrink: GlProgram = program(SHRINK)
    private val gauss: GlProgram = program(GAUSS)
    private val cells = GlUtil.createTexture(width, height, false)
    private val cellsFbo = GlUtil.createFboForTexture(cells)
    private val across = GlUtil.createTexture(width, height, false)
    private val acrossFbo = GlUtil.createFboForTexture(across)
    private val down = GlUtil.createTexture(width, height, false)
    private val downFbo = GlUtil.createFboForTexture(down)

    /** The finished background. */
    val resultTexId: Int get() = down

    /** Draws the passes from the picture [inputTexId], then puts the caller's framebuffer and viewport back. */
    fun draw(
        inputTexId: Int,
        g: PlacementGeometry,
        fromBitmap: Boolean,
    ) {
        val fbo = IntArray(1)
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

        // Shrink: the cover crop of the picture, turned, one small pixel per 8x8 of it.
        GlUtil.focusFramebufferUsingCurrentContext(cellsFbo, width, height)
        shrink.use()
        shrink.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        shrink.setFloatsUniform(
            "uBackground",
            floatArrayOf(g.background.x.toFloat(), g.background.y.toFloat(), g.background.w.toFloat(), g.background.h.toFloat()),
        )
        shrink.setFloatUniform("uTurns", g.quarterTurns.toFloat())
        shrink.setFloatUniform("uUnpremultiply", if (fromBitmap) 1f else 0f)
        // A quarter of one small pixel, across and down, in the small texture's own size.
        shrink.setFloatsUniform("uCellStep", floatArrayOf(0.25f / width, 0.25f / height))
        shrink.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        gauss.use()
        // Across.
        GlUtil.focusFramebufferUsingCurrentContext(acrossFbo, width, height)
        gauss.setSamplerTexIdUniform("uTexSampler", cells, 0)
        gauss.setFloatsUniform("uStep", floatArrayOf(1f / width, 0f))
        gauss.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Down.
        GlUtil.focusFramebufferUsingCurrentContext(downFbo, width, height)
        gauss.setSamplerTexIdUniform("uTexSampler", across, 0)
        gauss.setFloatsUniform("uStep", floatArrayOf(0f, 1f / height))
        gauss.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
        GlUtil.focusFramebufferUsingCurrentContext(fbo[0], viewport[2], viewport[3])
    }

    fun release() {
        runCatching { GlUtil.deleteFbo(cellsFbo) }
        runCatching { GlUtil.deleteFbo(acrossFbo) }
        runCatching { GlUtil.deleteFbo(downFbo) }
        runCatching { GlUtil.deleteTexture(cells) }
        runCatching { GlUtil.deleteTexture(across) }
        runCatching { GlUtil.deleteTexture(down) }
        runCatching { shrink.delete() }
        runCatching { gauss.delete() }
    }

    companion object {
        /** Taps each side of the centre, and the Gaussian's sigma, in small pixels (6 small = 48 full pixels). */
        const val TAPS = 15
        const val SIGMA = 6

        /** A side at an eighth of the window, never under 8 pixels. */
        fun size(windowPx: Int): Int = (windowPx / 8).coerceAtLeast(8)

        private fun program(fragment: String): GlProgram =
            GlProgram(SinglePassShaderProgram.VERTEX_SHADER, fragment).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

        // vUv is the small texture's own space, (0, 0) bottom left; it maps into the
        // background crop of the picture (y down), turned as the item is. Each small pixel
        // stands for about 8x8 picture pixels: a 4x4 grid of bilinear reads (each of 2x2
        // pixels) covers all of them, so no thin line is skipped and nothing shimmers.
        private const val SHRINK = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec4 uBackground;
uniform float uTurns;
uniform float uUnpremultiply;
uniform vec2 uCellStep;
varying vec2 vUv;

void main() {
  vec2 p = vec2(vUv.x, 1.0 - vUv.y);
  vec2 centre = uBackground.xy + p * uBackground.zw;
  vec2 d = uBackground.zw * uCellStep;
  vec4 sum = vec4(0.0);
  for (int i = 0; i < 4; i++) {
    for (int j = 0; j < 4; j++) {
      vec2 q = clamp(centre + vec2(float(i) - 1.5, float(j) - 1.5) * d, 0.0, 1.0);
      vec2 s = q;
      if (uTurns > 0.5 && uTurns < 1.5) {
        s = vec2(q.y, 1.0 - q.x);
      } else if (uTurns > 1.5 && uTurns < 2.5) {
        s = vec2(1.0 - q.x, 1.0 - q.y);
      } else if (uTurns > 2.5) {
        s = vec2(1.0 - q.y, q.x);
      }
      vec4 c = texture2D(uTexSampler, vec2(s.x, 1.0 - s.y));
      if (uUnpremultiply > 0.5 && c.a > 0.0) {
        c.rgb = c.rgb / c.a;
      }
      sum += c;
    }
  }
  gl_FragColor = vec4(sum.rgb / 16.0, 1.0);
}
"""

        // One half of the Gaussian: taps one small pixel apart along uStep.
        private const val GAUSS = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uStep;
varying vec2 vUv;

void main() {
  vec3 sum = vec3(0.0);
  float total = 0.0;
  for (int i = -$TAPS; i <= $TAPS; i++) {
    float w = exp(-float(i * i) / (2.0 * float($SIGMA * $SIGMA)));
    sum += texture2D(uTexSampler, clamp(vUv + float(i) * uStep, 0.0, 1.0)).rgb * w;
    total += w;
  }
  gl_FragColor = vec4(sum / total, 1.0);
}
"""
    }
}
