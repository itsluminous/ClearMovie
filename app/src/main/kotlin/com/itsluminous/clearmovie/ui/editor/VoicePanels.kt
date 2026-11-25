package com.itsluminous.clearmovie.ui.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.WordsSource
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.work.VoicePackNotifications

// The voice choice (cm-voice-choice): the dropdowns in a voice row's sheet, the voice item's panel, typed words, and
// the voice pack dialog.

const val VOICE_CHOICE_TAG = "voice_choice"
const val VOICE_LANGUAGE_TAG = "voice_language"
const val VOICE_MAKE_TAG = "voice_make"
const val VOICE_TYPED_TAG = "voice_typed_text"
const val VOICE_PACK_DIALOG_TAG = "voice_pack_dialog"
const val VOICE_PACK_DOWNLOAD_TAG = "voice_pack_download"
const val VOICE_PACK_ADD_TAG = "voice_pack_add"
const val VOICE_PACK_PROGRESS_TAG = "voice_pack_progress"
const val VOICE_PACK_HIDE_TAG = "voice_pack_hide"
const val VOICE_PACK_CANCEL_TAG = "voice_pack_cancel"
const val VOICE_PACK_BROWSER_TAG = "voice_pack_browser"
const val VOICE_PACK_WARNING_TAG = "voice_pack_warning"
const val TYPE_WORDS_OPTION = "type_words"

/** A voice's name, as the menu shows it. */
fun VoiceChoice.labelRes(): Int =
    when (this) {
        VoiceChoice.NONE -> R.string.voice_none
        VoiceChoice.BRITISH_MALE -> R.string.voice_british_male
        VoiceChoice.BRITISH_FEMALE -> R.string.voice_british_female
        VoiceChoice.INDIAN_MALE -> R.string.voice_indian_male
        VoiceChoice.INDIAN_FEMALE -> R.string.voice_indian_female
        VoiceChoice.AMERICAN_MALE -> R.string.voice_american_male
        VoiceChoice.AMERICAN_FEMALE -> R.string.voice_american_female
    }

private fun SpokenLanguage.labelRes(): Int =
    when (this) {
        SpokenLanguage.ENGLISH -> R.string.voice_language_english
        SpokenLanguage.HINDI -> R.string.voice_language_hindi
    }

/** A Material exposed dropdown: a read-only field that opens a menu of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(
    label: String,
    value: T,
    options: List<T>,
    name: @Composable (T) -> String,
    tag: String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open && enabled, onExpandedChange = { if (enabled) open = it }, modifier = modifier) {
        OutlinedTextField(
            value = name(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingText = supporting?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).testTag(tag),
        )
        ExposedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(name(o), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onPick(o)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = Modifier.testTag("${tag}_${o.toString().lowercase()}"),
                )
            }
        }
    }
}

/**
 * One voice dropdown with every voice, No change first, under [label]: the
 * Record voice sheet's own field, for a preset's default voice
 * (cm-preset-voice-default). Off, with the sheet's note, where [available] is false.
 */
@Composable
internal fun VoiceChoiceField(
    label: String,
    voice: VoiceChoice,
    available: Boolean,
    tag: String,
    onVoice: (VoiceChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Dropdown(
        label = label,
        value = voice,
        options = VoiceChoice.MENU,
        name = { stringResource(it.labelRes()) },
        tag = tag,
        onPick = onVoice,
        modifier = modifier,
        enabled = available,
        supporting = if (available) null else stringResource(R.string.voice_not_on_phone),
    )
}

/** "Voice in the video" and "Spoken in", side by side when there is room. */
@Composable
internal fun VoiceFields(
    voice: VoiceChoice,
    language: SpokenLanguage,
    available: Boolean,
    onVoice: (VoiceChoice) -> Unit,
    onLanguage: (SpokenLanguage) -> Unit,
    allowNone: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(
            label = stringResource(R.string.voice_in_video),
            value = voice,
            options = VoiceChoice.forLanguage(language).filter { allowNone || it != VoiceChoice.NONE },
            name = { stringResource(it.labelRes()) },
            tag = VOICE_CHOICE_TAG,
            onPick = onVoice,
            enabled = available,
            supporting = if (available) null else stringResource(R.string.voice_not_on_phone),
        )
        if (available && voice != VoiceChoice.NONE) {
            Dropdown(
                label = stringResource(R.string.voice_spoken_in),
                value = language,
                options = SpokenLanguage.entries,
                name = { stringResource(it.labelRes()) },
                tag = VOICE_LANGUAGE_TAG,
                onPick = onLanguage,
            )
        }
    }
}

/**
 * A voice row's sheet, above Record voice: the voice the next recording or
 * typed words are said in. With a voice other than "No change" the sheet
 * also offers Type the words.
 */
