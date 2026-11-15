package com.itsluminous.clearmovie.data.files

import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.domain.model.ProjectId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copied media of one project, for Settings -> Storage. */
data class ProjectStorage(
    val id: ProjectId,
    /** Null for an unnamed draft. */
    val name: String?,
    val copiedBytes: Long,
)

/** What the app keeps on the phone. */
data class StorageReport(
    /** Projects with copied media, largest first. */
    val projects: List<ProjectStorage>,
    val presetBytes: Long,
    /** Thumbnails, which can be made again. */
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = projects.sumOf { it.copiedBytes } + presetBytes + cacheBytes
}

/** Works out the [StorageReport] (ARCHITECTURE section 9: copied media is shown per project). */
class StorageUsage(
    private val db: ClearMovieDatabase,
    private val files: AppFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun report(): StorageReport =
        withContext(dispatcher) {
            val projects =
                db
                    .projects()
                    .summaryList()
                    .mapNotNull { row ->
                        val id = runCatching { ProjectId(row.id) }.getOrNull() ?: return@mapNotNull null
                        ProjectStorage(id, row.name, files.sizeOf(files.projectMediaDir(id)))
                    }.filter { it.copiedBytes > 0 }
                    .sortedByDescending { it.copiedBytes }
            StorageReport(projects, files.sizeOf(files.presetsRoot), files.sizeOf(files.thumbsRoot))
        }
}
