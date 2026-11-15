package com.itsluminous.clearmovie.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.pow

/**
 * Text must stay readable on every fixed media colour and on every pair of the
 * branded fallback schemes, in light and in dark: WCAG AA, 4.5:1 for normal text.
 */
class MediaColorsContrastTest {
    private val minimum = 4.5

    @Test
    fun `every media label clears 4,5 to 1 on its fill, light and dark`() {
        for ((name, colors) in listOf("Light" to MediaColors.Light, "Dark" to MediaColors.Dark)) {
            assertThat(colors.labelPairs).hasSize(5)
            colors.labelPairs.forEach { (fill, label) ->
                assertWithMessage("$name: ${fill.hex()} with ${label.hex()}").that(contrast(fill, label)).isAtLeast(minimum)
            }
        }
    }

    @Test
    fun `the preview background is near-black in both modes`() {
        for (colors in listOf(MediaColors.Light, MediaColors.Dark)) {
            assertThat(luminance(colors.previewBackground)).isLessThan(0.01)
        }
    }

    @Test
    fun `every on-colour of the fallback schemes clears 4,5 to 1`() {
        for ((name, scheme) in listOf("Light" to FallbackLightScheme, "Dark" to FallbackDarkScheme)) {
            scheme.textPairs().forEach { (pair, colors) ->
                val (background, foreground) = colors
                assertWithMessage("$name $pair: ${background.hex()} with ${foreground.hex()}")
                    .that(contrast(background, foreground))
                    .isAtLeast(minimum)
            }
        }
    }

    @Test
    fun `the contrast maths matches the WCAG reference values`() {
        assertThat(contrast(Color.Black, Color.White)).isWithin(0.01).of(21.0)
        assertThat(contrast(Color.White, Color.White)).isWithin(0.01).of(1.0)
        // #777777 on white is the classic just-under-AA example (4.48:1).
        assertThat(contrast(Color(0xFF777777), Color.White)).isWithin(0.01).of(4.48)
    }

    private fun ColorScheme.textPairs(): Map<String, Pair<Color, Color>> =
        mapOf(
            "primary" to (primary to onPrimary),
            "primaryContainer" to (primaryContainer to onPrimaryContainer),
            "secondary" to (secondary to onSecondary),
            "secondaryContainer" to (secondaryContainer to onSecondaryContainer),
            "tertiary" to (tertiary to onTertiary),
            "tertiaryContainer" to (tertiaryContainer to onTertiaryContainer),
            "error" to (error to onError),
            "errorContainer" to (errorContainer to onErrorContainer),
            "background" to (background to onBackground),
            "surface" to (surface to onSurface),
            "surface/onSurfaceVariant" to (surface to onSurfaceVariant),
            "surface/primary" to (surface to primary),
            "surfaceVariant" to (surfaceVariant to onSurfaceVariant),
            "surfaceContainerLowest" to (surfaceContainerLowest to onSurface),
            "surfaceContainerLow" to (surfaceContainerLow to onSurface),
            "surfaceContainer" to (surfaceContainer to onSurface),
            "surfaceContainerHigh" to (surfaceContainerHigh to onSurface),
            "surfaceContainerHighest" to (surfaceContainerHighest to onSurface),
            "inverseSurface" to (inverseSurface to inverseOnSurface),
        )

    private fun channel(c: Float): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    private fun Color.hex(): String = "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
