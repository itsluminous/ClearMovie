package com.itsluminous.clearmovie.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import kotlinx.coroutines.delay

// What the voice engine is doing while it runs, and Try again when it failed (cm-voice-progress): a line in the
// voice item's panel and in Type the words, and a bar above the editor's bar or panel when neither is open.

const val VOICE_STATUS_TAG = "voice_status"
const val VOICE_STATUS_BAR_TAG = "voice_status_bar"
const val VOICE_STOP_TAG = "voice_stop"
const val VOICE_TRY_AGAIN_TAG = "voice_try_again"
const val VOICE_TROUBLE_CLOSE_TAG = "voice_trouble_close"

/**
 * Whether [open] shows the voice status for [clipId] itself (cm-voice-progress): the voice item's
 * panel for its own item, Type the words for typed words (null). The editor's bar leaves it to them.
 */
internal fun showsVoiceStatus(
    open: OpenPanel?,
    clipId: ClipId?,
): Boolean =
    when (open?.panel) {
        Panel.VOICE -> open.clipId == clipId && clipId != null
        Panel.VOICE_TYPE -> clipId == null
        else -> false
    }

/** What [work] is, in a few words for the user. */
@Composable
internal fun voiceWorkText(work: VoiceWork): String =
    stringResource(
        when (work) {
            // Before the engine reports progress, it is loading (and the recording being read).
            is VoiceWork.Recognizing -> if (work.done == null) R.string.voice_engine_loading else R.string.voice_working_words

            is VoiceWork.Voicing -> if (work.done == null) R.string.voice_engine_loading else R.string.voice_working_voice

            is VoiceWork.AddingPack -> R.string.pack_adding

            is VoiceWork.Stopping -> R.string.voice_stopping
        },
    )

/** How far [work] is, from 0 to 1, or null while it cannot say. */
private fun VoiceWork.done(): Float? =
    when (this) {
        is VoiceWork.Recognizing -> done
        is VoiceWork.Voicing -> done
        is VoiceWork.AddingPack -> done
        is VoiceWork.Stopping -> null
    }

/** Whether Stop can end this work now (the voice pack being added is not stopped). */
private val VoiceWork.stoppable: Boolean
    // Throughout: the engine checks between each step of getting ready too; Stopping shows until it does.
    get() = this is VoiceWork.Recognizing || this is VoiceWork.Voicing

@Composable
private fun troubleText(trouble: VoiceTrouble): String =
    stringResource(if (trouble.finding) R.string.voice_words_failed else R.string.voice_failed)

/** The phase of [this] work TalkBack is told of: getting ready, the work itself, or Stopping, never its progress. */
private fun VoiceWork.phase(): String =
    when (this) {
        is VoiceWork.Recognizing -> if (done == null) "ready-words" else "words"
        is VoiceWork.Voicing -> if (done == null) "ready-voice" else "voice"
        is VoiceWork.AddingPack -> "pack"
        is VoiceWork.Stopping -> "stopping"
    }

/**
 * Tells TalkBack each step of the voice work (cm-voice-progress): each phase as it starts (getting
 * ready, finding the words or making the voice, Stopping), then how the job ended, from the
 * controller's own [VoiceChoiceController.outcomes], since a status that simply goes away is never
 * read. An end said by a snackbar ("There are no words to say") is not said twice. What was last
 * said is kept in the controller, so a rotation is quiet and a new process starts fresh. The pack
 * being added has its dialog.
 */
@Composable
internal fun VoiceAnnouncer(voices: VoiceChoiceController) {
    val work by voices.work.collectAsState()
    val view = LocalView.current
    val resources = LocalResources.current
    val now = work?.takeIf { it !is VoiceWork.AddingPack }
    val phase = now?.phase()
    val text = now?.let { voiceWorkText(it) }
    LaunchedEffect(phase) {
        // Getting ready is said only when it takes a while; a quick one goes straight to the work.
        if (phase?.startsWith("ready") == true) delay(READY_SAID_AFTER_MS)
        if (phase != null && phase != voices.announced) {
            @Suppress("DEPRECATION") // Still read by TalkBack on every Android the app runs on (10 to 16).
            view.announceForAccessibility(text)
        }
        voices.announced = phase
    }
    LaunchedEffect(voices) {
        voices.outcomes.collect { outcome ->
            val said =
                when (outcome) {
                    VoiceOutcome.WORDS_FOUND -> R.string.voice_words_done
                    VoiceOutcome.VOICE_MADE -> R.string.voice_made_done
                    VoiceOutcome.STOPPED -> R.string.voice_stopped
                    VoiceOutcome.FAILED -> if (voices.trouble.value?.finding == true) R.string.voice_words_failed else R.string.voice_failed
                }
            @Suppress("DEPRECATION")
            view.announceForAccessibility(resources.getString(said))
        }
    }
}

