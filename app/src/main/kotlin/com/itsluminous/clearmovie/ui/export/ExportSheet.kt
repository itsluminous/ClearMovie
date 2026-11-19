package com.itsluminous.clearmovie.ui.export

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.ui.components.ScrollingBody
import com.itsluminous.clearmovie.ui.components.sizeText
import com.itsluminous.clearmovie.work.ExportFailure

const val EXPORT_SHEET_TAG = "export_sheet"
const val EXPORT_SAVE_TAG = "export_save"
const val EXPORT_STOP_TAG = "export_stop"
const val EXPORT_HIDE_TAG = "export_hide"
const val EXPORT_PLAY_TAG = "export_play"
const val EXPORT_SHARE_TAG = "export_share"
const val EXPORT_CLOSE_TAG = "export_close"
const val EXPORT_PROGRESS_TAG = "export_progress"

fun exportQualityTag(quality: ExportQuality) = "export_quality_${quality.name}"

/** Something the editor knows stops an export before it starts. */
enum class ExportBlock { NOTHING, MISSING_MEDIA, PRESET_BROKEN }

/** What the sheet's buttons do. */
interface ExportSheetActions {
    fun save(quality: ExportQuality)

    fun stop()

    fun hide()

    fun close()

    fun play()

    fun share()

    fun tryAgain()

    fun replaceOther()
}

/**
 * The export sheet (ARCHITECTURE section 11.1): three big size choices with
 * their estimated sizes and Save; then the progress with the percentage, the
 * time left, Stop and Hide ("you can leave the app"); then the result with
 * Play and Share, or a plain explanation and Try again. Choices sit in a
 * column on a phone held upright and in a row when there is width for it.
 * Stateless apart from the chosen size and the Stop question, both kept
 * across a rotation.
 */
@Composable
fun ExportSheet(
    step: ExportStep,
    blocked: ExportBlock?,
    actions: ExportSheetActions,
) {
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Dialog(
        // Back or a tap outside never throws away a running export or its result (review F11).
        onDismissRequest = if (step is ExportStep.Choose || step == ExportStep.OtherRunning) actions::close else actions::hide,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ExportSheetContent(step, blocked, actions, onStop = { confirmStop = true })
    }
    if (confirmStop && step is ExportStep.Running) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.export_stop_confirm_title)) },
            text = { Text(stringResource(R.string.export_stop_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        actions.stop()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.export_stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.export_keep_saving))
                }
            },
        )
    }
    // The export ended while the question was up: it no longer applies.
    LaunchedEffect(step is ExportStep.Running) { if (step !is ExportStep.Running) confirmStop = false }
}

/**
 * The sheet itself, without its dialog window (so tests can lay it out at
 * a forced size): a column of choices below [WIDE], a row from there.
 */