@Composable
internal fun VoiceSheetFields(voices: VoiceChoiceController) {
    val voice by voices.choice.collectAsState()
    val language by voices.language.collectAsState()
    val available by voices.availability.collectAsState()
    VoiceFields(voice, language, available, voices::choose, voices::speakIn)
}

/** The panel of a voice item: its voice, the words it says (to check or type), and Make voice. */
@Composable
internal fun VoiceItemPanel(
    clip: AudioClip,
    voices: VoiceChoiceController,
    onDone: () -> Unit,
) {
    val over = clip.voiceOver
    val typed = over?.source == WordsSource.TYPED
    var language by rememberSaveable(clip.id.value) { mutableStateOf(over?.language ?: voices.language.value) }
    // The item's voice. Words waiting for a voice offer the one they were found for, a new item the sheet's;
    // an item set to "My recording" shows that.
    var voice by rememberSaveable(clip.id.value) {
        mutableStateOf(
            when {
                over == null -> voices.choice.value.forLanguage(language)
                over.voice != VoiceChoice.NONE -> over.voice
                else -> voices.pendingVoice(clip.id)?.forLanguage(language) ?: VoiceChoice.NONE
            },
        )
    }
    val available by voices.availability.collectAsState()
    val phrases =
        remember(clip.id.value, over?.phrases) { mutableStateListOf<String>().apply { addAll(over?.phrases.orEmpty().map { it.text }) } }
    val work by voices.work.collectAsState()
    val busy = work != null
    // Once this item's voice is made, the panel closes and the timeline shows again; if it could not be made, the
    // panel stays with its words (a note says why).
    var voicing by rememberSaveable(clip.id.value) { mutableStateOf(false) }
    val played = clip.voiceOver?.generated ?: clip.media
    var playedBefore by rememberSaveable(clip.id.value) { mutableStateOf<String?>(null) }
    LaunchedEffect(work, played) {
        val mine = (work as? VoiceWork.Voicing)?.clipId == clip.id
        if (mine && !voicing) {
            voicing = true
            playedBefore = played.toString()
        } else if (voicing && work == null) {
            voicing = false
            if (played.toString() != playedBefore) onDone()
        }
    }
    PanelTitle(stringResource(R.string.voice_panel_title), onDone = null, onCancel = onDone)
    VoiceFields(
        voice,
        language,
        available,
        onVoice = {
            voice = it
            // The words are here: only the voices and engine are needed.
            if (it != VoiceChoice.NONE) voices.askForPack(if (phrases.isEmpty()) language else null)
        },
        onLanguage = {
            language = it
            voice = voice.forLanguage(it)
        },
        allowNone = !typed,
    )
    if (voice != VoiceChoice.NONE || phrases.isNotEmpty()) {
        Text(
            stringResource(R.string.voice_accuracy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        over?.phrases.orEmpty().forEachIndexed { i, p ->
            if (i < phrases.size) {
                OutlinedTextField(
                    value = phrases[i],
                    onValueChange = { phrases[i] = it.take(Limits.MAX_TEXT_LENGTH) },
                    label = { Text(stringResource(R.string.voice_phrase_label, durationText(p.startMs * 1000)), maxLines = 1) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("voice_phrase_$i"),
                )
            }
        }
    }
    WorkLine(work)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!typed) {
            OutlinedButton(
                onClick = { voices.recognize(clip.id, language) },
                enabled = !busy && available,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.voice_find_words), maxLines = 2) }
        }
        Button(
            onClick = {
                val texts = over?.phrases.orEmpty().mapIndexed { i, p -> VoicePhrase(p.startMs, phrases.getOrElse(i) { p.text }) }
                voices.makeVoice(clip.id, texts, voice, language)
                if (voice == VoiceChoice.NONE) onDone()
            },
            enabled = !busy && (voice == VoiceChoice.NONE || phrases.any { it.isNotBlank() }),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(VOICE_MAKE_TAG),
        ) { Text(stringResource(if (voice == VoiceChoice.NONE) R.string.voice_use_recording else R.string.voice_make), maxLines = 2) }
    }
}

