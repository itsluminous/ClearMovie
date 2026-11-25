package com.itsluminous.clearmovie.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.files.StorageReport
import com.itsluminous.clearmovie.data.files.StorageUsage
import com.itsluminous.clearmovie.data.project.CrashLog
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.data.voice.PackDownloads
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
            }
        }

        private val storageState = MutableStateFlow<StorageReport?>(null)

        /** What the app keeps on the phone, for Settings -> Storage; null until measured. */
        val storage: StateFlow<StorageReport?> = storageState.asStateFlow()

        /** Measures the storage again (each time Settings shows). */
        fun refresh() {
            viewModelScope.launch {
                storageState.value = runCatching { storageUsage.report() }.getOrNull()
                packState.value = withContext(Dispatchers.IO) { runCatching { voiceTools.packBytes() }.getOrDefault(0L) }
                crashState.value = withContext(Dispatchers.IO) { crashLog?.last() }
            }
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
