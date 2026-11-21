package com.itsluminous.clearmovie.ui.preset

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EffectDefaults
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectCodec
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.refitBox
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.editor.AnimationChoices
import com.itsluminous.clearmovie.ui.editor.CaptionBoxDrag
import com.itsluminous.clearmovie.ui.editor.CaptionImage
import com.itsluminous.clearmovie.ui.editor.CaptionPlace
import com.itsluminous.clearmovie.ui.editor.CaptionPlaces
import com.itsluminous.clearmovie.ui.editor.CaptionTab
import com.itsluminous.clearmovie.ui.editor.CaptionTabBar
import com.itsluminous.clearmovie.ui.editor.ColourTab
import com.itsluminous.clearmovie.ui.editor.FontTab
import com.itsluminous.clearmovie.ui.editor.LocalCanvasShape
import com.itsluminous.clearmovie.ui.editor.PanelTitle
import com.itsluminous.clearmovie.ui.editor.PlaceTab
import com.itsluminous.clearmovie.ui.editor.SizeTab
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbSource
import com.itsluminous.clearmovie.ui.editor.usableCaptionText
import com.itsluminous.clearmovie.ui.layout.AdaptiveScreen
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors
import java.util.Locale

const val PRESET_EDIT_LIST_TAG = "preset_edit_list"
const val PRESET_NAME_TAG = "preset_name"
const val PRESET_SAVE_TAG = "preset_save"
const val PRESET_DELETE_TAG = "preset_delete"
const val PRESET_CAPTION_CHANGE_TAG = "preset_caption_change"
const val PRESET_FILES_TAG = "preset_files"
const val PRESET_WORDS_TAG = "preset_words"
const val PRESET_CAPTION_PREVIEW_TAG = "preset_caption_preview"
const val PRESET_CONFIRM_TAG = "preset_confirm"

/** The places the preset's caption editor offers besides dragging: the preset's own place is what is being set. */
private val PRESET_PLACES = listOf(CaptionPlace.TOP, CaptionPlace.MIDDLE, CaptionPlace.BOTTOM)

/** The preset editor wired to its view model. [onFiles] opens the preset's setup screen. */
@Composable
fun PresetEditRoute(
    onFiles: (PresetId) -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: PresetEditViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                PresetEditEvent.Done -> onBack()
                is PresetEditEvent.Deleted -> onDeleted()
            }
        }
    }
    LaunchedEffect(ui.missing) { if (ui.missing) onBack() }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    PresetEditScreen(
        ui = ui,
        onName = viewModel::setName,
        onStyle = viewModel::setStyle,
        onWords = viewModel::setWords,
        onShape = viewModel::setShape,
        onRevertCaption = viewModel::revertStyle,
        onCaptionAnimation = viewModel::setCaptionAnimation,
        onFiles = { onFiles(viewModel.presetId) },
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onBack = onBack,
        onDismissMessage = viewModel::dismissMessage,
    )
}

/** The words a new caption of [preset] starts with, in the app's language, as the editor shows them. */
@Composable
private fun shownWords(preset: Preset): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    return preset.defaultText?.let { PresetNames.pick(it, locale) }.orEmpty()
}

