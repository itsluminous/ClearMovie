package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.TextRules

const val RENAME_DIALOG_TAG = "editor_rename_dialog"
const val RENAME_FIELD_TAG = "editor_rename_field"
const val RENAME_CONFIRM_TAG = "editor_rename_confirm"
const val RENAME_CANCEL_TAG = "editor_rename_cancel"

/** The rename field grows to this many lines for a long name (a 100-character name at twice the text size is about five). */
private const val RENAME_MAX_LINES = 5

/**
 * "Rename video" (kupraki's request): one field holding [current], all of it
 * selected so typing replaces it. Rename gives back the name without the
 * spaces around it; it is off while the name has nothing to show, and the
 * field then says what it needs. Characters a name may not hold (a pasted
 * line break, invisible marks) are left out as they are typed, and a change
 * that would make the name longer than [Limits.MAX_NAME_LENGTH] characters
 * is not taken. What was typed survives a rotation.
 */
@Composable
internal fun RenameDialog(
    current: String,
    onRename: (name: String, edited: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current, selection = TextRange(0, current.length)))
    }
    // Whether the words were changed at all (so a typed name equal to the stand-in title still counts).
    var edited by rememberSaveable { mutableStateOf(false) }
    val name = value.text.trim()
    val valid = TextRules.isValidName(name)
    val needed = stringResource(R.string.editor_rename_needed)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The field is ready to type in: focused, with the keyboard up (a dialog does not raise it on its own).
    // After a rotation the dialog's new window takes a frame to attach, so the focus waits for one.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }

    fun confirm() {
        if (valid) onRename(name, edited)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RENAME_DIALOG_TAG),
        title = { Text(stringResource(R.string.editor_menu_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { typed ->
                    // Enter on a keyboard that shows it (or a hardware one) is Done, as on a one-line field.
                    if ('\n' in typed.text && '\n' !in value.text) {
                        confirm()
                        return@OutlinedTextField
                    }
                    val next = allowedName(typed)
                    // Past the length limit the change is not taken at all, rather than cutting the end
                    // off a name being corrected in the middle.
                    if (next.text.length <= Limits.MAX_NAME_LENGTH || next.text.length <= value.text.length) {
                        if (next.text != value.text) edited = true
                        value = next
                    }
                },
                label = { Text(stringResource(R.string.editor_rename_label)) },
                // Up to three lines, so a long name shows whole rather than scrolled to its end. A name has
                // no line breaks (allowedName leaves them out) and the keyboard's key is Done.
                singleLine = false,
                maxLines = RENAME_MAX_LINES,
                isError = !valid,
                // Said by TalkBack as it appears, in the app's own words rather than a generic "Invalid input".
                supportingText =
                    if (valid) {
                        null
                    } else {
                        { Text(needed, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { if (!valid) error(needed) }
                        .testTag(RENAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = valid, modifier = Modifier.testTag(RENAME_CONFIRM_TAG)) {
                Text(stringResource(R.string.editor_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(RENAME_CANCEL_TAG)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * [typed] without the characters a name may not hold (line breaks, the
 * invisible marks [TextRules] refuses, and lone halves of two-part
 * characters). The cursor, the selection and the word the keyboard is still
 * composing stay where they were, moved back by what was taken out before
 * them. The length limit is the dialog's to apply.
 */
internal fun allowedName(typed: TextFieldValue): TextFieldValue {
    val text = typed.text
    val kept = StringBuilder(text.length)
    // For each index of [text] (and its end), the index it has in what is kept.
    val moved = IntArray(text.length + 1)
    var i = 0
    while (i < text.length) {
        val size = Character.charCount(text.codePointAt(i))
        val piece = text.substring(i, i + size)
        val whole = !(size == 1 && Character.isSurrogate(piece[0]))
        for (k in 0 until size) moved[i + k] = kept.length
        if (whole && TextRules.isAllowed(piece, allowNewlines = false)) kept.append(piece)
        i += size
    }
    moved[text.length] = kept.length
    if (kept.length == text.length) return typed

    fun TextRange.moved() = TextRange(moved[start.coerceIn(0, text.length)], moved[end.coerceIn(0, text.length)])
    return TextFieldValue(kept.toString(), typed.selection.moved(), typed.composition?.moved()?.takeIf { !it.collapsed })
}
