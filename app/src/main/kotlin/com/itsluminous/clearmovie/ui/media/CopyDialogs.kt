package com.itsluminous.clearmovie.ui.media

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.ui.components.ScrollingBody

/** A size such as "1.4 GB", in the phone's own units and digits. */
@Composable
private fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * The copy budget question (ARCHITECTURE section 9): "These N photos and
 * videos need X GB of space on your phone", with Copy anyway, Choose fewer
 * and, under partial access, Allow all photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyBudgetDialog(
    verdict: CopyBudget.Verdict.NeedsConfirmation,
    onCopyAnyway: () -> Unit,
    onChooseFewer: () -> Unit,
    onAllowAll: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onChooseFewer) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            // The words scroll in a short window (a phone held sideways); the three answers stay in sight.
            ScrollingBody(
                modifier = Modifier.padding(24.dp),
                surface = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                actions = {
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (verdict.offerAllowAll) {
                            Button(onClick = onAllowAll, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(stringResource(R.string.media_access_allow_all))
                            }
                        }
                        OutlinedButton(onClick = onCopyAnyway, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.copy_budget_copy_anyway))
                        }
                        TextButton(onClick = onChooseFewer, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.copy_budget_choose_fewer))
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.copy_budget_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text(
                    pluralStringResource(R.plurals.copy_budget_body, verdict.itemCount, verdict.itemCount, sizeText(verdict.totalBytes)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.copy_budget_free, sizeText(verdict.freeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (verdict.offerAllowAll) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.copy_budget_allow_all_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** A copy that cannot fit on the phone at all. */
@Composable
fun NotEnoughSpaceDialog(
    verdict: CopyBudget.Verdict.NotEnoughSpace,
    onChooseFewer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onChooseFewer,
        title = { Text(stringResource(R.string.copy_no_space_title)) },
        text = { Text(stringResource(R.string.copy_no_space_body, sizeText(verdict.totalBytes), sizeText(verdict.freeBytes))) },
        confirmButton = {
            TextButton(onClick = onChooseFewer) { Text(stringResource(R.string.copy_budget_choose_fewer)) }
        },
    )
}

/**
 * Progress of a copy, with Cancel (ARCHITECTURE section 11: heavy work shows
 * progress with a cancel option). It cannot be dismissed by tapping outside,
 * so a stray tap never leaves a copy running without its progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyProgressDialog(
    copiedBytes: Long,
    totalBytes: Long,
    onCancel: () -> Unit,
) {
    val fraction = if (totalBytes <= 0) 0f else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.copy_progress_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.copy_progress_percent, (fraction * 100).toInt()), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
