package com.itsluminous.clearmovie.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import java.util.UUID

/**
 * The notification's Stop button. Not exported, and reached only through
 * the immutable PendingIntent in the notification ([intent]), so no other
 * app can send it or change which export it stops. (WorkManager's own
 * cancel intent is mutable on Android 12+, which this avoids.) Stopping
 * removes the unfinished file, as the screen's Stop does.
 */
class ExportStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_STOP) return
        val id = intent.getStringExtra(EXTRA_WORK_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        WorkManager.getInstance(context).cancelWorkById(id)
    }

    companion object {
        const val ACTION_STOP = "com.itsluminous.clearmovie.action.STOP_EXPORT"
        const val EXTRA_WORK_ID = "work_id"

        fun intent(
            context: Context,
            workId: UUID,
        ): Intent =
            Intent(context, ExportStopReceiver::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_WORK_ID, workId.toString())
    }
}
