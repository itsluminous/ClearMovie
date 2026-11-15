package com.itsluminous.clearmovie.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import com.itsluminous.clearmovie.domain.model.ThemeMode

/** The AppCompat night mode for a [ThemeMode]. */
fun ThemeMode.toAppCompatNightMode(): Int =
    when (this) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

/**
 * Applies the Theme override to the whole app, not only to Compose: the
 * window background, values-night resources and any framework dialog follow
 * it, and `isSystemInDarkTheme()` reports it. A no-op when the mode is already
 * set; otherwise AppCompat recreates any running activity (saved state survives).
 *
 * The Android 12+ system splash on a cold start is drawn before the app runs,
 * so it still follows the phone's own setting.
 */
fun applyNightMode(mode: ThemeMode) {
    val nightMode = mode.toAppCompatNightMode()
    if (AppCompatDelegate.getDefaultNightMode() != nightMode) AppCompatDelegate.setDefaultNightMode(nightMode)
}
