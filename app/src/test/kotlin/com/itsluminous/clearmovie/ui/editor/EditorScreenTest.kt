package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The editor shell (requirements 4 and 6): preview on top on a phone held
 * upright, on the left in two panes; Export always visible at the top right;
 * the read-only MAIN row.
 */
@RunWith(RobolectricTestRunner::class)
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain((1..5).map { if (it % 2 == 0) Fixtures.video("v$it") else Fixtures.photo("p$it") })
    private val ui =
        EditorUi(
            project = project,
            preset = Fixtures.preset,
            schedule = PreviewSchedule.of(RenderPlanBuilder(Fixtures.preset).build(project)),
        )

    private fun show(
        size: ScreenSize,
        fontScale: Float = 1f,
        state: EditorUi = ui,
        calls: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) { Editor(state, calls) }
            }
        }
    }

    @Composable
    private fun Editor(
        state: EditorUi,
        calls: MutableList<String>,
    ) {
        EditorScreen(
            ui = state,
            title = "Party",
            playhead = PlayheadUi(positionUs = 7_000_000, durationUs = 30_000_000),
            preview = { m -> Box(m.background(Color.Black).testTag(PREVIEW_TAG)) },
            thumbnail = { null },
            onBack = { calls += "back" },
            onExport = { calls += "export" },
            onTogglePlay = { calls += "play" },
            onSeek = { calls += "seek:$it" },
            onExact = { calls += "exact" },
            onAddMedia = { calls += "add" },
            onFixPreset = { calls += "fix" },
        )
    }

    private fun assertLayout(
        size: ScreenSize,
        fontScale: Float,
    ) {
        show(size, fontScale)
        val preview = compose.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        val export = compose.onNodeWithTag(EDITOR_EXPORT_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        val timeline = compose.onNodeWithTag(EDITOR_TIMELINE_TAG).getUnclippedBoundsInRoot()
        // Export sits at the top right of the screen (of the right pane in two panes).
        assertThat(export.right.value).isWithin(16f).of(size.size.width.value)
        if (size == ScreenSize.COMPACT) {
            assertThat(export.bottom.value).isAtMost(preview.top.value + 1f)
            assertThat(timeline.top.value).isAtLeast(preview.bottom.value)
        } else {
            assertThat(export.top.value).isLessThan(size.size.height.value / 4)
            assertThat(timeline.left.value).isAtLeast(preview.right.value)
            assertThat(preview.left.value).isLessThan(size.size.width.value / 4)
        }
        // A 16:9 picture.
        assertThat((preview.right - preview.left).value / (preview.bottom - preview.top).value).isWithin(0.02f).of(16f / 9f)
        compose.onNodeWithText(s(R.string.editor_row_main)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_play)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_exact)).assertIsDisplayed()
    }

    @Test
    fun compact() = assertLayout(ScreenSize.COMPACT, 1f)

    @Test
    fun medium() = assertLayout(ScreenSize.MEDIUM, 1f)

    @Test
    fun expanded() = assertLayout(ScreenSize.EXPANDED, 1f)

    @Test
    fun `compact at font scale 2`() = assertLayout(ScreenSize.COMPACT, 2f)

    @Test
    fun `medium at font scale 2`() = assertLayout(ScreenSize.MEDIUM, 2f)

    @Test
    fun `expanded at font scale 2`() = assertLayout(ScreenSize.EXPANDED, 2f)

    @Test
    fun `the MAIN row lists the intro, the five items and the ending, and a tile moves the playhead there`() {
        val calls = mutableListOf<String>()
        show(ScreenSize.EXPANDED, calls = calls)
        val photo = context.getString(R.string.editor_item_description, s(R.string.editor_item_photo), "0:03")
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.editor_item_description, s(R.string.preset_slot_intro), "0:07"),
            ).assertIsDisplayed()
        // The first photo starts where the intro ends.
        compose.onAllNodesWithContentDescription(photo)[0].performClick()
        compose.onNodeWithText(s(R.string.editor_export)).performClick()
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        compose.onNodeWithText(s(R.string.action_play)).performClick()
        compose.onNodeWithText(s(R.string.editor_exact)).performClick()
        assertThat(calls).containsExactly("seek:6500000", "export", "back", "play", "exact").inOrder()
    }

    @Test
    fun `an empty video offers Add photos and videos`() {
        val calls = mutableListOf<String>()
        val empty = ProjectFactory.blank(Fixtures.PROJECT_ID)
        show(ScreenSize.COMPACT, state = EditorUi(project = empty), calls = calls)
        compose.onNodeWithText(s(R.string.editor_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_add_media)).performScrollTo().performClick()
        assertThat(calls).containsExactly("add")
    }

    @Test
    fun `a missing preset file offers the repair screen`() {
        val calls = mutableListOf<String>()
        show(ScreenSize.COMPACT, state = ui.copy(presetNeedsRepair = true), calls = calls)
        compose.onNodeWithText(s(R.string.preset_repair_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_fix_preset)).performScrollTo().performClick()
        assertThat(calls).containsExactly("fix")
    }

    @Test
    fun `the editor shell comes back whole after a restore`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            AtSize(
                ScreenSize.COMPACT,
                2f,
            ) { ClearMovieTheme(dynamicColor = false) { Editor(ui.copy(presetNeedsRepair = true), mutableListOf()) } }
        }
        compose.onNodeWithText(s(R.string.editor_row_main)).performScrollTo().assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(s(R.string.editor_row_main)).assertIsDisplayed()
        compose.onNodeWithTag(EDITOR_EXPORT_TAG).assertIsDisplayed()
    }
}
