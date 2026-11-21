package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeyMath
import com.itsluminous.clearmovie.domain.model.KeySpec

/**
 * Keys out a green-screen colour (ARCHITECTURE section 7, OVERLAY rows):
 * alpha comes from the distance to [KeySpec.color] in the BT.709 Cb/Cr plane,
 * fully transparent within [KeySpec.tolerance] and fully opaque beyond
 * tolerance + [KeySpec.softness]. In the soft band the green spill is pulled
 * down to the larger of red and blue, so edges carry no green fringe; a key
 * of another colour the user picked (cm-frame-area) has no such step, as
 * its spill is not green. [KeyMath] holds the same arithmetic for the CPU.
 *
 * The key colour is stored as true BT.709 RGB (cm-storage measures it with
 * the stream's own matrix), but the GPU's video sampler does not always
 * honour the BT.709 tag: the emulator's decodes it as BT.601, which moves
 * the window green by Delta E 16 (KeyColourSpaceTest). A calibrated export
 * corrects that before keying ([Bt709Correction]). Each pixel is still
 * compared with both forms of the key, as decoded by either matrix
 * ([misdecodedAsBt601]), and the nearer one counts, so a device the
 * calibration could not read keys correctly too; the cost below applies on
 * every device. The two forms are 0.057
 * apart in chroma, inside the tolerance, so the keyed area grows by about
 * 14%, and on any one device one of the two forms is spurious:
 * - On a decoder that honours BT.709, the added form costs at most 0.023
 *   alpha (#7EFF00, which the stored key alone leaves at 0.9995) for
 *   colours the stored key leaves opaque;
 *   colours already in the soft band lose more (#76FF03: 0.86 to 0.75).
 * - On a decoder that uses BT.601, the stored form is the spurious one, as
 *   it was before this form was added: mid and mint greens near the soft
 *   band are partly keyed there (#4CAF50 alpha 0.55, #69F0AE 0.39).
 * Keying one form chosen by measuring the decoder would remove both costs.
 *
 * Only chroma is compared, not brightness: a dark green in the frame's own
 * artwork close in hue to the key is keyed too. A preset frame is a flat key
 * colour around artwork in other colours, which the bind-time key check
 * (cm-storage) measures.
 */
@UnstableApi
class ChromaKeyEffect(
    private val key: KeySpec,
) : GlEffect {
    init {
        require(key.tolerance in 0.0..1.0 && key.softness in 0.0..1.0) { "Key tolerance and softness must be in 0..1" }
    }

    override fun equals(other: Any?): Boolean = other is ChromaKeyEffect && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program()

    private inner class Program : SinglePassShaderProgram(FRAGMENT) {
        init {
            val (cb, cr) = chroma(key.color)
            val (cb2, cr2) = chroma(misdecodedAsBt601(key.color))
            program.setFloatsUniform("uKey", floatArrayOf(cb, cr))
            program.setFloatsUniform("uKey601", floatArrayOf(cb2, cr2))
            program.setFloatUniform("uTolerance", key.tolerance.toFloat())
            program.setFloatUniform("uSoftness", key.softness.toFloat().coerceAtLeast(1e-4f))
            program.setFloatUniform("uGreenSpill", if (KeyMath.suppressesGreenSpill(key.color)) 1f else 0f)
        }

        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ) = Size(inputWidth, inputHeight)

        override fun setUniforms(presentationTimeUs: Long) = Unit
    }

    companion object {
        /** BT.709 (Cb, Cr) of [color], each in -0.5..0.5. The shader uses the same formula ([KeyMath.chroma]). */
        fun chroma(color: ArgbColor): Pair<Float, Float> {
            val (cb, cr) = KeyMath.chroma(color.argb.toInt())
            return cb.toFloat() to cr.toFloat()
        }

        /** [KeyMath.misdecodedAsBt601]. */
        fun misdecodedAsBt601(color: ArgbColor): ArgbColor = KeyMath.misdecodedAsBt601(color)

        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uKey;
uniform vec2 uKey601;
uniform float uTolerance;
uniform float uSoftness;
uniform float uGreenSpill;
varying vec2 vUv;

void main() {
  vec4 c = texture2D(uTexSampler, vUv);
  float y = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
  vec2 cc = vec2((c.b - y) / 1.8556, (c.r - y) / 1.5748);
  float d = min(distance(cc, uKey), distance(cc, uKey601));
  float keep = smoothstep(uTolerance, uTolerance + uSoftness, d);
  vec3 rgb = c.rgb;
  float limit = max(rgb.r, rgb.b);
  if (uGreenSpill > 0.5 && rgb.g > limit) {
    rgb.g = mix(limit, rgb.g, keep);
  }
  gl_FragColor = vec4(rgb, c.a * keep);
}
"""
    }
}
