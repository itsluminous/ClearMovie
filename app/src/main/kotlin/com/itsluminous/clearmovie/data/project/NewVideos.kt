package com.itsluminous.clearmovie.data.project

import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import javax.inject.Inject

/** The outcome of [NewVideos]. */
sealed interface NewVideoResult {
    /** A draft exists and is open in the repository. */
    data class Created(
        val projectId: ProjectId,
    ) : NewVideoResult

    /** A preset file is not set up, or was moved or deleted: the setup or repair screen comes first. */
    data object NeedsSetup : NewVideoResult

    /** The draft could not be written (the phone is full). */
    data object NotSaved : NewVideoResult
}

/** Starts a new draft video, from a preset (with its track template) or blank. */
class NewVideos
    @Inject
    constructor(
        private val presets: PresetStore,
        private val projects: ProjectRepository,
    ) {
        suspend fun fromPreset(id: PresetId): NewVideoResult {
            if (!presets.status(id).isReady) return NewVideoResult.NeedsSetup
            val preset = presets.effectivePreset(id) ?: return NewVideoResult.NeedsSetup
            val project =
                try {
                    ProjectFactory.fromPreset(ProjectId(RandomIds.next()), preset, presets.presetMedia(id))
                } catch (e: IllegalArgumentException) {
                    // A bound file with an unusable length: choosing it again repairs it.
                    return NewVideoResult.NeedsSetup
                }
            return if (projects.createDraft(project)) NewVideoResult.Created(project.id) else NewVideoResult.NotSaved
        }

        suspend fun blank(): NewVideoResult {
            val project = ProjectFactory.blank(ProjectId(RandomIds.next()))
            return if (projects.createDraft(project)) NewVideoResult.Created(project.id) else NewVideoResult.NotSaved
        }
    }
