package com.itsluminous.clearmovie.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloadJob
import com.itsluminous.clearmovie.data.voice.PackDownloadOutcome
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.data.voice.PackDownloader
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.PackProgress
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The voice pack download (cm-voice-pack-download), as a foreground worker:
 * it carries on while the user edits, with the app in the background, and
 * after the editor closes. It runs only with a connection, so a dropped
 * connection pauses it and WorkManager runs it again when the phone is back
 * online; a file already partly downloaded is resumed. Progress is published
 * as [KEY_DONE], [KEY_TOTAL] and [KEY_CHECKING]; a failure as [KEY_FAILURE]
 * (a [PackDownloadError] name) and [KEY_MISSING_BYTES].
 */
@HiltWorker
class VoicePackDownloadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val tools: VoiceTools,
        private val downloader: PackDownloader,
    ) : CoroutineWorker(context, params) {
        private val notifications = VoicePackNotifications(applicationContext)

        // On Android 10 and 11 expedited work runs as a foreground service before doWork, with this notification.
        override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(PackProgress(0, 0))

        override suspend fun doWork(): Result {
            active.value = true
            try {
                return download()
            } finally {
                active.value = false
            }
        }

        private suspend fun download(): Result {
            val names = inputData.getStringArray(KEY_FILES)?.toList().orEmpty()
            notifications.cancelResult()
            try {
                setForeground(foregroundInfo(PackProgress(0, 0)))
            } catch (e: IllegalStateException) {
                // Not allowed to start a foreground service now: carry on without the notification.
                Log.w(TAG, "Voice pack download runs without a foreground notification", e)
            }
            var shown = -1
            val outcome =
                try {
                    runInterruptible(Dispatchers.IO) {
                        PackDownloadJob(tools, downloader).run(names, progress = { p ->
                            // Published once per percent (and when checking starts), not for every read.
                            val step = percent(p) * 2 + if (p.checking) 1 else 0
                            if (step != shown) {
                                shown = step
                                setProgressAsync(progressData(p))
                                notifications.post(VoicePackNotifications.PROGRESS_ID, notifications.progress(p))
                            }
                        }, stopped = { isStopped })
                    }
                } finally {
                    notifications.cancelProgress()
                }
            return when (outcome) {
                PackDownloadOutcome.Done -> {
                    // With ClearMovie on screen, its own note says so; a notification as well would say it twice.
                    val onScreen =
                        ProcessLifecycleOwner
                            .get()
                            .lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                    if (!onScreen) notifications.post(VoicePackNotifications.RESULT_ID, notifications.done())
                    Result.success()
                }

                // Stopped by WorkManager (the connection went): it runs again, and resumes.
                PackDownloadOutcome.Stopped -> {
                    Result.retry()
                }

                is PackDownloadOutcome.Failed -> {
                    notifications.post(VoicePackNotifications.RESULT_ID, notifications.failed(outcome.error))
                    Result.failure(workDataOf(KEY_FAILURE to outcome.error.name, KEY_MISSING_BYTES to outcome.missingBytes))
                }
            }
        }

        private fun foregroundInfo(p: PackProgress): ForegroundInfo =
            ForegroundInfo(VoicePackNotifications.PROGRESS_ID, notifications.progress(p), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        companion object {
            private const val TAG = "VoicePackDownload"

            /** One voice pack download at a time. */
            const val UNIQUE_NAME = "voice_pack_download"

            const val KEY_FILES = "files"
            const val KEY_DONE = "done"
            const val KEY_TOTAL = "total"
            const val KEY_CHECKING = "checking"
            const val KEY_FAILURE = "failure"
            const val KEY_MISSING_BYTES = "missing_bytes"

            /**
             * True while a download worker of this process runs its job, so Cancel and Remove can wait for it
             * to let go of the pack's files: WorkManager marks the work cancelled before the worker stops.
             */
            val active = MutableStateFlow(false)

            fun percent(p: PackProgress): Int = if (p.total <= 0) 0 else (p.done * 100 / p.total).toInt().coerceIn(0, 100)

            fun progressData(p: PackProgress): Data = workDataOf(KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_CHECKING to p.checking)

            /** What [info] (the unique download's latest work) means for the screens. */
            fun stateOf(info: WorkInfo?): PackDownloadState {
                if (info == null) return PackDownloadState.Idle
                val id = info.id.toString()
                return when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        PackDownloadState.Waiting(id)
                    }

                    WorkInfo.State.RUNNING -> {
                        PackDownloadState.Running(
                            id,
                            PackProgress(
                                info.progress.getLong(KEY_DONE, 0),
                                info.progress.getLong(KEY_TOTAL, 0),
                                info.progress.getBoolean(KEY_CHECKING, false),
                            ),
                        )
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        PackDownloadState.Done(id)
                    }

                    WorkInfo.State.FAILED -> {
                        PackDownloadState.Failed(
                            id,
                            info.outputData
                                .getString(KEY_FAILURE)
                                ?.let { n -> PackDownloadError.entries.firstOrNull { it.name == n } } ?: PackDownloadError.FAILED,
                            info.outputData.getLong(KEY_MISSING_BYTES, 0),
                        )
                    }

                    WorkInfo.State.CANCELLED -> {
                        PackDownloadState.Idle
                    }
                }
            }
        }
    }

/** [PackDownloads] through WorkManager: the unique [VoicePackDownloadWorker]. */
class WorkPackDownloads(
    private val workManager: WorkManager,
    private val tools: VoiceTools,
    private val scope: CoroutineScope,
) : PackDownloads {
    private val infos = workManager.getWorkInfosForUniqueWorkFlow(VoicePackDownloadWorker.UNIQUE_NAME)

    override val state: StateFlow<PackDownloadState> =
        infos
            .map { list -> VoicePackDownloadWorker.stateOf(list.firstOrNull { !it.state.isFinished } ?: list.firstOrNull()) }
            .stateIn(scope, SharingStarted.Eagerly, PackDownloadState.Idle)

    override fun start(files: List<VoicePackFile>) {
        if (files.isEmpty()) return
        val request =
            OneTimeWorkRequestBuilder<VoicePackDownloadWorker>()
                .setInputData(workDataOf(VoicePackDownloadWorker.KEY_FILES to files.map { it.file }.toTypedArray()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_SECONDS, TimeUnit.SECONDS)
                .build()
        // REPLACE also clears an earlier, finished download, so the one followed is always this one. Only
        // started from the dialog while no download runs; a part already downloaded is resumed.
        workManager.enqueueUniqueWork(VoicePackDownloadWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        scope.launch { cancelAndWait() }
    }

    override suspend fun cancelAndWait() {
        workManager.cancelUniqueWork(VoicePackDownloadWorker.UNIQUE_NAME)
        // Once the worker has let go of its files, what it downloaded goes too; not when a newer
        // download has started meanwhile, whose part it would be.
        withTimeoutOrNull(STOP_WAIT_MS) { VoicePackDownloadWorker.active.first { !it } }
        if (!VoicePackDownloadWorker.active.value) tools.clearDownloads()
    }

    private companion object {
        const val RETRY_SECONDS = 30L
        const val STOP_WAIT_MS = 60_000L
    }
}