/** Type the words: what the voice should say, said as a new item from the sheet's time. */
@Composable
internal fun TypeWordsPanel(
    open: OpenPanel,
    playheadMs: Long,
    voices: VoiceChoiceController,
    onDone: () -> Unit,
) {
    val chosen by voices.choice.collectAsState()
    val chosenLanguage by voices.language.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val work by voices.work.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    val at = open.atMs ?: playheadMs
    // When the typed words went on the timeline, the panel closes.
    LaunchedEffect(work, started) { if (started && work == null) onDone() }
    PanelTitle(stringResource(R.string.voice_type_words), onDone = null, onCancel = onDone)
    VoiceFields(
        chosen.takeIf { it != VoiceChoice.NONE } ?: VoiceChoice.INDIAN_FEMALE,
        chosenLanguage,
        voices.availability.collectAsState().value,
        onVoice = voices::choose,
        onLanguage = voices::speakIn,
        allowNone = false,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(TYPED_MAX) },
        label = { Text(stringResource(R.string.voice_typed_hint), maxLines = 1) },
        supportingText = { Text(stringResource(R.string.voice_typed_from, durationText(at * 1000))) },
        minLines = 3,
        enabled = work == null,
        modifier = Modifier.fillMaxWidth().testTag(VOICE_TYPED_TAG),
    )
    WorkLine(work)
    Button(
        onClick = {
            val v = chosen.takeIf { it != VoiceChoice.NONE && it.speaks(chosenLanguage) } ?: VoiceChoice.INDIAN_FEMALE
            started = voices.makeTyped(open.trackId, at, text, v, chosenLanguage)
        },
        enabled = work == null && text.isNotBlank() && voices.availability.collectAsState().value,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(min = 48.dp)
                .testTag(VOICE_MAKE_TAG),
    ) { Text(stringResource(R.string.voice_make)) }
}

/** What the engine is doing, with its progress. */
@Composable
private fun WorkLine(work: VoiceWork?) {
    if (work == null) return
    val (text, done) =
        when (work) {
            is VoiceWork.Recognizing -> stringResource(R.string.voice_working_words) to null
            is VoiceWork.Voicing -> stringResource(R.string.voice_working_voice) to work.done
            is VoiceWork.AddingPack -> stringResource(R.string.pack_adding) to work.done
        }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (done == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ done }, Modifier.fillMaxWidth())
    }
}

/**
 * The voice pack dialog (cm-voice-pack-download): what it is, how big, and
 * Download, which downloads it in the app; then its progress (Hide keeps it
 * going in the background, Cancel stops it), or why it stopped, with Try
 * again. "Download in the browser instead" keeps the older way: the steps,
 * the files' links, and Add the downloaded files. Its state lives in
 * [voices], so it is as it was after a rotation or a trip to the browser.
 */
