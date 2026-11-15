package com.itsluminous.clearmovie.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours for media surfaces (the preview and the timeline clips). These are
 * fixed, NOT taken from the dynamic palette, so a photo row reads the same on
 * any wallpaper and labels stay legible. Each fill has a label colour chosen
 * for at least 4.5:1 contrast, in light and in dark (MediaColorsContrastTest).
 * The editor screens that use them arrive from cm-flow on.
 */
@Immutable
data class MediaColors(
    /** Behind the video preview: always near-black, in light and in dark. */
    val previewBackground: Color,
    val onPreviewBackground: Color,
    /** Photo and video clips (blue). */
    val visualClip: Color,
    val onVisualClip: Color,
    /** Text clips (violet). */
    val textClip: Color,
    val onTextClip: Color,
    /** Music and voice clips (green). */
    val audioClip: Color,
    val onAudioClip: Color,
    /** Frame / overlay clips (amber). */
    val frameClip: Color,
    val onFrameClip: Color,
) {
    /** Every (fill, label) pair, for the contrast test. */
    val labelPairs: List<Pair<Color, Color>>
        get() =
            listOf(
                previewBackground to onPreviewBackground,
                visualClip to onVisualClip,
                textClip to onTextClip,
                audioClip to onAudioClip,
                frameClip to onFrameClip,
            )

    companion object {
        private val PreviewBackground = Color(0xFF101010)
        private val White = Color(0xFFFFFFFF)
        private val Black = Color(0xFF000000)

        val Light =
            MediaColors(
                previewBackground = PreviewBackground,
                onPreviewBackground = White,
                visualClip = Color(0xFF1565C0),
                onVisualClip = White,
                textClip = Color(0xFF6A1B9A),
                onTextClip = White,
                audioClip = Color(0xFF2E7D32),
                onAudioClip = White,
                frameClip = Color(0xFFFFB300),
                onFrameClip = Black,
            )

        val Dark =
            MediaColors(
                previewBackground = PreviewBackground,
                onPreviewBackground = White,
                visualClip = Color(0xFF90CAF9),
                onVisualClip = Black,
                textClip = Color(0xFFCE93D8),
                onTextClip = Black,
                audioClip = Color(0xFFA5D6A7),
                onAudioClip = Black,
                frameClip = Color(0xFFFFCA28),
                onFrameClip = Black,
            )
    }
}

val LocalMediaColors = staticCompositionLocalOf { MediaColors.Light }
