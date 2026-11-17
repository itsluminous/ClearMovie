package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionAttachment
import com.itsluminous.clearmovie.domain.model.CaptionFont
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
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
                for (t in CaptionTab.entries) {
                    val node = compose.onNodeWithTag(captionTabTag(t)).assertExists()
                    assertThat(node.heightDp()).isAtLeast(47.5f)
                }
                panel(TEXT_FIELD_TAG).performTextInput("नमस्ते")
                assertThat(h.caption().text).isEqualTo("नमस्ते")
                for (t in CaptionTab.entries) tab(t)
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
    fun `words show as they are typed, and characters a caption can't hold are left out`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
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
    fun `Cancel takes a new caption away, and an existing caption's changes back`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
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
        // Tapping another item closes the editor.
        h.select("v2")
        compose.waitForIdle()
        assertThat(h.project.captions()).isEmpty()
        // Nothing was left to undo either.
        assertThat(h.state!!.panel).isNull()
    }

    @Test
    fun `leaving the editor with Back while a new caption has no words takes the caption away`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.ADD_CAPTION)
        assertThat(h.project.captions()).hasSize(1)
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
}
