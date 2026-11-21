package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.TextOutline
import com.itsluminous.clearmovie.ui.components.RectGrip
import com.itsluminous.clearmovie.ui.editor.CaptionPlaces
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * cm-caption-box: the height a caption's box needs for its words at their
 * size, measured by the export's own renderer with the bundled fonts (real
 * text measurement, hence native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptionBoxHeightTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val renderer = CaptionBitmapRenderer(CanvasShape.REFERENCE_SHORT_SIDE, CaptionTypefaces.get(context), CanvasShape.LANDSCAPE)
    private val style = CaptionStyle.STANDARD.copy(outline = TextOutline(ArgbColor.BLACK, 4))
    private val words = "नमस्ते दुनिया, यह एक लंबा कैप्शन है"

    @Test
    fun `a narrower box needs more lines, so more height, and one line fits the usual box`() {
        val wide = renderer.boxHeightFor(words, style, 0.81)
        val narrow = renderer.boxHeightFor(words, style, 0.3)
        assertThat(narrow).isGreaterThan(wide * 1.5)
        // One short line at the usual size fits the preset's usual box height.
        assertThat(renderer.boxHeightFor("नमस्ते", style, 0.81)).isAtMost(CaptionStyle.DEFAULT_BOX.h)
    }

    @Test
    fun `an upright canvas measures in its own height`() {
        val upright = CaptionBitmapRenderer(CanvasShape.REFERENCE_SHORT_SIDE, CaptionTypefaces.get(context), CanvasShape.PORTRAIT)
        // The same width share is narrower in letters on an upright canvas, and its height is 1920 lines, not 1080.
        val landscapeLines = renderer.boxHeightFor(words, style, 0.5) * 1080
        val uprightLines = upright.boxHeightFor(words, style, 0.5) * 1920
        assertThat(uprightLines).isGreaterThan(landscapeLines)
    }

    @Test
    fun `a box made as tall as the words need leaves them their size at every export size`() {
        for (w in listOf(0.2, 0.31, 0.5, 0.81)) {
            val need = renderer.boxHeightFor(words, style, w)
            // The room the renderer sees at each export size, after the box is rounded to whole pixels.
            for (short in listOf(480, 720, 1080)) {
                val scale = short / 1080.0
                val logicalH = (need * short).roundToInt() / scale
                val room = logicalH - 2 * CaptionBitmapRenderer.PADDING_PX - 2 * 4
                val text = need * 1080 - 2 * CaptionBitmapRenderer.PADDING_PX - 2 * 4 - 4
                assertThat(room).isAtLeast(text)
            }
        }
    }

    @Test
    fun `resizing narrower grows the box to the words' height, measured this way`() {
        val safe = NormRect(0.02, 0.04, 0.96, 0.74)
        val usual = CaptionStyle.DEFAULT_BOX
        val minW = CaptionPlaces.minWidth(style.sizePx, CanvasShape.LANDSCAPE)
        val (box, _) =
            CaptionPlaces.resized(
                usual,
                RectGrip.RIGHT,
                -0.5,
                0.0,
                safe,
                usual,
                minW,
            ) { renderer.boxHeightFor(words, style, it) }
        assertThat(box.w).isWithin(2e-4).of(0.31)
        assertThat(box.h).isAtLeast(renderer.boxHeightFor(words, style, box.w) - 2e-4)
        assertThat(box.h).isGreaterThan(usual.h)
    }
}
