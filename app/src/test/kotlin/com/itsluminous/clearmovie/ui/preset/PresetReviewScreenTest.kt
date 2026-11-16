package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PartChoice
import com.itsluminous.clearmovie.domain.model.PresetChoices
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Preset review (requirement 2): what the preset contains, each part replaceable or skippable, then Next. */
@RunWith(RobolectricTestRunner::class)
class PresetReviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val ui = ReviewUi(preset = Fixtures.preset, bound = Fixtures.presetMedia)

    private fun show(
        state: ReviewUi = ui,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
        calls: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) {
                    PresetReviewScreen(
                        ui = state,
                        working = false,
                        onBack = { calls += "back" },
                        onPlay = { calls += "play:$it" },
                        onReplace = { calls += "replace:$it" },
                        onSkip = { calls += "skip:$it" },
                        onUsePreset = { calls += "use:$it" },
                        onNext = { calls += "next" },
                        onDismissMessage = {},
                    )
                }
            }
        }
    }

    /** Next is pinned under the list on a phone held upright, and scrolls with the text in two panes. */
    private fun SemanticsNodeInteraction.scrollIfNeeded(): SemanticsNodeInteraction =
        try {
            performScrollTo()
        } catch (e: AssertionError) {
            this
        }

    private fun allPartsAt(
        size: ScreenSize,
        fontScale: Float,
    ) {
        show(size = size, fontScale = fontScale)
        for (text in listOf(
            s(R.string.review_title),
            s(R.string.preset_slot_intro),
            s(R.string.preset_slot_frame),
            s(R.string.preset_slot_ending),
        )) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(s(R.string.review_frame_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_next)).scrollIfNeeded().assertIsDisplayed()
    }

    @Test
    fun compact() = allPartsAt(ScreenSize.COMPACT, 1f)

    @Test
    fun medium() = allPartsAt(ScreenSize.MEDIUM, 1f)

    @Test
    fun expanded() = allPartsAt(ScreenSize.EXPANDED, 1f)

    @Test
    fun `compact at font scale 2`() = allPartsAt(ScreenSize.COMPACT, 2f)

    @Test
    fun `medium at font scale 2`() = allPartsAt(ScreenSize.MEDIUM, 2f)

    @Test
    fun `expanded at font scale 2`() = allPartsAt(ScreenSize.EXPANDED, 2f)

    @Test
    fun `each part offers Play, Replace and Skip, and Next goes on`() {
        val calls = mutableListOf<String>()
        show(calls = calls)
        val intro = s(R.string.preset_slot_intro)
        compose.onNodeWithContentDescription(context.getString(R.string.review_play_part, intro)).performScrollTo().performClick()
        compose.onAllNodesWithText(s(R.string.action_replace))[0].performScrollTo().performClick()
        compose.onAllNodesWithText(s(R.string.review_skip))[1].performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.action_next)).scrollIfNeeded().performClick()
        assertThat(calls).containsExactly("play:INTRO", "replace:INTRO", "skip:FRAME", "next").inOrder()
    }

    @Test
    fun `a skipped part says so and offers the preset's again, a replaced one names the file`() {
        val calls = mutableListOf<String>()
        val mine = MediaRef.Store("content://media/external/video/media/3")
        show(
            ui.copy(choices = PresetChoices(intro = PartChoice.Replace(mine, 4000, "party.mp4"), frame = PartChoice.Skip)),
            calls = calls,
        )
        compose.onNodeWithText(s(R.string.review_skipped)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.review_your_file, "party.mp4", "0:04")).performScrollTo().assertIsDisplayed()
        // Neither the skipped frame's Play nor its Skip is offered.
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.review_play_part, s(R.string.preset_slot_frame)),
            ).assertDoesNotExist()
        compose.onAllNodesWithText(s(R.string.review_use_preset))[1].performScrollTo().performClick()
        assertThat(calls).containsExactly("use:FRAME")
    }

    @Test
    fun `the scroll position survives a restore`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            AtSize(ScreenSize.COMPACT, 2f) {
                ClearMovieTheme(dynamicColor = false) {
                    PresetReviewScreen(ui, false, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        val ending = hasContentDescription(context.getString(R.string.review_play_part, s(R.string.preset_slot_ending)))
        compose.onNode(ending).performScrollTo().assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.onNode(ending).assertIsDisplayed()
    }
}
