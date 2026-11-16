package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.itsluminous.clearmovie.render.plan.Placement

/**
 * Draws one picture onto a transparent canvas-sized frame (ARCHITECTURE
 * section 7, `Placement`): full frame, into the frame window by fit (cover
 * and crop, fit with bars, fit over a blurred copy), picture in picture with
 * a white border, or stretched into a caption box. Every layer of the
 * composition is canvas-sized, so the compositor only has to stack them.
 *
 * [fromBitmap] marks pictures Media3 loads as Android bitmaps (photos,
 * captions, cards, held frames, fills). Those reach GL with premultiplied
 * alpha, while the compositor blends straight alpha, so the shader divides
 * the colour by alpha again; otherwise a translucent caption box would be
 * darkened twice. Videos are already straight (and opaque).
 *
 * Equal effects compare equal, so Media3 keeps its shader programs between
 * consecutive items with the same placement instead of rebuilding them.
 *
 * Fit with blur (cm-timeline): the background is the picture's cover crop
 * blurred by a separable Gaussian at an eighth of the window's size, in two
 * passes of their own ([BlurPasses]) before the final one, which scales it
 * up (bilinear, so smoother still). The earlier single pass of 49 spaced
 * taps showed ghost copies of sharp edges.
 */
@UnstableApi
class PlacementEffect(
    private val placement: Placement,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val fromBitmap: Boolean = false,
) : GlEffect {
    init {
        require(canvasWidth > 0 && canvasHeight > 0) { "Canvas size must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is PlacementEffect &&
            other.placement == placement &&
            other.canvasWidth == canvasWidth &&
            other.canvasHeight == canvasHeight &&
            other.fromBitmap == fromBitmap

    override fun hashCode(): Int = listOf(placement, canvasWidth, canvasHeight, fromBitmap).hashCode()

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program()

    private inner class Program : SinglePassShaderProgram(FRAGMENT) {
        private var blur: BlurPasses? = null
        private var geometry: PlacementGeometry? = null

        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ): Size {
            val g = PlacementGeometry.of(placement, inputWidth, inputHeight, canvasWidth, canvasHeight)
            program.setFloatsUniform("uContent", g.content.toFloats())
            program.setFloatsUniform("uSource", g.source.toFloats())
            program.setFloatsUniform("uWindow", g.window.toFloats())
            program.setFloatsUniform("uBorder", floatArrayOf(g.borderX.toFloat(), g.borderY.toFloat()))
            program.setFloatUniform("uMode", g.mode.code.toFloat())
            program.setFloatUniform("uTurns", g.quarterTurns.toFloat())
            program.setFloatUniform("uUnpremultiply", if (fromBitmap) 1f else 0f)
            geometry = g
            if (g.mode == PlacementGeometry.Mode.BLUR) {
                val w = (g.window.w * canvasWidth).toInt()
                val h = (g.window.h * canvasHeight).toInt()
                blur?.release()
                blur = BlurPasses(BlurPasses.size(w), BlurPasses.size(h))
            }
            return Size(canvasWidth, canvasHeight)
        }

        override fun beforeDraw(
            inputTexId: Int,
            presentationTimeUs: Long,
        ) {
            val g = geometry ?: return
            val passes = blur ?: return
            passes.draw(inputTexId, g, fromBitmap)
        }

        override fun setUniforms(presentationTimeUs: Long) {
            // The blurred background, on texture unit 1 (a 1x1 stand-in when there is none).
            blur?.let { program.setSamplerTexIdUniform("uBlurTex", it.resultTexId, 1) }
                ?: program.setSamplerTexIdUniform("uBlurTex", placeholder(), 1)
        }

        private var placeholderTex = -1

        private fun placeholder(): Int {
            if (placeholderTex < 0) {
                placeholderTex =
                    androidx.media3.common.util.GlUtil
                        .createTexture(1, 1, false)
            }
            return placeholderTex
        }

        override fun releaseResources() {
            blur?.release()
            blur = null
            if (placeholderTex >= 0) {
                androidx.media3.common.util.GlUtil
                    .deleteTexture(placeholderTex)
            }
            placeholderTex = -1
        }
    }

    private companion object {
        fun com.itsluminous.clearmovie.domain.model.NormRect.toFloats() = floatArrayOf(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())

        // p is canvas space, y down. Pictures are sampled in their own space,
        // y down, after the user's rotation (uTurns quarter turns clockwise).
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec4 uContent;
uniform vec4 uSource;
uniform vec4 uWindow;
uniform vec2 uBorder;
uniform sampler2D uBlurTex;
uniform float uMode;
uniform float uTurns;
uniform float uUnpremultiply;
varying vec2 vUv;

bool inside(vec2 p, vec4 r) {
  return p.x >= r.x && p.y >= r.y && p.x < r.x + r.z && p.y < r.y + r.w;
}

vec4 samplePicture(vec2 q) {
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

void main() {
  vec2 p = vec2(vUv.x, 1.0 - vUv.y);
  if (inside(p, uContent)) {
    vec2 q = uSource.xy + (p - uContent.xy) / uContent.zw * uSource.zw;
    gl_FragColor = samplePicture(q);
    return;
  }
  vec4 framed = vec4(uContent.xy - uBorder, uContent.zw + 2.0 * uBorder);
  if (uBorder.x > 0.0 && inside(p, framed)) {
    gl_FragColor = vec4(1.0);
    return;
  }
  if (inside(p, uWindow)) {
    if (uMode > 1.5) {
      // The blurred cover crop, drawn by the passes before this one, laid over the window.
      vec2 w = (p - uWindow.xy) / uWindow.zw;
      vec4 c = texture2D(uBlurTex, vec2(w.x, 1.0 - w.y));
      gl_FragColor = vec4(c.rgb * 0.8, 1.0);
      return;
    }
    if (uMode > 0.5) {
      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
      return;
    }
  }
  gl_FragColor = vec4(0.0);
}
"""
    }
}
