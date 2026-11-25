package com.itsluminous.clearmovie.data.voice

import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The voice pack download as the app sees it (cm-voice-pack-download); [id] tells one download from the next. */
sealed interface PackDownloadState {
    data object Idle : PackDownloadState

    /** Queued: it starts once the phone is connected. */
    data class Waiting(
        val id: String,
    ) : PackDownloadState

    data class Running(
        val id: String,
        val progress: PackProgress,
    ) : PackDownloadState

    data class Failed(
        val id: String,
        val error: PackDownloadError,
        val missingBytes: Long = 0,
    ) : PackDownloadState

    data class Done(
        val id: String,
    ) : PackDownloadState
}

/**
 * Starts, follows and stops the voice pack download, which runs in the
 * background (a foreground worker, with its own notification), so it goes
 * on while the user edits, after the editor closes and through a rotation.
 * One at a time.
 */
interface PackDownloads {
    val state: StateFlow<PackDownloadState>

    /** Downloads [files] (from the app's own manifest) and adds them. */
    fun start(files: List<VoicePackFile>)

    /** Stops the download and removes what it had downloaded, without waiting. */
    fun cancel()

    /**
     * Stops the download and waits until its worker has stopped touching the pack's files (or a
     * minute has passed), then removes what it had downloaded. Settings' Remove waits on this first.
     */
    suspend fun cancelAndWait()

    companion object {
        /** No downloads (tests, and a build without a pack). */
        val NONE: PackDownloads =
            object : PackDownloads {
                override val state: StateFlow<PackDownloadState> = MutableStateFlow(PackDownloadState.Idle).asStateFlow()

                override fun start(files: List<VoicePackFile>) = Unit

                override fun cancel() = Unit

                override suspend fun cancelAndWait() = Unit
            }
    }
}
