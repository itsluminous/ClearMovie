package com.itsluminous.clearmovie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.project.LaunchDecision
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Holds the appearance settings for the whole activity (null until they have loaded), and where a fresh start goes. */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        private val projects: ProjectRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings?> =
            settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Home, or the editor again when the app stopped with a video open in it (ARCHITECTURE section 10). */
        suspend fun launchDecision(): LaunchDecision = runCatching { projects.launchDecision() }.getOrDefault(LaunchDecision.Home)
    }
