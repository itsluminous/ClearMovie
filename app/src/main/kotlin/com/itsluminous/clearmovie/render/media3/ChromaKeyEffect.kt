package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeySpec

/**
 * Keys out a green-screen colour (ARCHITECTURE section 7, OVERLAY rows):
 * alpha comes from the distance to [KeySpec.color] in the BT.709 Cb/Cr plane,
 * fully transparent within [KeySpec.tolerance] and fully opaque beyond
 * tolerance + [KeySpec.softness]. In the soft band the green spill is pulled
 * down to the larger of red and blue, so edges carry no green fringe.
 */
@UnstableApi
class ChromaKeyEffect(
    private val key: KeySpec,
) : GlEffect {
    init {
        require(key.tolerance in 0.0..1.0 && key.softness in 0.0..1.0) { "Key tolerance and softness must be in 0..1" }
    }

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program()

    private inner class Program : SinglePassShaderProgram(FRAGMENT) {
        init {
            val (cb, cr) = chroma(key.color)
            program.setFloatsUniform("uKey", floatArrayOf(cb, cr))
            program.setFloatUniform("uTolerance", key.tolerance.toFloat())
            program.setFloatUniform("uSoftness", key.softness.toFloat().coerceAtLeast(1e-4f))
        }

        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ) = Size(inputWidth, inputHeight)

        override fun setUniforms(presentationTimeUs: Long) = Unit
    }

    companion object {
        /** BT.709 (Cb, Cr) of [color], each in -0.5..0.5. The shader uses the same formula. */
        fun chroma(color: ArgbColor): Pair<Float, Float> {
            val r = ((color.argb shr 16) and 0xFF) / 255f
            val g = ((color.argb shr 8) and 0xFF) / 255f
            val b = (color.argb and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            return (b - y) / 1.8556f to (r - y) / 1.5748f
        }

        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uKey;
uniform float uTolerance;
uniform float uSoftness;
varying vec2 vUv;

void main() {
  vec4 c = texture2D(uTexSampler, vUv);
  float y = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
  vec2 cc = vec2((c.b - y) / 1.8556, (c.r - y) / 1.5748);
  float d = distance(cc, uKey);
  float keep = smoothstep(uTolerance, uTolerance + uSoftness, d);
  vec3 rgb = c.rgb;
  float limit = max(rgb.r, rgb.b);
  if (rgb.g > limit) {
    rgb.g = mix(limit, rgb.g, keep);
  }
  gl_FragColor = vec4(rgb, c.a * keep);
}
"""
    }
}
