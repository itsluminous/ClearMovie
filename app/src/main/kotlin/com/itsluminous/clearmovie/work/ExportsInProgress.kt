package com.itsluminous.clearmovie.work

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.domain.model.ProjectId

/** Whether an export of a video is waiting or running, so deleting the video waits for it (cm-delete-project). */
fun interface ExportsInProgress {
    /** True while an export of [projectId] has not finished; also when WorkManager cannot answer. */
    fun of(projectId: ProjectId): Boolean

    /**
     * Stops an export of [projectId] that was asked for after the check, once
     * the video is deleted. A gallery video an export already finished is
     * never touched; an unfinished one leaves nothing behind (ExportWorker).
     */
    fun cancel(projectId: ProjectId) = Unit

    companion object {
        /** The answer from WorkManager's export work. Blocks: call it off the main thread. */
        @OptIn(UnstableApi::class)
        fun from(workManager: WorkManager): ExportsInProgress =
            object : ExportsInProgress {
                override fun of(projectId: ProjectId): Boolean =
                    runCatching {
                        workManager
                            .getWorkInfosForUniqueWork(ExportWorker.UNIQUE_NAME)
                            .get()
                            .any { !it.state.isFinished && ExportWorker.projectTag(projectId) in it.tags }
                    }.getOrDefault(true)

                override fun cancel(projectId: ProjectId) {
                    runCatching { workManager.cancelAllWorkByTag(ExportWorker.projectTag(projectId)) }
                }
            }
    }
}
