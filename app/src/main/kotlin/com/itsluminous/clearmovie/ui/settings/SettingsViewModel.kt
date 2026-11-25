package com.itsluminous.clearmovie.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.files.StorageReport
import com.itsluminous.clearmovie.data.files.StorageUsage
import com.itsluminous.clearmovie.data.project.CrashLog
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Hindi HD in Settings (cm-hindi-voices): the Hindi recognizer this phone's memory
 * runs, the files still to download for it (empty: it is added), and the voice
 * pack download now.
 */
data class HindiHdOffer(
    val model: HindiModel,
    val files: List<VoicePackFile>,
    val download: PackDownloadState = PackDownloadState.Idle,
) {
    val bytes: Long get() = files.sumOf { it.bytes }
    val unpackedBytes: Long get() = files.sumOf { it.unpackedBytes }

    /** Whether the download includes the voice pack Hindi HD needs (the phone does not have it yet). */
    val withPack: Boolean get() = files.any { it.part !in VoicePackFile.HINDI_HD_PARTS }
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val storageUsage: StorageUsage,
        private val voiceTools: VoiceTools = VoiceTools.NONE,
        private val crashLog: CrashLog? = null,
        private val packDownloads: PackDownloads = PackDownloads.NONE,
    ) : ViewModel() {
        private val crashState = MutableStateFlow<CrashLog.Crash?>(null)

        /** The last crash, which About offers to share (cm-crash-after-media-pick); null when none is kept. */
        val crash: StateFlow<CrashLog.Crash?> = crashState.asStateFlow()

        private val packState = MutableStateFlow(0L)

        /** Bytes the voice pack takes on the phone (cm-voice-choice); 0 when it is not added. */
        val voicePackBytes: StateFlow<Long> = packState.asStateFlow()

        /** Removes the voice pack, stopping a download of it first. Voices already made stay in their videos. */
        fun removeVoicePack() {
            viewModelScope.launch(Dispatchers.IO) {
                // A download still running, or still stopping, must not add its file back into the removed pack.
                packDownloads.cancelAndWait()
                voiceTools.removePack()
                packState.value = voiceTools.packBytes()
                hindiHdState.value = hindiHdOffer()
            }
        }

        private val hindiHdState = MutableStateFlow<HindiHdOffer?>(null)

        /** Hindi HD's row (cm-hindi-voices); null where the voice pack cannot run, or has no Hindi HD. */
        val hindiHd: StateFlow<HindiHdOffer?> = hindiHdState.asStateFlow()

        init {
            viewModelScope.launch {
                packDownloads.state.collect { state ->
                    // Idle right after Download is the worker not started yet: the row keeps showing it starting.
                    hindiHdState.update { o ->
                        if (o?.download is PackDownloadState.Waiting && (o.download as PackDownloadState.Waiting).id == STARTING &&
                            state == PackDownloadState.Idle
                        ) {
                            o
                        } else {
                            o?.copy(download = state)
                        }
                    }
                    if (state is PackDownloadState.Done) refresh()
                }
            }
        }

        private fun hindiHdOffer(): HindiHdOffer? {
            val manifest = voiceTools.manifest ?: return null
            if (!voiceTools.supported || manifest.files.none { it.part == VoicePackFile.SPEECH_HI_HD }) return null
            val now = packDownloads.state.value
            // A download just started from here stays shown as starting until the worker reports it.
            val starting = (hindiHdState.value?.download as? PackDownloadState.Waiting)?.id == STARTING && now == PackDownloadState.Idle
            return HindiHdOffer(voiceTools.hindiModel, voiceTools.hindiHdMissing(), if (starting) hindiHdState.value!!.download else now)
        }

        /** Downloads what this phone still needs for Hindi HD, in the background (the voice pack download). */
        fun downloadHindiHd() {
            val files = hindiHdState.value?.files.orEmpty()
            if (files.isEmpty()) return
            // Shown at once; the worker's own state follows when it starts.
            hindiHdState.update { it?.copy(download = PackDownloadState.Waiting(STARTING)) }
            packDownloads.start(files)
            // A worker that never reports (another download already queued) must not leave the row starting.
            viewModelScope.launch {
                delay(STARTING_MS)
                hindiHdState.update { o ->
                    val w = o?.download as? PackDownloadState.Waiting
                    if (w?.id == STARTING) o.copy(download = packDownloads.state.value) else o
                }
            }
        }

        /** Stops the voice pack download and removes what it had downloaded. */
        fun cancelDownload() {
            packDownloads.cancel()
            hindiHdState.update { it?.copy(download = PackDownloadState.Idle) }
        }

        private val storageState = MutableStateFlow<StorageReport?>(null)

        /** What the app keeps on the phone, for Settings -> Storage; null until measured. */
        val storage: StateFlow<StorageReport?> = storageState.asStateFlow()

        /** Measures the storage again (each time Settings shows). */
        fun refresh() {
            viewModelScope.launch {
                storageState.value = runCatching { storageUsage.report() }.getOrNull()
                packState.value = withContext(Dispatchers.IO) { runCatching { voiceTools.packBytes() }.getOrDefault(0L) }
                hindiHdState.value = withContext(Dispatchers.IO) { runCatching { hindiHdOffer() }.getOrNull() }
                crashState.value = withContext(Dispatchers.IO) { crashLog?.last() }
            }
        }

        private companion object {
            /** The download Settings started, before the worker reports it. */
            const val STARTING = "settings"
            const val STARTING_MS = 20_000L
        }

        /** Null until the stored settings are read, so the screen never shows defaults first. */
        val settings: StateFlow<AppSettings?> =
            settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { settingsRepository.setThemeMode(mode) }
        }

        fun setDynamicColor(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
        }
    }