/**
 * The preset editor (cm-preset-manager): the name, the captions (a picture
 * of the frame with a new caption on it, and Change caption style), the
 * files (opening setup), and Save. Every preset has Delete in the top bar
 * (its videos keep working). Change caption
 * style turns the screen into the caption editor's tabs over the same
 * picture, with Cancel and Done. In two panes the picture sits on the left.
 * Leaving with unsaved changes asks first. Stateless apart from which dialog
 * or editor is open (kept across rotation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    ui: PresetEditUi,
    onName: (String) -> Unit,
    onStyle: (CaptionStyle) -> Unit,
    onWords: (String) -> Unit,
    onRevertCaption: (CaptionStyle?, String?, EffectDefaults?) -> Unit,
    onFiles: () -> Unit,
    onSave: (shownName: String, shownWords: String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
    onShape: (CanvasShape) -> Unit = {},
    onCaptionAnimation: (AnimKind?) -> Unit = {},
) {
    // Captions are drawn for the preset's own shape (cm-aspect-ratio).
    CompositionLocalProvider(LocalCanvasShape provides ui.shape) {
        PresetEditContent(
            ui,
            onName,
            onStyle,
            onWords,
            onRevertCaption,
            onFiles,
            onSave,
            onDelete,
            onBack,
            onDismissMessage,
            onShape,
            onCaptionAnimation,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditContent(
    ui: PresetEditUi,
    onName: (String) -> Unit,
    onStyle: (CaptionStyle) -> Unit,
    onWords: (String) -> Unit,
    onRevertCaption: (CaptionStyle?, String?, EffectDefaults?) -> Unit,
    onFiles: () -> Unit,
    onSave: (shownName: String, shownWords: String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
    onShape: (CanvasShape) -> Unit,
    onCaptionAnimation: (AnimKind?) -> Unit,
) {
    val definition = ui.definition
    val preset = ui.preset
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var captionOpen by rememberSaveable { mutableStateOf(false) }
    // The draft as the caption editor found it, so its Cancel puts it back.
    var openedStyle by rememberSaveable { mutableStateOf<String?>(null) }
    var openedWords by rememberSaveable { mutableStateOf<String?>(null) }
    var openedEffects by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(CaptionTab.WORDS.name) }
    val shownName = preset?.let { presetName(it) }.orEmpty()
    val shownWords = preset?.let { shownWords(it) }.orEmpty()
    val name = ui.draftName ?: shownName
    val words = ui.draftWords ?: shownWords
    val dirty = preset != null && PresetEditViewModel.changedPreset(preset, ui, shownName, shownWords) != null
    val nameOk = PresetEditViewModel.isUsableName(name)

    fun closeCaption(cancel: Boolean) {
        if (cancel) {
            onRevertCaption(
                openedStyle?.let {
                    runCatching { ProjectCodec.json.decodeFromString(CaptionStyle.serializer(), it) }.getOrNull()
                },
                openedWords,
                openedEffects?.let {
                    runCatching { ProjectCodec.json.decodeFromString(EffectDefaults.serializer(), it) }.getOrNull()
                },
            )
        }
        captionOpen = false
    }
    BackHandler(enabled = captionOpen) { closeCaption(cancel = true) }
    BackHandler(enabled = !captionOpen && dirty) { dialog = DIALOG_DISCARD }

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(ui.message) {
        val text =
            when (ui.message) {
                PresetEditMessage.NOT_SAVED -> resources.getString(R.string.preset_not_saved)
                null -> return@LaunchedEffect
            }
        snackbar.showSnackbar(text)
        onDismissMessage()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preset_edit), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (captionOpen) {
                            closeCaption(cancel = true)
                        } else if (dirty) {
                            dialog = DIALOG_DISCARD
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (definition != null && !captionOpen) {
                        IconButton(
                            onClick = { dialog = DIALOG_DELETE },
                            enabled = !ui.busy,
                            modifier = Modifier.testTag(PRESET_DELETE_TAG),
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.preset_delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        AdaptiveScreen(Modifier.fillMaxSize().padding(padding)) { window ->
            if (preset == null || ui.status == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@AdaptiveScreen
            }
            val style = ui.style ?: preset.caption
            val frameWindow = preset.frame?.contentRect ?: NormRect.FULL
            val aspect = ui.shape.aspect.toFloat()
            // The preset's own caption place, on the shape being chosen.
            val usualBox = refitBox(preset.caption.box, preset.shape, ui.shape)
            val preview: @Composable (editing: Boolean, modifier: Modifier) -> Unit = { editing, modifier ->
                PresetCaptionPreview(
                    modifier = modifier,
                    still = ui.frameStill,
                    style = style,
                    words = words,
                    usual = usualBox,
                    safe = CaptionPlaces.safeArea(frameWindow, usualBox, ui.shape.aspect),
                    editing = editing,
                    presetKey = preset.id.value,
                    onBox = { onStyle(style.copy(box = it)) },
                )
            }
            if (captionOpen) {
                val editor: @Composable ColumnScope.() -> Unit = {
                    CaptionStyleEditor(
                        tab = CaptionTab.PRESET.firstOrNull { it.name == tab } ?: CaptionTab.WORDS,
                        onTab = { tab = it.name },
                        preset = preset,
                        style = style,
                        words = words,
                        frameWindow = frameWindow,
                        onStyle = onStyle,
                        onWords = onWords,
                        animation = ui.captionAnimation,
                        onAnimation = onCaptionAnimation,
                        onCancel = { closeCaption(cancel = true) },
                        onDone = { closeCaption(cancel = false) },
                    )
                }
                if (window.twoPane) {
                    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            preview(true, Modifier.width(minOf(maxWidth, maxHeight * aspect)))
                        }
                        Column(Modifier.weight(1f).fillMaxSize()) { editor() }
                    }
                } else {
                    // The picture takes at most a third of the height (less while the keyboard is up), so the
                    // tabs and the words field always have room, at any text size.
                    val pictureWidth = minOf(maxWidth - 32.dp, maxHeight * PREVIEW_SHARE * aspect)
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        preview(true, Modifier.width(pictureWidth))
                        Spacer(Modifier.height(8.dp))
                        editor()
                    }
                }
                return@AdaptiveScreen
            }
            val nameField: @Composable () -> Unit = {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.preset_name_label)) },
                    singleLine = true,
                    isError = !nameOk,
                    supportingText = if (nameOk) null else ({ Text(stringResource(R.string.preset_name_needed)) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().testTag(PRESET_NAME_TAG),
                )
            }
            val openCaption = {
                openedStyle = ui.draftStyle?.let { ProjectCodec.json.encodeToString(CaptionStyle.serializer(), it) }
                openedWords = ui.draftWords
                openedEffects = ui.draftEffects?.let { ProjectCodec.json.encodeToString(EffectDefaults.serializer(), it) }
                tab = CaptionTab.WORDS.name
                captionOpen = true
            }
            val captions: @Composable () -> Unit = {
                // An upright or square picture is narrower than the card, so it never fills the screen on its own.
                CaptionsCard(preview = { preview(false, Modifier.fillMaxWidth(minOf(1f, aspect))) }, onChange = openCaption)
            }
            val shapeCard: @Composable () -> Unit = {
                // The kept Event frame is made for its frame's shape: no choice is shown.
                if (ui.madeHere) {
                    // Its intro and frame, once chosen, were made for the shape it had.
                    val filesChosen = listOf(PresetSlot.INTRO, PresetSlot.FRAME).any { ui.status.slots[it] is SlotStatus.Ready }
                    PresetShapeCard(
                        ui.shape,
                        enabled = ui.canChangeShape,
                        onShape = onShape,
                        filesNote = filesChosen && ui.shape != preset.shape,
                    )
                }
            }
            val files: @Composable () -> Unit = { FilesCard(ui.status, onFiles) }
            val save: @Composable () -> Unit = {
                Button(
                    onClick = { onSave(shownName, shownWords) },
                    enabled = nameOk && !ui.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(PRESET_SAVE_TAG),
                ) { AutoSizeLabel(stringResource(R.string.preset_save)) }
            }
            if (window.twoPane) {
                // The picture on the left, as large as the height allows; everything else scrolls on the right.
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        preview(false, Modifier.width(minOf(maxWidth, maxHeight * aspect)))
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag(PRESET_EDIT_LIST_TAG),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        nameField()
                        shapeCard()
                        CaptionsCard(preview = null, onChange = openCaption)
                        files()
                        save()
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag(PRESET_EDIT_LIST_TAG),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        nameField()
                        shapeCard()
                        captions()
                        files()
                        save()
                    }
                }
            }
        }
    }
    when (dialog) {
        DIALOG_DELETE -> {
            ConfirmDialog(
                title = stringResource(R.string.preset_delete_title),
                body = stringResource(R.string.preset_delete_body, shownName),
                confirm = stringResource(R.string.action_delete),
                destructive = true,
                onConfirm = {
                    dialog = null
                    onDelete()
                },
                onDismiss = { dialog = null },
            )
        }

        DIALOG_DISCARD -> {
            ConfirmDialog(
                title = stringResource(R.string.preset_discard_title),
                body = stringResource(R.string.preset_discard_body),
                confirm = stringResource(R.string.preset_discard),
                dismiss = stringResource(R.string.preset_keep_editing),
                destructive = true,
                onConfirm = {
                    dialog = null
                    onBack()
                },
                onDismiss = { dialog = null },
            )
        }
    }
}

private const val DIALOG_DELETE = "delete"
private const val DIALOG_DISCARD = "discard"

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag(PRESET_CONFIRM_TAG)) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(dismiss) }
        },
    )
}

@Composable
private fun CaptionsCard(
    preview: (@Composable () -> Unit)?,
    onChange: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.preset_caption_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.preset_caption_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview?.invoke()
            OutlinedButton(
                onClick = onChange,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag(PRESET_CAPTION_CHANGE_TAG),
            ) {
                Icon(painterResource(R.drawable.ic_format_color_text), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                AutoSizeLabel(stringResource(R.string.preset_caption_change))
            }
        }
    }
}

/** The preset's parts with their state, and one button to choose or change their files on the setup screen. */
@Composable
private fun FilesCard(
    status: PresetStatus,
    onFiles: () -> Unit,
) {
    val needed = PresetSlot.entries.mapNotNull { slot -> status.slots[slot]?.takeUnless { it is SlotStatus.NotNeeded }?.let { slot to it } }
    if (needed.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.preset_files_title), style = MaterialTheme.typography.titleMedium)
            for ((slot, s) in needed) {
                val broken = s is SlotStatus.Missing || s is SlotStatus.Changed
                val (icon, tint) =
                    when {
                        broken -> R.drawable.ic_warning to MaterialTheme.colorScheme.error
                        s is SlotStatus.Ready -> R.drawable.ic_check_circle to MaterialTheme.colorScheme.primary
                        else -> R.drawable.ic_radio_unchecked to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                val line =
                    when (s) {
                        is SlotStatus.Missing -> R.string.preset_slot_missing
                        is SlotStatus.Changed -> R.string.preset_slot_changed
                        is SlotStatus.Ready -> R.string.preset_slot_ready
                        else -> R.string.preset_slot_not_set
                    }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(slot.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(line),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (broken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val ready = needed.all { it.second is SlotStatus.Ready }
            val label = stringResource(if (ready) R.string.preset_files_change else R.string.preset_files_choose)
            val content: @Composable () -> Unit = {
                Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                AutoSizeLabel(label)
            }
            val modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag(PRESET_FILES_TAG)
            if (ready) {
                OutlinedButton(onClick = onFiles, modifier = modifier) { content() }
            } else {
                Button(onClick = onFiles, modifier = modifier) { content() }
            }
        }
    }
}

/**
 * The 16:9 picture of a video from this preset: the frame (its green window
 * cut out) and a new caption on it, drawn as the export draws it. While
 * [editing], the caption's box can be moved and resized.
 */
@Composable
private fun PresetCaptionPreview(
    modifier: Modifier,
    still: Bitmap?,
    style: CaptionStyle,
    words: String,
    usual: NormRect,
    safe: NormRect,
    editing: Boolean,
    presetKey: String,
    onBox: (NormRect) -> Unit,
) {
    val media = LocalMediaColors.current
    val sample = stringResource(R.string.caption_font_sample)
    BoxWithConstraints(
        modifier
            .aspectRatio(LocalCanvasShape.current.aspect.toFloat())
            .clip(RoundedCornerShape(8.dp))
            .background(media.previewBackground)
            .testTag(PRESET_CAPTION_PREVIEW_TAG),
    ) {
        if (still != null) {
            Image(
                still.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val box = style.box
        val shown = words.isNotBlank()
        Box(
            Modifier
                .offset(maxWidth * box.x.toFloat(), maxHeight * box.y.toFloat())
                .size(maxWidth * box.w.toFloat(), maxHeight * box.h.toFloat())
                .alpha(if (shown) 1f else SAMPLE_ALPHA),
        ) { CaptionImage(if (shown) words else sample, style) }
        if (editing) {
            CaptionBoxDrag(
                style = style,
                usual = usual,
                safe = safe,
                showSample = false,
                dragKey = presetKey,
                text = if (shown) words else sample,
                onBox = onBox,
            )
        }
    }
}

private const val SAMPLE_ALPHA = 0.6f

/** A caption to show the start animations on (only its kind matters: tiles draw it as a plain block). */
private val SAMPLE_CAPTION = TextClip(ClipId("preset-caption-sample"), 0, Limits.DEFAULT_TEXT_MS, "")

/** Animation tiles for a caption need no thumbnails. */
private val NO_THUMBNAILS: ThumbSource = { _, _ -> null }

/** The share of the screen's height the picture takes while the caption style is edited on a phone held upright. */
private const val PREVIEW_SHARE = 0.3f

/**
 * The caption editor's tabs for a preset: the words a new caption starts with, its place, size, font and
 * colour, and the animation it comes in with ([animation], null for none).
 */
@Composable
private fun ColumnScope.CaptionStyleEditor(
    tab: CaptionTab,
    onTab: (CaptionTab) -> Unit,
    preset: Preset,
    style: CaptionStyle,
    words: String,
    frameWindow: NormRect,
    onStyle: (CaptionStyle) -> Unit,
    onWords: (String) -> Unit,
    animation: AnimKind?,
    onAnimation: (AnimKind?) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    PanelTitle(stringResource(R.string.preset_caption_change), onDone = onDone, onCancel = onCancel)
    CaptionTabBar(tab, CaptionTab.PRESET, onTab)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        when (tab) {
            CaptionTab.WORDS -> {
                OutlinedTextField(
                    value = words,
                    onValueChange = { onWords(usableCaptionText(it)) },
                    label = { Text(stringResource(R.string.caption_text_hint)) },
                    placeholder = { Text(stringResource(R.string.preset_caption_no_words)) },
                    supportingText = { Text(stringResource(R.string.preset_caption_words_label)) },
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().testTag(PRESET_WORDS_TAG),
                )
            }

            CaptionTab.PLACE -> {
                PlaceTab(
                    style,
                    refitBox(preset.caption.box, preset.shape, LocalCanvasShape.current),
                    CaptionPlaces.safeArea(
                        frameWindow,
                        refitBox(preset.caption.box, preset.shape, LocalCanvasShape.current),
                        LocalCanvasShape.current.aspect,
                    ),
                    onStyle,
                    PRESET_PLACES,
                )
            }

            CaptionTab.SIZE -> {
                // 100% is the app's usual caption size, so a size chosen here reads the same when opened again.
                SizeTab(preset.id.value, words, style, CaptionStyle.STANDARD, onStyle)
            }

            CaptionTab.FONT -> {
                FontTab(style, onStyle)
            }

            CaptionTab.COLOUR -> {
                ColourTab(style, onStyle)
            }

            CaptionTab.ANIMATION -> {
                Text(
                    stringResource(R.string.preset_caption_animation_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // The editor's own Start choices, on a caption: what a new caption of this preset gets.
                AnimationChoices(SAMPLE_CAPTION, AnimSlot.START, animation, Anim.DEFAULT_MS, NO_THUMBNAILS, onAnimation)
            }
        }
    }
}
