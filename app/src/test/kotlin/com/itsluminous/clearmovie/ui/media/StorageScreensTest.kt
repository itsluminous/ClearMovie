package com.itsluminous.clearmovie.ui.media

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.preset.PresetSlotList
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The storage milestone's visible states, at the three sizes and at font scale 2.0. */
@RunWith(RobolectricTestRunner::class)
class StorageScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private fun binding(
        slot: PresetSlot,
        adjusted: Boolean = false,
    ) = PresetBinding(
        slot,
        File("/x/$slot"),
        "00",
        1,
        null,
        null,
        1_000_000,
        null,
        if (adjusted) ArgbColor(0xFF05C925) else null,
        null,
        null,
        0,
    )

    private val repair =
        PresetStatus(
            PresetId("event-frame"),
            mapOf(
                PresetSlot.INTRO to SlotStatus.Ready(binding(PresetSlot.INTRO)),
                PresetSlot.FRAME to SlotStatus.Missing(binding(PresetSlot.FRAME)),
                PresetSlot.ENDING to SlotStatus.NotSet,
            ),
        )

    @Test
    fun `the explainer asks first, offers Try again after a refusal, and Settings when blocked`() {
        for (size in ScreenSize.entries) {
            for ((state, title, button) in listOf(
                Triple(MediaAccessState.NotAsked, R.string.media_access_title, R.string.media_access_continue),
                Triple(MediaAccessState.Denied, R.string.media_access_denied_title, R.string.media_access_try_again),
                Triple(MediaAccessState.DeniedForGood, R.string.media_access_denied_title, R.string.media_access_open_settings),
            )) {
                var continued = 0
                var settings = 0
                var notNow = 0
                val rule = show(size, 2f) { MediaAccessExplainer(state, { continued++ }, { notNow++ }, { settings++ }) }
                rule.onNodeWithText(s(title)).performScrollTo().assertIsDisplayed()
                rule.onNodeWithText(s(R.string.media_access_privacy)).performScrollTo().assertIsDisplayed()
                rule.onNodeWithText(s(button)).assertIsDisplayed().performClick()
                rule.onNodeWithText(s(R.string.media_access_not_now)).assertIsDisplayed().performClick()
                assertThat(continued + settings).isEqualTo(1)
                assertThat(settings).isEqualTo(if (state == MediaAccessState.DeniedForGood) 1 else 0)
                assertThat(notNow).isEqualTo(1)
            }
        }
    }

    @Test
    fun `full and partial access show no explainer, and partial access offers Allow all photos`() {
        var allowed = false
        val rule =
            show(ScreenSize.COMPACT) {
                MediaAccessExplainer(MediaAccessState.Full, {}, {}, {})
                MediaAccessExplainer(MediaAccessState.Partial, {}, {}, {})
                PartialAccessBanner(onAllowAll = { allowed = true })
            }
        rule.onAllNodesWithText(s(R.string.media_access_title)).assertCountEquals(0)
        rule.onNodeWithText(s(R.string.media_access_partial)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.media_access_allow_all)).performClick()
        assertThat(allowed).isTrue()
    }

    @Test
    fun `a missing preset file shows the repair state with Choose file for that slot`() {
        for (size in ScreenSize.entries) {
            val chosen = mutableListOf<PresetSlot>()
            val rule = show(size, 2f) { PresetSlotList(repair, onChoose = { chosen += it }, modifier = scrollable()) }
            rule.onNodeWithText(s(R.string.preset_repair_title)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(s(R.string.preset_slot_missing)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(s(R.string.preset_slot_not_set)).performScrollTo().assertIsDisplayed()
            val frameLabel = context.getString(R.string.preset_choose_file_for, s(R.string.preset_slot_frame))
            rule.onNodeWithContentDescription(frameLabel).performScrollTo().performClick()
            assertThat(chosen).containsExactly(PresetSlot.FRAME)
        }
    }

    @Test
    fun `an adjusted frame says so, and a first setup has no repair heading`() {
        val setup =
            PresetStatus(
                PresetId("event-frame"),
                mapOf(
                    PresetSlot.INTRO to SlotStatus.NotSet,
                    PresetSlot.FRAME to SlotStatus.Ready(binding(PresetSlot.FRAME, adjusted = true)),
                    PresetSlot.ENDING to SlotStatus.NotNeeded,
                ),
            )
        val rule = show(ScreenSize.COMPACT) { PresetSlotList(setup, onChoose = {}, modifier = scrollable()) }
        rule.onNodeWithText(s(R.string.preset_setup_title)).assertIsDisplayed()
        rule.onAllNodesWithText(s(R.string.preset_repair_title)).assertCountEquals(0)
        rule.onNodeWithText(s(R.string.preset_slot_frame_adjusted)).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(s(R.string.preset_slot_ending)).assertCountEquals(0)
    }

    @Test
    fun `a missing clip tile explains and offers Replace`() {
        var replaced = 0
        val rule =
            show(ScreenSize.COMPACT, 2f) {
                Column(scrollable()) {
                    MissingMediaTile(MediaStatus.MISSING, onReplace = { replaced++ })
                    MissingMediaTile(MediaStatus.NO_ACCESS, onReplace = { replaced++ })
                    MissingMediaTile(MediaStatus.AVAILABLE, onReplace = { replaced++ })
                }
            }
        rule.onNodeWithText(s(R.string.media_missing_body)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(s(R.string.media_no_access_body)).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(s(R.string.action_replace)).assertCountEquals(2)
        rule.onAllNodesWithText(s(R.string.action_replace))[0].performClick()
        assertThat(replaced).isEqualTo(1)
    }

    @Test
    fun `the copy budget prompt names the count and size and offers its three choices`() {
        val calls = mutableListOf<String>()
        val verdict = CopyBudget.Verdict.NeedsConfirmation(3, 1_400_000_000, 3_200_000_000, offerAllowAll = true)
        val rule = show(ScreenSize.COMPACT, 2f) { CopyBudgetDialog(verdict, { calls += "copy" }, { calls += "fewer" }, { calls += "all" }) }
        val size =
            android.text.format.Formatter
                .formatShortFileSize(context, 1_400_000_000)
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.copy_budget_body, 3, 3, size)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.media_access_allow_all)).assertIsDisplayed().performClick()
        rule.onNodeWithText(s(R.string.copy_budget_copy_anyway)).assertIsDisplayed().performClick()
        rule.onNodeWithText(s(R.string.copy_budget_choose_fewer)).assertIsDisplayed().performClick()
        assertThat(calls).containsExactly("all", "copy", "fewer").inOrder()
    }

    @Test
    fun `copy progress shows the percentage and Cancel`() {
        var cancelled = false
        val rule =
            show(ScreenSize.MEDIUM, 2f) { CopyProgressDialog(25L * 1024 * 1024, 100L * 1024 * 1024, onCancel = { cancelled = true }) }
        rule.onNodeWithText(context.getString(R.string.copy_progress_percent, 25)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.action_cancel)).performClick()
        assertThat(cancelled).isTrue()
        val space = CopyBudget.Verdict.NotEnoughSpace(2, 5_000_000_000, 1_000_000_000)
        var fewer = false
        val second = show(ScreenSize.EXPANDED) { NotEnoughSpaceDialog(space, onChooseFewer = { fewer = true }) }
        second.onNodeWithText(s(R.string.copy_no_space_title)).assertIsDisplayed()
        second.onNodeWithText(s(R.string.copy_budget_choose_fewer)).performClick()
        assertThat(fewer).isTrue()
    }

    private fun scrollable() = Modifier.scrollable()

    private fun show(
        size: ScreenSize,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) = compose.also {
        holder.value = Triple(size, fontScale, content)
        if (!started) {
            started = true
            compose.setContent {
                val (sz, scale, body) = holder.value
                AtSize(sz, scale) { ClearMovieTheme(dynamicColor = false) { body() } }
            }
        }
        compose.waitForIdle()
    }

    private var started = false
    private val holder =
        mutableStateOf<Triple<ScreenSize, Float, @Composable () -> Unit>>(
            Triple(ScreenSize.COMPACT, 1f, {}),
        )
}

private fun Modifier.scrollable(): Modifier = composed { verticalScroll(rememberScrollState()) }
