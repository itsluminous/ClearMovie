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
 */
@UnstableApi
class PlacementEffect(
    private val placement: Placement,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
) : GlEffect {
    init {
        require(canvasWidth > 0 && canvasHeight > 0) { "Canvas size must be positive" }
    }

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program()

    private inner class Program : SinglePassShaderProgram(FRAGMENT) {
        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ): Size {
            val g = PlacementGeometry.of(placement, inputWidth, inputHeight, canvasWidth, canvasHeight)
            program.setFloatsUniform("uContent", g.content.toFloats())
            program.setFloatsUniform("uSource", g.source.toFloats())
            program.setFloatsUniform("uWindow", g.window.toFloats())
            program.setFloatsUniform("uBackground", g.background.toFloats())
            program.setFloatsUniform("uBorder", floatArrayOf(g.borderX.toFloat(), g.borderY.toFloat()))
            program.setFloatUniform("uMode", g.mode.code.toFloat())
            program.setFloatUniform("uTurns", g.quarterTurns.toFloat())
            val blurStep = 0.035f
            program.setFloatsUniform("uBlurStep", floatArrayOf(blurStep, blurStep * canvasWidth / canvasHeight))
            return Size(canvasWidth, canvasHeight)
        }

        override fun setUniforms(presentationTimeUs: Long) = Unit
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
uniform vec4 uBackground;
uniform vec2 uBorder;
uniform vec2 uBlurStep;
uniform float uMode;
uniform float uTurns;
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
  return texture2D(uTexSampler, vec2(s.x, 1.0 - s.y));
}

vec4 blurred(vec2 q) {
  vec4 sum = vec4(0.0);
  float n = 0.0;
  for (int i = -3; i <= 3; i++) {
    for (int j = -3; j <= 3; j++) {
      sum += samplePicture(q + vec2(float(i), float(j)) * uBlurStep / 3.0);
      n += 1.0;
    }
  }
  vec4 c = sum / n;
  return vec4(c.rgb * 0.8, 1.0);
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
      vec2 q = uBackground.xy + (p - uWindow.xy) / uWindow.zw * uBackground.zw;
      gl_FragColor = blurred(q);
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
