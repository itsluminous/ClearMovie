package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.CopyReason
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Preset setup and repair (requirement 5: a missing file opens repair, never a crash). */
@RunWith(RobolectricTestRunner::class)
class PresetSetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private fun binding(slot: PresetSlot) = PresetBinding(slot, File("/x/$slot"), "00", 1, null, null, 6_000_000, null, null, null, null, 0)

    private fun status(vararg slots: Pair<PresetSlot, SlotStatus>) = PresetStatus(Fixtures.preset.id, slots.toMap())

    private val firstTime =
        status(
            PresetSlot.INTRO to SlotStatus.NotSet,
            PresetSlot.FRAME to SlotStatus.NotSet,
            PresetSlot.ENDING to SlotStatus.NotSet,
        )
    private val missingFrame =
        status(
            PresetSlot.INTRO to SlotStatus.Ready(binding(PresetSlot.INTRO)),
            PresetSlot.FRAME to SlotStatus.Missing(binding(PresetSlot.FRAME)),
            PresetSlot.ENDING to SlotStatus.Ready(binding(PresetSlot.ENDING)),
        )
    private val ready = status(*PresetSlot.entries.map { it to SlotStatus.Ready(binding(it)) as SlotStatus }.toTypedArray())

    private fun show(
        status: PresetStatus,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
        message: SetupMessage? = null,
        work: SetupWork? = null,
        onChoose: (PresetSlot) -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) {
                    PresetSetupScreen(
                        SetupUi(Fixtures.preset, status, work = work, message = message),
                        newVideo = true,
                        {},
                        onChoose,
                        {},
                        onDone,
                        {},
                        {},
                    )
                }
            }
        }
    }

    private fun firstTimeAt(
        size: ScreenSize,
        fontScale: Float,
    ) {
        show(firstTime, size, fontScale)
        for (text in listOf(s(R.string.preset_guide_title), s(R.string.preset_setup_title), s(R.string.preset_definition_title))) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(s(R.string.action_next)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `first time, compact`() = firstTimeAt(ScreenSize.COMPACT, 1f)

    @Test
    fun `first time, medium`() = firstTimeAt(ScreenSize.MEDIUM, 1f)

    @Test
    fun `first time, expanded`() = firstTimeAt(ScreenSize.EXPANDED, 1f)

    @Test
    fun `first time, compact at font scale 2`() = firstTimeAt(ScreenSize.COMPACT, 2f)

    @Test
    fun `first time, medium at font scale 2`() = firstTimeAt(ScreenSize.MEDIUM, 2f)

    @Test
    fun `first time, expanded at font scale 2`() = firstTimeAt(ScreenSize.EXPANDED, 2f)

    @Test
    fun `a missing file shows the repair heading, not the guide, and Choose file for that part`() {
        val chosen = mutableListOf<PresetSlot>()
        show(missingFrame, onChoose = { chosen += it })
        compose.onNodeWithText(s(R.string.preset_repair_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.preset_slot_missing)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.preset_guide_title)).assertDoesNotExist()
        val frame = context.getString(R.string.preset_choose_file_for, s(R.string.preset_slot_frame))
        compose.onAllNodesWithContentDescription(frame)[0].performScrollTo().performClick()
        assertThat(chosen).containsExactly(PresetSlot.FRAME)
        compose.onNodeWithText(s(R.string.action_next)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `with every part ready, Next is enabled`() {
        var done = 0
        show(ready, onDone = { done++ })
        compose
            .onNodeWithText(s(R.string.action_next))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun `a file used where it is shows Checking the file, never Copying`() {
        show(firstTime, work = SetupWork.Checking)
        compose.onNodeWithText(s(R.string.preset_bind_checking)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.preset_bind_copying)).assertDoesNotExist()
    }

    @Test
    fun `a copy the preset asked for shows Copying with no reason`() {
        show(firstTime, work = SetupWork.Copying(10, 100))
        compose.onNodeWithText(s(R.string.preset_bind_copying)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.preset_bind_copy_why_source)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.preset_bind_copy_why_limit)).assertDoesNotExist()
    }

    private fun copyInsteadAt(
        reason: CopyReason,
        text: Int,
        fontScale: Float,
        size: ScreenSize = ScreenSize.COMPACT,
    ) {
        show(firstTime, size, fontScale = fontScale, work = SetupWork.Copying(10, 100, reason))
        compose.onNodeWithText(s(R.string.preset_bind_copying)).assertIsDisplayed()
        compose.onNodeWithText(s(text)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_cancel)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a copy instead of a link says the place is why`() = copyInsteadAt(CopyReason.SOURCE, R.string.preset_bind_copy_why_source, 1f)

    @Test
    fun `a copy instead of a link says the limit is why`() = copyInsteadAt(CopyReason.LIMIT, R.string.preset_bind_copy_why_limit, 1f)

    @Test
    fun `a copy instead of a link keeps its reason and Cancel on screen at font scale 2`() =
        copyInsteadAt(CopyReason.SOURCE, R.string.preset_bind_copy_why_source, 2f)

    @Test
    fun `a copy instead of a link keeps Cancel reachable sideways at font scale 2`() =
        copyInsteadAt(CopyReason.LIMIT, R.string.preset_bind_copy_why_limit, 2f, ScreenSize.MEDIUM)

    @Test
    fun `after a copy instead of a link, a note says why`() {
        show(ready, message = SetupMessage.KeptCopy(CopyReason.SOURCE))
        compose.onNodeWithText(s(R.string.preset_bind_copy_why_source)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a wrong file explains itself in plain words`() {
        show(firstTime, message = SetupMessage.NotAVideo)
        compose.onNodeWithText(s(R.string.preset_bind_not_video)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the scroll position survives a restore`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            AtSize(ScreenSize.COMPACT, 2f) {
                ClearMovieTheme(dynamicColor = false) {
                    PresetSetupScreen(SetupUi(Fixtures.preset, firstTime), newVideo = true, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText(s(R.string.action_next)).performScrollTo().assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(s(R.string.action_next)).assertIsDisplayed()
    }
}
