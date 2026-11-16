package com.itsluminous.clearmovie.render.preview

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.itsluminous.clearmovie.render.media3.SinglePassShaderProgram

/**
 * Multiplies colour by alpha. The keyer leaves straight alpha (what Media3's
 * compositor wants); a TextureView blends premultiplied colour, so without
 * this the keyed-out green would still tint the picture under the frame.
 * The shader draws a full-screen quad, so every output pixel is written.
 */
@UnstableApi
internal class PremultiplyEffect : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program()

    private class Program : SinglePassShaderProgram(FRAGMENT) {
        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ) = Size(inputWidth, inputHeight)

        override fun setUniforms(presentationTimeUs: Long) = Unit
    }

    private companion object {
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vUv;

void main() {
  vec4 c = texture2D(uTexSampler, vUv);
  gl_FragColor = vec4(c.rgb * c.a, c.a);
}
"""
    }
}
