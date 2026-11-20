package com.itsluminous.clearmovie.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.util.concurrent.MoreExecutors
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.render.media3.ChromaKeyEffect
import com.itsluminous.clearmovie.render.media3.MotionEffect
import com.itsluminous.clearmovie.render.media3.PlacementEffect
import com.itsluminous.clearmovie.render.plan.PictureMotion
import com.itsluminous.clearmovie.render.plan.Placement
import com.itsluminous.clearmovie.render.preview.PremultiplyEffect
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every ClearMovie effect built on SinglePassShaderProgram (each placement
 * kind, chroma key, motion, premultiply) leaves texture unit 0 active after
 * it draws.
 * Media3 draws every layer of an export in one GL context, and its texture
 * uploads and a video's next frame bind on whatever unit is active; a
 * PlacementEffect that left unit 1 active (its blur sampler) went with
 * frames whose frame layer showed a clip's picture on the emulator
 * (LayerSwitchRenderTest).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ActiveTextureRenderTest {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var context: android.opengl.EGLContext
    private lateinit var surface: android.opengl.EGLSurface

    @Before
    fun setUp() {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "EGL did not initialise" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, IntArray(1), 0))
        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
    }

    @After
    fun tearDown() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }

    @Test
    fun everyEffectLeavesTextureUnitZeroActive() {
        val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)
        val effects: List<Pair<String, GlEffect>> =
            listOf(
                "placement full frame" to PlacementEffect(Placement.FullFrame, W, H),
                "placement window, cover" to PlacementEffect(Placement.Window(window, Fit.FILL_CROP), W, H),
                "placement window, blurred background" to PlacementEffect(Placement.Window(window, Fit.FIT_BLUR), W, H),
                "placement window, bars" to PlacementEffect(Placement.Window(window, Fit.FIT_BARS), W, H),
                "placement picture in picture" to PlacementEffect(Placement.Pip(NormRect(0.05, 0.05, 0.3, 0.3)), W, H),
                "placement caption box, bitmap" to PlacementEffect(Placement.Box(NormRect(0.15, 0.8, 0.83, 0.12)), W, H, fromBitmap = true),
                "chroma key" to ChromaKeyEffect(KeySpec(ArgbColor(0xFF00D922L), 0.18, 0.08)),
                "motion" to MotionEffect(PictureMotion(clipDurationUs = 1_000_000, rect = NormRect.FULL, clipToRect = false), 0, 0, W, H),
                "premultiply" to PremultiplyEffect(),
            )
        val failures = mutableListOf<String>()
        for ((name, effect) in effects) {
            val unit =
                activeUnitAfterOneDraw(
                    effect.toGlShaderProgram(
                        androidx.test.platform.app.InstrumentationRegistry
                            .getInstrumentation()
                            .targetContext,
                        false,
                    ),
                )
            if (unit != GLES20.GL_TEXTURE0) failures += "$name left unit ${unit - GLES20.GL_TEXTURE0} active"
        }
        assertWithMessage("effects that leave another texture unit active").that(failures).isEmpty()
    }

    /** Draws one frame through [program], as Media3 does, and returns the active texture unit afterwards. */
    private fun activeUnitAfterOneDraw(program: GlShaderProgram): Int {
        val input = GlUtil.createTexture(IN_W, IN_H, false)
        var output: GlTextureInfo? = null
        program.setInputListener(object : GlShaderProgram.InputListener {})
        program.setOutputListener(
            object : GlShaderProgram.OutputListener {
                override fun onOutputFrameAvailable(
                    outputTexture: GlTextureInfo,
                    presentationTimeUs: Long,
                ) {
                    output = outputTexture
                }
            },
        )
        program.setErrorListener(MoreExecutors.directExecutor()) { throw it }
        try {
            // Start on another unit, so the test also shows the draw puts unit 0 back.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            program.queueInputFrame(DefaultGlObjectsProvider(), GlTextureInfo(input, C_UNSET, C_UNSET, IN_W, IN_H), 0)
            checkNotNull(output) { "the effect drew no frame" }
            val unit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, unit, 0)
            return unit[0]
        } finally {
            program.release()
            GlUtil.deleteTexture(input)
        }
    }

    private companion object {
        const val W = 640
        const val H = 360
        const val IN_W = 320
        const val IN_H = 180
        const val C_UNSET = -1
    }
}
