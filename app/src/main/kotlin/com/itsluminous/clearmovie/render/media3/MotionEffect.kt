package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.itsluminous.clearmovie.render.plan.Mask
import com.itsluminous.clearmovie.render.plan.MotionFrame
import com.itsluminous.clearmovie.render.plan.MotionMath
import com.itsluminous.clearmovie.render.plan.PictureMotion

/**
 * Transitions and animations in the export (ARCHITECTURE section 7.1,
 * cm-effects): draws a placed, canvas-sized picture as [MotionMath] says for
 * the frame's moment, scaled about its motion rect's centre and moved, tinted
 * (fade through black or white), faded, and cut to a wipe edge or a circle.
 * It runs after [PlacementEffect], so its input is already on the canvas.
 *
 * The shader takes only numbers worked out here from the plan (each one
 * finite and clamped), never text, so a project file cannot change what the
 * shader does. It uses nothing beyond GLSL ES 1.0, so SwiftShader (the CI
 * emulators' GPU) draws it like a phone.
 *
 * Frame timestamps reach the effect in the item's sequence time;
 * [itemStartUs] is where the item starts in its sequence and [clipOffsetUs]
 * where it starts inside its clip, so `clip time = pts - itemStartUs +
 * clipOffsetUs`.
 */
@UnstableApi
class MotionEffect(
    private val motion: PictureMotion,
    private val itemStartUs: Long,
    private val clipOffsetUs: Long,
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

    /** What is drawn for the frame at [presentationTimeUs]. */
    fun frameAt(presentationTimeUs: Long): MotionFrame =
        MotionMath.frameAt(motion, presentationTimeUs - itemStartUs + clipOffsetUs, canvasWidth.toDouble() / canvasHeight)

    private inner class Program : SinglePassShaderProgram(FRAGMENT) {
        override fun outputSize(
            inputWidth: Int,
            inputHeight: Int,
        ) = Size(inputWidth, inputHeight)

        override fun setUniforms(presentationTimeUs: Long) {
            val f = frameAt(presentationTimeUs)
            val r = motion.rect
            program.setFloatsUniform("uRect", floatArrayOf(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat()))
            program.setFloatUniform("uScale", f.scale.safe(1f).coerceIn(MIN_SCALE, MAX_SCALE))
            program.setFloatsUniform("uOffset", floatArrayOf(f.dx.safe(0f).coerceIn(-2f, 2f), f.dy.safe(0f).coerceIn(-2f, 2f)))
            program.setFloatUniform("uAlpha", f.alpha.safe(1f).coerceIn(0f, 1f))
            val tint = f.tint.argb
            program.setFloatsUniform(
                "uTint",
                floatArrayOf(((tint shr 16) and 0xFF) / 255f, ((tint shr 8) and 0xFF) / 255f, (tint and 0xFF) / 255f),
            )
            program.setFloatUniform("uTintAmount", f.tintAmount.safe(0f).coerceIn(0f, 1f))
            program.setFloatUniform("uClip", if (f.clipRect != null) 1f else 0f)
            program.setFloatUniform("uAspect", canvasWidth.toFloat() / canvasHeight)
            program.setFloatsUniform("uPixel", floatArrayOf(1f / canvasWidth, 1f / canvasHeight))
            when (val m = f.mask) {
                Mask.None -> {
                    program.setFloatUniform("uMaskType", 0f)
                    program.setFloatsUniform("uMask", floatArrayOf(0f, 0f, 0f, 0f))
                }

                is Mask.Wipe -> {
                    program.setFloatUniform("uMaskType", 1f)
                    program.setFloatsUniform(
                        "uMask",
                        floatArrayOf(if (m.vertical) 1f else 0f, m.edge.safe(0f).coerceIn(-1f, 2f), if (m.keepAfter) 1f else 0f, 0f),
                    )
                }

                is Mask.Circle -> {
                    program.setFloatUniform("uMaskType", 2f)
                    program.setFloatsUniform(
                        "uMask",
                        floatArrayOf(m.cx.safe(0.5f), m.cy.safe(0.5f), m.radius.safe(0f).coerceIn(0f, 4f), if (m.inside) 1f else 0f),
                    )
                }
            }
        }
    }

    private companion object {
        const val MIN_SCALE = 0.01f
        const val MAX_SCALE = 10f

        fun Float.safe(fallback: Float) = if (isFinite()) this else fallback

        // p is the output point in canvas space, y down; q the input point it shows.
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec4 uRect;
uniform float uScale;
uniform vec2 uOffset;
uniform float uAlpha;
uniform vec3 uTint;
uniform float uTintAmount;
uniform float uClip;
uniform float uMaskType;
uniform vec4 uMask;
uniform float uAspect;
uniform vec2 uPixel;
varying vec2 vUv;

void main() {
  vec2 p = vec2(vUv.x, 1.0 - vUv.y);
  vec2 c = uRect.xy + 0.5 * uRect.zw;
  vec2 q = c + (p - c - uOffset) / uScale;
  float keep = 1.0;
  if (uClip > 0.5 && (p.x < uRect.x || p.y < uRect.y || p.x >= uRect.x + uRect.z || p.y >= uRect.y + uRect.w)) {
    keep = 0.0;
  }
  if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) {
    keep = 0.0;
  }
  vec4 col = texture2D(uTexSampler, vec2(clamp(q.x, 0.0, 1.0), 1.0 - clamp(q.y, 0.0, 1.0)));
  if (uMaskType > 0.5 && uMaskType < 1.5) {
    float v = p.x;
    float px = uPixel.x;
    if (uMask.x > 0.5) {
      v = p.y;
      px = uPixel.y;
    }
    float after = smoothstep(uMask.y - 0.5 * px, uMask.y + 0.5 * px, v);
    if (uMask.z > 0.5) {
      keep = keep * after;
    } else {
      keep = keep * (1.0 - after);
    }
  } else if (uMaskType > 1.5) {
    float dist = length(vec2((p.x - uMask.x) * uAspect, p.y - uMask.y));
    float inside = 1.0 - smoothstep(uMask.z - 0.5 * uPixel.y, uMask.z + 0.5 * uPixel.y, dist);
    if (uMask.w > 0.5) {
      keep = keep * inside;
    } else {
      keep = keep * (1.0 - inside);
    }
  }
  col.rgb = mix(col.rgb, uTint, uTintAmount);
  gl_FragColor = vec4(col.rgb, col.a * uAlpha * keep);
}
"""
    }
}