@Composable
internal fun VoicePackDialog(voices: VoiceChoiceController) {
    val ask by voices.packAsk.collectAsState()
    val work by voices.work.collectAsState()
    val download by voices.download.collectAsState()
    val context = LocalContext.current
    // Registered whether or not the dialog shows, so files picked just before the app was stopped still arrive.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val resolver = context.contentResolver
            voices.addPackFiles(uris.map { uri -> { resolver.openInputStream(uri) } })
        }
    val current = ask ?: return
    val network = rememberNetwork()
    val downloadBytes = current.files.sumOf { it.bytes }
    val unpacked = current.files.sumOf { it.unpackedBytes }

    fun size(b: Long) = Formatter.formatShortFileSize(context, b).replace(' ', '\u00A0')

    fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser: the steps still name the page, so it can be opened elsewhere.
        }
    }
    val running = download is PackDownloadState.Waiting || download is PackDownloadState.Running
    val failed = download as? PackDownloadState.Failed
    val mode =
        when {
            running -> PackMode.PROGRESS
            current.steps -> PackMode.STEPS
            failed != null -> PackMode.ERROR
            else -> PackMode.CONFIRM
        }
    AlertDialog(
        // Outside the dialog: while it downloads, the dialog hides and the download goes on.
        onDismissRequest = {
            when {
                running -> voices.hidePack()
                work == null -> voices.dismissPack()
            }
        },
        title = {
            Text(
                stringResource(
                    when (mode) {
                        PackMode.CONFIRM -> R.string.pack_title
                        PackMode.PROGRESS -> R.string.pack_downloading_title
                        PackMode.ERROR -> R.string.pack_failed_title
                        PackMode.STEPS -> R.string.pack_steps_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    PackMode.CONFIRM -> {
                        Text(stringResource(R.string.pack_body, size(downloadBytes), size(unpacked)))
                        Text(stringResource(R.string.pack_privacy))
                        when {
                            current.room > 0 -> PackNotice(stringResource(R.string.pack_error_space, size(current.room)), error = true)
                            !network.connected -> PackNotice(stringResource(R.string.pack_offline))
                            network.cellular -> PackNotice(stringResource(R.string.pack_mobile_data, size(downloadBytes)))
                            network.metered -> PackNotice(stringResource(R.string.pack_metered, size(downloadBytes)))
                        }
                        BrowserInstead {
                            open(VoicePackManifest.folderUrl(current.version))
                            voices.useBrowser()
                        }
                    }

                    PackMode.PROGRESS -> {
                        val progress = (download as? PackDownloadState.Running)?.progress
                        val line =
                            when {
                                progress?.checking == true -> {
                                    stringResource(R.string.pack_checking)
                                }

                                progress != null && progress.total > 0 -> {
                                    stringResource(
                                        R.string.pack_progress,
                                        size(progress.done),
                                        size(progress.total),
                                    )
                                }

                                !network.connected -> {
                                    stringResource(R.string.pack_waiting)
                                }

                                else -> {
                                    stringResource(R.string.pack_starting)
                                }
                            }
                        if (progress != null && progress.total > 0 && !progress.checking) {
                            LinearProgressIndicator(
                                { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                                Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(VOICE_PACK_PROGRESS_TAG),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(VOICE_PACK_PROGRESS_TAG))
                        }
                        Text(line)
                        Text(stringResource(R.string.pack_keep_editing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    PackMode.ERROR -> {
                        val error = failed?.error ?: PackDownloadError.FAILED
                        val missing = (failed?.missingBytes?.takeIf { it > 0 } ?: current.room).takeIf { it > 0 }
                        Text(
                            if (error == PackDownloadError.NO_SPACE) {
                                missing?.let { stringResource(R.string.pack_error_space, size(it)) }
                                    ?: stringResource(R.string.pack_no_space)
                            } else {
                                stringResource(VoicePackNotifications.errorText(error))
                            },
                        )
                        BrowserInstead {
                            open(VoicePackManifest.folderUrl(current.version))
                            voices.useBrowser()
                        }
                    }

                    PackMode.STEPS -> {
                        Text(stringResource(R.string.pack_steps_body))
                        current.files.forEach { f ->
                            TextButton(
                                onClick = { open(VoicePackManifest.downloadUrl(current.version, f.file)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(
                                    stringResource(R.string.pack_file_line, f.file, size(f.bytes)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(VoicePackManifest.folderUrl(current.version), style = MaterialTheme.typography.bodySmall)
                        WorkLine(work)
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                PackMode.CONFIRM, PackMode.ERROR -> {
                    TextButton(
                        onClick = voices::downloadPack,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_PACK_DOWNLOAD_TAG),
                    ) { AutoSizeLabel(stringResource(if (mode == PackMode.ERROR) R.string.export_try_again else R.string.pack_download)) }
                }

                PackMode.PROGRESS -> {
                    TextButton(onClick = voices::hidePack, modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_PACK_HIDE_TAG)) {
                        AutoSizeLabel(stringResource(R.string.export_hide))
                    }
                }

                PackMode.STEPS -> {
                    TextButton(
                        onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        enabled = work == null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_PACK_ADD_TAG),
                    ) { AutoSizeLabel(stringResource(R.string.pack_add_files)) }
                }
            }
        },
        dismissButton = {
            if (mode == PackMode.PROGRESS) {
                TextButton(onClick = voices::cancelDownload, modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_PACK_CANCEL_TAG)) {
                    AutoSizeLabel(stringResource(R.string.action_cancel))
                }
            } else {
                TextButton(onClick = voices::dismissPack, enabled = work == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    AutoSizeLabel(stringResource(R.string.media_access_not_now))
                }
            }
        },
        modifier = Modifier.testTag(VOICE_PACK_DIALOG_TAG),
    )
}

private enum class PackMode { CONFIRM, PROGRESS, ERROR, STEPS }

/** Something to know before downloading: mobile data or no connection (information), or no room ([error]). */
@Composable
private fun PackNotice(
    text: String,
    error: Boolean = false,
) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(VOICE_PACK_WARNING_TAG),
    )
}

/** The older way, kept as a fallback: download the files in the browser and add them. */
@Composable
private fun BrowserInstead(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // Lined up with the text above it; a little room at the end, so a Hindi mark past the last letter is not cut.
        contentPadding = PaddingValues(start = 0.dp, end = 8.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_PACK_BROWSER_TAG),
    ) {
        AutoSizeLabel(stringResource(R.string.pack_use_browser))
    }
}

/** Whether the phone is online now, whether that is mobile data, and whether it is metered at all, kept up to date. */
private data class NetworkNow(
    val connected: Boolean,
    val cellular: Boolean,
    val metered: Boolean,
)

@Composable
private fun rememberNetwork(): NetworkNow {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }

    fun now(): NetworkNow {
        val caps = manager?.let { m -> m.activeNetwork?.let { m.getNetworkCapabilities(it) } }
        // Validated, as WorkManager waits for: a Wi-Fi sign-in page is not a connection yet.
        val connected =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkNow(
            connected,
            connected && caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            connected && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        )
    }
    var state by remember { mutableStateOf(now()) }
    DisposableEffect(manager) {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    state = now()
                }

                override fun onLost(network: Network) {
                    state = now()
                }
            }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback) }.isSuccess && manager != null
        onDispose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return state
}

private const val TYPED_MAX = 4000
