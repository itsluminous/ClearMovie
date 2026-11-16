package com.itsluminous.clearmovie.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/** The export's notifications: quiet, with progress and Stop, then the result with Play and Share. */
@RunWith(RobolectricTestRunner::class)
class ExportNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = ExportNotifications(context)
    private val video = Uri.parse("content://media/external_primary/video/media/42")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        notifications.ensureChannel()
    }

    @Test
    fun `the channel is quiet`() {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(ExportNotifications.CHANNEL_ID)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.sound).isNull()
        assertThat(channel.name.toString()).isEqualTo(context.getString(R.string.notification_channel_export))
    }

    @Test
    fun `progress is ongoing, shows the percentage and has Stop`() {
        val work = UUID.randomUUID()
        val n = notifications.progress(work, 37, restarted = false)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(n.extras.getInt(Notification.EXTRA_PROGRESS)).isEqualTo(37)
        assertThat(n.extras.getInt(Notification.EXTRA_PROGRESS_MAX)).isEqualTo(100)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo(context.getString(R.string.notification_export_progress, 37))
        assertThat(n.actions.map { it.title.toString() }).containsExactly(context.getString(R.string.export_stop))
        // Stop is an immutable broadcast to the app's own unexported receiver, naming this export.
        val stop = shadowOf(n.actions[0].actionIntent)
        assertThat(stop.isBroadcastIntent).isTrue()
        assertThat(stop.isImmutable).isTrue()
        assertThat(stop.savedIntent.component?.className).isEqualTo(ExportStopReceiver::class.java.name)
        assertThat(stop.savedIntent.getStringExtra(ExportStopReceiver.EXTRA_WORK_ID)).isEqualTo(work.toString())
        // Tapping opens the app itself.
        val open = shadowOf(n.contentIntent).savedIntent
        assertThat(open.component?.packageName).isEqualTo(context.packageName)
        assertThat(n.category).isEqualTo(NotificationCompat.CATEGORY_PROGRESS)
    }

    @Test
    fun `the Stop receiver cancels only the export it names`() {
        val workManager = androidx.work.WorkManager.getInstance(context)
        // A stand-in export that runs until it is cancelled.
        val request = OneTimeWorkRequestBuilder<HoldWorker>().build()
        workManager.enqueue(request).result.get()
        ExportStopReceiver().onReceive(context, Intent("other.action").putExtra(ExportStopReceiver.EXTRA_WORK_ID, request.id.toString()))
        ExportStopReceiver().onReceive(
            context,
            Intent(ExportStopReceiver.ACTION_STOP).putExtra(ExportStopReceiver.EXTRA_WORK_ID, "not-a-uuid"),
        )
        val before = workManager.getWorkInfoById(request.id).get()!!.state
        assertThat(before.isFinished).isFalse()
        ExportStopReceiver().onReceive(context, ExportStopReceiver.intent(context, request.id))
        var state = workManager.getWorkInfoById(request.id).get()!!.state
        repeat(50) {
            if (state.isFinished) return@repeat
            Thread.sleep(20)
            state = workManager.getWorkInfoById(request.id).get()!!.state
        }
        assertThat(state).isEqualTo(androidx.work.WorkInfo.State.CANCELLED)
    }

    @Test
    fun `a restarted export says so`() {
        val n = notifications.progress(UUID.randomUUID(), 5, restarted = true)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo(context.getString(R.string.notification_export_restarted, 5))
    }

    @Test
    fun `done plays the video with read access to it alone, and offers Share`() {
        val n = notifications.done(video, "Spasht Dhaara.mp4")
        assertThat(n.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("Spasht Dhaara.mp4")
        val play = shadowOf(n.contentIntent).savedIntent
        assertThat(play.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(play.data).isEqualTo(video)
        assertThat(play.type).isEqualTo("video/mp4")
        assertThat(play.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(play.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
        assertThat(shadowOf(n.contentIntent).isImmutable).isTrue()
        val share = shadowOf(n.actions.single().actionIntent).savedIntent
        assertThat(share.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val send = share.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        @Suppress("DEPRECATION")
        assertThat(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(video)
        assertThat(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun `failed explains in plain words and opens the app`() {
        val message = ExportWorker.failureMessage(context, ExportFailure.NO_SPACE, 300L * 1024 * 1024)
        val n = notifications.failed(message)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(R.string.notification_export_failed_title))
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo(message)
        assertThat(message).contains("MB")
        assertThat(shadowOf(n.contentIntent).savedIntent.component?.packageName).isEqualTo(context.packageName)
        for (reason in ExportFailure.entries) assertThat(ExportWorker.failureMessage(context, reason, 1)).isNotEmpty()
    }

    @Test
    fun `nothing is posted when notifications are off`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.post(ExportNotifications.RESULT_ID, notifications.done(video, "a.mp4"))
        assertThat(shadowOf(manager).allNotifications).isEmpty()
        shadowOf(manager).setNotificationsEnabled(true)
        notifications.post(ExportNotifications.RESULT_ID, notifications.done(video, "a.mp4"))
        assertThat(shadowOf(manager).allNotifications).hasSize(1)
    }
}

/** Runs until cancelled. */
class HoldWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = awaitCancellation()
}
