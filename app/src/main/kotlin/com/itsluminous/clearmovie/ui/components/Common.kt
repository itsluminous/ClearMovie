package com.itsluminous.clearmovie.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** A length to a tenth of a second, such as "0:03.5", from milliseconds (the ruler, trim and split). */
@Composable
fun preciseDurationText(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) + 50) / 100
    return stringResource(R.string.duration_minutes_seconds_tenths, tenths / 600, (tenths / 10) % 60, tenths % 10)
}

/**
 * Heavy work in progress, with Cancel (ARCHITECTURE section 11). [fraction]
 * null shows a moving bar. [body], when set, says more under the title.
 * It cannot be dismissed by a stray tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDialog(
    title: String,
    fraction: Float?,
    onCancel: (() -> Unit)?,
    body: String? = null,
) {
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            // Scrolls when a body at a large font size is taller than a phone held sideways, so Cancel stays reachable.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (body != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
