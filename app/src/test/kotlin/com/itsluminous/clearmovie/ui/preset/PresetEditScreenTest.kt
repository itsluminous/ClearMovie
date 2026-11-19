package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetDefinition
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.CaptionTab
import com.itsluminous.clearmovie.ui.editor.captionTabTag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** cm-preset-manager: the preset editor and the new-preset screen. */
@RunWith(RobolectricTestRunner::class)
class PresetEditScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private fun binding(slot: PresetSlot) = PresetBinding(slot, File("/x/$slot"), "00", 1, null, null, 6_000_000, null, null, null, null, 0)

    private val own: Preset =
        Fixtures.preset.copy(id = PresetId("my-tok1"), displayNameRes = null, displayName = LocalizedText.same("Spasht Dhaara"))
    private val ready = PresetStatus(own.id, PresetSlot.entries.associateWith { SlotStatus.Ready(binding(it)) as SlotStatus })

    private class Calls {
        var saved: Pair<String, String>? = null
        var deleted = 0
        var reset = 0
        var files = 0
        var back = 0
        var style: CaptionStyle? = null
        var words: String? = null
    }

    private fun show(
        definition: PresetDefinition,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
        calls: Calls = Calls(),
    ): Calls {
        compose.setContent {
            var ui by androidx.compose.runtime.remember {
                mutableStateOf(PresetEditUi(definition = definition, status = ready))
            }
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) {
                    PresetEditScreen(
                        ui = ui,
                        onName = { ui = ui.copy(draftName = it) },
                        onStyle = {
                            calls.style = it
                            ui = ui.copy(draftStyle = it)
                        },
                        onWords = {
                            calls.words = it
                            ui = ui.copy(draftWords = it)
                        },
                        onRevertCaption = { st, w -> ui = ui.copy(draftStyle = st, draftWords = w) },
                        onFiles = { calls.files++ },
                        onSave = { n, w -> calls.saved = n to w },
                        onDelete = { calls.deleted++ },
                        onReset = { calls.reset++ },
                        onBack = { calls.back++ },
                        onDismissMessage = {},
                    )
                }
            }
        }
        return calls
    }

    @Test
    fun `a preset the user made can be deleted after a confirm that says its videos keep working`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false))
        compose.onNodeWithTag(PRESET_RESET_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription(s(R.string.preset_delete)).performClick()
        compose.onNodeWithText(s(R.string.preset_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.preset_delete_body, "Spasht Dhaara")).assertIsDisplayed()
        // Cancel deletes nothing.
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        assertThat(calls.deleted).isEqualTo(0)
        compose.onNodeWithContentDescription(s(R.string.preset_delete)).performClick()
        compose.onNodeWithTag(PRESET_CONFIRM_TAG).performClick()
        assertThat(calls.deleted).isEqualTo(1)
    }

    @Test
    fun `the built-in preset is never deleted, and offers Reset only once it was changed`() {
        show(PresetDefinition(Fixtures.preset, PresetDefinition.Source.BUNDLED))
        compose.onNodeWithTag(PRESET_DELETE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PRESET_RESET_TAG).assertDoesNotExist()
    }

    @Test
    fun `an edited built-in preset resets after a confirm`() {
        val calls =
            show(
                PresetDefinition(
                    Fixtures.preset.copy(displayName = LocalizedText.same("Mine")),
                    PresetDefinition.Source.USER,
                    builtIn = true,
                ),
            )
        compose.onNodeWithTag(PRESET_DELETE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PRESET_RESET_TAG).performClick()
        compose.onNodeWithText(s(R.string.preset_reset_body)).assertIsDisplayed()
        compose.onNodeWithTag(PRESET_CONFIRM_TAG).performClick()
        assertThat(calls.reset).isEqualTo(1)
    }

    @Test
    fun `renaming and saving hands the shown name over, and an empty name cannot be saved`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false))
        compose.onNodeWithTag(PRESET_NAME_TAG).performTextReplacement("   ")
        compose.onNodeWithTag(PRESET_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(PRESET_NAME_TAG).performScrollTo().performTextReplacement("New name")
        compose
            .onNodeWithTag(PRESET_SAVE_TAG)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertThat(calls.saved).isEqualTo("Spasht Dhaara" to "")
    }

    @Test
    fun `leaving with unsaved changes asks first`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false))
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        assertThat(calls.back).isEqualTo(1)
        compose.onNodeWithTag(PRESET_NAME_TAG).performTextReplacement("Changed")
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        compose.onNodeWithText(s(R.string.preset_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.preset_keep_editing)).performClick()
        assertThat(calls.back).isEqualTo(1)
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        compose.onNodeWithText(s(R.string.preset_discard)).performClick()
        assertThat(calls.back).isEqualTo(2)
    }

    @Test
    fun `the caption style editor changes the words and style live, and Cancel puts them back`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false))
        compose.onNodeWithTag(PRESET_CAPTION_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRESET_WORDS_TAG).performTextReplacement("सुंदर प्रकृति")
        assertThat(calls.words).isEqualTo("सुंदर प्रकृति")
        compose.onNodeWithTag(captionTabTag(CaptionTab.COLOUR)).performClick()
        compose.onNodeWithText(s(R.string.caption_background)).performClick()
        assertThat(calls.style!!.background).isNull()
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        // Back on the form, nothing changed: Back leaves without asking.
        compose.onNodeWithTag(PRESET_SAVE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        assertThat(calls.back).isEqualTo(1)
    }

    @Test
    fun `Change files opens the files in two panes`() = filesAt(ScreenSize.MEDIUM, 1f)

    @Test
    fun `Change files opens the files at large text`() = filesAt(ScreenSize.COMPACT, 2f)

    private fun filesAt(
        size: ScreenSize,
        scale: Float,
    ) {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false), size, scale)
        compose.onNodeWithTag(PRESET_FILES_TAG).performScrollTo().performClick()
        assertThat(calls.files).isEqualTo(1)
        // In two panes the picture stays put on the left; on a phone it scrolls with the rest.
        val preview = compose.onNodeWithTag(PRESET_CAPTION_PREVIEW_TAG)
        if (size == ScreenSize.COMPACT) preview.performScrollTo()
        preview.assertIsDisplayed()
    }

    @Test
    fun `the caption editor stays open across a rotation`() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            ClearMovieTheme(dynamicColor = false) {
                PresetEditScreen(
                    ui = PresetEditUi(definition = PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false), status = ready),
                    onName = {},
                    onStyle = {},
                    onWords = {},
                    onRevertCaption = { _, _ -> },
                    onFiles = {},
                    onSave = { _, _ -> },
                    onDelete = {},
                    onReset = {},
                    onBack = {},
                    onDismissMessage = {},
                )
            }
        }
        compose.onNodeWithTag(PRESET_CAPTION_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(captionTabTag(CaptionTab.FONT)).performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(PRESET_WORDS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(captionTabTag(CaptionTab.FONT)).assertIsDisplayed()
    }

    @Test
    fun `a new preset needs a name, then Create`() {
        var ui by mutableStateOf(PresetCreateUi())
        var created = 0
        compose.setContent {
            ClearMovieTheme(dynamicColor = false) {
                PresetCreateScreen(
                    ui = ui,
                    onName = { ui = ui.copy(name = it) },
                    onIntro = { ui = ui.copy(intro = it) },
                    onFrame = { ui = ui.copy(frame = it) },
                    onEnding = { ui = ui.copy(ending = it) },
                    onCreate = { created++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag(PRESET_CREATE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(PRESET_CREATE_NAME_TAG).performTextReplacement("Mine")
        compose.onNodeWithTag(presetPartTag("intro")).performClick()
        compose.onNodeWithTag(endingChoiceTag(EndingChoice.VIDEO)).performScrollTo().performClick()
        assertThat(ui.intro).isFalse()
        assertThat(ui.ending).isEqualTo(EndingChoice.VIDEO)
        compose
            .onNodeWithTag(PRESET_CREATE_TAG)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertThat(created).isEqualTo(1)
    }

    @Test
    fun `only a draft that differs from what is shown is a change`() {
        val ui = PresetEditUi(definition = PresetDefinition(own, PresetDefinition.Source.USER, builtIn = false))
        assertThat(PresetEditViewModel.changedPreset(own, ui.copy(draftName = " Spasht Dhaara "), "Spasht Dhaara", "")).isNull()
        assertThat(PresetEditViewModel.changedPreset(own, ui.copy(draftStyle = own.caption), "Spasht Dhaara", "")).isNull()
        val renamed = PresetEditViewModel.changedPreset(own, ui.copy(draftName = "Other"), "Spasht Dhaara", "")!!
        assertThat(renamed.displayName).isEqualTo(LocalizedText.same("Other"))
        val worded = PresetEditViewModel.changedPreset(own, ui.copy(draftWords = "नमस्ते"), "Spasht Dhaara", "")!!
        assertThat(worded.defaultText).isEqualTo(LocalizedText.same("नमस्ते"))
        val cleared =
            PresetEditViewModel.changedPreset(
                own.copy(defaultText = LocalizedText.same("x")),
                ui.copy(draftWords = ""),
                "Spasht Dhaara",
                "x",
            )!!
        assertThat(cleared.defaultText).isNull()
        // A name that shows nothing is never stored.
        assertThat(PresetEditViewModel.changedPreset(own, ui.copy(draftName = "\u200B"), "Spasht Dhaara", "")).isNull()
        assertThat(PresetEditViewModel.isUsableName("x".repeat(61))).isFalse()
    }
}
