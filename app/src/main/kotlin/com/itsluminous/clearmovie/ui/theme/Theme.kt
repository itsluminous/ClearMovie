package com.itsluminous.clearmovie.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.clearmovie.domain.model.ThemeMode

/**
 * Branded fallback palette (amber seed, clearly distinct from ClearSMS's teal),
 * used when Material You dynamic colour is unavailable (Android 10 and 11) or
 * switched off in Settings. Every on-colour pair is checked for 4.5:1 contrast
 * by MediaColorsContrastTest.
 */
internal val FallbackLightScheme: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF8B5000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCBE),
        onPrimaryContainer = Color(0xFF2C1600),
        secondary = Color(0xFF725A42),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCBE),
        onSecondaryContainer = Color(0xFF291806),
        tertiary = Color(0xFF58633A),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDCE8B4),
        onTertiaryContainer = Color(0xFF161E01),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF201B16),
        surface = Color(0xFFFFF8F5),
        onSurface = Color(0xFF201B16),
        surfaceVariant = Color(0xFFF2DFD1),
        onSurfaceVariant = Color(0xFF51453A),
        outline = Color(0xFF837468),
        outlineVariant = Color(0xFFD5C3B5),
        inverseSurface = Color(0xFF362F2A),
        inverseOnSurface = Color(0xFFFBEEE7),
        inversePrimary = Color(0xFFFFB870),
        surfaceDim = Color(0xFFE3D8D1),
        surfaceBright = Color(0xFFFFF8F5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFDF1EA),
        surfaceContainer = Color(0xFFF7EBE4),
        surfaceContainerHigh = Color(0xFFF1E6DF),
        surfaceContainerHighest = Color(0xFFECE0D9),
    )

internal val FallbackDarkScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFFFB870),
        onPrimary = Color(0xFF4A2800),
        primaryContainer = Color(0xFF693C00),
        onPrimaryContainer = Color(0xFFFFDCBE),
        secondary = Color(0xFFE1C1A4),
        onSecondary = Color(0xFF402C18),
        secondaryContainer = Color(0xFF59422C),
        onSecondaryContainer = Color(0xFFFFDCBE),
        tertiary = Color(0xFFC0CC9A),
        onTertiary = Color(0xFF2B3410),
        tertiaryContainer = Color(0xFF414B24),
        onTertiaryContainer = Color(0xFFDCE8B4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF17120E),
        onBackground = Color(0xFFECE0D9),
        surface = Color(0xFF17120E),
        onSurface = Color(0xFFECE0D9),
        surfaceVariant = Color(0xFF51453A),
        onSurfaceVariant = Color(0xFFD5C3B5),
        outline = Color(0xFF9E8E81),
        outlineVariant = Color(0xFF51453A),
        inverseSurface = Color(0xFFECE0D9),
        inverseOnSurface = Color(0xFF362F2A),
        inversePrimary = Color(0xFF8B5000),
        surfaceDim = Color(0xFF17120E),
        surfaceBright = Color(0xFF3F3732),
        surfaceContainerLowest = Color(0xFF120D09),
        surfaceContainerLow = Color(0xFF201B16),
        surfaceContainer = Color(0xFF241F1A),
        surfaceContainerHigh = Color(0xFF2F2924),
        surfaceContainerHighest = Color(0xFF3A3330),
    )

/** True when this phone can take its colours from the wallpaper (Android 12+). */
val isDynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * App theme: Material You dynamic colour on Android 12+ when [dynamicColor] is
 * on, the amber fallback otherwise; light or dark from [themeMode].
 * Media surfaces take their colours from [LocalMediaColors], never the palette.
 */
@Composable
fun ClearMovieTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())
    val colorScheme =
        when {
            dynamicColor && isDynamicColorSupported -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                FallbackDarkScheme
            }

            else -> {
                FallbackLightScheme
            }
        }
    CompositionLocalProvider(LocalMediaColors provides if (darkTheme) MediaColors.Dark else MediaColors.Light) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
