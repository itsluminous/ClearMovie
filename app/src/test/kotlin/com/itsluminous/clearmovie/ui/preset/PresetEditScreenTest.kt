package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetDefinition
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.EffectDefaults
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.components.shapeOptionTag
import com.itsluminous.clearmovie.ui.editor.CaptionTab
import com.itsluminous.clearmovie.ui.editor.animationTileTag
import com.itsluminous.clearmovie.ui.editor.captionTabTag
import com.itsluminous.clearmovie.ui.editor.transitionTileTag
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
        var files = 0
        var back = 0
        var style: CaptionStyle? = null
        var words: String? = null
        var animation: AnimKind? = AnimKind.ZOOM_OUT
        var photoAnimation: AnimKind? = AnimKind.ZOOM_OUT
        var transition: TransitionKind? = null
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
                        onRevertCaption = { st, w, e -> ui = ui.copy(draftStyle = st, draftWords = w, draftEffects = e) },
                        onFiles = { calls.files++ },
                        onSave = { n, w -> calls.saved = n to w },
                        onDelete = { calls.deleted++ },
                        onBack = { calls.back++ },
                        onDismissMessage = {},
                        onCaptionAnimation = {
                            calls.animation = it
                            ui = ui.copy(draftEffects = (ui.draftEffects ?: definition.preset.effects).copy(textStart = it))
                        },
                        // As the view model does (cm-preset-motion-defaults).
                        onPhotoAnimation = { kind ->
                            calls.photoAnimation = kind
                            PresetEditViewModel
                                .withPhotoAnimation(ui.draftEffects ?: definition.preset.effects, kind)
                                ?.let { ui = ui.copy(draftEffects = it) }
                        },
                        onTransition = { kind ->
                            calls.transition = kind
                            val effects = ui.draftEffects ?: definition.preset.effects
                            ui = ui.copy(draftEffects = PresetEditViewModel.withTransition(effects, definition.preset.effects, kind))
                        },
                    )
                }
            }
            shownUi = ui
        }
        return calls
    }

    private var shownUi: PresetEditUi? = null

    /** The node tagged [tag], scrolled into sight when it sits in something that scrolls. */
    private fun inSight(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        val scrolls =
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(tag) and
                        androidx.compose.ui.test
                            .hasAnyAncestor(
                                androidx.compose.ui.test
                                    .hasScrollAction(),
                            ),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        return if (scrolls) node.performScrollTo() else node
    }

    @Test
    fun `a preset the user made can be deleted after a confirm that says its videos keep working`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER))
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
    fun `the preset an older version shipped is deleted like any other`() {
        val calls = show(PresetDefinition(Fixtures.preset, PresetDefinition.Source.USER))
        compose.onNodeWithTag(PRESET_DELETE_TAG).performClick()
        compose.onNodeWithTag(PRESET_CONFIRM_TAG).performClick()
        assertThat(calls.deleted).isEqualTo(1)
    }

    @Test
    fun `renaming and saving hands the shown name over, and an empty name cannot be saved`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER))
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
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER))
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
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER))
        compose.onNodeWithTag(PRESET_CAPTION_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRESET_WORDS_TAG).performTextReplacement("नमस्ते")
        assertThat(calls.words).isEqualTo("नमस्ते")
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
    fun `the caption style editor sets the animation new captions start with, None included, and Cancel puts it back`() {
        val calls =
            show(
                PresetDefinition(
                    own.copy(effects = EffectDefaults(textStart = AnimKind.FADE_IN)),
                    PresetDefinition.Source.USER,
                ),
            )
        compose.onNodeWithTag(PRESET_CAPTION_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(captionTabTag(CaptionTab.ANIMATION)).performClick()
        compose.onNodeWithText(s(R.string.preset_caption_animation_hint)).assertIsDisplayed()
        // The preset's own animation is the one marked; only start animations a caption may have are offered.
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, AnimKind.FADE_IN)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, AnimKind.KEN_BURNS_IN)).assertDoesNotExist()
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, AnimKind.FADE_OUT)).assertDoesNotExist()
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, AnimKind.POP)).performScrollTo().performClick()
        assertThat(calls.animation).isEqualTo(AnimKind.POP)
        compose.waitForIdle()
        assertThat(shownUi!!.captionAnimation).isEqualTo(AnimKind.POP)
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, null)).performScrollTo().performClick()
        assertThat(calls.animation).isNull()
        compose.waitForIdle()
        assertThat(shownUi!!.captionAnimation).isNull()
        // Cancel: the preset's own animation again, and nothing to save.
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        assertThat(shownUi!!.captionAnimation).isEqualTo(AnimKind.FADE_IN)
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        assertThat(calls.back).isEqualTo(1)
    }

    @Test
    fun `the motion card shows what new items get, and the animation choices set it, None included, until Cancel`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER))
        // The preset's own: the template's gentle zoom and crossfade.
        compose.onNodeWithTag(PRESET_MOTION_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.anim_ken_burns_in)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.transition_crossfade)).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag(PRESET_ANIMATION_TAG)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText(s(R.string.preset_animation_hint)).assertIsDisplayed()
        // Only what a photo may have all through: None and the two gentle zooms, the preset's marked.
        compose.onNodeWithTag(animationTileTag(AnimSlot.DURING, AnimKind.KEN_BURNS_IN)).assertIsSelected()
        compose.onNodeWithTag(animationTileTag(AnimSlot.DURING, AnimKind.PULSE)).assertDoesNotExist()
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, AnimKind.FADE_IN)).assertDoesNotExist()
        compose.onNodeWithTag(animationTileTag(AnimSlot.DURING, AnimKind.KEN_BURNS_OUT)).performClick()
        assertThat(calls.photoAnimation).isEqualTo(AnimKind.KEN_BURNS_OUT)
        compose.waitForIdle()
        assertThat(shownUi!!.photoAnimation).isEqualTo(AnimKind.KEN_BURNS_OUT)
        compose.onNodeWithTag(animationTileTag(AnimSlot.DURING, null)).performClick()
        compose.waitForIdle()
        assertThat(shownUi!!.photoAnimation).isNull()
        // Cancel: the preset's own again, the card shows it, and there is nothing to save.
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        assertThat(shownUi!!.photoAnimation).isEqualTo(AnimKind.KEN_BURNS_IN)
        compose.onNodeWithTag(PRESET_MOTION_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        assertThat(calls.back).isEqualTo(1)
    }

    @Test
    fun `the transition choices set the default transition, Done keeps it on the card, and Save stores it`() {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER))
        compose.onNodeWithTag(PRESET_TRANSITION_TAG).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.preset_transition_hint)).assertIsDisplayed()
        compose.onNodeWithTag(transitionTileTag(TransitionKind.CROSSFADE)).assertIsSelected()
        compose.onNodeWithTag(transitionTileTag(TransitionKind.WIPE_LEFT)).performScrollTo().performClick()
        assertThat(calls.transition).isEqualTo(TransitionKind.WIPE_LEFT)
        compose.onNodeWithText(s(R.string.action_done)).performClick()
        compose.waitForIdle()
        // The preset's 0.6 s length is kept.
        assertThat(shownUi!!.transition).isEqualTo(Transition(TransitionKind.WIPE_LEFT, 600))
        val name = context.getString(R.string.effect_tile_with_direction, s(R.string.transition_wipe), s(R.string.direction_left))
        compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        // A real change: leaving asks first, and Save hands it over.
        assertThat(PresetEditViewModel.changedPreset(own, shownUi!!, "Spasht Dhaara", "")!!.effects.mainTransition)
            .isEqualTo(Transition(TransitionKind.WIPE_LEFT, 600))
        compose.onNodeWithTag(PRESET_SAVE_TAG).performScrollTo().performClick()
        assertThat(calls.saved).isNotNull()
    }

    @Test
    fun `the motion rows and their choices fit at font scale 2 on a phone`() = motionFits(ScreenSize.COMPACT, 2f)

    @Test
    fun `the motion rows and their choices fit in two panes`() = motionFits(ScreenSize.MEDIUM, 1f)

    private fun motionFits(
        size: ScreenSize,
        scale: Float,
    ) {
        show(PresetDefinition(own, PresetDefinition.Source.USER), size, scale)
        inSight(PRESET_ANIMATION_TAG).assertHeightIsAtLeast(48.dp)
        inSight(PRESET_TRANSITION_TAG).assertHeightIsAtLeast(48.dp).performClick()
        inSight(transitionTileTag(TransitionKind.ZOOM_IN)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_cancel)).performClick()
        inSight(PRESET_ANIMATION_TAG).performClick()
        inSight(animationTileTag(AnimSlot.DURING, AnimKind.KEN_BURNS_OUT)).assertIsDisplayed()
    }

    @Test
    fun `a default photo animation must suit a photo all through, and a transition keeps its length through None`() {
        val effects = own.effects
        assertThat(PresetEditViewModel.withPhotoAnimation(effects, AnimKind.FADE_IN)).isNull()
        assertThat(PresetEditViewModel.withPhotoAnimation(effects, AnimKind.PULSE)).isNull()
        assertThat(PresetEditViewModel.withPhotoAnimation(effects, null)!!.photoDuring).isNull()
        assertThat(PresetEditViewModel.withPhotoAnimation(effects, AnimKind.KEN_BURNS_OUT)!!.photoDuring).isEqualTo(AnimKind.KEN_BURNS_OUT)
        // Only the photo animation changes.
        assertThat(PresetEditViewModel.withPhotoAnimation(effects, null)!!.copy(photoDuring = effects.photoDuring)).isEqualTo(effects)
        val none = PresetEditViewModel.withTransition(effects, effects, TransitionKind.NONE)
        assertThat(none.mainTransition).isNull()
        // None and back: the preset's own length.
        assertThat(PresetEditViewModel.withTransition(none, effects, TransitionKind.SLIDE_UP).mainTransition)
            .isEqualTo(Transition(TransitionKind.SLIDE_UP, effects.mainTransition!!.durationMs))
        // A preset without one gets the editor's usual length.
        assertThat(PresetEditViewModel.withTransition(EffectDefaults(), EffectDefaults(), TransitionKind.CIRCLE).mainTransition)
            .isEqualTo(Transition(TransitionKind.CIRCLE, Transition.DEFAULT_MS))
    }

    @Test
    fun `the Animation tab fits at font scale 2 on a phone`() {
        show(PresetDefinition(own, PresetDefinition.Source.USER), ScreenSize.COMPACT, 2f)
        compose.onNodeWithTag(PRESET_CAPTION_CHANGE_TAG).performScrollTo().performClick()
        inSight(captionTabTag(CaptionTab.ANIMATION)).assertHeightIsAtLeast(48.dp).performClick()
        inSight(animationTileTag(AnimSlot.START, AnimKind.BOUNCE)).assertIsDisplayed()
    }

    @Test
    fun `Change files opens the files in two panes`() = filesAt(ScreenSize.MEDIUM, 1f)

    @Test
    fun `Change files opens the files at large text`() = filesAt(ScreenSize.COMPACT, 2f)

    private fun filesAt(
        size: ScreenSize,
        scale: Float,
    ) {
        val calls = show(PresetDefinition(own, PresetDefinition.Source.USER), size, scale)
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
                    ui = PresetEditUi(definition = PresetDefinition(own, PresetDefinition.Source.USER), status = ready),
                    onName = {},
                    onStyle = {},
                    onWords = {},
                    onRevertCaption = { _, _, _ -> },
                    onFiles = {},
                    onSave = { _, _ -> },
                    onDelete = {},
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
        val ui = PresetEditUi(definition = PresetDefinition(own, PresetDefinition.Source.USER))
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
        // The caption animation is a change only when it differs; None is stored as none.
        assertThat(PresetEditViewModel.changedPreset(own, ui.copy(draftEffects = own.effects), "Spasht Dhaara", "")).isNull()
        val none = PresetEditViewModel.changedPreset(own, ui.copy(draftEffects = own.effects.copy(textStart = null)), "Spasht Dhaara", "")
        assertThat(none!!.effects.textStart).isNull()
        assertThat(none.effects.photoDuring).isEqualTo(own.effects.photoDuring)
        val pop =
            PresetEditViewModel.changedPreset(
                own,
                ui.copy(draftEffects = own.effects.copy(textStart = AnimKind.POP)),
                "Spasht Dhaara",
                "",
            )
        assertThat(pop!!.effects.textStart).isEqualTo(AnimKind.POP)
        // A name that shows nothing is never stored.
        assertThat(PresetEditViewModel.changedPreset(own, ui.copy(draftName = "\u200B"), "Spasht Dhaara", "")).isNull()
        assertThat(PresetEditViewModel.isUsableName("x".repeat(61))).isFalse()
    }

    @Test
    fun `a new preset offers the video shapes, landscape first chosen, at font scale 2`() {
        run {
            val scale = 2f
            var ui by mutableStateOf(PresetCreateUi())
            compose.setContent {
                AtSize(ScreenSize.COMPACT, scale) {
                    ClearMovieTheme(dynamicColor = false) {
                        PresetCreateScreen(
                            ui = ui,
                            onName = { ui = ui.copy(name = it) },
                            onIntro = {},
                            onFrame = {},
                            onEnding = {},
                            onCreate = {},
                            onBack = {},
                            onShape = { ui = ui.copy(shape = it) },
                        )
                    }
                }
            }
            compose.onNodeWithTag(shapeOptionTag(CanvasShape.LANDSCAPE)).performScrollTo().assertIsSelected()
            compose
                .onNodeWithTag(shapeOptionTag(CanvasShape.PORTRAIT))
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            assertThat(ui.shape).isEqualTo(CanvasShape.PORTRAIT)
            compose.onNodeWithTag(shapeOptionTag(CanvasShape.PORTRAIT)).assertIsSelected()
        }
    }

    @Test
    fun `a preset the user made can change its shape until a video uses it, and the kept Event frame shows none`() {
        val mine = PresetDefinition(own, PresetDefinition.Source.USER)
        var ui by mutableStateOf(PresetEditUi(definition = mine, status = ready))
        compose.setContent {
            ClearMovieTheme(dynamicColor = false) {
                PresetEditScreen(
                    ui = ui,
                    onName = {},
                    onStyle = { ui = ui.copy(draftStyle = it) },
                    onWords = {},
                    onRevertCaption = { _, _, _ -> },
                    onFiles = {},
                    onSave = { _, _ -> },
                    onDelete = {},
                    onBack = {},
                    onDismissMessage = {},
                    onShape = { ui = ui.copy(draftShape = it) },
                )
            }
        }
        compose
            .onNodeWithTag(shapeOptionTag(CanvasShape.SQUARE))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertThat(ui.shape).isEqualTo(CanvasShape.SQUARE)
        assertThat(PresetEditViewModel.changedPreset(own, ui, "Spasht Dhaara", "")!!.shape).isEqualTo(CanvasShape.SQUARE)
        // Once a video uses it, the shape stays, and says so.
        ui = PresetEditUi(definition = mine, status = ready, videosUsing = 2)
        compose.waitForIdle()
        compose.onNodeWithTag(shapeOptionTag(CanvasShape.SQUARE)).performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithText(context.getString(R.string.preset_shape_fixed, s(R.string.shape_landscape)))
            .performScrollTo()
            .assertIsDisplayed()
        // The kept Event frame is made for its frame's shape (its window is the one its definition names): no choice is shown.
        ui = PresetEditUi(definition = PresetDefinition(Fixtures.preset, PresetDefinition.Source.USER), status = ready)
        compose.waitForIdle()
        compose.onNodeWithTag(PRESET_SHAPE_TAG).assertDoesNotExist()
    }
}
