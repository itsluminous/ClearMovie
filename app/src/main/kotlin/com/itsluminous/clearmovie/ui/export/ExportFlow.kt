package com.itsluminous.clearmovie.ui.export

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.ui.components.PlayFileDialog
import com.itsluminous.clearmovie.work.ExportNotifications

/**
 * The export sheet wired to [ExportViewModel] (shown by the editor's Export
 * button). [title] is what the editor calls the video, and names the file
 * of a video that has no name yet. [blocked] is a reason the editor already
 * knows the export cannot start. Hiding the sheet (Hide, Back, a tap
 * outside) leaves the export and its result where they are; opening it
 * again shows them. Only Close and Cancel put it back on the sizes.
 *
 * Before the first export on Android 13+, the notification permission is
 * asked for once, so progress and the result can show outside the app;
 * saving starts whatever the answer.
 */
@UnstableApi
@Composable
fun ExportFlow(
    title: String,
    blocked: ExportBlock?,
    onDismiss: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val current by viewModel.step.collectAsStateWithLifecycle()
    // The free space and the sizes are looked at again each time the sheet opens.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val context = LocalContext.current
    var playing by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf<String?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.notificationsAsked()
            val quality = ExportQuality.parse(waiting)
            waiting = null
            if (quality != null) viewModel.start(quality, title)
        }
    val noApp = stringResource(R.string.export_share_none)
    // Null until WorkManager has answered: nothing is shown for that blink.
    val step = current ?: return
    val actions =
        remember(viewModel, step, title) {
            object : ExportSheetActions {
                override fun save(quality: ExportQuality) {
                    if (viewModel.shouldAskNotifications()) {
                        waiting = quality.name
                        if (runCatching { permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }.isSuccess) return
                        waiting = null
                    }
                    viewModel.start(quality, title)
                }

                override fun stop() = viewModel.stop()

                override fun hide() = onDismiss()

                override fun close() {
                    when (step) {
                        is ExportStep.Done, is ExportStep.Failed, is ExportStep.Choose -> viewModel.forget()
                        else -> Unit
                    }
                    viewModel.keepOther()
                    onDismiss()
                }

                override fun play() {
                    (step as? ExportStep.Done)?.let { playing = it.uri }
                }

                override fun share() {
                    val done = step as? ExportStep.Done ?: return
                    try {
                        context.startActivity(ExportNotifications.shareChooser(context, done.uri.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
                    }
                }

                override fun tryAgain() = viewModel.forget()

                override fun replaceOther() = viewModel.replaceOther()
            }
        }
    ExportSheet(step, blocked, actions)
    playing?.let { uri ->
        PlayFileDialog(uri.toUri(), (step as? ExportStep.Done)?.displayName ?: title, audioOnly = false, onClose = { playing = null })
    }
}
