package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.ScreenSize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's actions (kupraki's request): Export in the playback strip opens
 * the export sheet, More options holds Rename video, Video shape and
 * Settings, and a tap on the title or Rename video opens one rename dialog
 * whose name is an undoable edit of the video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val project: Project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("m"))
            .addToMain(listOf(Fixtures.photo("p1"), Fixtures.video("v2", 4000)))

    private fun show(
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
    ): EditorHarness {
        val h = EditorHarness(project, Fixtures.preset).also { it.title = "Party" }
        compose.setContent { h.At(size, fontScale) }
        compose.waitForIdle()
        return h
    }

    private fun openMenu() = compose.onNodeWithTag(EDITOR_MORE_TAG).performClick()

    @Test
    fun `Export in the playback strip opens the export sheet, at every size and text size, even with nothing to play`() {
        var shown by mutableStateOf(ScreenSize.COMPACT to 1f)
        var harness by mutableStateOf(EditorHarness(project, Fixtures.preset))
        compose.setContent { key(shown, harness) { harness.At(shown.first, shown.second) } }
        for (scale in listOf(1f, 2f)) {
            for (size in ScreenSize.entries) {
                for (p in listOf(project, ProjectFactory.blank(Fixtures.PROJECT_ID))) {
                    val h = EditorHarness(p, Fixtures.preset).also { it.title = "Party" }
                    shown = size to scale
                    harness = h
                    compose.waitForIdle()
                    compose
                        .onNodeWithTag(EDITOR_EXPORT_TAG)
                        .assertIsDisplayed()
                        .assertIsEnabled()
                        .assertHasClickAction()
                        .performClick()
                    assertThat(h.calls).containsExactly("export")
                    compose.onNodeWithTag(PLAY_TAG).assertExists()
                }
            }
        }
    }

    @Test
    fun `More options holds Rename video, Video shape, Settings and Delete video, in that order, each 48 dp tall, at every size`() {
        var shown by mutableStateOf(ScreenSize.COMPACT to 1f)
        var harness by mutableStateOf(EditorHarness(project, Fixtures.preset))
        compose.setContent { key(shown, harness) { harness.At(shown.first, shown.second) } }
        for (size in ScreenSize.entries) {
            for (scale in listOf(1f, 2f)) {
                harness = EditorHarness(project, Fixtures.preset)
                shown = size to scale
                compose.waitForIdle()
                openMenu()
                val tops =
                    listOf(EDITOR_MENU_RENAME_TAG, EDITOR_MENU_SHAPE_TAG, EDITOR_MENU_SETTINGS_TAG, EDITOR_MENU_DELETE_TAG).map { tag ->
                        compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                        compose
                            .onNodeWithTag(tag)
                            .fetchSemanticsNode()
                            .boundsInRoot.top
                    }
                assertThat(tops).isInStrictOrder()
                compose.onNodeWithText(s(R.string.editor_menu_rename)).assertIsDisplayed()
                compose.onNodeWithText(s(R.string.editor_menu_shape)).assertIsDisplayed()
                compose.onNodeWithText(s(R.string.action_settings)).assertIsDisplayed()
                compose.onNodeWithText(s(R.string.editor_menu_delete)).assertIsDisplayed()
                // One height for every item.
                val heights =
                    listOf(EDITOR_MENU_RENAME_TAG, EDITOR_MENU_SHAPE_TAG, EDITOR_MENU_SETTINGS_TAG, EDITOR_MENU_DELETE_TAG).map {
                        compose
                            .onNodeWithTag(it)
                            .fetchSemanticsNode()
                            .size.height
                    }
                assertThat(heights.toSet()).hasSize(1)
                compose.onNodeWithTag(EDITOR_MENU_SETTINGS_TAG).performClick()
                assertThat(harness.calls).containsExactly("settings")
                // The menu closed on the way.
                compose.onNodeWithTag(EDITOR_MENU_SETTINGS_TAG).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `Delete video in More options asks the owner, and the menu closes`() {
        val h = show()
        openMenu()
        compose
            .onNodeWithTag(EDITOR_MENU_DELETE_TAG)
            .assertIsEnabled()
            .performClick()
        assertThat(h.calls).containsExactly("delete")
        compose.onNodeWithTag(EDITOR_MENU_DELETE_TAG).assertDoesNotExist()
        // Nothing is deleted by the menu itself: the video is still on screen.
        assertThat(h.project.mainClips).isNotEmpty()
    }

    @Test
    fun `Video shape stays in More options`() {
        val h = show()
        openMenu()
        compose.onNodeWithTag(EDITOR_MENU_SHAPE_TAG).performClick()
        assertThat(h.calls).containsExactly("shape")
    }

    @Test
    fun `a tap on the title opens Rename with the shown name, all of it selected`() {
        show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_menu_rename)).assertIsDisplayed()
        val field = compose.onNodeWithTag(RENAME_FIELD_TAG).fetchSemanticsNode()
        assertThat(field.config[SemanticsProperties.EditableText].text).isEqualTo("Party")
        // All of it selected, so typing replaces it.
        val selection = field.config[SemanticsProperties.TextSelectionRange]
        assertThat(selection.min).isEqualTo(0)
        assertThat(selection.max).isEqualTo("Party".length)
    }

    @Test
    fun `Rename video in More options opens the same dialog, and Rename names the video, trimmed, as one undo step`() {
        val h = show()
        openMenu()
        compose.onNodeWithTag(EDITOR_MENU_RENAME_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("  Asha's birthday  ")
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertDoesNotExist()
        assertThat(h.project.name).isEqualTo("Asha's birthday")
        // The bar shows the new name, and Undo takes it back.
        compose.onNodeWithTag(EDITOR_TITLE_TAG).assertTextEquals("Asha's birthday")
        compose.onNodeWithTag(UNDO_TAG).assertIsEnabled().performClick()
        assertThat(h.project.name).isNull()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).assertTextEquals("Party")
        compose.onNodeWithTag(REDO_TAG).performClick()
        assertThat(h.project.name).isEqualTo("Asha's birthday")
        // Opened again, it starts from the name it has now.
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        assertThat(
            compose
                .onNodeWithTag(RENAME_FIELD_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        ).isEqualTo("Asha's birthday")
    }

    @Test
    fun `a long name shows whole in the field, from its first word`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        val long = "Riya and Arjun's wedding sangeet night at the Jaipur farmhouse"
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement(long)
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        assertThat(h.project.name).isEqualTo(long)
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose
            .onNodeWithTag(RENAME_FIELD_TAG)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        val layout = results.single()
        assertThat(layout.lineCount).isGreaterThan(1)
        assertThat(layout.hasVisualOverflow).isFalse()
    }

    @Test
    fun `Enter renames, as Done does`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("Diwali")
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("\n")
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertDoesNotExist()
        assertThat(h.project.name).isEqualTo("Diwali")
    }

    @Test
    fun `the keyboard's Done renames too`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("Diwali")
        compose.onNodeWithTag(RENAME_FIELD_TAG).performImeAction()
        assertThat(h.project.name).isEqualTo("Diwali")
    }

    @Test
    fun `an empty name, or only spaces, cannot be used, and says what it needs`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        for (empty in listOf("", "    ")) {
            compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement(empty)
            compose.onNodeWithTag(RENAME_CONFIRM_TAG).assertIsNotEnabled()
            compose.onNodeWithText(s(R.string.editor_rename_needed)).assertIsDisplayed()
            compose.onNodeWithTag(RENAME_FIELD_TAG).performImeAction()
            compose.onNodeWithTag(RENAME_DIALOG_TAG).assertIsDisplayed()
        }
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Holi")
        compose.onNodeWithText(s(R.string.editor_rename_needed)).assertDoesNotExist()
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).assertIsEnabled()
        assertThat(h.project.name).isNull()
    }

    @Test
    fun `Cancel keeps the name, and Rename with nothing changed adds no undo step`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("Something else")
        compose.onNodeWithTag(RENAME_CANCEL_TAG).performClick()
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertDoesNotExist()
        assertThat(h.project.name).isNull()
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()

        // An unnamed video's stand-in title, confirmed as it is, is not made its name.
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertDoesNotExist()
        assertThat(h.project.name).isNull()
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()

        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("Diwali")
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        assertThat(h.project.name).isEqualTo("Diwali")
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        // Undo once takes the name back: the second, unchanged rename was not a step.
        compose.onNodeWithTag(UNDO_TAG).performClick()
        assertThat(h.project.name).isNull()
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a name never grows past the limit, and a change in its middle never cuts its end`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        val long = "a".repeat(Limits.MAX_NAME_LENGTH - 1) + "Z"
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement(long)
        // Typing in the middle of a full name is not taken; the end stays as it was.
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInputSelection(TextRange(10))
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("x")
        assertThat(fieldText()).isEqualTo(long)
        // A paste that would go past it is not taken either.
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement(long + "more")
        assertThat(fieldText()).isEqualTo(long)
        // Shorter is always fine.
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("Party time")
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        assertThat(h.project.name).isEqualTo("Party time")
    }

    @Test
    fun `the stand-in title typed out on purpose becomes the name`() {
        val h = show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("")
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Party")
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        assertThat(h.project.name).isEqualTo("Party")
        compose.onNodeWithTag(UNDO_TAG).assertIsEnabled()
    }

    private fun fieldText(): String =
        compose
            .onNodeWithTag(RENAME_FIELD_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    @Test
    fun `an empty name is announced in the app's own words`() {
        show()
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("")
        val field = compose.onNodeWithTag(RENAME_FIELD_TAG).fetchSemanticsNode()
        assertThat(field.config[SemanticsProperties.Error]).isEqualTo(s(R.string.editor_rename_needed))
        val message = compose.onNodeWithText(s(R.string.editor_rename_needed)).fetchSemanticsNode()
        assertThat(message.config[SemanticsProperties.LiveRegion]).isEqualTo(LiveRegionMode.Polite)
    }

    @Test
    @Config(qualifiers = "hi-w1300dp-h1300dp-xxhdpi")
    fun `in Hindi Export, the menu and the dialog speak Hindi`() {
        show()
        compose.onNodeWithTag(EDITOR_EXPORT_TAG).assertTextEquals("एक्सपोर्ट")
        openMenu()
        compose.onNodeWithText("वीडियो का नाम बदलें").assertIsDisplayed()
        compose.onNodeWithText("वीडियो की आकृति").assertIsDisplayed()
        compose.onNodeWithText("सेटिंग्स").assertIsDisplayed()
        compose.onNodeWithText("वीडियो हटाएँ").assertIsDisplayed()
        compose.onNodeWithTag(EDITOR_MENU_RENAME_TAG).performClick()
        compose.onNodeWithText("नाम बदलें").assertIsDisplayed()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("")
        compose.onNodeWithText("इस वीडियो का नाम लिखें").assertIsDisplayed()
    }

    @Test
    fun `an open rename dialog and what was typed survive a rotation`() {
        val h = EditorHarness(project, Fixtures.preset).also { it.title = "Party" }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { h.At(ScreenSize.COMPACT) }
        compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("Asha's mehendi")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertIsDisplayed()
        assertThat(
            compose
                .onNodeWithTag(RENAME_FIELD_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        ).isEqualTo("Asha's mehendi")
        compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
        assertThat(h.project.name).isEqualTo("Asha's mehendi")
    }

    @Test
    fun `a name keeps to one line of allowed characters and the length limit`() {
        // A pasted line break and an invisible mark are left out; the cursor moves back with them.
        val pasted = allowedName(TextFieldValue("Asha\n\u200Bparty", TextRange(11)))
        assertThat(pasted.text).isEqualTo("Ashaparty")
        assertThat(pasted.selection).isEqualTo(TextRange(9))
        // A lone half of a two-part character is left out too.
        assertThat(allowedName(TextFieldValue("Asha\uD83C", TextRange(5))).text).isEqualTo("Asha")
        // The word the keyboard is composing keeps its place when something before it is left out.
        val composing = TextFieldValue("A\nnamaste", TextRange(9), composition = TextRange(2, 9))
        assertThat(allowedName(composing).composition).isEqualTo(TextRange(1, 8))
        assertThat(allowedName(composing).text).isEqualTo("Anamaste")
        // Allowed text comes back as it was, composition and all.
        val fine = TextFieldValue("Asha's party", TextRange(3))
        assertThat(allowedName(fine)).isSameInstanceAs(fine)
    }
}
