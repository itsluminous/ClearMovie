package com.itsluminous.clearmovie.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.itsluminous.clearmovie.R

/** A size such as "1.4 GB", in the phone's own units and digits. */
@Composable
fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** A length such as "1:05" (minutes and seconds), from microseconds. */
@Composable
fun durationText(us: Long): String {
    val totalSeconds = (us.coerceAtLeast(0) + 500_000) / 1_000_000
    return stringResource(R.string.duration_minutes_seconds, totalSeconds / 60, totalSeconds % 60)
}

/**
 * Heavy work in progress, with Cancel (ARCHITECTURE section 11). [fraction]
 * null shows a moving bar. It cannot be dismissed by a stray tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDialog(
    title: String,
    fraction: Float?,
    onCancel: (() -> Unit)?,
) {
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.copy_progress_percent, (fraction.coerceIn(0f, 1f) * 100).toInt()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}