/**
 * The voice status in a panel: what runs, with Stop and a progress bar
 * (determinate once the engine reports it), or the failure with Try again.
 * Shows nothing while nothing runs.
 */
@Composable
internal fun VoiceStatusLine(
    voices: VoiceChoiceController,
    clipId: ClipId?,
) {
    val work by voices.work.collectAsState()
    val trouble by voices.trouble.collectAsState()
    // Only this panel's own work (another item's shows in the bar), and never the voice pack's (its dialog shows it).
    val now =
        work?.takeIf {
            it.clipId == clipId && it !is VoiceWork.AddingPack &&
                !(it is VoiceWork.Stopping && it.of is VoiceWork.AddingPack)
        }
    val failed = trouble?.takeIf { it.clipId == clipId }
    when {
        now != null -> {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).testTag(VOICE_STATUS_TAG),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    AutoSizeLabel(
                        voiceWorkText(now),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (now.stoppable) StopButton(voices)
                }
                val done = now.done()
                if (done ==
                    null
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator({ done }, Modifier.fillMaxWidth())
                }
            }
        }

        failed != null -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .testTag(VOICE_STATUS_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                AutoSizeLabel(
                    troubleText(failed),
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                // No Try again here: the panel's own Make voice or Find the words, under it, runs again with the
                // words as they are now edited (a retry of the old words would undo the edit).
            }
        }
    }
}

/**
 * The voice status above the editor's bar or panel, while finding words or
 * making a voice runs, or failed, and no panel shows it ([VOICE_STATUS_PANELS]):
 * the user closed the panel, or it has not opened yet. A small round
 * indicator, the status and Stop; or the failure, Try again and Close.
 */
@Composable
internal fun VoiceStatusBar(
    voices: VoiceChoiceController,
    open: OpenPanel?,
    shown: Boolean,
    modifier: Modifier = Modifier,
) {
    val work by voices.work.collectAsState()
    val trouble by voices.trouble.collectAsState()
    // The voice pack being added has its own dialog; a panel showing the status itself takes it.
    val now = work?.takeIf { it !is VoiceWork.AddingPack && !showsVoiceStatus(open, it.clipId) }
    val failed = trouble?.takeIf { now == null && !showsVoiceStatus(open, it.clipId) }
    AnimatedVisibility(
        visible = shown && (now != null || failed != null),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag(VOICE_STATUS_BAR_TAG)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (now != null) {
                    val done = now.done()
                    if (done == null) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator({ done }, Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                    AutoSizeLabel(
                        voiceWorkText(now),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (now.stoppable) StopButton(voices)
                } else if (failed != null) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    AutoSizeLabel(
                        troubleText(failed),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TryAgainButton(voices)
                        IconButton(onClick = voices::dismissTrouble, modifier = Modifier.testTag(VOICE_TROUBLE_CLOSE_TAG)) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopButton(voices: VoiceChoiceController) {
    TextButton(onClick = voices::stop, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = ACTION_MAX_WIDTH).testTag(VOICE_STOP_TAG)) {
        AutoSizeLabel(stringResource(R.string.voice_work_stop))
    }
}

@Composable
private fun TryAgainButton(voices: VoiceChoiceController) {
    TextButton(
        onClick = voices::tryAgain,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = ACTION_MAX_WIDTH).testTag(VOICE_TRY_AGAIN_TAG),
    ) {
        AutoSizeLabel(stringResource(R.string.voice_try_again))
    }
}

/** Getting ready is announced only once it has taken this long. */
private const val READY_SAID_AFTER_MS = 600L

/** At most this wide, so an action's label shrinks (then wraps) before the status beside it is squeezed. */
private val ACTION_MAX_WIDTH = 128.dp

/** What a busy voice item's bar cannot do until its work ends: Delete and Split would throw the work away. */
private val OFF_WHILE_BUSY = setOf(EditAction.DELETE, EditAction.SPLIT)

/** This bar, with [OFF_WHILE_BUSY] off when its item is [busy] (cm-voice-progress). */
internal fun List<BarAction>.offWhileVoiceBusy(busy: Boolean): List<BarAction> =
    if (!busy) this else map { if (it.action in OFF_WHILE_BUSY) it.copy(enabled = false) else it }
