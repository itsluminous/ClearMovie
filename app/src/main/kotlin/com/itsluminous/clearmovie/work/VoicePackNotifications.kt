package com.itsluminous.clearmovie.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.PackProgress
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The voice pack download's notifications (cm-voice-pack-download): its
 * progress, with Cancel, then "Voice pack added" or why it stopped. Quiet,
 * like the export's. Every PendingIntent is immutable and explicit.
 */
class VoicePackNotifications(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_voice_pack), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.notification_channel_voice_pack_description)
                    setSound(null, null)
                    enableVibration(false)
                }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun progress(p: PackProgress): Notification {
        val percent = VoicePackDownloadWorker.percent(p)
        val text =
            when {
                p.checking -> context.getString(R.string.pack_checking)
                p.total > 0 -> context.getString(R.string.pack_progress, size(p.done), size(p.total))
                else -> context.getString(R.string.pack_starting)
            }
        return base()
            .setContentTitle(context.getString(R.string.pack_downloading_title))
            .setContentText(text)
            .setProgress(100, percent, p.total <= 0 || p.checking)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(
                R.drawable.ic_close,
                context.getString(R.string.action_cancel),
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_CANCEL,
                    Intent(context, VoicePackCancelReceiver::class.java).setAction(VoicePackCancelReceiver.ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
    }

    fun done(): Notification =
        base()
            .setContentTitle(context.getString(R.string.notification_pack_done_title))
            .setContentText(context.getString(R.string.notification_pack_done_text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    fun failed(error: PackDownloadError): Notification {
        // The no-room text with its size is the dialog's; here, the short one.
        val message = context.getString(if (error == PackDownloadError.NO_SPACE) R.string.pack_no_space else errorText(error))
        return base()
            .setContentTitle(context.getString(R.string.pack_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    /** Posts [notification] when the user allows notifications; otherwise the app's own dialog is the only report. */
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

    private fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    private fun base(): NotificationCompat.Builder {
        // A foreground notification on a missing channel crashes the app; creating one that exists is a no-op.
        ensureChannel()
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_ID = "voice_pack"
        const val PROGRESS_ID = 4201
        const val RESULT_ID = 4202
        private const val REQUEST_OPEN = 11
        private const val REQUEST_CANCEL = 12

        /** The plain words for [error]; the no-room one takes the size to free (%1$s). */
        fun errorText(error: PackDownloadError): Int =
            when (error) {
                PackDownloadError.NETWORK -> R.string.pack_error_network
                PackDownloadError.SERVER -> R.string.pack_error_server
                PackDownloadError.CHECK -> R.string.pack_error_check
                PackDownloadError.NO_SPACE -> R.string.pack_error_space
                PackDownloadError.FAILED -> R.string.pack_error_failed
            }
    }
}

/**
 * The notification's Cancel. Not exported, and reached only through the
 * notification's immutable PendingIntent; it stops the download and removes
 * what it had downloaded, as the dialog's Cancel does.
 */
@AndroidEntryPoint
class VoicePackCancelReceiver : BroadcastReceiver() {
    @Inject
    lateinit var downloads: PackDownloads

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_CANCEL) downloads.cancel()
    }

    companion object {
        const val ACTION_CANCEL = "com.itsluminous.clearmovie.action.CANCEL_VOICE_PACK"
    }
}
