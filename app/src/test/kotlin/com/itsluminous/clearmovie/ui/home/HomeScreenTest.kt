package com.itsluminous.clearmovie.ui.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.project.ProjectSummary
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Home and preset pick at the three sizes and font scale 2.0 (ROADMAP global criteria, requirement 1). */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val ready = PresetCard(Fixtures.preset, PresetCard.State.Ready)
    private val own =
        PresetCard(
            Fixtures.preset.copy(id = PresetId("own"), displayName = LocalizedText(en = "Spasht-Dhaara")),
            PresetCard.State.Missing(1),
        )
    private val videos =
        (1..20).map {
            ProjectSummary(
                ProjectId("v$it"),
                name = "Party $it",
                presetId = null,
                updatedAt = 1_700_000_000_000,
            )
        }

    private fun showAt(
        size: ScreenSize,
        fontScale: Float = 1f,
        presets: List<PresetCard> = listOf(ready, own),
        list: List<ProjectSummary> = emptyList(),
        onPreset: (PresetId) -> Unit = {},
        onBlank: () -> Unit = {},
        onVideo: (ProjectId) -> Unit = {},
        onSettings: () -> Unit = {},
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) {
                    HomeScreen(presets, list, busy = false, onPreset, onBlank, onVideo, onSettings)
                }
            }
        }
    }

    private fun scrollTo(text: String) = compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(text))

    private fun assertAllShown() {
        compose.onNodeWithText(s(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.action_settings)).assertIsDisplayed()
        for (text in listOf(
            s(R.string.home_new_video),
            s(R.string.preset_event_frame_name),
            "Spasht-Dhaara",
            context.resources.getQuantityString(R.plurals.home_preset_missing, 1, 1),
            s(R.string.home_start_blank),
            s(R.string.home_my_videos),
            s(R.string.home_empty_title),
        )) {
            scrollTo(text)
            compose.onNodeWithText(text).assertIsDisplayed()
        }
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
    fun `expanded at font scale 2`() = showAt(ScreenSize.EXPANDED, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `tapping a preset, Start without a preset, a video and Settings each reach their callback`() {
        val picked = mutableListOf<String>()
        showAt(
            ScreenSize.COMPACT,
            list = videos.take(1),
            onPreset = { picked += "preset:${it.value}" },
            onBlank = { picked += "blank" },
            onVideo = { picked += "video:${it.value}" },
            onSettings = { picked += "settings" },
        )
        compose.onNodeWithText(s(R.string.preset_event_frame_name)).performClick()
        scrollTo("Spasht-Dhaara")
        compose.onNodeWithText("Spasht-Dhaara").performClick()
        scrollTo(s(R.string.home_start_blank))
        compose.onNodeWithText(s(R.string.home_start_blank)).performClick()
        scrollTo("Party 1")
        compose.onNodeWithText("Party 1").performClick()
        compose.onNodeWithContentDescription(s(R.string.action_settings)).performClick()
        assertThat(picked).containsExactly("preset:event-frame", "preset:own", "blank", "video:v1", "settings").inOrder()
    }

    @Test
    fun `a preset that was never set up says so`() {
        showAt(ScreenSize.COMPACT, presets = listOf(PresetCard(Fixtures.preset, PresetCard.State.NeedsSetup)))
        compose.onNodeWithText(s(R.string.home_preset_needs_setup)).assertIsDisplayed()
    }

    @Test
    fun `the list position survives a rotation or a process death`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            AtSize(ScreenSize.COMPACT) {
                ClearMovieTheme(dynamicColor = false) { HomeScreen(listOf(ready), videos, false, {}, {}, {}, {}) }
            }
        }
        scrollTo("Party 20")
        compose.onNodeWithText("Party 20").assertIsDisplayed()
        compose.onNodeWithText(s(R.string.home_new_video)).assertDoesNotExist()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Party 20").assertIsDisplayed()
        compose.onNodeWithText(s(R.string.home_new_video)).assertDoesNotExist()
    }
}
