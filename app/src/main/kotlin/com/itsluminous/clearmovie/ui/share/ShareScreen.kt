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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val ready = state as? ShareState.Ready
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
        busy = ready?.choosing == true,
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
    var retryAfterAccess by rememberSaveable { mutableStateOf(false) }
    val waiting = state as? ShareState.Waiting
    LaunchedEffect(waiting) {
        if (waiting?.target != null) share.begin(access.state)
    }
    LaunchedEffect(share) {
        share.nav.collect {
            if (it == ShareNav.AskForAccess) {
                retryAfterAccess = true
                access.request()
            } else {
                onNav(it)
            }
        }
    }
    // Back from Android's question (Allow all photos): the access it left, read before this resume.
    LifecycleResumeEffect(retryAfterAccess) {
        if (retryAfterAccess) {
            retryAfterAccess = false
            share.retry(access.state)
        }
        onPauseOrDispose {}
    }
    MediaIntakeDialogs(
        state = intake,
        onCopyAnyway = share.intake::copyAnyway,
        onChooseFewer = share.intake::chooseFewer,
        onAllowAll = share.intake::allowAll,
        onCancel = share.intake::cancel,
    )
    when (val s = state) {
        is ShareState.Asking -> {
            AlertDialog(
                onDismissRequest = share::cancel,
                title = { Text(stringResource(R.string.share_add_title)) },
                text = { Text(pluralStringResource(R.plurals.share_add_body, s.count, s.count)) },
                confirmButton = { TextButton(onClick = share::addToOpen) { Text(stringResource(R.string.share_add_here)) } },
                dismissButton = { TextButton(onClick = share::startNewVideo) { Text(stringResource(R.string.share_start_new)) } },
            )
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

        is ShareState.Added -> {
            LaunchedEffect(s) { if (!s.notes.any) share.dismiss() }
            if (s.notes.any) {
                Message(
                    title = stringResource(R.string.share_added_title),
                    lines = listOf(pluralStringResource(R.plurals.share_added_body, s.added, s.added)) + noteLines(s.notes),
                    onOk = share::dismiss,
                )
            }
        }

        is ShareState.Nothing -> {
            Message(
                title = stringResource(R.string.share_nothing_title),
                lines = listOf(stringResource(R.string.share_nothing_body)) + noteLines(s.notes),
                onOk = share::dismiss,
            )
        }

        is ShareState.Failed -> {
            Message(
                title = stringResource(R.string.copy_no_space_title),
                lines = listOf(stringResource(if (s.outOfSpace) R.string.pick_out_of_space else R.string.share_not_saved)),
                onOk = share::dismiss,
            )
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
