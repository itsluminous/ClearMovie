package com.itsluminous.clearmovie.ui.navigation

import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId

/**
 * How a new video is started, and so which screens come after the preset
 * pick (ARCHITECTURE R2 and R3).
 */
enum class EntryMode {
    /** From the launcher: review the preset, then the Photo Picker, then the editor. */
    LAUNCHER,

    /**
     * The photos and videos are already chosen (a share from the gallery,
     * cm-share): the review and the picker are skipped, and the editor opens
     * straight after the preset pick with the items on MAIN.
     */
    MEDIA_CHOSEN,
}

/** A screen of the new-video flow. */
sealed interface FlowStep {
    data class Setup(
        val presetId: PresetId,
    ) : FlowStep

    data class Review(
        val projectId: ProjectId,
    ) : FlowStep

    data class Pick(
        val projectId: ProjectId,
    ) : FlowStep

    data class Editor(
        val projectId: ProjectId,
    ) : FlowStep
}

/** Which screen follows which (pure, so the order is tested on the JVM). */
object NewVideoFlow {
    /**
     * After a preset is tapped: its files are set up first if any is not
     * ready (a missing file opens the repair screen); otherwise a project
     * exists ([project]) and the flow goes on by [mode].
     */
    fun afterPresetChosen(
        mode: EntryMode,
        presetId: PresetId,
        presetReady: Boolean,
        project: ProjectId?,
    ): FlowStep =
        when {
            !presetReady || project == null -> FlowStep.Setup(presetId)
            mode == EntryMode.MEDIA_CHOSEN -> FlowStep.Editor(project)
            else -> FlowStep.Review(project)
        }

    /** "Start without a preset": no review; the picker, or the editor when the media is chosen. */
    fun afterBlankChosen(
        mode: EntryMode,
        project: ProjectId,
    ): FlowStep = if (mode == EntryMode.MEDIA_CHOSEN) FlowStep.Editor(project) else FlowStep.Pick(project)

    /** After the setup screen's Done, for a new video. */
    fun afterSetup(
        mode: EntryMode,
        project: ProjectId,
    ): FlowStep = if (mode == EntryMode.MEDIA_CHOSEN) FlowStep.Editor(project) else FlowStep.Review(project)

    /** Review's Next opens the picker. */
    fun afterReview(project: ProjectId): FlowStep = FlowStep.Pick(project)

    /** The picker's items are on MAIN: the editor. */
    fun afterPick(project: ProjectId): FlowStep = FlowStep.Editor(project)
}

/** Navigation route ids (not shown to the user). */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"

    const val ARG_PRESET = "presetId"
    const val ARG_PROJECT = "projectId"
    const val ARG_NEW_VIDEO = "newVideo"
    const val ARG_SKIPPED = "skipped"
    const val ARG_BROUGHT_BACK = "broughtBack"

    const val SETUP = "setup/{$ARG_PRESET}?$ARG_NEW_VIDEO={$ARG_NEW_VIDEO}"
    const val REVIEW = "review/{$ARG_PROJECT}"
    const val PICK = "pick/{$ARG_PROJECT}"
    const val EDITOR = "editor/{$ARG_PROJECT}?$ARG_SKIPPED={$ARG_SKIPPED}&$ARG_BROUGHT_BACK={$ARG_BROUGHT_BACK}"

    fun setup(
        presetId: PresetId,
        newVideo: Boolean,
    ) = "setup/${presetId.value}?$ARG_NEW_VIDEO=$newVideo"

    fun review(projectId: ProjectId) = "review/${projectId.value}"

    fun pick(projectId: ProjectId) = "pick/${projectId.value}"

    fun editor(
        projectId: ProjectId,
        skipped: Int = 0,
        broughtBack: Boolean = false,
    ) = "editor/${projectId.value}?$ARG_SKIPPED=$skipped&$ARG_BROUGHT_BACK=$broughtBack"

    fun of(step: FlowStep): String =
        when (step) {
            is FlowStep.Setup -> setup(step.presetId, newVideo = true)
            is FlowStep.Review -> review(step.projectId)
            is FlowStep.Pick -> pick(step.projectId)
            is FlowStep.Editor -> editor(step.projectId)
        }
}
