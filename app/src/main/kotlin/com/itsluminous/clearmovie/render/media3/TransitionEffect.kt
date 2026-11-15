package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.itsluminous.clearmovie.render.plan.AlphaRamp

/**
 * The basic MAIN transition (ARCHITECTURE section 7.1): scales a picture's
 * opacity by its [ramps] (their product), evaluated in CLIP time. The A/B
 * lanes overlap during a transition and the upper lane carries this effect,
 * so the compositor blends the two clips. cm-effects adds the other
 * transition shapes on the same clip-time basis.
 *
 * Frame timestamps reach the effect in the item's sequence time;
 * [itemStartUs] is where the item starts in its sequence and [clipOffsetUs]
 * where it starts inside its clip, so `clip time = pts - itemStartUs +
 * clipOffsetUs`.
 */
@UnstableApi
class TransitionEffect(
    private val ramps: List<AlphaRamp>,
    private val itemStartUs: Long,
    private val clipOffsetUs: Long,
) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program()

    fun alphaAt(presentationTimeUs: Long): Float {
        val clipUs = presentationTimeUs - itemStartUs + clipOffsetUs
        return ramps.fold(1f) { acc, ramp -> acc * ramp.alphaAt(clipUs) }.coerceIn(0f, 1f)
    }

    private inner class Program : SinglePassShaderProgram(FRAGMENT) {
        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ) = Size(inputWidth, inputHeight)

        override fun setUniforms(presentationTimeUs: Long) {
            program.setFloatUniform("uAlpha", alphaAt(presentationTimeUs))
        }
    }

    private companion object {
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uAlpha;
varying vec2 vUv;
void main() {
  vec4 c = texture2D(uTexSampler, vUv);
  gl_FragColor = vec4(c.rgb, c.a * uAlpha);
}
"""
    }
}
