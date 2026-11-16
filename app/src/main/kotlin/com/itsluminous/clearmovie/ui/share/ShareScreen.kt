package com.itsluminous.clearmovie.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.ui.home.HomeScreen
import com.itsluminous.clearmovie.ui.home.HomeViewModel
import com.itsluminous.clearmovie.ui.home.ShareHeader
import com.itsluminous.clearmovie.ui.media.MediaAccessExplainer
import com.itsluminous.clearmovie.ui.media.MediaIntakeDialogs
import com.itsluminous.clearmovie.ui.media.rememberMediaAccessController

/**
 * The share's preset pick (ARCHITECTURE R3, section 11.12): the preset
 * cards and Start without a preset, under a header with the shared items,
 * while they are taken in and once they are ready. The first time ever, the
 * photo access explainer comes first, so the items can be kept as links
 * instead of copies. Back or Cancel drops the share.
 */
@Composable
fun ShareRoute(
    share: ShareViewModel,
    onIdle: () -> Unit,
    home: HomeViewModel = hiltViewModel(),
) {
    val state by share.state.collectAsStateWithLifecycle()
    val restored by share.restoredFlow.collectAsStateWithLifecycle()
    val presets by home.presets.collectAsStateWithLifecycle()
    val asked by share.askedBefore.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(home) {
        home.refresh()
        onPauseOrDispose {}
    }
    // No share here (one restored after a process death that could not come back): Home. Never while leaving for the editor.
    LaunchedEffect(state, restored) {
        if (restored && state == ShareState.Idle && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onIdle()
    }
    BackHandler(onBack = share::cancel)
    val askedBefore = asked ?: return
    val access = rememberMediaAccessController(askedBefore = askedBefore, onAsked = share::markAsked)
    var notNow by rememberSaveable { mutableStateOf(false) }
    val waiting = state as? ShareState.Waiting
    val explain = waiting != null && waiting.target == null && access.state == MediaAccessState.NotAsked && !notNow
    LaunchedEffect(waiting, explain, access.state) {
        if (waiting != null && waiting.target == null && !explain) share.begin(access.state)
    }
    if (explain) {
        MediaAccessExplainer(
            state = access.state,
            onContinue = access::request,
            onNotNow = { notNow = true },
            onOpenSettings = access::openSettings,
        )
        return
    }
    val ready = share.pick.collectAsStateWithLifecycle().value
    val header =
        ShareHeader(
            ready = ready != null,
            items = ready?.items ?: 0,
            notMedia = ready?.notes?.notMedia ?: 0,
            unreadable = ready?.notes?.unreadable ?: 0,
            overLimit = ready?.notes?.overLimit ?: 0,
            limit = ShareLinks.MAX_ITEMS,
        )
    HomeScreen(
        presets = presets,
        videos = emptyList(),
        // The cards wait while a preset is applied, or while a later share asks or adds over this pick.
        busy = ready != null && (state !is ShareState.Ready || ready.choosing),
        onPreset = { share.choose(it) },
        onStartBlank = { share.choose(null) },
        onVideo = {},
        onOpenSettings = {},
        share = header,
        onCancelShare = share::cancel,
    )
}

/**
 * The share's questions and messages, over whatever screen shows (the
 * editor, for a share added to the open video): add or start new, the
 * many-items notice, the copy dialogs, and what was added or left out.
 */
@Composable
fun ShareDialogs(
    share: ShareViewModel,
    onNav: (ShareNav) -> Unit,
) {
    val state by share.state.collectAsStateWithLifecycle()
    val intake by share.intake.state.collectAsStateWithLifecycle()
    // Adding to the open video needs no explainer: the items go in with the access there is.
    val access = rememberMediaAccessController(askedBefore = true, onAsked = share::markAsked)
    // The access answer count when Allow all photos asked; -1 when not waiting for one.
    var retryAfterAccess by rememberSaveable { mutableIntStateOf(-1) }
    val waiting = state as? ShareState.Waiting
    LaunchedEffect(waiting) {
        if (waiting?.target != null) share.begin(access.state)
    }
    LaunchedEffect(share) {
        share.nav.collect {
            if (it == ShareNav.AskForAccess) {
                retryAfterAccess = access.answers
                access.request()
            } else {
                onNav(it)
            }
        }
    }
    // Android answered the Allow all photos question: take the items in again with the access it left.
    LaunchedEffect(access.answers, retryAfterAccess) {
        if (retryAfterAccess >= 0 && access.answers != retryAfterAccess) {
            retryAfterAccess = -1
            share.retry(access.state)
        }
    }
    MediaIntakeDialogs(
        state = intake,
        onCopyAnyway = share.intake::copyAnyway,
        onChooseFewer = share::chooseFewer,
        onAllowAll = share.intake::allowAll,
        onCancel = share.intake::cancel,
    )
    when (val s = state) {
        is ShareState.Asking -> {
            // Three plain choices, one per line; a tap beside the dialog does nothing (Back still cancels).
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(stringResource(if (s.toWaitingShare) R.string.share_add_waiting_title else R.string.share_add_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            if (s.toWaitingShare) R.plurals.share_add_waiting_body else R.plurals.share_add_body,
                            s.count,
                            s.count,
                        ),
                    )
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = share::addToOpen) { Text(stringResource(R.string.share_add_here)) }
                        TextButton(onClick = share::startNewVideo) { Text(stringResource(R.string.share_start_new)) }
                        TextButton(onClick = share::cancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            )
            BackHandler(onBack = share::cancel)
        }

        is ShareState.ManyItems -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.share_many_title)) },
                text = { Text(pluralStringResource(R.plurals.share_many_body, s.minutes, s.minutes)) },
                confirmButton = { TextButton(onClick = share::continueMany) { Text(stringResource(R.string.share_continue)) } },
                dismissButton = { TextButton(onClick = share::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        // Always said, so items added to a video he was not looking at never arrive unnoticed.
        is ShareState.Added -> {
            Message(
                title = stringResource(R.string.share_added_title),
                lines = listOf(pluralStringResource(R.plurals.share_added_body, s.added, s.added)) + noteLines(s.notes),
                onOk = share::dismiss,
            )
        }

        is ShareState.Nothing -> {
            // The first line says why; the notes below give the counts.
            val n = s.notes
            val why =
                when {
                    n.notMedia + n.unreadable + n.overLimit == 0 -> stringResource(R.string.share_nothing_empty)
                    n.notMedia > 0 && n.unreadable == 0 -> stringResource(R.string.share_nothing_body)
                    else -> null
                }
            Message(
                title = stringResource(R.string.share_nothing_title),
                lines = listOfNotNull(why) + noteLines(n),
                onOk = share::dismiss,
            )
        }

        is ShareState.Failed -> {
            val (title, body) =
                when (s.reason) {
                    FailReason.OUT_OF_SPACE -> R.string.copy_no_space_title to R.string.pick_out_of_space
                    FailReason.PHONE_FULL -> R.string.copy_no_space_title to R.string.share_not_saved
                    FailReason.CHOICE_NOT_SAVED -> R.string.copy_no_space_title to R.string.share_choice_not_saved
                    FailReason.CHOOSE_FEWER -> R.string.share_failed_title to R.string.share_choose_fewer_body
                    FailReason.OTHER -> R.string.share_failed_title to R.string.share_failed_body
                }
            Message(title = stringResource(title), lines = listOf(stringResource(body)), onOk = share::dismiss)
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun noteLines(notes: ShareNotes): List<String> =
    buildList {
        if (notes.notMedia > 0) add(pluralStringResource(R.plurals.share_skipped_not_media, notes.notMedia, notes.notMedia))
        if (notes.unreadable > 0) add(pluralStringResource(R.plurals.share_skipped_unreadable, notes.unreadable, notes.unreadable))
        if (notes.overLimit > 0) {
            add(pluralStringResource(R.plurals.share_skipped_over_limit, notes.overLimit, notes.overLimit, ShareLinks.MAX_ITEMS))
        }
    }

@Composable
private fun Message(
    title: String,
    lines: List<String>,
    onOk: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.forEach { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.action_ok)) } },
    )
}
