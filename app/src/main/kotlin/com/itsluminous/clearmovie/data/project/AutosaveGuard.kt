package com.itsluminous.clearmovie.data.project

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Writes pending edits when they are at risk (ARCHITECTURE section 10):
 *
 * - when the whole app goes to the background (ON_STOP of
 *   `ProcessLifecycleOwner`), because Android only kills a background app for
 *   memory, so nothing is lost to a low-memory kill; this also records that
 *   the user has seen the project as saved ([ProjectRepository.markViewed]);
 * - when the app crashes, from the default uncaught-exception handler, for up
 *   to [crashFlushMs], before the crash goes on to the previous handler. The
 *   crash is kept in [crashLog] first, so the next launch opens Home and
 *   Settings can share what happened (cm-crash-after-media-pick).
 */
class AutosaveGuard(
    private val repository: ProjectRepository,
    private val scope: CoroutineScope,
    private val crashFlushMs: Long = 1500,
    private val crashLog: CrashLog? = null,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        scope.launch { repository.markViewed() }
    }

    /** Chains a flush in front of the current default handler. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashLog?.record(thread, error)
                repository.flushBlocking(crashFlushMs)
            } catch (ignored: Throwable) {
                // The crash itself must still reach the previous handler.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
