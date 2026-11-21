package com.itsluminous.clearmovie.ui.export

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.di.ExportModule
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.ui.navigation.Routes
import com.itsluminous.clearmovie.work.ExportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * The export sheet of the editor (ARCHITECTURE section 11.1, Export). It
 * owns nothing of the export itself, which is [ExportWorker]'s: it starts
 * one, watches the unique export work, and stops it. What it watches is in
 * the saved state, so a rotation or a process death keeps showing the same
 * export and its result.
 */
@UnstableApi
@HiltViewModel
class ExportViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        @param:ApplicationContext private val context: Context,
        private val repository: ProjectRepository,
        private val projectMedia: ProjectMedia,
        private val workManager: WorkManager,
    ) : ViewModel() {
        val projectId: ProjectId = ProjectId(requireNotNull(savedState.get<String>(Routes.ARG_PROJECT)))

        private val watched = MutableStateFlow(savedState.get<String>(KEY_WATCHED)?.let { runCatching { UUID.fromString(it) }.getOrNull() })

        /** When [watched] was set: an export asked for and never seen counts as running only for a while (review F7). */
        private var watchedAt = System.currentTimeMillis()
        private val choose = MutableStateFlow(ExportStep.Choose())

        /** Set when the user agreed to stop another video's export; the next start replaces it. */
        private val replaceAgreed = MutableStateFlow(savedState.get<Boolean>(KEY_REPLACE) ?: false)

        private val ticker =
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(TICK_MS)
                }
            }

        /** Null until WorkManager has answered, so an export in progress never flashes the sizes first (review F12). */
        val step: StateFlow<ExportStep?> =
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(ExportWorker.UNIQUE_NAME).map { list -> list.map(::toExportWork) },
                watched,
                choose,
                replaceAgreed,
                ticker,
            ) { works, watchedId, sizes, replace, now ->
                // A running export of this video is the one to report on, even one started before a restart.
                works.firstOrNull { !it.finished && it.projectId == projectId.value }?.let { if (it.id != watchedId) watch(it.id) }
                val lost = watchedId != null && works.none { it.id == watchedId } && now - watchedAt > LOST_AFTER_MS
                ExportSteps.step(works, watchedId.takeUnless { lost }, projectId, sizes, now, replaceAgreed = replace)
            }.distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        init {
            viewModelScope.launch {
                repository.open
                    .filterNotNull()
                    .filter { it.id == projectId }
                    .distinctUntilChanged()
                    .collect { measure(it) }
            }
        }

        /** Works out the sizes, and how much room is free, again: after the user freed space, for example (review F3). */
        fun refresh() {
            val project = repository.open.value?.takeIf { it.id == projectId } ?: return
            viewModelScope.launch { measure(project) }
        }

        private suspend fun measure(project: com.itsluminous.clearmovie.domain.model.Project) {
            val durationUs =
                withContext(Dispatchers.Default) {
                    runCatching {
                        val set = projectMedia.forProject(project)
                        RenderPlanBuilder(set.preset, set.loudness).build(project, shortSide = ExportQuality.SMALL.shortSide).durationUs
                    }.getOrNull()
                }
            val free = withContext(Dispatchers.IO) { ExportModule.exportFreeBytes(context) }
            choose.value = ExportSteps.choose(durationUs, free)
        }

        /** Starts exporting at [quality]; [title] names the file of a video that has no name yet. */
        fun start(
            quality: ExportQuality,
            title: String,
        ) {
            if (!replaceAgreed.value) {
                watch(ExportWorker.enqueue(workManager, projectId, quality, title, replace = false))
                return
            }
            // Another video's export is stopped first, and this one starts only once it has
            // ended, so two exports never hold the encoders together (review F9).
            setReplace(false)
            viewModelScope.launch {
                workManager.cancelUniqueWork(ExportWorker.UNIQUE_NAME)
                withTimeoutOrNull(REPLACE_WAIT_MS) {
                    workManager.getWorkInfosForUniqueWorkFlow(ExportWorker.UNIQUE_NAME).first { list -> list.all { it.state.isFinished } }
                }
                watch(ExportWorker.enqueue(workManager, projectId, quality, title, replace = false))
            }
        }

        /** Stops the export of this video; its unfinished file is removed. */
        fun stop() {
            watched.value?.let(workManager::cancelWorkById)
        }

        /** The user agreed to stop another video's export: the sizes show, and Save replaces it (Close keeps it). */
        fun replaceOther() {
            forget()
            setReplace(true)
        }

        /** The sheet was closed without saving: another video's export is left alone. */
        fun keepOther() = setReplace(false)

        private fun setReplace(value: Boolean) {
            replaceAgreed.value = value
            savedState[KEY_REPLACE] = value
        }

        /** The result was seen: next time the sheet opens on the sizes. */
        fun forget() {
            watched.value = null
            savedState.remove<String>(KEY_WATCHED)
            refresh()
        }

        /** Whether to ask for the notification permission before the first export (Android 13+, asked once). */
        fun shouldAskNotifications(): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefs().getBoolean(PREF_ASKED_NOTIFICATIONS, false)

        fun notificationsAsked() = prefs().edit { putBoolean(PREF_ASKED_NOTIFICATIONS, true) }

        private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun watch(id: UUID) {
            if (watched.value != id) watchedAt = System.currentTimeMillis()
            watched.value = id
            savedState[KEY_WATCHED] = id.toString()
        }

        companion object {
            const val KEY_WATCHED = "export_watched"
            const val KEY_REPLACE = "export_replace"
            private const val PREFS = "export"
            private const val PREF_ASKED_NOTIFICATIONS = "asked_notifications"
            private const val TICK_MS = 1_000L
            private const val STOP_TIMEOUT_MS = 5_000L
            private const val LOST_AFTER_MS = 10_000L
            private const val REPLACE_WAIT_MS = 15_000L

            fun toExportWork(info: WorkInfo): ExportWork {
                val data = if (info.state.isFinished) info.outputData else info.progress
                return ExportWork(
                    id = info.id,
                    state =
                        when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ExportWork.State.WAITING
                            WorkInfo.State.RUNNING -> ExportWork.State.RUNNING
                            WorkInfo.State.SUCCEEDED -> ExportWork.State.SUCCEEDED
                            WorkInfo.State.FAILED -> ExportWork.State.FAILED
                            WorkInfo.State.CANCELLED -> ExportWork.State.CANCELLED
                        },
                    projectId =
                        info.tags
                            .firstOrNull { it.startsWith(PROJECT_TAG_PREFIX) }
                            ?.removePrefix(PROJECT_TAG_PREFIX),
                    percent = data.getInt(ExportWorker.KEY_PERCENT, 0),
                    restarted = data.getBoolean(ExportWorker.KEY_RESTARTED, false),
                    startedAt = data.getLong(ExportWorker.KEY_STARTED_AT, 0),
                    uri = data.getString(ExportWorker.KEY_URI),
                    displayName = data.getString(ExportWorker.KEY_NAME),
                    sizeBytes = data.getLong(ExportWorker.KEY_SIZE, 0),
                    failure = data.getString(ExportWorker.KEY_FAILURE),
                    missingBytes = data.getLong(ExportWorker.KEY_MISSING_BYTES, 0),
                )
            }

            private const val PROJECT_TAG_PREFIX = "project:"
        }
    }
