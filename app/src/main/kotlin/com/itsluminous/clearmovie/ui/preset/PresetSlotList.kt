package com.itsluminous.clearmovie.ui.preset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.PresetSlot

/** The frame row's "Colour and area" button (cm-frame-area). */
const val FRAME_LOOK_BUTTON_TAG = "preset_frame_look"

/** The name of a preset part, as the user sees it. */
fun PresetSlot.labelRes(): Int =
    when (this) {
        PresetSlot.INTRO -> R.string.preset_slot_intro
        PresetSlot.FRAME -> R.string.preset_slot_frame
        PresetSlot.ENDING -> R.string.preset_slot_ending
    }

/**
 * Preset setup and repair (ARCHITECTURE section 11.1): a heading that says
 * whether this is the first setup or a repair, then one row per preset file
 * with its status and a large Choose file button. A missing or changed file
 * is a repair state with plain words, never an error dialog. cm-flow puts
 * this on the setup screen with the first-time guide and Done.
 */
@Composable
fun PresetSlotList(
    status: PresetStatus,
    onChoose: (PresetSlot) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the frame's colour and picture area (cm-frame-area); null leaves the button out. */
    onFrameLook: (() -> Unit)? = null,
    /** The preset uses its files where they are, rather than copies. */
    linked: Boolean = false,
) {
    val repair = status.needsRepair.isNotEmpty()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (repair) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.preset_repair_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.preset_repair_body), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else {
            Text(stringResource(R.string.preset_setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(if (linked) R.string.preset_setup_body_linked else R.string.preset_setup_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (slot in PresetSlot.entries) {
            val slotStatus = status.slots[slot] ?: continue
            if (slotStatus is SlotStatus.NotNeeded) continue
            SlotRow(slot, slotStatus, onChoose = { onChoose(slot) }, onFrameLook = onFrameLook.takeIf { slot == PresetSlot.FRAME })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotRow(
    slot: PresetSlot,
    status: SlotStatus,
    onChoose: () -> Unit,
    onFrameLook: (() -> Unit)? = null,
) {
    val broken = status is SlotStatus.Missing || status is SlotStatus.Changed
    val ready = status as? SlotStatus.Ready
    val (icon, tint) =
        when {
            broken -> R.drawable.ic_warning to MaterialTheme.colorScheme.error
            ready != null -> R.drawable.ic_check_circle to MaterialTheme.colorScheme.primary
            else -> R.drawable.ic_radio_unchecked to MaterialTheme.colorScheme.onSurfaceVariant
        }
    val line =
        when (status) {
            is SlotStatus.Missing -> R.string.preset_slot_missing
            is SlotStatus.Changed -> R.string.preset_slot_changed
            is SlotStatus.Ready -> R.string.preset_slot_ready
            else -> R.string.preset_slot_not_set
        }
    val label = stringResource(slot.labelRes())
    val chooseDescription = stringResource(R.string.preset_choose_file_for, label)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(line),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (broken) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    if (ready?.binding?.keyAdjusted == true) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.preset_slot_frame_adjusted),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val buttonModifier =
                Modifier
                    .align(Alignment.End)
                    .heightIn(min = 56.dp)
                    .semantics { contentDescription = chooseDescription }
            if (ready == null) {
                Button(onClick = onChoose, modifier = buttonModifier) { ChooseLabel() }
            } else if (onFrameLook == null) {
                OutlinedButton(onClick = onChoose, modifier = buttonModifier) { ChooseLabel() }
            } else {
                // The frame's colour and picture area, then Choose file: side by side when they fit, else stacked.
                FlowRow(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onFrameLook, modifier = Modifier.heightIn(min = 56.dp).testTag(FRAME_LOOK_BUTTON_TAG)) {
                        Icon(painterResource(R.drawable.ic_colorize), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.preset_slot_frame_look))
                    }
                    OutlinedButton(
                        onClick = onChoose,
                        modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = chooseDescription },
                    ) { ChooseLabel() }
                }
            }
        }
    }
}

@Composable
private fun ChooseLabel() {
    Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.preset_choose_file))
}
