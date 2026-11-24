package com.itsluminous.clearmovie.work

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.domain.model.ProjectId

/** Whether an export of a video is waiting or running, so deleting the video waits for it (cm-delete-project). */
fun interface ExportsInProgress {
    /** True while an export of [projectId] has not finished; also when WorkManager cannot answer. */
    fun of(projectId: ProjectId): Boolean

    companion object {
        /** The answer from WorkManager's export work. Blocks: call it off the main thread. */
        @OptIn(UnstableApi::class)
        fun from(workManager: WorkManager): ExportsInProgress =
            ExportsInProgress { id ->
                runCatching {
                    workManager
                        .getWorkInfosForUniqueWork(ExportWorker.UNIQUE_NAME)
                        .get()
                        .any { !it.state.isFinished && ExportWorker.projectTag(id) in it.tags }
                }.getOrDefault(true)
            }
    }
}
