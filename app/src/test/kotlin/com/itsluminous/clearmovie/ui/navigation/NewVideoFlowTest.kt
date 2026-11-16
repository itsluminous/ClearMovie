package com.itsluminous.clearmovie.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import org.junit.Test

/** The order of the new-video screens (requirements 1 to 5, and the share entry cm-share adds). */
class NewVideoFlowTest {
    private val preset = PresetId("event-frame")
    private val project = ProjectId("p1")

    @Test
    fun `from the launcher a ready preset goes to review, then the picker, then the editor`() {
        assertThat(NewVideoFlow.afterPresetChosen(EntryMode.LAUNCHER, preset, presetReady = true, project = project))
            .isEqualTo(FlowStep.Review(project))
        assertThat(NewVideoFlow.afterReview(project)).isEqualTo(FlowStep.Pick(project))
        assertThat(NewVideoFlow.afterPick(project)).isEqualTo(FlowStep.Editor(project))
    }

    @Test
    fun `a preset with a missing or unset file opens setup or repair first, whatever the entry`() {
        for (mode in EntryMode.entries) {
            assertThat(NewVideoFlow.afterPresetChosen(mode, preset, presetReady = false, project = null)).isEqualTo(FlowStep.Setup(preset))
        }
        assertThat(NewVideoFlow.afterSetup(EntryMode.LAUNCHER, project)).isEqualTo(FlowStep.Review(project))
    }

    @Test
    fun `with the media already chosen the preset pick jumps straight to the editor`() {
        assertThat(NewVideoFlow.afterPresetChosen(EntryMode.MEDIA_CHOSEN, preset, presetReady = true, project = project))
            .isEqualTo(FlowStep.Editor(project))
        assertThat(NewVideoFlow.afterBlankChosen(EntryMode.MEDIA_CHOSEN, project)).isEqualTo(FlowStep.Editor(project))
        assertThat(NewVideoFlow.afterSetup(EntryMode.MEDIA_CHOSEN, project)).isEqualTo(FlowStep.Editor(project))
    }

    @Test
    fun `start without a preset skips the review`() {
        assertThat(NewVideoFlow.afterBlankChosen(EntryMode.LAUNCHER, project)).isEqualTo(FlowStep.Pick(project))
    }

    @Test
    fun `routes carry the ids and match their patterns`() {
        assertThat(Routes.of(FlowStep.Setup(preset))).isEqualTo("setup/event-frame?newVideo=true")
        assertThat(Routes.of(FlowStep.Review(project))).isEqualTo("review/p1")
        assertThat(Routes.of(FlowStep.Pick(project))).isEqualTo("pick/p1")
        assertThat(Routes.editor(project, skipped = 2, broughtBack = true)).isEqualTo("editor/p1?skipped=2&broughtBack=true")
        assertThat(Routes.EDITOR).startsWith("editor/{projectId}")
        // A share's setup carries the draft that Done gives the preset (cm-share).
        assertThat(Routes.setup(preset, newVideo = true, shareDraft = project)).isEqualTo("setup/event-frame?newVideo=true&shareDraft=p1")
        assertThat(Routes.SETUP).endsWith("&shareDraft={shareDraft}")
    }
}
