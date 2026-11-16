package com.itsluminous.clearmovie.ui.export

import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.export.ExportSpace
import com.itsluminous.clearmovie.domain.export.ExportTimeLeft
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.work.ExportFailure
import java.util.UUID

/** What the export sheet shows. */
sealed interface ExportStep {
    /**
     * The three sizes. [sizes] is each one's estimated size (null while it is
     * worked out); [shortBy] the free space each one still needs, when the
     * phone is too full for it. [stopped] says the last export was stopped.
     */
    data class Choose(
        val sizes: Map<ExportQuality, Long>? = null,
        val shortBy: Map<ExportQuality, Long> = emptyMap(),
        val stopped: Boolean = false,
    ) : ExportStep

    data class Running(
        val percent: Int,
        val minutesLeft: Int?,
        val restarted: Boolean,
    ) : ExportStep

    /** [uri] is the gallery entry, as text (`content://media/...`). */
    data class Done(
        val uri: String,
        val displayName: String,
        val sizeBytes: Long,
    ) : ExportStep

    data class Failed(
        val reason: ExportFailure,
        val missingBytes: Long = 0,
    ) : ExportStep

    /** Another video's export is running; only one runs at a time. */
    data object OtherRunning : ExportStep
}

/** The parts of a WorkManager `WorkInfo` the sheet needs, so the mapping is tested on the JVM. */
data class ExportWork(
    val id: UUID,
    val state: State,
    val projectId: String?,
    val percent: Int = 0,
    val restarted: Boolean = false,
    val startedAt: Long = 0,
    val uri: String? = null,
    val displayName: String? = null,
    val sizeBytes: Long = 0,
    val failure: String? = null,
    val missingBytes: Long = 0,
) {
    enum class State { WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    val finished: Boolean get() = state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED
}

/**
 * Turns the unique export work and what this sheet started ([watched]) into
 * an [ExportStep]:
 * - a running export of this video shows its progress (and becomes the
 *   watched one, so its result shows even after the app was closed);
 * - a running export of another video offers to stop it (and, once the
 *   user agreed, [replaceAgreed], shows the sizes: Save replaces it);
 * - the watched export's result shows until the user closes it;
 * - otherwise the sizes to choose from.
 * An export just asked for and not yet visible counts as running at 0%.
 */
object ExportSteps {
    fun step(
        works: List<ExportWork>,
        watched: UUID?,
        projectId: ProjectId,
        choose: ExportStep.Choose,
        nowMs: Long,
        replaceAgreed: Boolean = false,
    ): ExportStep {
        val active = works.firstOrNull { !it.finished }
        if (active != null) {
            if (active.projectId != null && active.projectId != projectId.value) {
                return if (replaceAgreed) choose else ExportStep.OtherRunning
            }
            return running(active, nowMs)
        }
        val mine = watched?.let { id -> works.firstOrNull { it.id == id } }
        if (watched != null && mine == null) return ExportStep.Running(0, null, restarted = false)
        return when (mine?.state) {
            ExportWork.State.SUCCEEDED -> {
                val uri = mine.uri
                if (uri.isNullOrEmpty()) {
                    ExportStep.Failed(
                        ExportFailure.FAILED,
                    )
                } else {
                    ExportStep.Done(uri, mine.displayName.orEmpty(), mine.sizeBytes)
                }
            }

            ExportWork.State.FAILED -> {
                ExportStep.Failed(ExportFailure.entries.firstOrNull { it.name == mine.failure } ?: ExportFailure.FAILED, mine.missingBytes)
            }

            ExportWork.State.CANCELLED -> {
                choose.copy(stopped = true)
            }

            else -> {
                choose
            }
        }
    }

    private fun running(
        work: ExportWork,
        nowMs: Long,
    ): ExportStep.Running {
        val percent = work.percent.coerceIn(0, 100)
        val left = if (work.startedAt > 0) ExportTimeLeft.estimateMs(nowMs - work.startedAt, percent) else null
        return ExportStep.Running(percent, left?.let(ExportTimeLeft::minutes), work.restarted)
    }

    /** The sizes step for a video of [durationUs] with [freeBytes] free. */
    fun choose(
        durationUs: Long?,
        freeBytes: Long?,
    ): ExportStep.Choose {
        if (durationUs == null) return ExportStep.Choose()
        val sizes = ExportQuality.entries.associateWith { it.estimatedBytes(durationUs) }
        val short =
            if (freeBytes == null) {
                emptyMap()
            } else {
                sizes
                    .mapValues { (_, bytes) -> (ExportSpace.decide(bytes, freeBytes) as? ExportSpace.NotEnough)?.missingBytes }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
            }
        return ExportStep.Choose(sizes, short)
    }
}
