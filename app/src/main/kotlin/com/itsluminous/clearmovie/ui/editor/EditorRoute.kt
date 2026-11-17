package com.itsluminous.clearmovie.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.Side
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.ui.components.PlayFileDialog
import com.itsluminous.clearmovie.ui.components.WorkDialog
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.itemName
import com.itsluminous.clearmovie.ui.export.ExportBlock
import com.itsluminous.clearmovie.ui.export.ExportFlow
import com.itsluminous.clearmovie.ui.media.MediaIntakeDialogs
import com.itsluminous.clearmovie.ui.media.rememberMediaAccessController
import com.itsluminous.clearmovie.ui.preset.presetName

/**
 * The editor wired to its view model, the layered preview and the gallery:
 * "+" and Add open the system photo picker for several items, Replace for
 * one (a video only for the intro, a sound file for a spoken ending), and
 * what comes back is taken in by the view model with the copy questions
 * shown here. The picker's answer arrives even after a rotation, and is
 * taken in once.
 */
@OptIn(UnstableApi::class)
@Composable
fun EditorRoute(
    onClosed: () -> Unit,
    onFixPreset: (PresetId) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val preview by viewModel.engine.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val intake by viewModel.intake.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val screen = rememberEditorScreenState()
    var exportInfo by rememberSaveable { mutableStateOf(false) }
    var notSaved by rememberSaveable { mutableStateOf(false) }
    var outOfSpace by rememberSaveable { mutableStateOf(false) }
    var playingPart by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the picker's answer goes; saved, as the answer can arrive after a rotation.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val access = rememberMediaAccessController(askedBefore = false, onAsked = {})

    fun taken(uris: List<Uri>) {
        val purpose = pending?.let(IntakePurpose::decode)
        pending = null
        if (purpose != null) viewModel.takeIn(purpose, uris, access.state)
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { taken(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { taken(listOfNotNull(it)) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { taken(listOfNotNull(it)) }

    fun pick(
        purpose: IntakePurpose,
        launch: () -> Unit,
    ) {
        viewModel.pause()
        pending = purpose.encode()
        if (runCatching(launch).isFailure) {
            pending = null
            screen.panel = null
        }
    }

    // Music, voice and overlay videos come from Files: several at once, as photos and videos do from the gallery.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { taken(it) }

    /** Opens what a row of [type] takes things from: the gallery, or Files for sounds and overlay videos. */
    fun pickFor(
        type: TrackType?,
        purpose: IntakePurpose,
    ) = pick(purpose) {
        when (type) {
            TrackType.AUDIO -> pickFiles.launch(arrayOf("audio/*"))
            TrackType.OVERLAY -> pickFiles.launch(arrayOf("video/*"))
            else -> pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                EditorEvent.Closed -> {
                    onClosed()
                }

                EditorEvent.NotSaved -> {
                    notSaved = true
                }

                is EditorEvent.Inserted -> {
                    screen.selectedRow = null
                    screen.selected = it.first
                    screen.panel = null
                }

                is EditorEvent.Skipped -> {
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.pick_skipped, it.count, it.count))
                }

                is EditorEvent.WrongFile -> {
                    snackbar.showSnackbar(
                        resources.getString(
                            when (it.wanted) {
                                WantedFile.PHOTO_OR_VIDEO -> R.string.editor_wrong_photo_video
                                WantedFile.VIDEO -> R.string.editor_wrong_video
                                WantedFile.SOUND -> R.string.editor_wrong_sound
                                WantedFile.MUSIC -> R.string.editor_wrong_music
                                WantedFile.OVERLAY -> R.string.editor_wrong_overlay
                            },
                        ),
                    )
                }

                EditorEvent.OutOfSpace -> {
                    outOfSpace = true
                }

                EditorEvent.CantDoThat -> {
                    snackbar.showSnackbar(resources.getString(R.string.editor_cant_do_that))
                }
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
    BackHandler(enabled = screen.panel == null, onBack = viewModel::close)
    BackHandler(enabled = screen.panel != null) { screen.panel = null }

    if (ui.failed) {
        CannotOpen(onBack = onClosed)
        return
    }
    val title = ui.project?.name ?: ui.preset?.let { presetName(it) } ?: stringResource(R.string.editor_title_new)
    val actions =
        remember(viewModel) {
            object : EditorActions {
                override fun back() = viewModel.close()

                override fun export() {
                    viewModel.pause()
                    exportInfo = true
                }

                override fun togglePlay() = viewModel.togglePlay()

                override fun pause() = viewModel.pause()

                override fun exact() = viewModel.requestDraft()

                override fun fixPreset() {
                    viewModel.ui.value.project
                        ?.presetId
                        ?.let(onFixPreset)
                }

                override fun edit(change: Project.() -> Project): Boolean = viewModel.edit(change = change)

                override fun editMerged(
                    mergeKey: String,
                    change: Project.() -> Project,
                ): Boolean = viewModel.edit(mergeKey, change)

                override fun endMerge() = viewModel.endMerge()

                override fun revertMerge(mergeKey: String): Boolean = viewModel.revertMerge(mergeKey)

                override fun undo() = viewModel.undo()

                override fun redo() = viewModel.redo()

                override fun seek(ms: Long) = viewModel.seekTo(ms * 1000)

                override fun previewFrom(ms: Long) = viewModel.previewFrom(ms * 1000)

                override fun pickForInsert(
                    anchor: ClipId,
                    side: InsertSide,
                ) = pickFor(
                    viewModel.ui.value.project
                        ?.trackOf(anchor)
                        ?.type,
                    IntakePurpose.Insert(anchor, if (side == InsertSide.BEFORE) Side.BEFORE else Side.AFTER),
                )

                override fun pickForRow(
                    trackId: TrackId,
                    atMs: Long,
                ) = pickFor(
                    viewModel.ui.value.project
                        ?.track(trackId)
                        ?.type,
                    IntakePurpose.AddToRow(trackId, atMs),
                )

                override fun pickForReplace(clipId: ClipId) {
                    val clip =
                        viewModel.ui.value.project
                            ?.mainClips
                            ?.firstOrNull { it.id == clipId } ?: return
                    when {
                        clip is IntroClip || (clip is EndingClip && clip.kind == EndingKind.VIDEO) -> {
                            pick(IntakePurpose.ReplacePart(clipId)) {
                                pickOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }
                        }

                        clip is EndingClip -> {
                            pick(IntakePurpose.ReplacePart(clipId)) { pickSound.launch(arrayOf("audio/*")) }
                        }

                        else -> {
                            pick(IntakePurpose.Replace(clipId)) {
                                pickOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        }
                    }
                }

                override fun pickToAdd() =
                    pick(IntakePurpose.Add) {
                        pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }

                override fun insertCard(
                    anchor: ClipId,
                    side: InsertSide,
                    text: String?,
                    color: ArgbColor,
                ) = viewModel.insertCard(anchor, if (side == InsertSide.BEFORE) Side.BEFORE else Side.AFTER, text, color)

                override fun playPart(clipId: ClipId) {
                    viewModel.pause()
                    playingPart = clipId.value
                }
            }
        }
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
        thumbnail = viewModel::stripFrame,
        actions = actions,
        state = screen,
        snackbar = snackbar,
    )
    MediaIntakeDialogs(
        state = intake,
        onCopyAnyway = viewModel.intake::copyAnyway,
        onChooseFewer = viewModel.intake::chooseFewer,
        // Allow all photos: Android's own question; the items are then chosen again with full access.
        onAllowAll = {
            viewModel.intake.chooseFewer()
            access.request()
        },
        onCancel = viewModel.intake::cancel,
    )
    playingPart?.let { id ->
        val clipId = runCatching { ClipId(id) }.getOrNull()
        val uri = clipId?.let(viewModel::playable)
        val clip = clipId?.let { c -> ui.project?.mainClips?.firstOrNull { it.id == c } }
        if (uri == null || clip == null) {
            playingPart = null
        } else {
            PlayFileDialog(uri, stringResource(itemName(clip)), audioOnly = viewModel.isSoundPart(clipId), onClose = { playingPart = null })
        }
    }
    if (exportInfo) {
        ExportFlow(title = title, blocked = exportBlock(ui), onDismiss = { exportInfo = false })
    }
    if (notSaved) {
        AlertDialog(
            onDismissRequest = { notSaved = false },
            title = { Text(stringResource(R.string.copy_no_space_title)) },
            text = { Text(stringResource(R.string.editor_not_saved)) },
            confirmButton = { TextButton(onClick = { notSaved = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (outOfSpace) {
        AlertDialog(
            onDismissRequest = { outOfSpace = false },
            title = { Text(stringResource(R.string.copy_no_space_title)) },
            text = { Text(stringResource(R.string.pick_out_of_space)) },
            confirmButton = { TextButton(onClick = { outOfSpace = false }) { Text(stringResource(R.string.action_ok)) } },
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

/** What the editor already knows stops an export: nothing to save, a missing preset file, a missing photo or video. */
internal fun exportBlock(ui: EditorUi): ExportBlock? =
    when {
        !ui.hasContent -> ExportBlock.NOTHING
        ui.presetNeedsRepair -> ExportBlock.PRESET_BROKEN
        ui.missing.isNotEmpty() -> ExportBlock.MISSING_MEDIA
        else -> null
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
