package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R

const val DELETE_DIALOG_TAG = "editor_delete_dialog"
const val DELETE_CONFIRM_TAG = "editor_delete_confirm"
const val DELETE_CANCEL_TAG = "editor_delete_cancel"
const val DELETE_BUSY_DIALOG_TAG = "editor_delete_busy_dialog"

/** Dialog buttons keep a full touch target. */
private val DIALOG_BUTTON_HEIGHT = 48.dp

/**
 * "Delete this video?" (cm-delete-project): Material's alert dialog, saying
 * in plain words that only the video in My videos goes, for good, and that
 * what was saved to the gallery stays. Delete is in the theme's error colour.
 */
@Composable
internal fun DeleteVideoDialog(
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_delete_title)) },
        text = { Text(stringResource(R.string.editor_delete_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = DIALOG_BUTTON_HEIGHT).testTag(DELETE_CONFIRM_TAG)) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DIALOG_BUTTON_HEIGHT).testTag(DELETE_CANCEL_TAG)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = Modifier.testTag(DELETE_DIALOG_TAG),
    )
}

/** The video is being saved to the gallery: it can be deleted once that has finished or been stopped. */
@Composable
internal fun DeleteBlockedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_delete_busy_title)) },
        text = { Text(stringResource(R.string.editor_delete_busy_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DIALOG_BUTTON_HEIGHT)) {
                Text(stringResource(R.string.action_ok))
            }
        },
        modifier = Modifier.testTag(DELETE_BUSY_DIALOG_TAG),
    )
}
