package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.unit.DpRect
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineGeometry
import com.itsluminous.clearmovie.ui.editor.timeline.markerTag
import com.itsluminous.clearmovie.ui.editor.timeline.plusTag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * cm-effects in the editor (ROADMAP cm-effects, Compose UI acceptance): the
 * Transition and Animation buttons, picking, changing and removing a
 * transition and an animation, the Length slider, Use for all items, the
 * junction marker opening its transition and never sharing a spot with the
 * "+", the preview playing the change, and the open picker kept across a
 * restore, at the three window sizes and at font scale 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
class EffectsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(
                listOf(
                    Fixtures.photo("p1"),
                    Fixtures.video("v2", 4000),
                    Fixtures.photo("p3"),
                    CardClip(ClipId("k4"), 3000, ArgbColor.BLACK, "Hello"),
                ),
            )

    private var shown by mutableStateOf<Triple<ScreenSize, Float, EditorHarness>?>(null)
    private var contentSet = false

    /** Shows [p]; a test may call it again for another project or size (the content is set once). */
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
        compose.waitForIdle()
    }

    private fun tapBar(action: EditAction) {
        val scroller = compose.onAllNodes(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
        // When every button fits, the bar does not scroll.
        if (scroller.fetchSemanticsNodes().isNotEmpty()) scroller.onFirst().performScrollToNode(hasTestTag(barActionTag(action)))
        compose.onNodeWithTag(barActionTag(action)).performClick()
        compose.waitForIdle()
    }

    /** A control inside the open panel, scrolled into view first. */
    private fun panel(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = false).performScrollTo()

    private fun Project.transitionOf(id: String) = (requireClip(ClipId(id)) as MainClip).transitionToNext

    @Test
    fun `pick, change and remove a transition, and the preview plays it`() {
        val h = show()
        h.select("p1")
        tapBar(EditAction.TRANSITION)
        compose.onNodeWithTag(PANEL_TAG).assertIsDisplayed()
        panel(transitionTileTag(TransitionKind.CROSSFADE)).performClick()
        assertThat(h.project.transitionOf("p1")).isEqualTo(Transition(TransitionKind.CROSSFADE, 1000))
        panel(transitionTileTag(TransitionKind.CROSSFADE)).assertIsSelected()
        // The preview plays from a second before the overlap starts.
        val p1 = h.project.mainSlot(ClipId("p1"))!!
        assertThat(h.calls).contains("preview:${p1.endMs - 1000 - PREVIEW_LEAD_MS}")

        panel(transitionTileTag(TransitionKind.SLIDE_LEFT)).performClick()
        assertThat(h.project.transitionOf("p1")!!.kind).isEqualTo(TransitionKind.SLIDE_LEFT)
        panel(TRANSITION_REMOVE_TAG).performClick()
        assertThat(h.project.transitionOf("p1")).isNull()
        panel(transitionTileTag(TransitionKind.FADE_WHITE)).performClick()
        panel(transitionTileTag(TransitionKind.NONE)).performClick()
        assertThat(h.project.transitionOf("p1")).isNull()
        // Each choice was one undo step.
        h.actions.undo()
        compose.waitForIdle()
        assertThat(h.project.transitionOf("p1")!!.kind).isEqualTo(TransitionKind.FADE_WHITE)
    }

    @Test
    fun `the length slider re-applies the transition, within half the shorter item`() {
        val h = show(project.setTransition(ClipId("p1"), Transition(TransitionKind.CROSSFADE, 1000)))
        h.select("p1")
        tapBar(EditAction.TRANSITION)
        panel(EFFECT_LENGTH_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(1500f) }
        compose.waitForIdle()
        assertThat(h.project.transitionOf("p1")!!.durationMs).isEqualTo(1500)
        // p1 is 3 s: half of it is the most the slider offers.
        panel(EFFECT_LENGTH_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(2000f) }
        compose.waitForIdle()
        assertThat(h.project.transitionOf("p1")!!.durationMs).isEqualTo(1500)
    }

    @Test
    fun `use for all items puts the transition on every junction`() {
        val h = show()
        h.select("v2")
        tapBar(EditAction.TRANSITION)
        panel(transitionTileTag(TransitionKind.WIPE_UP)).performClick()
        panel(TRANSITION_APPLY_ALL_TAG).performClick()
        val leads = h.project.mainClips.dropLast(1)
        assertThat(leads.map { it.transitionToNext?.kind }.toSet()).containsExactly(TransitionKind.WIPE_UP)
        assertThat(h.project.transitionForAll!!.kind).isEqualTo(TransitionKind.WIPE_UP)
        panel(TRANSITION_APPLY_ALL_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the last item's Transition is greyed out, and the intro has one`() {
        val h = show()
        h.select(
            h.project.mainClips
                .last()
                .id.value,
        )
        // The ending: no Transition at all.
        compose.onNodeWithTag(barActionTag(EditAction.TRANSITION)).assertDoesNotExist()
        h.select(
            h.project.mainClips
                .first()
                .id.value,
        )
        tapBar(EditAction.TRANSITION)
        panel(transitionTileTag(TransitionKind.FADE_BLACK)).performClick()
        assertThat(
            h.project.mainClips
                .first()
                .transitionToNext!!
                .kind,
        ).isEqualTo(TransitionKind.FADE_BLACK)
        val last = Fixtures.blankWith(Fixtures.photo("a"), Fixtures.photo("b"), ids = SequentialIds("b"))
        val h2 = show(last)
        h2.select("b")
        compose
            .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
            .performScrollToNode(hasTestTag(barActionTag(EditAction.TRANSITION)))
        compose.onNodeWithTag(barActionTag(EditAction.TRANSITION)).assertIsNotEnabled()
    }

    @Test
    fun `tapping the junction marker opens that transition`() {
        val h = show(project.setTransition(ClipId("v2"), Transition(TransitionKind.CIRCLE, 1000)))
        val boundary = h.project.mainClips.indexOfFirst { it.id == ClipId("v2") } + 1
        compose.onNodeWithTag(markerTag(boundary)).performClick()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.TRANSITION, ClipId("v2")))
        assertThat(h.state!!.selected).isEqualTo(ClipId("v2"))
        panel(transitionTileTag(TransitionKind.CIRCLE)).assertIsSelected()
    }

    @Test
    fun `the marker and the plus never share a spot, at three zoom levels`() {
        val withAll =
            project
                .setTransition(ClipId("p1"), Transition(TransitionKind.CROSSFADE, 1000))
                .setTransition(ClipId("v2"), Transition(TransitionKind.SLIDE_UP, 1000))
                .setTransition(ClipId("p3"), Transition(TransitionKind.WIPE_LEFT, 1000))
        val h = show(withAll)
        val boundaries = listOf("p1", "v2", "p3").map { id -> h.project.mainClips.indexOfFirst { it.id == ClipId(id) } + 1 }
        var markersSeen = 0
        // Fit all, then two and five steps closer.
        for (steps in listOf(0, 2, 3)) {
            repeat(steps) { compose.onNodeWithTag(ZOOM_IN_TAG).performClick() }
            compose.waitForIdle()
            for (b in boundaries) {
                val marker = runCatching { compose.onNodeWithTag(markerTag(b)).fetchSemanticsNode() }.getOrNull() ?: continue
                markersSeen++
                val m = compose.onNodeWithTag(markerTag(b)).getUnclippedBoundsInRoot()
                assertThat(width(m).value).isWithin(0.5f).of(TimelineGeometry.MARKER_HIT_DP.toFloat())
                val plus = runCatching { compose.onNodeWithTag(plusTag(b)).fetchSemanticsNode() }.getOrNull()
                if (plus != null) {
                    val p = compose.onNodeWithTag(plusTag(b)).getUnclippedBoundsInRoot()
                    assertThat(overlaps(m, p)).isFalse()
                }
                assertThat(marker).isNotNull()
            }
        }
        assertThat(markersSeen).isAtLeast(3)
    }

    private fun width(r: DpRect) = r.right - r.left

    private fun overlaps(
        a: DpRect,
        b: DpRect,
    ) = a.left < b.right - 0.5f.dpv() && b.left < a.right - 0.5f.dpv() && a.top < b.bottom - 0.5f.dpv() && b.top < a.bottom - 0.5f.dpv()

    private fun Float.dpv() =
        androidx.compose.ui.unit
            .Dp(this)

    @Test
    fun `pick, change and remove an animation on a photo`() {
        val h = show()
        h.select("p3")
        tapBar(EditAction.ANIMATION)
        panel(animationTabTag(AnimSlot.DURING)).performClick()
        panel(animationTileTag(AnimSlot.DURING, AnimKind.KEN_BURNS_IN)).performClick()
        assertThat(
            h.project
                .requireClip(ClipId("p3"))
                .animations.during!!
                .kind,
        ).isEqualTo(AnimKind.KEN_BURNS_IN)
        assertThat(h.calls.any { it.startsWith("preview:") }).isTrue()
        panel(animationTabTag(AnimSlot.START)).performClick()
        // Photos get no text-only animations.
        compose.onNodeWithTag(animationTileTag(AnimSlot.START, AnimKind.POP)).assertDoesNotExist()
        panel(animationTileTag(AnimSlot.START, AnimKind.SLIDE_IN_L)).performClick()
        panel(EFFECT_LENGTH_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(1000f) }
        compose.waitForIdle()
        assertThat(
            h.project
                .requireClip(ClipId("p3"))
                .animations.start,
        ).isEqualTo(
            com.itsluminous.clearmovie.domain.model
                .Anim(AnimKind.SLIDE_IN_L, 1000),
        )
        panel(animationTileTag(AnimSlot.START, null)).performClick()
        assertThat(
            h.project
                .requireClip(ClipId("p3"))
                .animations.start,
        ).isNull()
        assertThat(
            h.project
                .requireClip(ClipId("p3"))
                .animations.during,
        ).isNotNull()
    }

    @Test
    fun `a text card offers Pop and Pulse, a video no gentle zoom`() {
        val h = show()
        h.select("k4")
        tapBar(EditAction.ANIMATION)
        panel(animationTileTag(AnimSlot.START, AnimKind.POP)).performClick()
        assertThat(
            h.project
                .requireClip(ClipId("k4"))
                .animations.start!!
                .kind,
        ).isEqualTo(AnimKind.POP)
        panel(animationTabTag(AnimSlot.DURING)).performClick()
        panel(animationTileTag(AnimSlot.DURING, AnimKind.PULSE)).assertIsDisplayed()
        panel(PANEL_DONE_TAG).performClick()
        h.select("v2")
        tapBar(EditAction.ANIMATION)
        // A video has Start and End only.
        compose.onNodeWithTag(animationTabTag(AnimSlot.DURING)).assertDoesNotExist()
        panel(animationTabTag(AnimSlot.END)).performClick()
        panel(animationTileTag(AnimSlot.END, AnimKind.ZOOM_OUT)).performClick()
        assertThat(
            h.project
                .requireClip(ClipId("v2"))
                .animations.end!!
                .kind,
        ).isEqualTo(AnimKind.ZOOM_OUT)
    }

    @Test
    fun `the pickers open at every size and at font scale 2`() {
        for (scale in listOf(1f, 2f)) {
            for (size in ScreenSize.entries) {
                val h = show(size = size, fontScale = scale)
                h.select("p1")
                tapBar(EditAction.TRANSITION)
                panel(transitionTileTag(TransitionKind.ZOOM_IN)).assertIsDisplayed()
                panel(EFFECT_LENGTH_TAG).assertIsDisplayed()
                panel(PANEL_DONE_TAG).performClick()
                tapBar(EditAction.ANIMATION)
                panel(animationTileTag(AnimSlot.START, AnimKind.FADE_IN)).assertIsDisplayed()
                panel(PANEL_DONE_TAG).performClick()
            }
        }
    }

    @Test
    fun `an open picker and its tab are kept across a restore`() {
        val h = EditorHarness(project, Fixtures.preset)
        val tester = StateRestorationTester(compose)
        tester.setContent { AtSize(ScreenSize.MEDIUM, 1f) { ClearMovieTheme(dynamicColor = false) { h.Content() } } }
        compose.waitForIdle()
        h.select("p1")
        tapBar(EditAction.ANIMATION)
        panel(animationTabTag(AnimSlot.END)).performClick()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.ANIMATION, ClipId("p1")))
        panel(animationTileTag(AnimSlot.END, AnimKind.FADE_OUT)).assertIsDisplayed()
        panel(PANEL_DONE_TAG).performClick()
        tapBar(EditAction.TRANSITION)
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(h.state!!.panel).isEqualTo(OpenPanel(Panel.TRANSITION, ClipId("p1")))
    }
}
