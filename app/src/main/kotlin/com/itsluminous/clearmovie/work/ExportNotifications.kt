package com.itsluminous.clearmovie.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.export.ExportStore
import java.util.UUID

/**
 * The export's notifications (ARCHITECTURE section 12): progress with a
 * Stop button while it runs, then "Your video is ready" (tap to play, or
 * Share) or "Your video wasn't saved". Quiet: no sound, no vibration.
 *
 * Every PendingIntent is immutable and explicit where it targets this app:
 * the app's own activity, and its own unexported Stop receiver. The
 * finished video is opened by the phone's player, which gets read access to
 * that one gallery entry only.
 */
class ExportNotifications(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_export), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.notification_channel_export_description)
                    setSound(null, null)
                    enableVibration(false)
                }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** The running export, at [percent]; [workId] is what Stop cancels. */
    fun progress(
        workId: UUID,
        percent: Int,
        restarted: Boolean,
    ): Notification =
        base()
            .setContentTitle(context.getString(R.string.notification_export_title))
            .setContentText(
                context.getString(
                    if (restarted) R.string.notification_export_restarted else R.string.notification_export_progress,
                    percent.coerceIn(0, 100),
                ),
            ).setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.export_stop),
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_STOP,
                    ExportStopReceiver.intent(context, workId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    fun done(
        uri: Uri,
        displayName: String,
    ): Notification =
        base()
            .setContentTitle(context.getString(R.string.notification_export_done_title))
            .setContentText(context.getString(R.string.notification_export_done_text, displayName))
            .setAutoCancel(true)
            .setContentIntent(activity(REQUEST_PLAY, playIntent(uri)))
            .addAction(R.drawable.ic_share, context.getString(R.string.export_share), activity(REQUEST_SHARE, shareChooser(context, uri)))
            .build()

    fun failed(message: String): Notification =
        base()
            .setContentTitle(context.getString(R.string.notification_export_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    /** Posts [notification] when the user allows notifications; otherwise the app's own screen is the only report. */
    fun post(
        id: Int,
        notification: Notification,
    ) {
        if (!canPost()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications were turned off between the check and the post.
        }
    }

    fun cancelResult() = manager.cancel(RESULT_ID)

    fun cancelProgress() = manager.cancel(PROGRESS_ID)

    fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun base(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_export)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

    private fun openApp(): PendingIntent =
        activity(REQUEST_OPEN, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

    private fun activity(
        request: Int,
        intent: Intent,
    ): PendingIntent =
        PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val CHANNEL_ID = "export"

        /** The running export's notification (also the foreground service's). */
        const val PROGRESS_ID = 4101

        /** The last finished or failed export's notification. */
        const val RESULT_ID = 4102

        private const val REQUEST_OPEN = 1
        private const val REQUEST_PLAY = 2
        private const val REQUEST_SHARE = 3
        private const val REQUEST_STOP = 4

        /** Plays the gallery video [uri] in the phone's player, with read access to it alone. */
        fun playIntent(uri: Uri): Intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ExportStore.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The share sheet for the gallery video [uri]. */
        fun shareChooser(
            context: Context,
            uri: Uri,
        ): Intent {
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType(ExportStore.MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            return Intent
                .createChooser(send, context.getString(R.string.export_share_title))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
