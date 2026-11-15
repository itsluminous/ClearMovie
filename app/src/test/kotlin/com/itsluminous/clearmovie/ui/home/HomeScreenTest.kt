package com.itsluminous.clearmovie.ui.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun showAt(
        size: ScreenSize,
        fontScale: Float = 1f,
        onOpenSettings: () -> Unit = {},
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) { HomeScreen(onOpenSettings = onOpenSettings) }
            }
        }
    }

    private fun assertAllShown() {
        compose.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.action_settings)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_empty_title)).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(context.resources.getQuantityString(R.plurals.home_saved_videos, 0, 0))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_empty_body)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun compact() = showAt(ScreenSize.COMPACT).also { assertAllShown() }

    @Test
    fun medium() = showAt(ScreenSize.MEDIUM).also { assertAllShown() }

    @Test
    fun expanded() = showAt(ScreenSize.EXPANDED).also { assertAllShown() }

    @Test
    fun `compact at font scale 2`() = showAt(ScreenSize.COMPACT, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `medium at font scale 2`() = showAt(ScreenSize.MEDIUM, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `the settings button opens settings`() {
        var opened = 0
        showAt(ScreenSize.COMPACT, onOpenSettings = { opened++ })
        compose.onNodeWithContentDescription(context.getString(R.string.action_settings)).performClick()
        assertThat(opened).isEqualTo(1)
    }
}
