package com.itsluminous.clearmovie.ui.preset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.preset.PresetParts
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The ending a new preset has, as the create screen offers it. */
enum class EndingChoice(
    val kind: EndingKind?,
) {
    VOICE(EndingKind.AUDIO),
    VIDEO(EndingKind.VIDEO),
    NONE(null),
}

data class PresetCreateUi(
    val name: String = "",
    val intro: Boolean = true,
    val frame: Boolean = true,
    val ending: EndingChoice = EndingChoice.VOICE,
    val shape: CanvasShape = CanvasShape.LANDSCAPE,
    /** Keep copies of its files (cm-frame-area); off uses them where they are. */
    val copyFiles: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
    val canCreate: Boolean get() = !busy && PresetEditViewModel.isUsableName(name)
}

/**
 * Making a new preset (cm-preset-manager): its name and which parts it has.
 * It starts with the caption style of the preset the app ships; Create
 * stores it and the editor opens on it, where its files and caption style
 * are chosen. The choices survive rotation and the system closing the app.
 */
@HiltViewModel
class PresetCreateViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val presetStore: PresetStore,
    ) : ViewModel() {
        private val uiState =
            MutableStateFlow(
                PresetCreateUi(
                    name = savedState[KEY_NAME] ?: "",
                    intro = savedState[KEY_INTRO] ?: true,
                    frame = savedState[KEY_FRAME] ?: true,
                    ending =
                        savedState.get<String>(KEY_ENDING)?.let { n -> EndingChoice.entries.firstOrNull { it.name == n } }
                            ?: EndingChoice.VOICE,
                    shape =
                        savedState.get<String>(KEY_SHAPE)?.let { n -> CanvasShape.entries.firstOrNull { it.name == n } }
                            ?: CanvasShape.LANDSCAPE,
                    copyFiles = savedState[KEY_COPY] ?: false,
                ),
            )
        val ui: StateFlow<PresetCreateUi> = uiState.asStateFlow()

        private val created = Channel<PresetId>(Channel.BUFFERED)

        /** The new preset's id, once it is stored. */
        val events: Flow<PresetId> = created.receiveAsFlow()

        fun setName(name: String) {
            val usable = name.filterNot { it == '\n' || it == '\r' }.take(PresetParser.MAX_DISPLAY_NAME)
            savedState[KEY_NAME] = usable
            uiState.update { it.copy(name = usable, failed = false) }
        }

        fun setIntro(on: Boolean) {
            savedState[KEY_INTRO] = on
            uiState.update { it.copy(intro = on) }
        }

        fun setFrame(on: Boolean) {
            savedState[KEY_FRAME] = on
            uiState.update { it.copy(frame = on) }
        }

        fun setEnding(choice: EndingChoice) {
            savedState[KEY_ENDING] = choice.name
            uiState.update { it.copy(ending = choice) }
        }

        fun setShape(shape: CanvasShape) {
            savedState[KEY_SHAPE] = shape.name
            uiState.update { it.copy(shape = shape) }
        }

        fun setCopyFiles(on: Boolean) {
            savedState[KEY_COPY] = on
            uiState.update { it.copy(copyFiles = on) }
        }

        fun create() {
            val ui = uiState.value
            if (!ui.canCreate) return
            uiState.update { it.copy(busy = true, failed = false) }
            viewModelScope.launch {
                val id =
                    presetStore.create(
                        ui.name,
                        PresetParts(
                            intro = ui.intro,
                            frame = ui.frame,
                            ending = ui.ending.kind,
                            shape = ui.shape,
                            copyFiles = ui.copyFiles,
                        ),
                    )
                if (id != null) created.send(id)
                uiState.update { it.copy(busy = false, failed = id == null) }
            }
        }

        private companion object {
            const val KEY_NAME = "name"
            const val KEY_INTRO = "intro"
            const val KEY_FRAME = "frame"
            const val KEY_ENDING = "ending"
            const val KEY_SHAPE = "shape"
            const val KEY_COPY = "copyFiles"
        }
    }
