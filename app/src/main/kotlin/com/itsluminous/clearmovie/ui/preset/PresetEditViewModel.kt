package com.itsluminous.clearmovie.ui.preset

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.preset.Lab
import com.itsluminous.clearmovie.data.preset.PresetDefinition
import com.itsluminous.clearmovie.data.preset.PresetDeleteResult
import com.itsluminous.clearmovie.data.preset.PresetSaveResult
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetParser
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectCodec
import com.itsluminous.clearmovie.domain.model.TextRules
import com.itsluminous.clearmovie.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the preset editor shows. The draft fields are null while unchanged:
 * the screen then shows the preset's own value (its name and words are
 * worked out in the app's language by the screen, which has the resources).
 */
data class PresetEditUi(
    val definition: PresetDefinition? = null,
    val status: PresetStatus? = null,
    val draftName: String? = null,
    val draftStyle: CaptionStyle? = null,
    val draftWords: String? = null,
    /** The frame file's picture with its green window cut out, for the caption preview. */
    val frameStill: Bitmap? = null,
    val busy: Boolean = false,
    val message: PresetEditMessage? = null,
    /** True when the preset is gone (deleted elsewhere): the screen leaves. */
    val missing: Boolean = false,
) {
    val preset: Preset? get() = definition?.preset
    val style: CaptionStyle? get() = draftStyle ?: preset?.caption
}

enum class PresetEditMessage { NOT_SAVED, RESET_DONE }

sealed interface PresetEditEvent {
    /** Saved, or nothing to save: back to where the editor was opened from. */
    data object Done : PresetEditEvent

    /** Deleted ([videos] still use it, and keep working). */
    data class Deleted(
        val videos: Int,
    ) : PresetEditEvent
}

/**
 * Editing a preset (cm-preset-manager): its name, the words and style a new
 * caption starts with, and (on the setup screen) its files. Changes are a
 * draft, kept across rotation and the system closing the app, until Save.
 * A preset the user made can be deleted; one the app ships can be reset.
 */
