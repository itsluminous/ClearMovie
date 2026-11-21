package com.itsluminous.clearmovie.ui.preset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel

const val PRESET_CREATE_NAME_TAG = "preset_create_name"
const val PRESET_CREATE_TAG = "preset_create"

fun presetPartTag(part: String) = "preset_part_$part"

fun endingChoiceTag(choice: EndingChoice) = "preset_ending_${choice.name}"

/** The new-preset screen wired to its view model; [onCreated] opens the editor on the new preset. */
@Composable
fun PresetCreateRoute(
    onCreated: (PresetId) -> Unit,
    onBack: () -> Unit,
    viewModel: PresetCreateViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.events.collect { onCreated(it) } }
    PresetCreateScreen(
        ui = ui,
        onName = viewModel::setName,
        onIntro = viewModel::setIntro,
        onFrame = viewModel::setFrame,
        onEnding = viewModel::setEnding,
        onShape = viewModel::setShape,
        onCreate = viewModel::create,
        onBack = onBack,
    )
}

/**
 * A new preset (cm-preset-manager): its name, and which parts it has (an
 * intro, a frame, and a spoken, video or no ending), then Create. The parts
 * cannot change later, which the screen says. Stateless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetCreateScreen(
    ui: PresetCreateUi,
    onName: (String) -> Unit,
    onIntro: (Boolean) -> Unit,
    onFrame: (Boolean) -> Unit,
    onEnding: (EndingChoice) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    onShape: (CanvasShape) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preset_new), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = ui.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.preset_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().testTag(PRESET_CREATE_NAME_TAG),
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(vertical = 16.dp).fillMaxWidth()) {
                        Text(
                            stringResource(R.string.preset_parts_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Text(
                            stringResource(R.string.preset_parts_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        PartSwitch(stringResource(R.string.preset_slot_intro), ui.intro, "intro", onIntro)
                        PartSwitch(stringResource(R.string.preset_slot_frame), ui.frame, "frame", onFrame)
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Text(
                            stringResource(R.string.preset_slot_ending),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Column(Modifier.selectableGroup()) {
                            for (choice in EndingChoice.entries) {
                                EndingRow(choice, choice == ui.ending) { onEnding(choice) }
                            }
                        }
                    }
                }
                PresetShapeCard(ui.shape, enabled = true, onShape = onShape)
                if (ui.failed) {
                    Text(
                        stringResource(R.string.preset_create_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onCreate,
                    enabled = ui.canCreate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(PRESET_CREATE_TAG),
                ) { AutoSizeLabel(stringResource(R.string.preset_create)) }
            }
        }
    }
}

@Composable
private fun PartSwitch(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp)
            .testTag(presetPartTag(tag)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun EndingRow(
    choice: EndingChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label =
        stringResource(
            when (choice) {
                EndingChoice.VOICE -> R.string.preset_ending_voice
                EndingChoice.VIDEO -> R.string.preset_ending_video
                EndingChoice.NONE -> R.string.preset_ending_none
            },
        )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp)
            .testTag(endingChoiceTag(choice)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
