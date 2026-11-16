package com.itsluminous.clearmovie.ui.preset

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.ui.components.WorkDialog
import com.itsluminous.clearmovie.ui.components.sizeText
import com.itsluminous.clearmovie.ui.layout.AdaptiveScreen
import com.itsluminous.clearmovie.ui.navigation.FlowStep

/** Test tag of the setup screen's scrolling content. */
const val SETUP_LIST_TAG = "setup_list"

/** The setup and repair screen wired to its view model and to Android's file chooser. */
@Composable
fun PresetSetupRoute(
    onGo: (FlowStep) -> Unit,
    onBack: () -> Unit,
    viewModel: PresetSetupViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is SetupEvent.Go -> onGo(it.step)
                SetupEvent.Back -> onBack()
            }
        }
    }
    // A file may have been moved while the app was away.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    // Which part the file chooser is open for; saved, so a rotation or a
    // process death while it is open still binds the result to that part.
    var pendingSlot by rememberSaveable { mutableStateOf<String?>(null) }
    val chooseFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val slot = pendingSlot?.let { name -> PresetSlot.entries.firstOrNull { it.name == name } }
            pendingSlot = null
            if (uri != null && slot != null) viewModel.choose(slot, uri)
        }
    val chooseDefinition =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) viewModel.chooseDefinition(uri)
        }
    PresetSetupScreen(
        ui = ui,
        newVideo = viewModel.forNewVideo,
        onBack = onBack,
        onChoose = { slot ->
            pendingSlot = slot.name
            chooseFile.launch(viewModel.mimeTypes(slot))
        },
        onChooseDefinition = { chooseDefinition.launch(PresetSetupViewModel.DEFINITION) },
        onDone = viewModel::done,
        onCancelWork = viewModel::cancel,
        onDismissMessage = viewModel::dismissMessage,
    )
}

/**
 * Preset setup and repair (ARCHITECTURE section 11.1): the first time, a
 * short guide; then one row per preset file with its status and Choose file;
 * the optional preset file; and Done, enabled when every part is ready. A
 * missing file shows the repair heading instead of the guide. In two panes
 * the guide and Done sit on the left, the files on the right. Stateless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSetupScreen(
    ui: SetupUi,
    newVideo: Boolean,
    onBack: () -> Unit,
    onChoose: (PresetSlot) -> Unit,
    onChooseDefinition: () -> Unit,
    onDone: () -> Unit,
    onCancelWork: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.preset?.let { presetName(it) } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        val status = ui.status
        AdaptiveScreen(Modifier.fillMaxSize().padding(padding)) { window ->
            if (status == null) return@AdaptiveScreen
            val ready = status.isReady
            val side: @Composable (withGuide: Boolean) -> Unit = { withGuide ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (withGuide && ui.firstTime) Guide()
                    ui.message?.let { MessageCard(it, onDismissMessage) }
                    DefinitionCard(userFile = ui.preset?.displayName != null, onChoose = onChooseDefinition)
                    Button(
                        onClick = onDone,
                        enabled = ready && ui.work == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(if (newVideo) R.string.action_next else R.string.preset_setup_done))
                    }
                }
            }
            if (window.twoPane) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { side(true) }
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 16.dp)
                            .testTag(SETUP_LIST_TAG),
                    ) {
                        PresetSlotList(status, onChoose = onChoose)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag(SETUP_LIST_TAG),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // The guide comes first, before the files it explains.
                        if (ui.firstTime) Guide()
                        PresetSlotList(status, onChoose = onChoose)
                        side(false)
                    }
                }
            }
        }
    }
    when (val w = ui.work) {
        is SetupWork.Copying -> {
            WorkDialog(
                stringResource(R.string.preset_bind_copying),
                w.total?.takeIf { it > 0 }?.let { w.copied.toFloat() / it },
                onCancelWork,
            )
        }

        SetupWork.Checking -> {
            WorkDialog(stringResource(R.string.preset_bind_checking), null, onCancelWork)
        }

        null -> {
            Unit
        }
    }
}

/** The short first-time guide: where the files come from, in three steps with pictures (review F14). */
@Composable
private fun Guide() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.preset_guide_title), style = MaterialTheme.typography.titleLarge)
            GuideStep(R.drawable.ic_phone_download, stringResource(R.string.preset_guide_step_copy))
            GuideStep(R.drawable.ic_folder_open, stringResource(R.string.preset_guide_step_choose))
            GuideStep(R.drawable.ic_check_circle, stringResource(R.string.preset_guide_step_done))
        }
    }
}

@Composable
private fun GuideStep(
    icon: Int,
    text: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun DefinitionCard(
    userFile: Boolean,
    onChoose: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(stringResource(R.string.preset_definition_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (userFile) R.string.preset_definition_in_use else R.string.preset_definition_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onChoose, modifier = Modifier.align(Alignment.End).heightIn(min = 56.dp)) {
                Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.preset_choose_file))
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: SetupMessage,
    onDismiss: () -> Unit,
) {
    val good = message == SetupMessage.DefinitionAdded
    val text =
        when (message) {
            SetupMessage.NotAVideo -> {
                stringResource(R.string.preset_bind_not_video)
            }

            SetupMessage.NotASound -> {
                stringResource(R.string.preset_bind_not_sound)
            }

            SetupMessage.Unreadable -> {
                stringResource(R.string.preset_bind_unreadable)
            }

            is SetupMessage.NoSpace -> {
                stringResource(
                    R.string.copy_no_space_body,
                    sizeText(message.neededBytes),
                    sizeText(message.freeBytes),
                )
            }

            SetupMessage.DefinitionInvalid -> {
                stringResource(R.string.preset_definition_invalid)
            }

            SetupMessage.DefinitionAdded -> {
                stringResource(R.string.preset_definition_added)
            }

            SetupMessage.DefinitionOtherPreset -> {
                stringResource(R.string.preset_definition_other)
            }

            SetupMessage.NotSaved -> {
                stringResource(R.string.new_video_not_saved)
            }
        }
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = if (good) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(if (good) R.drawable.ic_check_circle else R.drawable.ic_warning),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}
