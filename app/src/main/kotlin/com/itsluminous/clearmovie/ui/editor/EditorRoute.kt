package com.itsluminous.clearmovie.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.components.PlayFileDialog
import com.itsluminous.clearmovie.ui.components.WorkDialog
import com.itsluminous.clearmovie.ui.media.rememberMediaAccessController
import com.itsluminous.clearmovie.ui.preset.presetName

/** The editor shell wired to its view model and the layered preview. */
@OptIn(UnstableApi::class)
@Composable
fun EditorRoute(
    onClosed: () -> Unit,
    onAddMedia: (ProjectId) -> Unit,
    onFixPreset: (PresetId) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val preview by viewModel.engine.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var exportInfo by rememberSaveable { mutableStateOf(false) }
    var notSaved by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val access = rememberMediaAccessController(askedBefore = false, onAsked = {})

    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                EditorEvent.Closed -> onClosed()
                EditorEvent.NotSaved -> notSaved = true
            }
        }
    }
    LaunchedEffect(viewModel) {
        val notes = viewModel.arrivalNotes ?: return@LaunchedEffect
        viewModel.notesShown()
        if (notes.broughtBack) snackbar.showSnackbar(resources.getString(R.string.editor_brought_back))
        if (notes.skipped > 0) snackbar.showSnackbar(resources.getQuantityString(R.plurals.pick_skipped, notes.skipped, notes.skipped))
    }
    // A part the preview could not open: said once per new count, never silently left out.
    LaunchedEffect(preview.unavailable) {
        if (preview.unavailable > 0) snackbar.showSnackbar(resources.getString(R.string.editor_part_unavailable))
    }
    // Back from the repair screen or Android Settings: look at the files and access again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh(access.state) }
    // Going to the background pauses; a rotation (the activity is recreated) does not.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (context.findActivity()?.isChangingConfigurations != true) viewModel.pause()
    }
    BackHandler(onBack = viewModel::close)

    if (ui.failed) {
        CannotOpen(onBack = onClosed)
        return
    }
    val title = ui.project?.name ?: ui.preset?.let { presetName(it) } ?: stringResource(R.string.editor_title_new)
    EditorScreen(
        ui = ui,
        title = title,
        playhead = PlayheadUi(preview.positionUs, preview.durationUs, preview.playing),
        preview = { modifier ->
            val schedule = ui.schedule
            if (schedule != null) {
                LayeredPreview(viewModel.engine, schedule, preview, viewModel.stills, viewModel.resolver, modifier)
            }
        },
        thumbnail = viewModel::thumbnail,
        onBack = viewModel::close,
        onExport = {
            viewModel.pause()
            exportInfo = true
        },
        onTogglePlay = viewModel::togglePlay,
        onSeek = viewModel::seekTo,
        onExact = viewModel::requestDraft,
        onAddMedia = {
            viewModel.pause()
            onAddMedia(viewModel.projectId)
        },
        onFixPreset = { ui.project?.presetId?.let(onFixPreset) },
        snackbar = snackbar,
    )
    if (exportInfo) {
        AlertDialog(
            onDismissRequest = { exportInfo = false },
            title = { Text(stringResource(R.string.editor_export_soon_title)) },
            text = { Text(stringResource(R.string.editor_export_soon_body)) },
            confirmButton = { TextButton(onClick = { exportInfo = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (notSaved) {
        AlertDialog(
            onDismissRequest = { notSaved = false },
            title = { Text(stringResource(R.string.copy_no_space_title)) },
            text = { Text(stringResource(R.string.editor_not_saved)) },
            confirmButton = { TextButton(onClick = { notSaved = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    when (val d = draft) {
        is DraftState.Preparing -> {
            WorkDialog(stringResource(R.string.editor_exact_preparing), d.percent / 100f, viewModel::cancelDraft)
        }

        is DraftState.Ready -> {
            PlayFileDialog(d.file.toUri(), stringResource(R.string.editor_exact), audioOnly = false, onClose = viewModel::closeDraft)
        }

        DraftState.Failed -> {
            AlertDialog(
                onDismissRequest = viewModel::closeDraft,
                text = { Text(stringResource(R.string.editor_exact_failed)) },
                confirmButton = { TextButton(onClick = viewModel::closeDraft) { Text(stringResource(R.string.action_ok)) } },
            )
        }

        DraftState.Idle -> {
            Unit
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** The video could not be opened (gone, damaged, or from a newer app): say so, with a way back. */
@Composable
private fun CannotOpen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.editor_cannot_open_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.editor_cannot_open_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_back)) }
        }
    }
}
