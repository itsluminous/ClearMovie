package com.itsluminous.clearmovie.ui.preset

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.PartChoice
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.ui.components.PlayFileDialog
import com.itsluminous.clearmovie.ui.components.WorkDialog
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.ui.layout.AdaptiveScreen
import com.itsluminous.clearmovie.ui.media.IntakeState
import com.itsluminous.clearmovie.ui.media.MediaIntakeDialogs
import com.itsluminous.clearmovie.ui.media.rememberMediaAccessController
import com.itsluminous.clearmovie.ui.navigation.FlowStep

/** Test tag of the review screen's list of parts. */
const val REVIEW_LIST_TAG = "review_list"

/** The review screen wired to its view model, the pickers and the player. */
@Composable
fun PresetReviewRoute(
    onGo: (FlowStep) -> Unit,
    onBack: () -> Unit,
    viewModel: PresetReviewViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val intake by viewModel.intake.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is ReviewEvent.Go -> onGo(it.step)
                ReviewEvent.Back -> onBack()
            }
        }
    }
    BackHandler(onBack = viewModel::discard)
    // Only read here: the access question is asked before the photo picker, not for one file.
    val access = rememberMediaAccessController(askedBefore = false, onAsked = {})
    var pendingSlot by rememberSaveable { mutableStateOf<String?>(null) }

    fun taken(uri: Uri?) {
        val slot = pendingSlot?.let { n -> PresetSlot.entries.firstOrNull { it.name == n } }
        pendingSlot = null
        if (uri != null && slot != null) viewModel.replace(slot, uri, access.state)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { taken(it) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { taken(it) }
    var playing by rememberSaveable { mutableStateOf<String?>(null) }

    PresetReviewScreen(
        ui = ui,
        // The copy question, no-space and copy progress have dialogs of their own.
        working =
            ui.replacing != null &&
                intake !is IntakeState.Confirm &&
                intake !is IntakeState.NoSpace &&
                intake !is IntakeState.Copying,
        onBack = viewModel::discard,
        onPlay = { playing = it.name },
        onReplace = { slot ->
            pendingSlot = slot.name
            if (slot == PresetSlot.ENDING && ui.spokenEnding) {
                pickSound.launch(arrayOf("audio/*"))
            } else {
                pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        },
        onSkip = viewModel::skip,
        onUsePreset = viewModel::usePreset,
        onNext = viewModel::next,
        onDismissMessage = viewModel::dismissMessage,
    )
    MediaIntakeDialogs(
        state = intake,
        onCopyAnyway = viewModel.intake::copyAnyway,
        onChooseFewer = viewModel.intake::chooseFewer,
        onAllowAll = viewModel.intake::chooseFewer,
        onCancel = viewModel.intake::cancel,
    )
    val slot = playing?.let { n -> PresetSlot.entries.firstOrNull { it.name == n } }
    if (slot != null) {
        val uri by produceState<Uri?>(null, slot, ui.choices) { value = viewModel.playable(slot) }
        uri?.let {
            PlayFileDialog(
                uri = it,
                title = stringResource(slot.labelRes()),
                audioOnly = slot == PresetSlot.ENDING && ui.spokenEnding,
                onClose = { playing = null },
            )
        }
    }
}

/**
 * Preset review (ARCHITECTURE section 11.1, requirement 2): what the preset
 * puts in this video, the intro, the frame and the ending, each with Play,
 * Replace and Skip for this video (or Use the preset's again), then Next. In
 * two panes the explanation and Next are on the left, the parts on the right.
 * Stateless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetReviewScreen(
    ui: ReviewUi,
    working: Boolean,
    onBack: () -> Unit,
    onPlay: (PresetSlot) -> Unit,
    onReplace: (PresetSlot) -> Unit,
    onSkip: (PresetSlot) -> Unit,
    onUsePreset: (PresetSlot) -> Unit,
    onNext: () -> Unit,
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
        if (ui.preset == null) return@Scaffold
        AdaptiveScreen(Modifier.fillMaxSize().padding(padding)) { window ->
            val intro: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.review_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.review_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val next: @Composable () -> Unit = {
                Button(onClick = onNext, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_next), style = MaterialTheme.typography.titleMedium)
                }
            }
            val parts: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(REVIEW_LIST_TAG)) {
                    ui.message?.let { ReviewMessageCard(it, ui.spokenEnding, onDismissMessage) }
                    for (slot in ui.parts) {
                        PartCard(slot, ui, enabled = !working, onPlay, onReplace, onSkip, onUsePreset)
                    }
                }
            }
            if (window.twoPane) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        intro()
                        next()
                    }
                    Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { parts() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                            .widthIn(max = 640.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        intro()
                        parts()
                    }
                    Column(Modifier.padding(16.dp)) { next() }
                }
            }
        }
    }
    if (working) WorkDialog(stringResource(R.string.preset_bind_checking), null, null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartCard(
    slot: PresetSlot,
    ui: ReviewUi,
    enabled: Boolean,
    onPlay: (PresetSlot) -> Unit,
    onReplace: (PresetSlot) -> Unit,
    onSkip: (PresetSlot) -> Unit,
    onUsePreset: (PresetSlot) -> Unit,
) {
    val choice = ui.choices[slot]
    val spoken = slot == PresetSlot.ENDING && ui.spokenEnding
    val icon =
        when {
            slot == PresetSlot.FRAME -> R.drawable.ic_frame
            spoken -> R.drawable.ic_music_note
            else -> R.drawable.ic_videocam
        }
    val presetMs =
        when (slot) {
            PresetSlot.INTRO -> ui.bound.introMs
            PresetSlot.FRAME -> ui.bound.frameMs
            PresetSlot.ENDING -> ui.bound.endingMs
        }
    val label = stringResource(slot.labelRes())
    val skipped = choice == PartChoice.Skip
    val container = if (skipped) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    val line =
                        when (choice) {
                            PartChoice.UsePreset -> {
                                presetMs?.let { stringResource(R.string.review_from_preset_length, durationText(it * 1000)) }
                                    ?: stringResource(R.string.review_from_preset)
                            }

                            PartChoice.Skip -> {
                                stringResource(R.string.review_skipped)
                            }

                            is PartChoice.Replace -> {
                                stringResource(
                                    R.string.review_your_file,
                                    choice.name ?: stringResource(R.string.review_your_file_unnamed),
                                    durationText(choice.durationMs * 1000),
                                )
                            }
                        }
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                    val hint =
                        when {
                            slot == PresetSlot.FRAME -> R.string.review_frame_hint
                            spoken -> R.string.review_spoken_hint
                            slot == PresetSlot.INTRO -> R.string.review_intro_hint
                            else -> R.string.review_video_ending_hint
                        }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val playDescription = stringResource(R.string.review_play_part, label)
                if (!skipped) {
                    FilledTonalButton(
                        onClick = { onPlay(slot) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = playDescription },
                    ) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_play))
                    }
                }
                OutlinedButton(onClick = { onReplace(slot) }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_replace))
                }
                if (choice == PartChoice.UsePreset) {
                    TextButton(onClick = { onSkip(slot) }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(painterResource(R.drawable.ic_block), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.review_skip))
                    }
                } else {
                    TextButton(onClick = { onUsePreset(slot) }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(painterResource(R.drawable.ic_restore), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.review_use_preset))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewMessageCard(
    message: ReviewMessage,
    spokenEnding: Boolean,
    onDismiss: () -> Unit,
) {
    val text =
        when (message) {
            is ReviewMessage.WrongKind -> {
                stringResource(
                    if (message.slot == PresetSlot.ENDING &&
                        spokenEnding
                    ) {
                        R.string.preset_bind_not_sound
                    } else {
                        R.string.preset_bind_not_video
                    },
                )
            }

            ReviewMessage.Unreadable -> {
                stringResource(R.string.preset_bind_unreadable)
            }
        }
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}
