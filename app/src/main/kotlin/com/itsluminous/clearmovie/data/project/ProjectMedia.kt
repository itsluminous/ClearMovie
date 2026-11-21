package com.itsluminous.clearmovie.data.project

import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.render.media3.FileMediaResolver
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.LoudnessTable
import javax.inject.Inject

/** Everything the preview needs to open one project's media. */
data class ProjectMediaSet(
    /** The preset as the project plays it (with an adjusted key colour), or null without one. */
    val preset: Preset?,
    /** Null for a project without a preset. */
    val presetStatus: PresetStatus?,
    val resolver: MediaResolver,
    val loudness: LoudnessTable,
)

/**
 * Opens a project's media: preset slots to the copies bound on this phone
 * (only those that pass the launch check; a missing one is left out and the
 * editor offers the repair screen), copied items to the project's own
 * folder, gallery items by their link.
 */
class ProjectMedia
    @Inject
    constructor(
        private val presets: PresetStore,
        private val files: AppFiles,
    ) {
        suspend fun forProject(project: Project): ProjectMediaSet {
            val id = project.presetId
            val status = id?.let { presets.status(it) }
            val ready =
                status
                    ?.slots
                    .orEmpty()
                    .mapNotNull { (slot, s) -> (s as? SlotStatus.Ready)?.let { slot to it.binding } }
                    .toMap()
            val lufs = ready.mapKeys { (slot, _) -> MediaRef.Preset(slot) as MediaRef }.mapValues { it.value.loudnessLufs }
            return ProjectMediaSet(
                preset = id?.let { presets.effectivePreset(it) },
                presetStatus = status,
                resolver =
                    FileMediaResolver(
                        ready.mapNotNull { (slot, b) -> b.file?.let { slot to it } }.toMap(),
                        files.projectMediaDir(project.id),
                        ready.mapNotNull { (slot, b) -> b.linkedUri?.let { slot to it } }.toMap(),
                    ),
                loudness = LoudnessTable { lufs[it] },
            )
        }

        /** The bound copy of [slot] of the project's preset, when it is ready. */
        suspend fun presetFile(
            project: Project,
            slot: PresetSlot,
        ) = project.presetId?.let { (presets.status(it).slots[slot] as? SlotStatus.Ready)?.binding?.file }
    }
