package com.itsluminous.clearmovie.domain.model

/** Light or dark: follow the phone, or override it in Settings -> Theme. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Whether the app draws dark, given whether the phone is in dark mode. */
    fun isDark(systemInDarkTheme: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemInDarkTheme
            LIGHT -> false
            DARK -> true
        }
}
