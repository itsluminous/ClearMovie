package com.itsluminous.clearmovie.data.project

import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import javax.inject.Inject

/** The outcome of [ShareDrafts.choose]. */
sealed interface ShareChoice {
    /** The video is ready for the editor. */
    data class Ready(
        val projectId: ProjectId,
    ) : ShareChoice

    /** A preset file is not set up, or was moved or deleted: setup or repair first, then choose again. */
    data object NeedsSetup : ShareChoice

    /** The shared items' video is gone (deleted elsewhere). */
    data object Gone : ShareChoice

    /** It could not be written (the phone is full); nothing changed. */
    data object NotSaved : ShareChoice
}

/**
 * The pending draft of a share (ARCHITECTURE section 11.12): shared items
 * are taken in at once, while their grants last, into a new blank draft that
 * is out of the session (cm-flow D5: only the editor puts a video there). The
 * items are on its MAIN row and written before the preset pick shows, so a
 * rotation or a process death during the pick never loses them, and a draft
 * left behind shows in "My videos" with the items. [choose] then gives the
 * draft the chosen preset's intro, frame and ending, or none, keeping its
 * id, and with it the copies and grants the intake made.
 */
class ShareDrafts
    @Inject
    constructor(
        private val presets: PresetStore,
        private val projects: ProjectRepository,
    ) {
        /** A new empty draft for a share, open but not in the editor; null when it could not be written. */
        suspend fun create(): ProjectId? {
            // Asks for its shape too if it is ever opened without choosing a preset (a draft left in My videos).
            val project = ProjectFactory.blank(ProjectId(RandomIds.next()), askShape = true)
            return if (projects.createDraft(project, inEditor = false)) project.id else null
        }

        /**
         * Rebuilds [draft] from [preset]'s track template (or blank, for
         * Start without a preset) with the same id and the same photos and
         * videos, in the same order. A photo takes the preset's photo length
         * and every item its fit, as if it had been added to that preset's
         * video. Choosing again (after Back from setup) starts from the items
         * again, so it never stacks two intros.
         */
        suspend fun choose(
            draft: ProjectId,
            preset: PresetId?,
        ): ShareChoice {
            val opened = projects.open(draft, inEditor = false)
            val current =
                when (opened) {
                    is OpenResult.Opened -> opened.project
                    OpenResult.NotSaved -> return ShareChoice.NotSaved
                    else -> return ShareChoice.Gone
                }
            val base =
                if (preset == null) {
                    ProjectFactory.blank(draft, askShape = true)
                } else {
                    if (!presets.status(preset).isReady) return ShareChoice.NeedsSetup
                    val definition = presets.effectivePreset(preset) ?: return ShareChoice.NeedsSetup
                    try {
                        ProjectFactory.fromPreset(draft, definition, presets.presetMedia(preset))
                    } catch (e: IllegalArgumentException) {
                        // A bound file with an unusable length: choosing it again repairs it.
                        return ShareChoice.NeedsSetup
                    }
                }
            // The shared items are new to the chosen preset, so they take its effect defaults.
            val items = userItems(current, base)
            val next = base.copy(name = current.name).addToMain(items).withEffectDefaultsFor(items.map { it.id })
            if (!projects.update { next }) return ShareChoice.NotSaved
            return if (projects.flush()) ShareChoice.Ready(draft) else ShareChoice.NotSaved
        }

        /** Throws away a share's draft the user backed out of, with its copies. */
        suspend fun discard(draft: ProjectId) {
            projects.delete(draft)
        }

        private fun userItems(
            from: Project,
            base: Project,
        ): List<MainClip> =
            from.mainClips.mapNotNull {
                when (it) {
                    is PhotoClip -> PhotoClip(it.id, it.media, base.photoDurationMs, fit = base.defaultFit)
                    is VideoClip -> VideoClip(it.id, it.media, sourceDurationMs = it.sourceDurationMs, fit = base.defaultFit)
                    else -> null
                }
            }
    }
