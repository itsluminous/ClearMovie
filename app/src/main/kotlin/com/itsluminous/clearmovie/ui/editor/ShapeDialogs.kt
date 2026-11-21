package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.ui.components.ShapeOptions

const val SHAPE_FIRST_TAG = "shape_first"
const val SHAPE_FIRST_CONTINUE_TAG = "shape_first_continue"
const val SHAPE_CHANGE_TAG = "shape_change"
const val SHAPE_CHANGE_CONFIRM_TAG = "shape_change_confirm"

/**
 * The first time the editor opens a video made without a preset
 * (cm-aspect-ratio): which shape it should be. [suggested] (from the first
 * photo or video) is chosen already, so Continue is the one tap needed; Back
 * or a tap outside keeps the choice shown too, so the question is never left
 * unanswered. The choice survives a rotation.
 */
@Composable
fun FirstShapeDialog(
    suggested: CanvasShape?,
    onChoose: (CanvasShape) -> Unit,
) {
    // The user's own pick, once made; until then the suggestion, which may arrive a moment late.
    var picked by rememberSaveable { mutableStateOf<CanvasShape?>(null) }
    val shown = picked ?: suggested ?: CanvasShape.LANDSCAPE
    AlertDialog(
        onDismissRequest = { onChoose(shown) },
        title = { Text(stringResource(R.string.shape_first_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShapeOptions(shown, onSelect = { picked = it }, sidePadding = 0.dp)
                Text(
                    stringResource(R.string.shape_first_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(shown) }, modifier = Modifier.heightIn(min = 48.dp).testTag(SHAPE_FIRST_CONTINUE_TAG)) {
                Text(stringResource(R.string.action_continue))
            }
        },
        modifier = Modifier.testTag(SHAPE_FIRST_TAG),
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/**
 * "Video shape" from the editor's More options (cm-aspect-ratio): the
 * shapes, the current one chosen, and Change. [frameShape] is the shape the
 * preset's frame is made for, when the video has one: the dialog then says
 * the frame will be switched off (or on again) with the change.
 */
@Composable
fun ChangeShapeDialog(
    current: CanvasShape,
    frameShape: CanvasShape?,
    onChange: (CanvasShape) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by rememberSaveable(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shape_change_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShapeOptions(picked, onSelect = { picked = it }, sidePadding = 0.dp)
                val frameNote =
                    when {
                        frameShape == null || picked == current -> null
                        picked != frameShape && current == frameShape -> R.string.shape_change_frame_off
                        picked == frameShape -> R.string.shape_change_frame_on
                        else -> null
                    }
                if (picked != current) {
                    Text(
                        stringResource(frameNote ?: R.string.shape_change_captions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChange(picked) },
                enabled = picked != current,
                modifier = Modifier.heightIn(min = 48.dp).testTag(SHAPE_CHANGE_CONFIRM_TAG),
            ) { Text(stringResource(R.string.shape_change)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag(SHAPE_CHANGE_TAG),
    )
}
