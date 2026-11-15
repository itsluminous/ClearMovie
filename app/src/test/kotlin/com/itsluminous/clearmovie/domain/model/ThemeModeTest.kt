package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `SYSTEM follows the phone and the overrides ignore it`() {
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false)).isFalse()
        assertThat(ThemeMode.LIGHT.isDark(systemInDarkTheme = true)).isFalse()
        assertThat(ThemeMode.DARK.isDark(systemInDarkTheme = false)).isTrue()
    }
}