@HiltViewModel
class PresetEditViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val presetStore: PresetStore,
    ) : ViewModel() {
        val presetId: PresetId = PresetId(requireNotNull(savedState.get<String>(Routes.ARG_PRESET)))

        private val uiState =
            MutableStateFlow(
                PresetEditUi(
                    draftName = savedState[KEY_NAME],
                    draftStyle = savedState.get<String>(KEY_STYLE)?.let(::decodeStyle),
                    draftWords = savedState[KEY_WORDS],
                ),
            )
        val ui: StateFlow<PresetEditUi> = uiState.asStateFlow()

        private val eventChannel = Channel<PresetEditEvent>(Channel.BUFFERED)
        val events: Flow<PresetEditEvent> = eventChannel.receiveAsFlow()

        private var job: Job? = null
        private var stillFor: String? = null

        init {
            refresh()
        }

        /** Reads the preset and its files again: called whenever the screen shows (setup may have changed a file). */
        fun refresh() {
            viewModelScope.launch {
                val definition = presetStore.definition(presetId)
                if (definition == null || definition.retired) {
                    uiState.update { it.copy(definition = null, missing = true) }
                    return@launch
                }
                val status = presetStore.status(presetId)
                uiState.update { it.copy(definition = definition, status = status) }
                loadStill(status, definition.preset)
            }
        }

        fun setName(name: String) {
            val usable = name.filterNot { it == '\n' || it == '\r' }.take(PresetParser.MAX_DISPLAY_NAME)
            savedState[KEY_NAME] = usable
            uiState.update { it.copy(draftName = usable) }
        }

        fun setStyle(style: CaptionStyle) {
            savedState[KEY_STYLE] = ProjectCodec.json.encodeToString(CaptionStyle.serializer(), style)
            uiState.update { it.copy(draftStyle = style) }
        }

        fun setWords(words: String) {
            savedState[KEY_WORDS] = words
            uiState.update { it.copy(draftWords = words) }
        }

        /** Puts the caption style back to the preset's saved one (the draft only). */
        fun revertStyle(
            style: CaptionStyle?,
            words: String?,
        ) {
            savedState[KEY_STYLE] = style?.let { ProjectCodec.json.encodeToString(CaptionStyle.serializer(), it) }
            savedState[KEY_WORDS] = words
            uiState.update { it.copy(draftStyle = style, draftWords = words) }
        }

        /**
         * Saves the draft. [shownName] and [shownWords] are the preset's own
         * name and words as the screen shows them, so an unchanged field
         * stores nothing new.
         */
        fun save(
            shownName: String,
            shownWords: String,
        ) {
            if (job?.isActive == true) return
            val ui = uiState.value
            val preset = ui.preset ?: return
            val change = changedPreset(preset, ui, shownName, shownWords)
            if (change == null) {
                clearDraft()
                eventChannel.trySend(PresetEditEvent.Done)
                return
            }
            job =
                viewModelScope.launch {
                    uiState.update { it.copy(busy = true, message = null) }
                    when (presetStore.save(change)) {
                        is PresetSaveResult.Saved -> {
                            clearDraft()
                            eventChannel.send(PresetEditEvent.Done)
                        }

                        PresetSaveResult.Invalid, PresetSaveResult.NotSaved -> {
                            uiState.update { it.copy(message = PresetEditMessage.NOT_SAVED) }
                        }
                    }
                    uiState.update { it.copy(busy = false) }
                }
        }

        /** Deletes a preset the user made (a built-in one is reset instead). */
        fun delete() {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    uiState.update { it.copy(busy = true) }
                    when (val r = presetStore.delete(presetId)) {
                        PresetDeleteResult.Deleted -> {
                            clearDraft()
                            eventChannel.send(PresetEditEvent.Deleted(0))
                        }

                        is PresetDeleteResult.Retired -> {
                            clearDraft()
                            eventChannel.send(PresetEditEvent.Deleted(r.videos))
                        }

                        PresetDeleteResult.BuiltIn, PresetDeleteResult.Unknown -> {
                            Unit
                        }
                    }
                    uiState.update { it.copy(busy = false) }
                }
        }

        /** A built-in preset back to the app's own name and caption style; its files stay. */
        fun reset() {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    uiState.update { it.copy(busy = true) }
                    presetStore.reset(presetId)
                    clearDraft()
                    val definition = presetStore.definition(presetId)
                    uiState.update { it.copy(definition = definition, busy = false, message = PresetEditMessage.RESET_DONE) }
                }
        }

        fun dismissMessage() = uiState.update { it.copy(message = null) }

        private fun clearDraft() {
            savedState[KEY_NAME] = null
            savedState[KEY_STYLE] = null
            savedState[KEY_WORDS] = null
            uiState.update { it.copy(draftName = null, draftStyle = null, draftWords = null) }
        }

        private fun loadStill(
            status: PresetStatus,
            preset: Preset,
        ) {
            val frame = (status.slots[PresetSlot.FRAME] as? SlotStatus.Ready)?.binding
            val key = frame?.let { "${it.file.path}:${it.sha256}" }
            if (key == stillFor) return
            stillFor = key
            if (frame == null) {
                uiState.update { it.copy(frameStill = null) }
                return
            }
            val keyColor = frame.keyColor ?: preset.frame?.key?.color ?: return
            viewModelScope.launch {
                val still = withContext(Dispatchers.IO) { keyedStill(frame.file.path, keyColor.argb.toInt()) }
                uiState.update { it.copy(frameStill = still) }
            }
        }

        companion object {
            private const val KEY_NAME = "draftName"
            private const val KEY_STYLE = "draftStyle"
            private const val KEY_WORDS = "draftWords"
            private const val STILL_AT_US = 1_000_000L
            private const val STILL_WIDTH = 640
            private const val STILL_HEIGHT = 360

            /** How near the key colour (CIE76) a pixel is cut out of the preview picture. */
            private const val CUT_DELTA_E = 30.0

            private fun decodeStyle(text: String): CaptionStyle? =
                runCatching { ProjectCodec.json.decodeFromString(CaptionStyle.serializer(), text) }.getOrNull()

            /** A name the preset can carry: one line that shows something, at most [PresetParser.MAX_DISPLAY_NAME] long. */
            fun isUsableName(name: String): Boolean {
                val trimmed = name.trim()
                return trimmed.isNotEmpty() && trimmed.length <= PresetParser.MAX_DISPLAY_NAME && TextRules.isValidName(trimmed)
            }

            /**
             * [preset] with the draft applied, or null when the draft changes
             * nothing. A name or words equal to what the screen shows are
             * left as they are, so saving an untouched built-in preset never
             * turns its translated name into fixed text.
             */
            fun changedPreset(
                preset: Preset,
                ui: PresetEditUi,
                shownName: String,
                shownWords: String,
            ): Preset? {
                val name = ui.draftName?.trim()?.takeIf { it != shownName.trim() && isUsableName(it) }
                val words = ui.draftWords?.trim()?.takeIf { it != shownWords.trim() }
                val style = ui.draftStyle?.takeIf { it != preset.caption }
                if (name == null && words == null && style == null) return null
                return preset.copy(
                    displayName = name?.let(LocalizedText::same) ?: preset.displayName,
                    defaultText =
                        when {
                            words == null -> preset.defaultText
                            words.isEmpty() -> null
                            else -> LocalizedText.same(words)
                        },
                    caption = style ?: preset.caption,
                )
            }

            /** The frame's picture at one second, its green window made see-through. */
            internal fun keyedStill(
                path: String,
                keyArgb: Int,
            ): Bitmap? {
                val r = MediaMetadataRetriever()
                return try {
                    r.setDataSource(path)
                    val frame =
                        r.getScaledFrameAtTime(STILL_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, STILL_WIDTH, STILL_HEIGHT)
                            ?: return null
                    val bitmap = frame.copy(Bitmap.Config.ARGB_8888, true)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val key = Lab.of(keyArgb)
                    for (i in pixels.indices) {
                        if (Lab.of(pixels[i]).deltaE(key) < CUT_DELTA_E) pixels[i] = 0
                    }
                    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    bitmap
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { r.release() }
                }
            }
        }
    }
