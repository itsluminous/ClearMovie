package com.itsluminous.clearmovie.render.media3

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * The fit-with-blur background in two passes of a separable Gaussian, at an
 * eighth of the frame window's size: the first samples the picture's cover
 * crop (rotated as the item is) and blurs across, the second blurs down.
 * The taps are one small pixel apart (taps further apart turn a sharp edge
 * into a staircase of copies), [TAPS] of them with a sigma of [SIGMA] small
 * pixels, about 2.5% of the window's width; the final pass scales the result
 * up with bilinear filtering. Must be made, drawn and released on the GL
 * thread.
 */
@UnstableApi
internal class BlurPasses(
    private val width: Int,
    private val height: Int,
) {
    private val program: GlProgram =
        GlProgram(SinglePassShaderProgram.VERTEX_SHADER, FRAGMENT).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    private val across = GlUtil.createTexture(width, height, false)
    private val acrossFbo = GlUtil.createFboForTexture(across)
    private val down = GlUtil.createTexture(width, height, false)
    private val downFbo = GlUtil.createFboForTexture(down)

    /** The finished background. */
    val resultTexId: Int get() = down

    /** Draws both passes from the picture [inputTexId], then puts the caller's framebuffer and viewport back. */
    fun draw(
        inputTexId: Int,
        g: PlacementGeometry,
        fromBitmap: Boolean,
    ) {
        val fbo = IntArray(1)
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
        program.use()
        program.setFloatsUniform(
            "uBackground",
            floatArrayOf(g.background.x.toFloat(), g.background.y.toFloat(), g.background.w.toFloat(), g.background.h.toFloat()),
        )
        program.setFloatUniform("uTurns", g.quarterTurns.toFloat())
        program.setFloatUniform("uUnpremultiply", if (fromBitmap) 1f else 0f)
        // Across, from the picture.
        GlUtil.focusFramebufferUsingCurrentContext(acrossFbo, width, height)
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatUniform("uFirst", 1f)
        program.setFloatsUniform("uStep", floatArrayOf(1f / width, 0f))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Down, from the first pass.
        GlUtil.focusFramebufferUsingCurrentContext(downFbo, width, height)
        program.setSamplerTexIdUniform("uTexSampler", across, 0)
        program.setFloatUniform("uFirst", 0f)
        program.setFloatsUniform("uStep", floatArrayOf(0f, 1f / height))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
        GlUtil.focusFramebufferUsingCurrentContext(fbo[0], viewport[2], viewport[3])
    }

    fun release() {
        runCatching { GlUtil.deleteFbo(acrossFbo) }
        runCatching { GlUtil.deleteFbo(downFbo) }
        runCatching { GlUtil.deleteTexture(across) }
        runCatching { GlUtil.deleteTexture(down) }
        runCatching { program.delete() }
    }

    companion object {
        /** Taps each side of the centre, and the Gaussian's sigma, in small pixels (6 small = 48 full pixels). */
        const val TAPS = 15
        const val SIGMA = 6

        /** A side at an eighth of the window, never under 8 pixels. */
        fun size(windowPx: Int): Int = (windowPx / 8).coerceAtLeast(8)

        // vUv is the small texture's own space, (0, 0) bottom left. On the first pass it maps
        // into the background crop of the picture (y down), turned as the item is.
        private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec4 uBackground;
uniform vec2 uStep;
uniform float uFirst;
uniform float uTurns;
uniform float uUnpremultiply;
varying vec2 vUv;

vec4 picture(vec2 q) {
  q = clamp(q, 0.0, 1.0);
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
  return c;
}

vec4 tap(vec2 uv) {
  if (uFirst > 0.5) {
    vec2 p = vec2(uv.x, 1.0 - uv.y);
    return picture(uBackground.xy + p * uBackground.zw);
  }
  return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
}

void main() {
  vec4 sum = vec4(0.0);
  float total = 0.0;
  for (int i = -$TAPS; i <= $TAPS; i++) {
    float w = exp(-float(i * i) / (2.0 * float($SIGMA * $SIGMA)));
    sum += tap(vUv + float(i) * uStep) * w;
    total += w;
  }
  gl_FragColor = vec4(sum.rgb / total, 1.0);
}
"""
    }
}
