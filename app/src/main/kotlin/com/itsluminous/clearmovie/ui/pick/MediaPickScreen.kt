package com.itsluminous.clearmovie.ui.pick

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.media.MediaAccessController
import com.itsluminous.clearmovie.ui.media.MediaAccessExplainer
import com.itsluminous.clearmovie.ui.media.MediaIntakeDialogs
import com.itsluminous.clearmovie.ui.media.rememberMediaAccessController

/** Test tag of the "Adding your photos and videos" state. */
const val PICK_ADDING_TAG = "pick_adding"

/**
 * The pick step wired to Android: the explainer and permission request,
 * the system Photo Picker (opened once per visit: whether it was opened is
 * saved, so a rotation or a process death never opens it again, and its
 * answer still arrives), and the intake.
 */
@Composable
fun MediaPickRoute(
    onAdded: (ProjectId, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: MediaPickViewModel = hiltViewModel(),
) {
    val asked by viewModel.askedBefore.collectAsStateWithLifecycle()
    val askedBefore = asked ?: return
    val access = rememberMediaAccessController(askedBefore = askedBefore, onAsked = viewModel::markAsked)
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    val intake by viewModel.intake.state.collectAsStateWithLifecycle()
    val step = rememberPickStepState()
    var retryAfterAccess by rememberSaveable { mutableStateOf(false) }
    var outOfSpace by rememberSaveable { mutableStateOf(false) }
    var notSaved by rememberSaveable { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            viewModel.picked(uris, access.state)
        }
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is PickEvent.Added -> {
                    onAdded(it.projectId, it.skipped)
                }

                PickEvent.Back -> {
                    onBack()
                }

                PickEvent.AskForAccess -> {
                    retryAfterAccess = true
                    access.request()
                }

                PickEvent.PickAgain -> {
                    step.launched = false
                }

                PickEvent.OutOfSpace -> {
                    outOfSpace = true
                }

                PickEvent.NotSaved -> {
                    notSaved = true
                }
            }
        }
    }
    // A process death during the intake: the picked links are saved; take them in again.
    LaunchedEffect(viewModel) {
        if (step.launched && viewModel.pending != null && !viewModel.adding.value) viewModel.start(access.state)
    }
    LaunchedEffect(access.state, retryAfterAccess) {
        if (retryAfterAccess && access.state != MediaAccessState.NotAsked) {
            retryAfterAccess = false
            viewModel.start(access.state)
        }
    }
    BackHandler(onBack = viewModel::leave)
    PickStep(
        state = step,
        access = access,
        adding = adding,
        onLaunchPicker = {
            runCatching {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            }.isSuccess
        },
        onBack = viewModel::leave,
    )
    MediaIntakeDialogs(
        state = intake,
        onCopyAnyway = viewModel.intake::copyAnyway,
        onChooseFewer = viewModel::chooseFewer,
        onAllowAll = viewModel.intake::allowAll,
        onCancel = viewModel.intake::cancel,
    )
    if (notSaved) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.copy_no_space_title)) },
            text = { Text(stringResource(R.string.editor_not_saved)) },
            confirmButton = {
                TextButton(onClick = {
                    notSaved = false
                    viewModel.leave()
                }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    if (outOfSpace) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.copy_no_space_title)) },
            text = { Text(stringResource(R.string.pick_out_of_space)) },
            confirmButton = {
                TextButton(onClick = {
                    outOfSpace = false
                    viewModel.leave()
                }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

/** Whether the picker was opened on this visit, and whether the user said Not now; saved across rotation and process death. */
@Stable
class PickStepState(
    launched: Boolean,
    notNow: Boolean,
    noPicker: Boolean = false,
) {
    var launched by mutableStateOf(launched)
    var notNow by mutableStateOf(notNow)

    /** The phone has no photo picker to open (a phone without the usual apps). */
    var noPicker by mutableStateOf(noPicker)

    companion object {
        val Saver: Saver<PickStepState, List<Boolean>> =
            Saver(save = { listOf(it.launched, it.notNow, it.noPicker) }, restore = { PickStepState(it[0], it[1], it[2]) })
    }
}

@Composable
fun rememberPickStepState(): PickStepState =
    rememberSaveable(saver = PickStepState.Saver) {
        PickStepState(launched = false, notNow = false)
    }

/**
 * The pick step: the explainer before Android's own question (and after a
 * refusal: Try again, or Open Settings when Android won't ask again), then
 * the picker, opened by [onLaunchPicker] exactly once per visit (Not now
 * opens it without access: the items are then copied or kept by their
 * grant), then "Adding your photos and videos" while they are taken in.
 */
@Composable
fun PickStep(
    state: PickStepState,
    access: MediaAccessController,
    adding: Boolean,
    onLaunchPicker: () -> Boolean,
    onBack: () -> Unit = {},
) {
    val ready = access.state == MediaAccessState.Full || access.state == MediaAccessState.Partial || state.notNow
    LaunchedEffect(ready, state.launched) {
        if (ready && !state.launched) {
            state.launched = true
            if (!onLaunchPicker()) state.noPicker = true
        }
    }
    if (state.noPicker) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.pick_no_picker), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_back)) }
            }
        }
        return
    }
    if (!state.launched && !ready) {
        MediaAccessExplainer(
            state = access.state,
            onContinue = access::request,
            onNotNow = { state.notNow = true },
            onOpenSettings = access::openSettings,
        )
        return
    }
    Surface(Modifier.fillMaxSize()) {
        if (adding) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp)
                    .testTag(PICK_ADDING_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pick_adding), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            }
        }
    }
}
