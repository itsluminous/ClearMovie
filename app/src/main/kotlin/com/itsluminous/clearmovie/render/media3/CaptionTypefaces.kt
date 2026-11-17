package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.util.Log
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CaptionFont
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled caption fonts as typefaces (cm-captions). Each [CaptionFont] is
 * a Latin font with its Devanagari partner as the first fallback, then the
 * system's own fonts (for emoji and other scripts), built with
 * [Typeface.CustomFallbackBuilder] so the platform's text shaper (HarfBuzz)
 * forms conjuncts and places matras with the bundled Devanagari font. The
 * files are the app's own resources, chosen from a fixed table by the font
 * id; nothing in a style or a preset ever names a file. If a font can't be
 * read, the system font of the same weight is used, so a caption always
 * draws.
 */
class CaptionTypefaces private constructor(
    private val resources: Resources,
) {
    private val cache = ConcurrentHashMap<CaptionFont, Typeface>()
    private val fellBack = ConcurrentHashMap.newKeySet<CaptionFont>()

    /** Whether [fontId] draws with the bundled files (false after they failed to load and the system font stood in). */
    fun isBundled(fontId: String): Boolean {
        val font = CaptionFont.of(fontId)
        of(font)
        return font !in fellBack
    }

    /** The typeface for a style's font id (unknown ids draw as [CaptionFont.DEFAULT]). */
    fun of(fontId: String): Typeface = of(CaptionFont.of(fontId))

    fun of(font: CaptionFont): Typeface = cache.getOrPut(font) { build(font) }

    private fun build(font: CaptionFont): Typeface {
        val (latin, devanagari) = files(font)
        val weight = if (font.bold) FontStyle.FONT_WEIGHT_BOLD else FontStyle.FONT_WEIGHT_NORMAL
        return try {
            Typeface
                .CustomFallbackBuilder(family(latin, weight))
                .addCustomFallback(family(devanagari, weight))
                .setSystemFallback(SYSTEM_FALLBACK)
                .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
        } catch (e: Exception) {
            // Not expected with the app's own files; a test runtime without real fonts lands here too.
            Log.w(TAG, "Bundled font ${font.id} could not be loaded: ${e.javaClass.simpleName}")
            fellBack += font
            Typeface.create(Typeface.SANS_SERIF, if (font.bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun family(
        res: Int,
        weight: Int,
    ): FontFamily = FontFamily.Builder(Font.Builder(resources, res).setWeight(weight).build()).build()

    companion object {
        private const val TAG = "CaptionTypefaces"
        private const val SYSTEM_FALLBACK = "sans-serif"

        @Volatile
        private var instance: CaptionTypefaces? = null

        /** The app-wide set (typefaces are immutable and shared by the preview and every export). */
        fun get(context: Context): CaptionTypefaces =
            instance ?: synchronized(this) {
                instance ?: CaptionTypefaces(context.applicationContext.resources).also { instance = it }
            }

        /** The (Latin, Devanagari) font resources of [font]. */
        fun files(font: CaptionFont): Pair<Int, Int> =
            when (font) {
                CaptionFont.SANS -> R.font.noto_sans_regular to R.font.noto_sans_devanagari_regular
                CaptionFont.SANS_BOLD -> R.font.noto_sans_bold to R.font.noto_sans_devanagari_bold
                CaptionFont.SERIF -> R.font.noto_serif_regular to R.font.noto_serif_devanagari_regular
                CaptionFont.SERIF_BOLD -> R.font.noto_serif_bold to R.font.noto_serif_devanagari_bold
            }
    }
}
