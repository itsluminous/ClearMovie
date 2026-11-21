package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionAttachment
import com.itsluminous.clearmovie.domain.model.CaptionFont
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.CaptionWords
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.render.plan.frameWindow
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The caption editor (ROADMAP cm-captions, Compose UI acceptance): Add
 * caption on a photo or video, the words, the place (chips and dragging the
 * box), the size slider and its sample, the fonts, the colours, outline and
 * band, Reset to preset style, Cancel and Done, one undo step per visit, and
 * the editor kept while typing across a restore; at the three window sizes
 * and font scales 1.0, 1.3 and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class CaptionEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("c"))
            .addToMain(listOf(Fixtures.photo("p1"), Fixtures.video("v2", 4000), Fixtures.photo("p3")))

    private val defaults: CaptionStyle get() = project.captionDefaults
    private val safe get() = CaptionPlaces.safeArea(frameWindow(project, Fixtures.preset))

    private var shown by mutableStateOf<Triple<ScreenSize, Float, EditorHarness>?>(null)
    private var contentSet = false

    private fun show(
        p: Project = project,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
    ): EditorHarness {
        val h = EditorHarness(p, Fixtures.preset)
        if (!contentSet) {
            contentSet = true
            compose.setContent { shown?.let { (sz, sc, hh) -> key(hh) { hh.At(sz, sc) } } }
        }
        shown = Triple(size, fontScale, h)
        compose.waitForIdle()
        return h
    }

    private fun EditorHarness.select(id: String) {
        state!!.selected = ClipId(id)
        state!!.panel = null
        compose.waitForIdle()
    }

    private fun tapBar(action: EditAction) {
        val scroller = compose.onAllNodes(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
        if (scroller.fetchSemanticsNodes().isNotEmpty()) scroller.onFirst().performScrollToNode(hasTestTag(barActionTag(action)))
        compose.onNodeWithTag(barActionTag(action)).performClick()
        compose.waitForIdle()
    }

    /** A control in the open panel, scrolled into view when it sits in the scrolling part (the title and tabs stay put). */
    private fun panel(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        val inScroll =
            compose
                .onAllNodes(
                    hasTestTag(tag) and
                        hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasScrollAction(),
                        ),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        return if (inScroll) node.performScrollTo() else node
    }

    private fun tab(t: CaptionTab) {
        panel(captionTabTag(t)).performClick()
        compose.waitForIdle()
    }

    private fun Project.captions(): List<TextClip> = tracks.flatMap { it.clips }.filterIsInstance<TextClip>()

    private fun EditorHarness.caption(): TextClip = project.captions().single()

    private fun EditorHarness.style(): CaptionStyle = caption().style.resolve(project.captionDefaults)

    /** Inside [r], allowing for boxes being stored to 1/10000 of the canvas. */
    private fun NormRect.inside(r: NormRect) = x >= r.x - 2e-4 && y >= r.y - 2e-4 && x + w <= r.x + r.w + 2e-4 && y + h <= r.y + r.h + 2e-4

    /** A new caption starts with the app's own words (cm-caption-default); the user's words replace them. */
    private fun EditorHarness.clearNewWords() {
        assertThat(caption().text).isEqualTo(CaptionWords.DEFAULT)
        panel(TEXT_FIELD_TAG).performTextClearance()
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.heightDp(): Float {
        val b = getUnclippedBoundsInRoot()
        return (b.bottom - b.top).value
    }

    @Test
    fun `Add caption on a photo or video opens the editor, at every size and text scale`() {
        for (size in ScreenSize.entries) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                val h = show(size = size, fontScale = scale)
                h.select("v2")
                tapBar(EditAction.ADD_CAPTION)
                // A caption attached to the video, in the preset's style, selected, with its editor open.
                val c = h.caption()
                assertThat(c.attachedTo).isEqualTo(CaptionAttachment(ClipId("v2")))
                assertThat(c.style).isEqualTo(CaptionStyleOverride.NONE)
                assertThat(h.state!!.selected).isEqualTo(c.id)
                assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.TEXT, c.id))
                compose.onNodeWithTag(CAPTION_BOX_TAG).assertExists()
                // Every tab is there, one line high, and at least 48 dp to touch.
                for (t in CaptionTab.EDITOR) {
                    val node = compose.onNodeWithTag(captionTabTag(t)).assertExists()
                    assertThat(node.heightDp()).isAtLeast(47.5f)
                }
                h.clearNewWords()
                panel(TEXT_FIELD_TAG).performTextInput("नमस्ते")
                assertThat(h.caption().text).isEqualTo("नमस्ते")
                for (t in CaptionTab.EDITOR) tab(t)
                panel(PANEL_DONE_TAG).performClick()
                compose.waitForIdle()
                assertThat(h.state!!.panel).isNull()
                assertThat(h.caption().text).isEqualTo("नमस्ते")
                // The whole visit, the new caption included, is one undo step.
                h.actions.undo()
                compose.waitForIdle()
                assertThat(h.project.captions()).isEmpty()
            }
        }
    }

    @Test
    fun `a photo on a picture-in-picture row takes a caption too`() {
        val pip = TrackId("pip")
        val p =
            project
                .addRow(TrackType.VISUAL, id = pip)
                .addToTrack(pip, Fixtures.photo("q", 2000, PipPlacement()), 7000)
        val h = show(p)
        h.select("q")
        compose.onNodeWithTag(barActionTag(EditAction.ADD_CAPTION)).assertIsDisplayed()
        tapBar(EditAction.ADD_CAPTION)
        assertThat(h.caption().attachedTo).isEqualTo(CaptionAttachment(ClipId("q")))
        assertThat(h.caption().startMs to h.caption().endMs).isEqualTo(7000L to 9000L)
        // The preview goes to the caption, so its changes show there.
        assertThat(h.calls).contains("seek:8000")
    }

    @Test
    fun `Add caption on a photo that has one opens it, so a second is never drawn over the first`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Hello")
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val first = h.caption().id
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        panel(TEXT_FIELD_TAG).performTextInput(" there")
        assertThat(h.project.captions()).hasSize(1)
        assertThat(h.caption().id).isEqualTo(first)
        assertThat(h.caption().text).isEqualTo("Hello there")
    }

    @Test
    fun `words show as they are typed, and characters a caption can't hold are left out`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Hello")
        assertThat(h.caption().text).isEqualTo("Hello")
        panel(TEXT_FIELD_TAG).performTextReplacement("दादी\u202E की ख़ुशी")
        assertThat(h.caption().text).isEqualTo("दादी की ख़ुशी")
    }

    @Test
    fun `the place chips move the box inside the frame window, and Usual is the preset's place`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        tab(CaptionTab.PLACE)
        panel(captionPlaceTag(CaptionPlace.USUAL)).assertIsSelected()
        panel(captionPlaceTag(CaptionPlace.TOP)).performClick()
        compose.waitForIdle()
        assertThat(h.style().box).isEqualTo(CaptionPlaces.box(CaptionPlace.TOP, defaults.box, safe))
        assertThat(h.style().box.y).isAtLeast(safe.y)
        panel(captionPlaceTag(CaptionPlace.TOP)).assertIsSelected()
        panel(captionPlaceTag(CaptionPlace.USUAL)).assertIsNotSelected()
        panel(captionPlaceTag(CaptionPlace.MIDDLE)).performClick()
        panel(captionPlaceTag(CaptionPlace.BOTTOM)).performClick()
        val bottom = h.style().box
        assertThat(bottom.y + bottom.h).isWithin(1e-4).of(safe.y + safe.h)
        panel(captionPlaceTag(CaptionPlace.USUAL)).performClick()
        assertThat(h.caption().style.box).isNull()
    }

    @Test
    fun `dragging the box on the preview moves the caption, kept inside the safe area`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        val before = h.style().box
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(center, center + Offset(0f, -200f), 300) }
        compose.waitForIdle()
        val after = h.style().box
        assertThat(after.y).isLessThan(before.y)
        assertThat(after.y).isAtLeast(safe.y - 1e-9)
        // Far past the top: it stops at the safe area.
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(center, center + Offset(0f, -5000f), 300) }
        compose.waitForIdle()
        assertThat(h.style().box.y).isWithin(1e-4).of(safe.y)
        // The Place tab is the way without dragging (and shows no place picked now).
        tab(CaptionTab.PLACE)
        panel(captionPlaceTag(CaptionPlace.TOP)).assertIsSelected()
        // Adding and dragging are one step.
        panel(PANEL_DONE_TAG).performClick()
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()
    }

    @Test
    fun `the size slider changes the size and shows a sample`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Hello")
        tab(CaptionTab.SIZE)
        compose.onNodeWithTag(CAPTION_SAMPLE_TAG).assertExists()
        panel(CAPTION_SIZE_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(96f) }
        compose.waitForIdle()
        assertThat(h.style().sizePx).isEqualTo(96)
        panel(CAPTION_SIZE_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(33f) }
        compose.waitForIdle()
        assertThat(h.style().sizePx).isEqualTo(32)
    }

    @Test
    fun `fonts, colours, outline and band change the caption, and Reset puts the preset's style back`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Hello")
        panel(CAPTION_RESET_TAG).assertIsNotEnabled()
        tab(CaptionTab.FONT)
        panel(captionFontTag(CaptionFont.SANS_BOLD)).assertIsSelected()
        panel(captionFontTag(CaptionFont.SERIF)).performClick()
        compose.waitForIdle()
        assertThat(h.style().font).isEqualTo(CaptionFont.SERIF.id)
        panel(captionFontTag(CaptionFont.SERIF)).assertIsSelected()
        tab(CaptionTab.COLOUR)
        panel(captionColourTag(1)).performClick()
        compose.waitForIdle()
        assertThat(h.style().color).isEqualTo(CAPTION_COLOURS[1].first)
        panel(captionColourTag(1)).assertIsSelected()
        // Black letters: the black outline and dark band would hide them, so they turn light.
        panel(captionColourTag(2)).performClick()
        compose.waitForIdle()
        assertThat(h.style().outline!!.color).isEqualTo(ArgbColor.WHITE)
        assertThat(isLightBand(h.style().background!!.color)).isTrue()
        panel(CAPTION_OUTLINE_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.style().outline).isNull()
        panel(CAPTION_BACKGROUND_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.style().background).isNull()
        panel(CAPTION_OUTLINE_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.style().outline).isNotNull()
        panel(CAPTION_RESET_TAG).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertThat(h.caption().style).isEqualTo(CaptionStyleOverride.NONE)
        assertThat(h.style()).isEqualTo(defaults)
        // The words stay.
        assertThat(h.caption().text).isEqualTo("Hello")
    }

    private fun isLightBand(c: ArgbColor) = (c.argb and 0xFFFFFFL) == 0xFFFFFFL

    @Test
    fun `while a caption is being written the title and Rename video do nothing, so the visit stays whole`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        panel(TEXT_FIELD_TAG).performTextInput("Hello")
        compose.onNodeWithTag(EDITOR_TITLE_TAG).assertIsNotEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RENAME_DIALOG_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EDITOR_MORE_TAG).performClick()
        compose.onNodeWithTag(EDITOR_MENU_RENAME_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(EDITOR_MENU_SHAPE_TAG).performClick()
        compose.waitForIdle()
        // The caption editor is still open with its words, and its Cancel still takes the whole visit back.
        assertThat(h.state!!.panel?.panel).isEqualTo(Panel.TEXT)
        panel(PANEL_CANCEL_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()
        assertThat(h.project.name).isNull()
    }

    @Test
    fun `Cancel takes a new caption away, and an existing caption's changes back`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Hello")
        panel(PANEL_CANCEL_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()
        assertThat(h.state!!.panel).isNull()
        // Nothing to redo: Cancel is not an undo.
        h.actions.redo()
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()

        // An existing caption: Edit text opens the editor; Cancel puts the style back.
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Kept")
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val id = h.caption().id
        h.select(id.value)
        tapBar(EditAction.EDIT_TEXT)
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.TEXT, id))
        tab(CaptionTab.SIZE)
        panel(CAPTION_SIZE_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        panel(PANEL_CANCEL_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.caption().style).isEqualTo(CaptionStyleOverride.NONE)
        assertThat(h.caption().text).isEqualTo("Kept")
    }

    @Test
    fun `a caption left without words goes when the editor closes, however it closes`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        assertThat(h.project.captions()).hasSize(1)
        h.clearNewWords()
        // Tapping another item closes the editor.
        h.select("v2")
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()
        // Nothing was left to undo either.
        assertThat(h.state!!.panel).isNull()
    }

    @Test
    fun `a new caption closed untouched keeps The image caption`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        assertThat(h.caption().text).isEqualTo("The image caption")
        // Closed by a tap on another item, too.
        h.select("p3")
        tapBar(EditAction.ADD_CAPTION)
        h.select("v2")
        compose.waitForIdle()
        assertThat(h.project.captions().map { it.text }).containsExactly("The image caption", "The image caption")
    }

    @Test
    fun `leaving the editor with Back while a new caption has no words takes the caption away`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        assertThat(h.project.captions()).hasSize(1)
        h.clearNewWords()
        compose
            .onNode(
                androidx.compose.ui.test.hasContentDescription(
                    androidx.test.core.app.ApplicationProvider
                        .getApplicationContext<android.content.Context>()
                        .getString(com.itsluminous.clearmovie.R.string.action_back),
                ),
            ).performClick()
        compose.waitForIdle()
        assertThat(h.calls).contains("back")
        assertThat(h.project.captions()).isEmpty()
    }

    @Test
    fun `the editor and the words being typed survive a restore`() {
        val tester = StateRestorationTester(compose)
        val h = EditorHarness(project, Fixtures.preset)
        tester.setContent { h.At(ScreenSize.COMPACT) }
        h.state!!.selected = ClipId("p1")
        compose.waitForIdle()
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("नमस्ते सब")
        tab(CaptionTab.FONT)
        val id = h.caption().id
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.TEXT, id))
        compose.onNodeWithTag(captionTabTag(CaptionTab.FONT)).assertIsSelected()
        tab(CaptionTab.WORDS)
        panel(TEXT_FIELD_TAG).performTextInput("!")
        assertThat(h.caption().text).isEqualTo("नमस्ते सब!")
        // The restore did not close the editor, so the caption was not taken away.
        assertThat(h.project.captions()).hasSize(1)
    }

    @Test
    fun `a caption made with the plus on a text row stays free`() {
        val h = show()
        h.state!!.selectedRow =
            h.project.tracks
                .first { it.type == TrackType.TEXT }
                .id
        h.state!!.panel = OpenPanel(Panel.INSERT, trackId = h.state!!.selectedRow)
        compose.waitForIdle()
        panel(insertOptionTag("text")).performClick()
        compose.waitForIdle()
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Free words")
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.caption().text).isEqualTo("Free words")
        assertThat(h.caption().attachedTo).isNull()
    }

    @Test
    fun `tapping another item on the timeline closes the editor and keeps the words`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Kept")
        compose
            .onNodeWithTag(
                com.itsluminous.clearmovie.ui.editor.timeline
                    .clipTag(ClipId("v2")),
            ).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        assertThat(h.caption().text).isEqualTo("Kept")
    }

    @Test
    fun `Cancel puts the caption back even after the app was closed by the system`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.clearNewWords()
        panel(TEXT_FIELD_TAG).performTextInput("Kept")
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val id = h.caption().id
        h.select(id.value)
        tapBar(EditAction.EDIT_TEXT)
        tab(CaptionTab.COLOUR)
        panel(captionColourTag(1)).performClick()
        compose.waitForIdle()
        assertThat(h.caption().style).isNotEqualTo(CaptionStyleOverride.NONE)
        // The view model's undo history is gone; the open editor came back with the screen.
        h.newSession()
        panel(PANEL_CANCEL_TAG).performClick()
        compose.waitForIdle()
        assertThat(h.caption().style).isEqualTo(CaptionStyleOverride.NONE)
        assertThat(h.caption().text).isEqualTo("Kept")
    }

    @Test
    fun `dragging sideways snaps back onto the preset's place`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(center, center + Offset(-150f, 0f), 300) }
        compose.waitForIdle()
        assertThat(h.style().box.x).isLessThan(defaults.box.x)
        // Dragged far right it stops at the safe area's edge, close enough to the preset's place to snap onto it.
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(center, center + Offset(600f, 0f), 300) }
        compose.waitForIdle()
        assertThat(h.style().box.x).isWithin(1e-4).of(defaults.box.x)
        tab(CaptionTab.PLACE)
        panel(captionPlaceTag(CaptionPlace.USUAL)).assertIsSelected()
    }

    @Test
    fun `the editor works on a narrow phone too`() {
        val h = EditorHarness(project, Fixtures.preset)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 760.dp))) {
                ClearMovieTheme(dynamicColor = false) { h.Content() }
            }
        }
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        for (t in CaptionTab.EDITOR) {
            tab(t)
            val b = compose.onNodeWithTag(captionTabTag(t)).getUnclippedBoundsInRoot()
            assertThat((b.right - b.left).value).isAtLeast(47.5f)
        }
        for (i in CAPTION_COLOURS.indices) {
            val b = compose.onNodeWithTag(captionColourTag(i)).getUnclippedBoundsInRoot()
            assertThat(b.right.value).isAtMost(360.5f)
        }
    }

    @Test
    fun `fast typing is never dropped when the screen shows the words late`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        h.lagging = true
        h.clearNewWords()
        for (c in listOf("C", "u", "t")) panel(TEXT_FIELD_TAG).performTextInput(c)
        // The project shows "C" while the field already holds "Cut"; then another key, then the rest arrives.
        h.catchUp()
        compose.waitForIdle()
        panel(TEXT_FIELD_TAG).performTextInput("s")
        while (h.catchUp()) compose.waitForIdle()
        h.lagging = false
        assertThat(h.caption().text).isEqualTo("Cuts")
    }

    // cm-caption-box -------------------------------------------------------------------------------

    @Test
    fun `a new caption starts with the preset's start animation, or none when the preset has none`() {
        for (kind in listOf(AnimKind.POP, null)) {
            val preset = Fixtures.preset.copy(effects = Fixtures.preset.effects.copy(textStart = kind))
            val p =
                ProjectFactory
                    .fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds("c"))
                    .addToMain(listOf(Fixtures.photo("p1")))
            val h = show(p)
            h.select("p1")
            tapBar(EditAction.ADD_CAPTION)
            assertThat(
                h
                    .caption()
                    .animations.start
                    ?.kind,
            ).isEqualTo(kind)
            panel(PANEL_CANCEL_TAG).performClick()
            compose.waitForIdle()
        }
    }

    /** The caption box's right handle (the middle of its right edge), in the box node's own coordinates. */
    private fun TouchInjectionScope.rightHandle() = centerRight - Offset(2f, 0f)

    @Test
    fun `dragging the right handle makes the box narrower and the words wrap at their size`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        panel(TEXT_FIELD_TAG).performTextInput("नमस्ते दुनिया, यह एक लंबा कैप्शन है")
        val before = h.style().box
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(rightHandle(), rightHandle() - Offset(width * 0.5f, 0f), 300) }
        compose.waitForIdle()
        val after = h.style().box
        assertThat(after.x).isWithin(1e-4).of(before.x)
        assertThat(after.w).isLessThan(before.w * 0.75)
        // The text keeps its size (the box's height for the extra lines is CaptionBoxHeightTest's, with real fonts).
        assertThat(after.h).isAtLeast(before.h)
        assertThat(h.style().sizePx).isEqualTo(defaults.sizePx)
        assertThat(after.inside(safe)).isTrue()
        // A long drag stops at the narrowest box.
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(rightHandle(), rightHandle() - Offset(5000f, 0f), 300) }
        compose.waitForIdle()
        assertThat(h.style().box.w).isWithin(2e-4).of(CaptionPlaces.minWidth(defaults.sizePx, CanvasShape.LANDSCAPE))
        // Adding, typing and resizing in one visit are one undo step.
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()
    }

    @Test
    fun `a selected caption's box can be resized on the preview, each drag one undo step`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        panel(TEXT_FIELD_TAG).performTextInput("Kept")
        panel(PANEL_DONE_TAG).performClick()
        compose.waitForIdle()
        val c = h.caption()
        // Selected, its editor closed, the playhead on it: the box shows with its handles.
        h.state!!.selected = c.id
        h.playheadUs = (c.startMs + 100) * 1000
        compose.waitForIdle()
        assertThat(h.state!!.panel).isNull()
        val before = h.style().box
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(rightHandle(), rightHandle() - Offset(width * 0.3f, 0f), 300) }
        compose.waitForIdle()
        val narrower = h.style().box
        assertThat(narrower.w).isLessThan(before.w)
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput { swipe(center, center + Offset(0f, -100f), 300) }
        compose.waitForIdle()
        assertThat(h.style().box.y).isLessThan(narrower.y)
        // Undo takes back the move, then the resize; the caption itself stays.
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.style().box).isEqualTo(narrower)
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.style().box).isEqualTo(before)
        assertThat(h.caption().text).isEqualTo("Kept")
        // Away from the caption's time the box is not offered.
        h.playheadUs = 0
        compose.waitForIdle()
        compose.onNodeWithTag(CAPTION_BOX_TAG).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `TalkBack can make the box wider and narrower`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        val before = h.style().box
        compose.onNodeWithTag(CAPTION_BOX_TAG).performCustomAccessibilityActionWithLabel(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getString(R.string.caption_box_narrower),
        )
        compose.waitForIdle()
        val narrower = h.style().box
        assertThat(narrower.w).isWithin(2e-4).of(before.w - 0.1)
        assertThat(narrower.x + narrower.w / 2).isWithin(2e-4).of(before.x + before.w / 2)
    }
}
