package com.itsluminous.clearmovie.ui.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** cm-preset-manager: presets are made and edited from where they are picked. */
@RunWith(RobolectricTestRunner::class)
class HomePresetManagerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val ready = PresetCard(Fixtures.preset, PresetCard.State.Ready)

    private fun show(
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
        share: ShareHeader? = null,
        onEdit: (PresetId) -> Unit = {},
        onNew: () -> Unit = {},
        onPreset: (PresetId) -> Unit = {},
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) {
                    HomeScreen(
                        listOf(ready),
                        emptyList(),
                        busy = false,
                        onPreset = onPreset,
                        onStartBlank = {},
                        onVideo = {},
                        onOpenSettings = {},
                        share = share,
                        onEditPreset = onEdit,
                        onNewPreset = onNew,
                    )
                }
            }
        }
    }

    @Test
    fun `each preset has an Edit button, and New preset follows the presets`() {
        var edited: PresetId? = null
        var made = 0
        var picked: PresetId? = null
        show(onEdit = { edited = it }, onNew = { made++ }, onPreset = { picked = it })
        val name = context.getString(R.string.preset_event_frame_name)
        compose
            .onNodeWithContentDescription(context.getString(R.string.preset_edit_for, name))
            .assertIsDisplayed()
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()
        assertThat(edited).isEqualTo(Fixtures.preset.id)
        // Editing never starts a video.
        assertThat(picked).isNull()
        compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasTestTag(NEW_PRESET_TAG))
        compose.onNodeWithTag(NEW_PRESET_TAG).assertIsDisplayed().performClick()
        assertThat(made).isEqualTo(1)
    }

    @Test
    fun `at large text in a narrow window the Edit button still shows`() {
        show(ScreenSize.COMPACT, fontScale = 2f)
        compose.onNodeWithTag(presetEditTag(Fixtures.preset.id)).assertIsDisplayed()
    }

    @Test
    fun `choosing a preset for shared items offers no editing`() {
        show(share = ShareHeader(ready = true, items = 2))
        compose.onNodeWithTag(presetEditTag(Fixtures.preset.id)).assertDoesNotExist()
        compose.onNodeWithTag(NEW_PRESET_TAG).assertDoesNotExist()
    }
}
