package com.itsluminous.clearmovie.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
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
