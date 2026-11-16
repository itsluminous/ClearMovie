package com.itsluminous.clearmovie.render.media3

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * A one-pass shader over a full-screen quad. The varying `vUv` is the
 * output position with (0, 0) at the bottom left, the GL texture convention
 * Media3 uses for every frame; shaders that think in picture space (y down)
 * flip it themselves.
 *
 * Subclasses set their uniforms in [setUniforms]. [release] deletes the GL
 * program as well as the texture pool, so no GL object outlives the effect.
 * A subclass that needs passes of its own before the final one (the
 * fit-with-blur background) draws them in [beforeDraw] and frees what they
 * use in [releaseResources]; every other subclass leaves both alone.
 */
@UnstableApi
internal abstract class SinglePassShaderProgram(
    fragmentShader: String,
) : BaseGlShaderProgram(false, 1) {
    protected val program: GlProgram =
        try {
            GlProgram(VERTEX_SHADER, fragmentShader).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

    private var released = false

    /** The output size for an input of this size. */
    abstract fun outputSize(
        inputWidth: Int,
        inputHeight: Int,
    ): Size

    abstract fun setUniforms(presentationTimeUs: Long)

    /**
     * Runs before the final pass, with the output framebuffer bound; anything
     * it binds it must put back. The final pass then draws into the output.
     */
    protected open fun beforeDraw(
        inputTexId: Int,
        presentationTimeUs: Long,
    ) = Unit

    /** Frees GL objects the subclass made, once. */
    protected open fun releaseResources() = Unit

    final override fun configure(
        inputWidth: Int,
        inputHeight: Int,
    ): Size = outputSize(inputWidth, inputHeight)

    final override fun drawFrame(
        inputTexId: Int,
        presentationTimeUs: Long,
    ) {
        try {
            beforeDraw(inputTexId, presentationTimeUs)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            setUniforms(presentationTimeUs)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            super.release()
        } finally {
            if (!released) {
                released = true
                try {
                    releaseResources()
                    program.delete()
                } catch (e: GlUtil.GlException) {
                    throw VideoFrameProcessingException(e)
                }
            }
        }
    }

    companion object {
        const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""
    }
}
