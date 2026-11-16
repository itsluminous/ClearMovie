package com.itsluminous.clearmovie.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.files.StorageReport
import com.itsluminous.clearmovie.data.files.StorageUsage
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.ui.home.PresetCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val presetStore: PresetStore,
        private val storageUsage: StorageUsage,
    ) : ViewModel() {
        private val presetsState = MutableStateFlow<List<PresetCard>>(emptyList())

        /** Every preset with whether its files are ready, for Settings -> Presets. */
        val presets: StateFlow<List<PresetCard>> = presetsState.asStateFlow()

        private val storageState = MutableStateFlow<StorageReport?>(null)

        /** What the app keeps on the phone, for Settings -> Storage; null until measured. */
        val storage: StateFlow<StorageReport?> = storageState.asStateFlow()

        /** Reads the presets and the storage again (each time Settings shows). */
        fun refresh() {
            viewModelScope.launch {
                presetsState.value =
                    presetStore.definitions().map { PresetCard(it.preset, PresetCard.stateOf(presetStore.status(it.preset.id))) }
                storageState.value = runCatching { storageUsage.report() }.getOrNull()
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
