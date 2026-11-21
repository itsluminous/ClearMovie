package com.itsluminous.clearmovie.ui.editor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.ui.components.durationText

// "Record voice" on a voice row (cm-record-voice): the microphone question and the recording panel.

const val RECORDING_PANEL_TAG = "recording_panel"
const val RECORDING_STOP_TAG = "recording_stop"
const val RECORDING_CANCEL_TAG = "recording_cancel"
const val RECORDING_DISCARD_TAG = "recording_discard"
const val MIC_RATIONALE_TAG = "mic_rationale"
const val MIC_CONTINUE_TAG = "mic_continue"
const val MIC_OFF_TAG = "mic_off"

/** Where a recording asked for goes: its voice row and where on the video it starts. */
private data class RecordTarget(
    val trackId: TrackId,
    val atMs: Long,
) {
    fun encode() = "$atMs:${trackId.value}"

    companion object {
        fun decode(text: String?): RecordTarget? =
            text?.let {
                runCatching {
                    val (ms, id) = it.split(':', limit = 2)
                    RecordTarget(TrackId(id), ms.toLong().coerceAtLeast(0))
                }.getOrNull()
            }
    }
}

/**
 * The microphone question for Record voice. Returns what Record voice
 * calls: with the microphone allowed it starts the recording ([onStart]) at
 * once; otherwise it first explains, in plain words, what the microphone is
 * used for, then Android asks. When Android will not ask again, it says
 * where in Settings to allow it, with a button there. Survives a rotation.
 */
@Composable
internal fun rememberRecordVoice(onStart: (TrackId, Long) -> Unit): (TrackId, Long) -> Unit {
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = RecordTarget.decode(asking)
            asking = null
            if (granted) {
                target?.let { onStart(it.trackId, it.atMs) }
            } else {
                // Denied, and Android will not show its question again: only Settings can allow it now.
                val activity = context.findActivityForMic()
                blocked =
                    activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            }
        }
    var explaining by rememberSaveable { mutableStateOf<String?>(null) }
    explaining?.let { saved ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            icon = { Icon(painterResource(R.drawable.ic_record_voice), contentDescription = null) },
            title = { Text(stringResource(R.string.mic_title)) },
            text = { Text(stringResource(R.string.mic_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explaining = null
                        asking = saved
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.testTag(MIC_CONTINUE_TAG),
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { explaining = null }) { Text(stringResource(R.string.media_access_not_now)) } },
            modifier = Modifier.testTag(MIC_RATIONALE_TAG),
        )
    }
    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            icon = { Icon(painterResource(R.drawable.ic_record_voice), contentDescription = null) },
            title = { Text(stringResource(R.string.mic_off_title)) },
            text = { Text(stringResource(R.string.mic_off_body)) },
            confirmButton = {
                TextButton(onClick = {
                    blocked = false
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }) { Text(stringResource(R.string.media_access_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { blocked = false }) { Text(stringResource(R.string.action_close)) } },
            modifier = Modifier.testTag(MIC_OFF_TAG),
        )
    }
    return { trackId, atMs ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onStart(trackId, atMs)
        } else {
            explaining = RecordTarget(trackId, atMs).encode()
        }
    }
}

/**
 * The recording panel, in place of the bar while a voice is recorded: the
 * countdown, then a red dot with the time said so far, where it started and
 * how much video is left, a large Stop and Cancel. The panel keeps its size
 * from the countdown to the recording, so nothing moves as the user starts
 * to speak. Cancel after a few seconds of speaking asks first. Nothing else
 * on the screen can be used meanwhile (see [EditorScreen]'s `recording`).
 */
@Composable
internal fun RecordingPanel(
    ui: RecordingUi,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val cancel = {
        if (ui is RecordingUi.Recording && ui.elapsedMs >= CONFIRM_CANCEL_AFTER_MS) confirmCancel = true else onCancel()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth().testTag(RECORDING_PANEL_TAG)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val start = durationText(ui.startMs * 1000)
            // The heading is read out when it changes (Get ready, then Recording); the numbers are not, every second.
            val announce =
                Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                }
            when (ui) {
                is RecordingUi.Countdown -> {
                    Text(stringResource(R.string.record_get_ready), style = MaterialTheme.typography.titleMedium, modifier = announce)
                    val said = pluralStringResource(R.plurals.record_starts_in, ui.secondsLeft, ui.secondsLeft)
                    Text(
                        ui.secondsLeft.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { contentDescription = said },
                    )
                    Body(stringResource(R.string.record_countdown_body, start))
                    Buttons(recording = false, onStop, cancel)
                }

                is RecordingUi.Recording -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = announce) {
                        Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.record_recording), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        // Rounded as the playhead's time is, so the two read alike.
                        durationText(ui.elapsedMs * 1000),
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Body(stringResource(R.string.record_progress, start, durationText(ui.leftMs * 1000)))
                    Buttons(recording = true, onStop, cancel)
                }

                is RecordingUi.Saving -> {
                    Text(stringResource(R.string.record_saving), style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator(Modifier.padding(8.dp))
                }
            }
        }
    }
    if (confirmCancel && ui is RecordingUi.Recording) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.record_discard_title)) },
            text = { Text(stringResource(R.string.record_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.record_keep_recording)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        onCancel()
                    },
                    modifier = Modifier.testTag(RECORDING_DISCARD_TAG),
                ) { Text(stringResource(R.string.record_discard)) }
            },
        )
    }
}

/** The panel's line under the time: always two lines high, so the countdown and the recording panels are one size. */
@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        minLines = 2,
    )
}

/** Cancel and Stop, the same row throughout: Stop shows from the countdown, and works once the recording runs. */
@Composable
private fun Buttons(
    recording: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(RECORDING_CANCEL_TAG)) {
            Text(stringResource(R.string.action_cancel), textAlign = TextAlign.Center)
        }
        Button(
            onClick = onStop,
            enabled = recording,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            modifier =
                Modifier
                    .weight(
                        1f,
                    ).heightIn(min = 56.dp)
                    .then(if (recording) Modifier.testTag(RECORDING_STOP_TAG) else Modifier),
        ) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.record_stop), textAlign = TextAlign.Center)
        }
    }
}

/** Cancel asks first once this much has been said. */
private const val CONFIRM_CANCEL_AFTER_MS = 3_000L

/**
 * A part of the editor while a voice is recorded: still seen, dimmed to
 * [dim] so it reads as out of use, but it takes
 * no touches (each is used up before the controls inside see it) and
 * TalkBack skips it, so nothing can move the picture under the recording.
 */
internal fun Modifier.lockedForRecording(dim: Float): Modifier =
    alpha(dim)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.clearAndSetSemantics {}

private tailrec fun Context.findActivityForMic(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivityForMic()
        else -> null
    }
