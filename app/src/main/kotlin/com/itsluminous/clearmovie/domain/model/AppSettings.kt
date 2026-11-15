package com.itsluminous.clearmovie.domain.model

/**
 * The user's appearance settings. The UI language is not here: it is a per-app
 * locale, stored by Android itself (LocaleManager on 13+, AppCompat below).
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+). Off, or on Android 10-11, uses the brand colours. */
    val dynamicColor: Boolean = true,
)
