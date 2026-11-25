package com.itsluminous.clearmovie.ui.preset

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.clearmovie.data.preset.FrameStills
import com.itsluminous.clearmovie.data.preset.PresetDefinition
import com.itsluminous.clearmovie.data.preset.PresetDeleteResult
import com.itsluminous.clearmovie.data.preset.PresetSaveResult
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.EffectDefaults
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetParser
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectCodec
import com.itsluminous.clearmovie.domain.model.TextRules
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.refitBox
import com.itsluminous.clearmovie.ui.frame.keyedPicture
import com.itsluminous.clearmovie.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /**
     * The effect defaults with a new start animation for new captions (cm-caption-box), a new animation
     * for new photos or a new transition between items (cm-preset-motion-defaults); null while unchanged.
     */
    val draftEffects: EffectDefaults? = null,
    /** A new shape (cm-aspect-ratio), for a preset the user made that no video uses yet. */
    val draftShape: CanvasShape? = null,
    /** A new default voice for new voice recordings (cm-preset-voice-default), null while unchanged. */
    val draftVoice: VoiceChoice? = null,
    /** False where the voice pack cannot run on this phone: the default voice is off, and ignored here. */
    val voiceAvailable: Boolean = false,
    /** How many videos were made from this preset; while any is, its shape stays. */
    val videosUsing: Int = 0,
    /** The frame file's picture with its green window cut out, for the caption preview. */
    val frameStill: Bitmap? = null,
    val busy: Boolean = false,
    val message: PresetEditMessage? = null,
    /** True when the preset is gone (deleted elsewhere): the screen leaves. */
    val missing: Boolean = false,
) {
    val preset: Preset? get() = definition?.preset
    val style: CaptionStyle? get() = draftStyle ?: preset?.caption

    /** The animation a new caption starts with: the draft's, else the preset's (null is none). */
    val captionAnimation: AnimKind? get() = (draftEffects ?: preset?.effects)?.textStart

    /** The animation a new photo gets all through (a gentle zoom): the draft's, else the preset's (null is none). */
    val photoAnimation: AnimKind? get() = (draftEffects ?: preset?.effects)?.photoDuring

    /** The transition new items get between them: the draft's, else the preset's (null is none). */
    val transition: Transition? get() = (draftEffects ?: preset?.effects)?.mainTransition

    /** The default voice: the draft's, else the preset's. */
    val voice: VoiceChoice get() = draftVoice ?: preset?.voice ?: VoiceChoice.NONE

    /** The shape shown: the draft's, else the preset's. */
    val shape: CanvasShape get() = draftShape ?: preset?.shape ?: CanvasShape.LANDSCAPE

    /**
     * A preset the user made can change its shape while no video uses it.
     * One the app once shipped (the kept Event frame) keeps its own: its
     * frame's window is the one its definition names, measured for that shape.
     */
    val canChangeShape: Boolean get() = madeHere && videosUsing == 0

    /** Made in the app, not one the app once shipped (cm-home-presets). */
    val madeHere: Boolean get() =
        definition
            ?.preset
            ?.id
            ?.value
            ?.startsWith(PresetStore.OWN_PREFIX) == true
}

enum class PresetEditMessage { NOT_SAVED }

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
 * caption starts with, the animation new photos get and the transition
 * between new items (cm-preset-motion-defaults), the voice a new voice
 * recording starts in (cm-preset-voice-default), and (on the setup screen)
 * its files. Changes are a
 * draft, kept across rotation and the system closing the app, until Save.
 * Any preset can be deleted; the videos made from it keep working.
 */
