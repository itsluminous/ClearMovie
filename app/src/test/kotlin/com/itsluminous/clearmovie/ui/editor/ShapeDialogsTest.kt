package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.components.shapeOptionTag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The video shape in the editor (cm-aspect-ratio): the first-time question
 * for a video made without a preset, More options > Video shape, and its
 * dialog, at every window size and at font scale 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class ShapeDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun s(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private val sizes = listOf(ScreenSize.COMPACT to 1f, ScreenSize.MEDIUM to 1f, ScreenSize.COMPACT to 2f, ScreenSize.MEDIUM to 2f)

    @Test
    fun `the first-time question shows the suggestion chosen, and Continue takes it in one tap`() {
        var shown by mutableStateOf(ScreenSize.COMPACT to 1f)
        var chosen: CanvasShape? = null
        compose.setContent {
            val (size, scale) = shown
            key(shown) {
                AtSize(size, scale) { ClearMovieTheme(dynamicColor = false) { FirstShapeDialog(CanvasShape.PORTRAIT) { chosen = it } } }
            }
        }
        for (size in sizes) {
            shown = size
            chosen = null
            compose.waitForIdle()
            compose.onNodeWithText(s(R.string.shape_first_title)).assertIsDisplayed()
            compose.onNodeWithTag(shapeOptionTag(CanvasShape.PORTRAIT)).assertIsSelected()
            compose.onNodeWithTag(shapeOptionTag(CanvasShape.LANDSCAPE)).assertIsNotSelected()
            for (shape in CanvasShape.entries) {
                compose
                    .onNodeWithTag(shapeOptionTag(shape))
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp)
            }
            compose
                .onNodeWithTag(SHAPE_FIRST_CONTINUE_TAG)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            assertThat(chosen).isEqualTo(CanvasShape.PORTRAIT)
        }
    }

    @Test
    fun `another shape can be picked first, and the pick survives a rotation`() {
        val restore = StateRestorationTester(compose)
        var chosen: CanvasShape? = null
        restore.setContent { ClearMovieTheme(dynamicColor = false) { FirstShapeDialog(CanvasShape.LANDSCAPE) { chosen = it } } }
        compose.onNodeWithTag(shapeOptionTag(CanvasShape.SQUARE)).performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(shapeOptionTag(CanvasShape.SQUARE)).assertIsSelected()
        compose.onNodeWithTag(SHAPE_FIRST_CONTINUE_TAG).performClick()
        assertThat(chosen).isEqualTo(CanvasShape.SQUARE)
    }

    @Test
    fun `until the suggestion arrives nothing is chosen and Continue waits, unless the user picks`() {
        var suggested by mutableStateOf<CanvasShape?>(null)
        var chosen: CanvasShape? = null
        compose.setContent { ClearMovieTheme(dynamicColor = false) { FirstShapeDialog(suggested) { chosen = it } } }
        for (shape in CanvasShape.entries) compose.onNodeWithTag(shapeOptionTag(shape)).assertIsNotSelected()
        compose.onNodeWithTag(SHAPE_FIRST_CONTINUE_TAG).assertIsNotEnabled()
        suggested = CanvasShape.PORTRAIT
        compose.waitForIdle()
        compose.onNodeWithTag(shapeOptionTag(CanvasShape.PORTRAIT)).assertIsSelected()
        compose.onNodeWithTag(SHAPE_FIRST_CONTINUE_TAG).performClick()
        assertThat(chosen).isEqualTo(CanvasShape.PORTRAIT)
    }

    @Test
    fun `More options holds Video shape, out of the main bar, at every size`() {
        val project: Project =
            ProjectFactory
                .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("m"))
                .addToMain(listOf(Fixtures.photo("p1")))
        var shown by mutableStateOf(ScreenSize.COMPACT to 1f)
        var harness by mutableStateOf(EditorHarness(project, Fixtures.preset))
        compose.setContent { key(shown, harness) { harness.At(shown.first, shown.second) } }
        for (size in sizes + (ScreenSize.EXPANDED to 1f)) {
            harness = EditorHarness(project, Fixtures.preset)
            shown = size
            compose.waitForIdle()
            compose
                .onNodeWithTag(EDITOR_MORE_TAG)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            compose
                .onNodeWithTag(EDITOR_MENU_SHAPE_TAG)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            assertThat(harness.calls).contains("shape")
            // The title and Save video stay in the bar beside it.
            compose.onNodeWithTag(EDITOR_EXPORT_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `the change dialog starts on the current shape, says what happens to the frame, and changes on Change`() {
        var shown by mutableStateOf(ScreenSize.COMPACT to 1f)
        var changed: CanvasShape? = null
        var dismissed = false
        compose.setContent {
            key(shown) {
                AtSize(shown.first, shown.second) {
                    ClearMovieTheme(dynamicColor = false) {
                        ChangeShapeDialog(CanvasShape.LANDSCAPE, CanvasShape.LANDSCAPE, onChange = { changed = it }, onDismiss = {
                            dismissed =
                                true
                        })
                    }
                }
            }
        }
        for (size in sizes) {
            shown = size
            changed = null
            compose.waitForIdle()
            compose.onNodeWithTag(shapeOptionTag(CanvasShape.LANDSCAPE)).assertIsSelected()
            compose.onNodeWithTag(SHAPE_CHANGE_CONFIRM_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(shapeOptionTag(CanvasShape.PORTRAIT)).performScrollTo().performClick()
            compose.onNodeWithText(s(R.string.shape_change_frame_off)).performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithTag(SHAPE_CHANGE_CONFIRM_TAG)
                .assertIsEnabled()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            assertThat(changed).isEqualTo(CanvasShape.PORTRAIT)
        }
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun `going back to the frame's own shape says the frame comes back, and without a frame it speaks of captions`() {
        var frame by mutableStateOf<CanvasShape?>(CanvasShape.LANDSCAPE)
        compose.setContent {
            ClearMovieTheme(dynamicColor = false) { ChangeShapeDialog(CanvasShape.PORTRAIT, frame, onChange = {}, onDismiss = {}) }
        }
        compose.onNodeWithTag(shapeOptionTag(CanvasShape.LANDSCAPE)).performClick()
        compose.onNodeWithText(s(R.string.shape_change_frame_on)).assertIsDisplayed()
        frame = null
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.shape_change_captions)).assertIsDisplayed()
    }

    @Test
    fun `a frame the user already switched off is not said to be switched off`() {
        compose.setContent {
            ClearMovieTheme(dynamicColor = false) {
                ChangeShapeDialog(CanvasShape.LANDSCAPE, CanvasShape.LANDSCAPE, onChange = {}, onDismiss = {}, frameOn = false)
            }
        }
        compose.onNodeWithTag(shapeOptionTag(CanvasShape.SQUARE)).performClick()
        compose.onNodeWithText(s(R.string.shape_change_frame_off)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.shape_change_captions)).assertIsDisplayed()
    }

    @Test
    fun `the frame's shape is the preset's only while the video plays the preset's own frame file`() {
        val project =
            ProjectFactory.fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("f"))
        assertThat(frameShapeOf(project, Fixtures.preset)).isEqualTo(CanvasShape.LANDSCAPE)
        assertThat(frameShapeOf(project, null)).isNull()
        // A frame replaced for this video only: the preset does not know its shape, so it is left as it is.
        val replaced =
            ProjectFactory.fromPreset(
                Fixtures.PROJECT_ID,
                Fixtures.preset,
                Fixtures.presetMedia.copy(frame = MediaRef.Copied("own-frame.mp4")),
                SequentialIds("g"),
            )
        assertThat(frameShapeOf(replaced, Fixtures.preset)).isNull()
    }
}