@Composable
fun ExportSheetContent(
    step: ExportStep,
    blocked: ExportBlock?,
    actions: ExportSheetActions,
    onStop: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= WIDE
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .padding(16.dp)
                    .widthIn(max = if (wide) 840.dp else 560.dp)
                    .fillMaxWidth()
                    .testTag(EXPORT_SHEET_TAG),
        ) {
            Column(Modifier.padding(24.dp)) {
                when {
                    blocked != null && step is ExportStep.Choose -> Blocked(blocked, actions)
                    else -> Step(step, wide, actions, onStop)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Step(
    step: ExportStep,
    wide: Boolean,
    actions: ExportSheetActions,
    onStop: () -> Unit,
) {
    when (step) {
        is ExportStep.Choose -> Choose(step, wide, actions)
        is ExportStep.Running -> Running(step, actions, onStop)
        is ExportStep.Done -> Done(step, actions)
        is ExportStep.Failed -> Failed(step, actions)
        ExportStep.OtherRunning -> OtherRunning(actions)
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(12.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Choose(
    step: ExportStep.Choose,
    wide: Boolean,
    actions: ExportSheetActions,
) {
    var chosen by rememberSaveable { mutableStateOf(ExportQuality.suggested.name) }
    val quality = ExportQuality.parse(chosen) ?: ExportQuality.suggested
    val fits = step.shortBy[quality] == null
    Sheet(buttons = {
        TextButton(onClick = actions::close, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_CLOSE_TAG)) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = { actions.save(quality) },
            enabled = fits,
            modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_SAVE_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_export), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_save))
        }
    }) {
        Title(stringResource(R.string.export_title))
        if (step.stopped) {
            Text(
                stringResource(R.string.export_stopped),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(stringResource(R.string.export_choose_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        val cards: @Composable (Modifier) -> Unit = { modifier ->
            for (q in ExportQuality.shown) {
                QualityCard(
                    quality = q,
                    selected = q == quality,
                    bytes = step.sizes?.get(q),
                    shortBy = step.shortBy[q],
                    onSelect = { chosen = q.name },
                    modifier = modifier,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cards(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun QualityCard(
    quality: ExportQuality,
    selected: Boolean,
    bytes: Long?,
    shortBy: Long?,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
                contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
            ),
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier =
            modifier
                .heightIn(min = 72.dp)
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .testTag(exportQualityTag(quality)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(quality.label()), style = MaterialTheme.typography.titleLarge)
                    if (quality == ExportQuality.suggested) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer,
                        ) {
                            Text(
                                stringResource(R.string.export_suggested),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(quality.hint()), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                if (bytes != null) {
                    Text(stringResource(R.string.export_size_about, sizeText(bytes)), style = MaterialTheme.typography.bodyLarge)
                }
                if (shortBy != null) {
                    Text(
                        stringResource(R.string.export_needs_space, sizeText(shortBy)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Running(
    step: ExportStep.Running,
    actions: ExportSheetActions,
    onStop: () -> Unit,
) {
    Sheet(buttons = {
        OutlinedButton(
            onClick = onStop,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_STOP_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_stop))
        }
        Button(onClick = actions::hide, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_HIDE_TAG)) {
            Text(stringResource(R.string.export_hide))
        }
    }) {
        Title(stringResource(R.string.export_saving_title))
        if (step.percent <= 0) {
            // Before the first frame (measuring the sound, checking the colours): moving, so it never looks stuck (review F8).
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).testTag(EXPORT_PROGRESS_TAG))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.export_getting_ready), style = MaterialTheme.typography.titleMedium)
        } else {
            LinearProgressIndicator(
                progress = { step.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).testTag(EXPORT_PROGRESS_TAG),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.export_percent, step.percent), style = MaterialTheme.typography.titleMedium)
        }
        step.minutesLeft?.let {
            Text(pluralStringResource(R.plurals.export_minutes_left, it, it), style = MaterialTheme.typography.bodyLarge)
        }
        if (step.restarted) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.export_restarted), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.export_leave_ok), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColumnScope.Done(
    step: ExportStep.Done,
    actions: ExportSheetActions,
) {
    Sheet(buttons = {
        TextButton(onClick = actions::close, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_CLOSE_TAG)) {
            Text(stringResource(R.string.action_close))
        }
        FilledTonalButton(onClick = actions::share, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_SHARE_TAG)) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_share))
        }
        Button(onClick = actions::play, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_PLAY_TAG)) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_play))
        }
    }) {
        Icon(
            painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.export_done_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.export_done_body, step.displayName, sizeText(step.sizeBytes)),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ColumnScope.Failed(
    step: ExportStep.Failed,
    actions: ExportSheetActions,
) {
    Sheet(buttons = {
        TextButton(onClick = actions::close, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_CLOSE_TAG)) {
            Text(stringResource(R.string.action_close))
        }
        if (step.reason != ExportFailure.GONE) {
            Button(
                onClick = actions::tryAgain,
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.export_try_again)) }
        }
    }) {
        Problem(
            title = stringResource(R.string.export_failed_title),
            body =
                when (step.reason) {
                    ExportFailure.GONE -> stringResource(R.string.export_failed_gone)
                    ExportFailure.MISSING_MEDIA -> stringResource(R.string.export_failed_missing)
                    ExportFailure.PRESET_BROKEN -> stringResource(R.string.export_failed_preset)
                    ExportFailure.NOTHING -> stringResource(R.string.export_failed_nothing)
                    ExportFailure.NO_SPACE -> stringResource(R.string.export_failed_space, sizeText(step.missingBytes))
                    ExportFailure.FAILED -> stringResource(R.string.export_failed_other)
                },
        )
    }
}

@Composable
private fun ColumnScope.Blocked(
    block: ExportBlock,
    actions: ExportSheetActions,
) {
    Sheet(buttons = {
        Button(onClick = actions::close, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_CLOSE_TAG)) {
            Text(stringResource(R.string.action_ok))
        }
    }) {
        Problem(
            title = stringResource(R.string.export_title),
            body =
                when (block) {
                    ExportBlock.NOTHING -> stringResource(R.string.export_failed_nothing)
                    ExportBlock.MISSING_MEDIA -> stringResource(R.string.export_blocked_missing)
                    ExportBlock.PRESET_BROKEN -> stringResource(R.string.export_blocked_preset)
                },
        )
    }
}

@Composable
private fun ColumnScope.OtherRunning(actions: ExportSheetActions) {
    Sheet(buttons = {
        TextButton(onClick = actions::close, modifier = Modifier.heightIn(min = 56.dp).testTag(EXPORT_CLOSE_TAG)) {
            Text(stringResource(R.string.action_close))
        }
        Button(onClick = actions::replaceOther, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.export_other_replace))
        }
    }) {
        Problem(title = stringResource(R.string.export_other_running_title), body = stringResource(R.string.export_other_running_body))
    }
}

@Composable
private fun ColumnScope.Problem(
    title: String,
    body: String,
) {
    Icon(
        painterResource(R.drawable.ic_warning),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.fillMaxWidth().semantics {
                heading()
            },
    )
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge)
}

/**
 * One step of the sheet: its [body] scrolls when the window is short (a
 * phone held sideways, very large text) while its [buttons] stay in sight
 * at the end; they wrap onto a second line when the text is large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sheet(
    buttons: @Composable FlowRowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    ScrollingBody(
        actions = {
            Spacer(Modifier.height(16.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = buttons,
            )
        },
        body = body,
    )
}

private fun ExportQuality.label(): Int =
    when (this) {
        ExportQuality.BEST -> R.string.export_quality_best
        ExportQuality.GOOD -> R.string.export_quality_good
        ExportQuality.SMALL -> R.string.export_quality_small
    }

private fun ExportQuality.hint(): Int =
    when (this) {
        ExportQuality.BEST -> R.string.export_quality_best_hint
        ExportQuality.GOOD -> R.string.export_quality_good_hint
        ExportQuality.SMALL -> R.string.export_quality_small_hint
    }

private val WIDE = 600.dp
