package com.itsluminous.clearmovie.ui.home

import android.content.Context
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.project.ProjectSummary
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * cm-home-presets: with no presets, New preset comes first and Start
 * without a preset under it; with presets, the presets, Start without a
 * preset, then New preset; My videos below either way. Every card under
 * "Make a new video" is one height. Laid out in a window tall enough to
 * hold the whole list, so every card is measured where it is.
 */
@RunWith(RobolectricTestRunner::class)
class HomeOrderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val ready = PresetCard(Fixtures.preset, PresetCard.State.Ready)
    private val own = "Spasht Dhaara"
    private val missing =
        PresetCard(
            Fixtures.preset.copy(id = PresetId("my-own"), displayNameRes = null, displayName = LocalizedText.same(own)),
            PresetCard.State.Missing(2),
        )
    private val video = ProjectSummary(ProjectId("v1"), name = "Party", presetId = null, updatedAt = 1_700_000_000_000)

    private fun show(
        presets: List<PresetCard>?,
        width: Int = 411,
        fontScale: Float = 1f,
        share: ShareHeader? = null,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 4000.dp)) then DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                ClearMovieTheme(dynamicColor = false) {
                    HomeScreen(
                        presets,
                        listOf(video),
                        busy = false,
                        onPreset = {},
                        onStartBlank = {},
                        onVideo = {},
                        onOpenSettings = {},
                        share = share,
                        onEditPreset = {},
                        onNewPreset = {},
                    )
                }
            }
        }
    }

    /** A card by its test tag, or by a text it shows (a card merges its words, so the node is the card). */
    private fun node(tagOrText: String): SemanticsNodeInteraction =
        if (tagOrText == NEW_PRESET_TAG || tagOrText == BLANK_TAG) compose.onNodeWithTag(tagOrText) else compose.onNodeWithText(tagOrText)

    private fun assertInOrder(vararg tagsOrTexts: String) {
        val bounds = tagsOrTexts.map { node(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        for (i in 1 until bounds.size) assertThat(bounds[i].top).isAtLeast(bounds[i - 1].bottom)
    }

    private fun assertOneHeight(vararg tagsOrTexts: String) {
        val heights = tagsOrTexts.map { node(it).getUnclippedBoundsInRoot().let { b -> (b.bottom - b.top).value } }
        for (h in heights) assertThat(h).isWithin(1f).of(heights.first())
        assertThat(heights.first()).isAtLeast(48f)
    }

    @Test
    fun `with no presets New preset comes first, then Start without a preset, then My videos`() {
        show(emptyList())
        assertInOrder(s(R.string.home_new_video), NEW_PRESET_TAG, BLANK_TAG, s(R.string.home_my_videos), "Party")
    }

    @Test
    fun `with presets they come first, then Start without a preset, then New preset, then My videos`() {
        show(listOf(ready, missing))
        assertInOrder(
            s(R.string.home_new_video),
            s(R.string.preset_event_frame_name),
            own,
            BLANK_TAG,
            NEW_PRESET_TAG,
            s(R.string.home_my_videos),
        )
    }

    @Test
    fun `while the presets are read no card shows, so none moves when they arrive`() {
        show(null)
        compose.onNodeWithTag(BLANK_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NEW_PRESET_TAG).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.home_my_videos)).assertIsDisplayed()
    }

    @Test
    fun `Start without a preset is the same size as every other card`() {
        show(listOf(ready, missing))
        assertOneHeight(BLANK_TAG, NEW_PRESET_TAG, s(R.string.preset_event_frame_name), own)
    }

    @Test
    fun `with no presets the two cards are one size`() {
        show(emptyList())
        assertOneHeight(BLANK_TAG, NEW_PRESET_TAG)
    }

    @Test
    fun `at font scale 2 every card is still one size`() {
        show(listOf(ready, missing), fontScale = 2f)
        assertOneHeight(BLANK_TAG, NEW_PRESET_TAG, s(R.string.preset_event_frame_name), own)
    }

    @Test
    @Config(qualifiers = "hi")
    fun `in Hindi, two to a row, every card is one size`() {
        show(listOf(ready, missing), width = 700)
        assertOneHeight(BLANK_TAG, NEW_PRESET_TAG, s(R.string.preset_event_frame_name), own)
    }

    @Test
    @Config(qualifiers = "b+hi+Latn")
    fun `in Hinglish at font scale 2 every card is one size`() {
        show(emptyList(), fontScale = 2f)
        assertOneHeight(BLANK_TAG, NEW_PRESET_TAG)
    }

    @Test
    fun `choosing a preset for shared items with no presets offers Start without a preset alone`() {
        show(emptyList(), share = ShareHeader(ready = true, items = 3))
        compose.onNodeWithText(s(R.string.share_choose_preset)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.home_new_video)).assertIsDisplayed()
        compose.onNodeWithTag(BLANK_TAG).assertIsDisplayed()
        compose.onNodeWithTag(NEW_PRESET_TAG).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.home_my_videos)).assertDoesNotExist()
    }
}