@HiltViewModel
class PresetEditViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val presetStore: PresetStore,
        private val frameStills: FrameStills,
        voiceTools: VoiceTools,
    ) : ViewModel() {
        val presetId: PresetId = PresetId(requireNotNull(savedState.get<String>(Routes.ARG_PRESET)))

        private val uiState =
            MutableStateFlow(
                PresetEditUi(
                    draftName = savedState[KEY_NAME],
                    draftStyle = savedState.get<String>(KEY_STYLE)?.let(::decodeStyle),
                    draftWords = savedState[KEY_WORDS],
                    draftEffects = savedState.get<String>(KEY_EFFECTS)?.let(::decodeEffects),
                    draftShape = savedState.get<String>(KEY_SHAPE)?.let { n -> CanvasShape.entries.firstOrNull { it.name == n } },
                    draftVoice = VoiceChoice.fromId(savedState.get<String>(KEY_VOICE)),
                    // As in the editor's voice sheet: off where the processor has no engine, or it did not load.
                    voiceAvailable = voiceTools.supported && voiceTools.engineWorks != false,
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
                val videos = presetStore.videosUsing(presetId)
                uiState.update { it.copy(definition = definition, status = status, videosUsing = videos) }
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

        /**
         * A new shape for the preset's videos (only while [PresetEditUi.canChangeShape]): its caption
         * place moves with it, as a video's does when its shape changes.
         */
        fun setShape(shape: CanvasShape) {
            val ui = uiState.value
            if (!ui.canChangeShape || shape == ui.shape) return
            val style = ui.style ?: return
            setStyle(style.copy(box = refitBox(style.box, ui.shape, shape)))
            savedState[KEY_SHAPE] = shape.name
            uiState.update { it.copy(draftShape = shape) }
        }

        /** The animation a new caption starts with ([kind] null for none): only start animations a caption may have. */
        fun setCaptionAnimation(kind: AnimKind?) {
            if (kind != null && (AnimSlot.START !in kind.slots || kind.photoOnly)) return
            val ui = uiState.value
            val effects = (ui.draftEffects ?: ui.preset?.effects ?: return).copy(textStart = kind)
            setEffects(effects)
        }

        /** The animation a new photo gets all through ([kind] null for none): only the ones a photo may have there. */
        fun setPhotoAnimation(kind: AnimKind?) {
            val ui = uiState.value
            val effects = ui.draftEffects ?: ui.preset?.effects ?: return
            withPhotoAnimation(effects, kind)?.let(::setEffects)
        }

        /** The transition new items get between them ([TransitionKind.NONE] for none), at [withTransition]'s length. */
        fun setTransition(kind: TransitionKind) {
            val ui = uiState.value
            val preset = ui.preset ?: return
            setEffects(withTransition(ui.draftEffects ?: preset.effects, preset.effects, kind))
        }

        /** Puts the effect defaults back to [effects] (the draft only), leaving the caption draft as it is. */
        fun revertEffects(effects: EffectDefaults?) = setEffects(effects)

        private fun setEffects(effects: EffectDefaults?) {
            savedState[KEY_EFFECTS] = effects?.let { ProjectCodec.json.encodeToString(EffectDefaults.serializer(), it) }
            uiState.update { it.copy(draftEffects = effects) }
        }

        /** The voice a new voice recording starts in ([VoiceChoice.NONE] for No change); kept as it is where voices are off. */
        fun setVoice(voice: VoiceChoice) {
            if (!uiState.value.voiceAvailable) return
            savedState[KEY_VOICE] = voice.id
            uiState.update { it.copy(draftVoice = voice) }
        }

        fun setWords(words: String) {
            savedState[KEY_WORDS] = words
            uiState.update { it.copy(draftWords = words) }
        }

        /** Puts the caption style, words and caption animation back to what they were (the draft only). */
        fun revertStyle(
            style: CaptionStyle?,
            words: String?,
            effects: EffectDefaults?,
        ) {
            savedState[KEY_STYLE] = style?.let { ProjectCodec.json.encodeToString(CaptionStyle.serializer(), it) }
            savedState[KEY_WORDS] = words
            uiState.update { it.copy(draftStyle = style, draftWords = words) }
            setEffects(effects)
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

        /** Deletes the preset; the videos made from it keep working. */
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

                        PresetDeleteResult.Unknown -> {
                            Unit
                        }
                    }
                    uiState.update { it.copy(busy = false) }
                }
        }

        fun dismissMessage() = uiState.update { it.copy(message = null) }

        private fun clearDraft() {
            savedState[KEY_NAME] = null
            savedState[KEY_STYLE] = null
            savedState[KEY_WORDS] = null
            savedState[KEY_SHAPE] = null
            savedState[KEY_EFFECTS] = null
            savedState[KEY_VOICE] = null
            uiState.update {
                it.copy(draftName = null, draftStyle = null, draftWords = null, draftShape = null, draftEffects = null, draftVoice = null)
            }
        }

        private fun loadStill(
            status: PresetStatus,
            preset: Preset,
        ) {
            val frame = (status.slots[PresetSlot.FRAME] as? SlotStatus.Ready)?.binding
            val stillKey = frame?.let { "${it.uri}:${it.sha256}:${it.sizeBytes}:${it.keyColor}:${preset.frame?.key}" }
            if (stillKey == stillFor) return
            stillFor = stillKey
            if (frame == null) {
                uiState.update { it.copy(frameStill = null) }
                return
            }
            val frameKey = preset.frame?.key ?: return
            val keySpec = frame.keyColor?.let { frameKey.copy(color = it) } ?: frameKey
            viewModelScope.launch {
                // The frame's picture keyed as the export keys it (cm-frame-area), with the colour it was set to.
                val sample = frameStills.still(frame.uri, STILL_AT_US.coerceAtMost(frame.durationUs / 2), STILL_WIDTH)
                val still = sample?.let { withContext(Dispatchers.Default) { keyedPicture(it, keySpec) } }
                uiState.update { it.copy(frameStill = still) }
            }
        }

        companion object {
            private const val KEY_NAME = "draftName"
            private const val KEY_STYLE = "draftStyle"
            private const val KEY_WORDS = "draftWords"
            private const val KEY_SHAPE = "draftShape"
            private const val KEY_EFFECTS = "draftEffects"
            private const val KEY_VOICE = "draftVoice"
            private const val STILL_AT_US = 1_000_000L
            private const val STILL_WIDTH = 640

            private fun decodeStyle(text: String): CaptionStyle? =
                runCatching { ProjectCodec.json.decodeFromString(CaptionStyle.serializer(), text) }.getOrNull()

            private fun decodeEffects(text: String): EffectDefaults? =
                runCatching { ProjectCodec.json.decodeFromString(EffectDefaults.serializer(), text) }.getOrNull()

            /**
             * [effects] with [kind] as the animation new photos get all through, or null when a photo
             * cannot have it there (a start or end animation, or one for text).
             */
            fun withPhotoAnimation(
                effects: EffectDefaults,
                kind: AnimKind?,
            ): EffectDefaults? =
                if (kind != null && (AnimSlot.DURING !in kind.slots || kind.textOnly)) null else effects.copy(photoDuring = kind)

            /**
             * [effects] with [kind] as the transition between new items ([TransitionKind.NONE] for none).
             * It keeps the length of the transition chosen so far, else of the one the preset has [saved]
             * (so None and back, before Save, keeps it), else the editor's usual length for a new transition.
             */
            fun withTransition(
                effects: EffectDefaults,
                saved: EffectDefaults,
                kind: TransitionKind,
            ): EffectDefaults {
                if (kind == TransitionKind.NONE) return effects.copy(mainTransition = null)
                val length = effects.mainTransition?.durationMs ?: saved.mainTransition?.durationMs ?: Transition.DEFAULT_MS
                return effects.copy(mainTransition = Transition(kind, length))
            }

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
                val shape = ui.draftShape?.takeIf { it != preset.shape }
                val effects = ui.draftEffects?.takeIf { it != preset.effects }
                val voice = ui.draftVoice?.takeIf { it != preset.voice }
                if (name == null && words == null && style == null && shape == null && effects == null && voice == null) return null
                return preset.copy(
                    displayName = name?.let(LocalizedText::same) ?: preset.displayName,
                    defaultText =
                        when {
                            words == null -> preset.defaultText
                            words.isEmpty() -> null
                            else -> LocalizedText.same(words)
                        },
                    caption = style ?: preset.caption,
                    shape = shape ?: preset.shape,
                    effects = effects ?: preset.effects,
                    voice = voice ?: preset.voice,
                )
            }
        }
    }
