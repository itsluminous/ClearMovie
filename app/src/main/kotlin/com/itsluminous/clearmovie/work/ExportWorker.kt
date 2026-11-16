package com.itsluminous.clearmovie.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.ProjectId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * The export, as a foreground worker (ARCHITECTURE section 12): it is not
 * part of any screen, so it carries on through a rotation, while the app is
 * in the background, and after the editor is closed. WorkManager runs it
 * again from the start if the process is killed (the screen then says it
 * started again). The work is unique app-wide ([UNIQUE_NAME]): one export at
 * a time, so two never fight over the phone's encoders.
 *
 * Progress is published as [KEY_PERCENT] (with [KEY_PROJECT],
 * [KEY_RESTARTED] and [KEY_STARTED_AT]) and in the notification. The result
 * is in the output data: [KEY_URI] and [KEY_NAME] on success, [KEY_FAILURE]
 * (an [ExportFailure] name) and [KEY_MISSING_BYTES] on failure. Cancelling
 * (the screen's Stop, or the notification's) removes the unfinished file.
 */
@UnstableApi
@HiltWorker
class ExportWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val pipeline: ExportPipeline,
    ) : CoroutineWorker(context, params) {
        private val notifications = ExportNotifications(applicationContext)
        private val attempts = ExportAttempts(applicationContext)

        // On Android 10 and 11 WorkManager runs expedited work as a foreground service before
        // doWork, with this notification, so the channel must exist here already (review F1).
        override suspend fun getForegroundInfo(): ForegroundInfo {
            notifications.ensureChannel()
            return foregroundInfo(0, attempts.wasStarted(id))
        }

        override suspend fun doWork(): Result {
            val projectId = inputData.getString(KEY_PROJECT)?.let { runCatching { ProjectId(it) }.getOrNull() }
            val quality = ExportQuality.parse(inputData.getString(KEY_QUALITY))
            if (projectId == null || quality == null) return failure(ExportFailure.FAILED, 0)
            val title = inputData.getString(KEY_TITLE).orEmpty()
            // WorkManager does not count a run the system killed, so the worker keeps its own
            // note that this export already started once (review F2).
            var restarted = attempts.wasStarted(id) || runAttemptCount > 0
            attempts.markStarted(id)
            var startedAt = System.currentTimeMillis()
            notifications.ensureChannel()
            notifications.cancelResult()
            try {
                setForeground(foregroundInfo(0, restarted))
            } catch (e: IllegalStateException) {
                // Not allowed to start a foreground service now (Android 12+ with the app in the
                // background, after a restart): carry on as normal work, without the notification.
                Log.w(TAG, "Export runs without a foreground notification", e)
            }
            var shown = -1

            suspend fun report(percent: Int) {
                if (percent == shown) return
                shown = percent
                setProgress(progressData(projectId, percent, restarted, startedAt))
                notifications.post(ExportNotifications.PROGRESS_ID, notifications.progress(id, percent, restarted))
            }
            val outcome =
                try {
                    report(0)
                    pipeline.run(
                        projectId,
                        quality,
                        title,
                        onRestart = {
                            // The hardware encoder failed; the software attempt starts from 0 (review F4).
                            restarted = true
                            startedAt = System.currentTimeMillis()
                            shown = -1
                            report(0)
                        },
                    ) { report(it) }
                } finally {
                    // Normally WorkManager removes it with the foreground service; not when that was refused.
                    notifications.cancelProgress()
                    attempts.clear(id)
                }
            return when (outcome) {
                is ExportOutcome.Done -> {
                    val d = outcome.done
                    notifications.post(ExportNotifications.RESULT_ID, notifications.done(d.uri, d.displayName))
                    Result.success(
                        workDataOf(
                            KEY_PROJECT to projectId.value,
                            KEY_URI to d.uri.toString(),
                            KEY_NAME to d.displayName,
                            KEY_SIZE to d.sizeBytes,
                            KEY_VIDEO_ENCODER to d.videoEncoder,
                            KEY_SOFTWARE_FALLBACK to d.softwareFallback,
                        ),
                    )
                }

                is ExportOutcome.Failed -> {
                    failure(outcome.reason, outcome.missingBytes, projectId)
                }
            }
        }

        private fun failure(
            reason: ExportFailure,
            missingBytes: Long,
            projectId: ProjectId? = null,
        ): Result {
            notifications.post(
                ExportNotifications.RESULT_ID,
                notifications.failed(failureMessage(applicationContext, reason, missingBytes)),
            )
            return Result.failure(
                workDataOf(KEY_PROJECT to projectId?.value, KEY_FAILURE to reason.name, KEY_MISSING_BYTES to missingBytes),
            )
        }

        private fun foregroundInfo(
            percent: Int,
            restarted: Boolean,
        ): ForegroundInfo {
            val notification = notifications.progress(id, percent, restarted)
            val type =
                if (Build.VERSION.SDK_INT >= 35) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            return ForegroundInfo(ExportNotifications.PROGRESS_ID, notification, type)
        }

        /** Which exports have started a run, so a run after a kill knows it started again. */
        class ExportAttempts(
            context: Context,
        ) {
            private val prefs = context.getSharedPreferences("export_attempts", Context.MODE_PRIVATE)

            fun wasStarted(id: UUID): Boolean = prefs.getBoolean(id.toString(), false)

            fun markStarted(id: UUID) = prefs.edit(commit = true) { putBoolean(id.toString(), true) }

            fun clear(id: UUID) = prefs.edit { remove(id.toString()) }
        }

        companion object {
            private const val TAG = "ExportWorker"

            /** One export at a time, on the whole phone. */
            const val UNIQUE_NAME = "export"
            const val TAG_EXPORT = "clearmovie-export"

            const val KEY_PROJECT = "project"
            const val KEY_QUALITY = "quality"
            const val KEY_TITLE = "title"
            const val KEY_PERCENT = "percent"
            const val KEY_RESTARTED = "restarted"
            const val KEY_STARTED_AT = "started_at"
            const val KEY_URI = "uri"
            const val KEY_NAME = "name"
            const val KEY_SIZE = "size"
            const val KEY_VIDEO_ENCODER = "video_encoder"
            const val KEY_SOFTWARE_FALLBACK = "software_fallback"
            const val KEY_FAILURE = "failure"
            const val KEY_MISSING_BYTES = "missing_bytes"

            fun progressData(
                projectId: ProjectId,
                percent: Int,
                restarted: Boolean,
                startedAt: Long,
            ): Data =
                workDataOf(
                    KEY_PROJECT to projectId.value,
                    KEY_PERCENT to percent,
                    KEY_RESTARTED to restarted,
                    KEY_STARTED_AT to startedAt,
                )

            /** The request for exporting [projectId] at [quality]; [title] names an unnamed video's file. */
            fun request(
                projectId: ProjectId,
                quality: ExportQuality,
                title: String,
            ): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<ExportWorker>()
                    .setInputData(workDataOf(KEY_PROJECT to projectId.value, KEY_QUALITY to quality.name, KEY_TITLE to title))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(TAG_EXPORT)
                    .addTag(projectTag(projectId))
                    .build()

            fun projectTag(projectId: ProjectId) = "project:${projectId.value}"

            /**
             * Starts the export. [replace] stops another video's export first (the user
             * said so); otherwise a running export is left alone and nothing new starts.
             * Returns the id of the export that was enqueued.
             */
            fun enqueue(
                workManager: WorkManager,
                projectId: ProjectId,
                quality: ExportQuality,
                title: String,
                replace: Boolean,
            ): UUID {
                val request = request(projectId, quality, title)
                workManager.enqueueUniqueWork(UNIQUE_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
                return request.id
            }

            /** The plain sentence for [reason]. */
            fun failureMessage(
                context: Context,
                reason: ExportFailure,
                missingBytes: Long,
            ): String =
                when (reason) {
                    ExportFailure.GONE -> {
                        context.getString(R.string.export_failed_gone)
                    }

                    ExportFailure.MISSING_MEDIA -> {
                        context.getString(R.string.export_failed_missing)
                    }

                    ExportFailure.PRESET_BROKEN -> {
                        context.getString(R.string.export_failed_preset)
                    }

                    ExportFailure.NOTHING -> {
                        context.getString(R.string.export_failed_nothing)
                    }

                    ExportFailure.NO_SPACE -> {
                        context.getString(
                            R.string.export_failed_space,
                            Formatter.formatShortFileSize(context, missingBytes),
                        )
                    }

                    ExportFailure.FAILED -> {
                        context.getString(R.string.export_failed_other)
                    }
                }
        }
    }
