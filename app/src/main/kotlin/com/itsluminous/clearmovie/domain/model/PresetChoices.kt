package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the user chose for one part of the preset on the review screen, for
 * this video only (ARCHITECTURE section 11.1, preset review).
 */
@Serializable
sealed interface PartChoice {
    /** The preset's own file. */
    @Serializable
    @SerialName("preset")
    data object UsePreset : PartChoice

    /** Left out of this video. */
    @Serializable
    @SerialName("skip")
    data object Skip : PartChoice

    /** The user's own file instead: [media] is [durationMs] long; [name] is the file's name, for display. */
    @Serializable
    @SerialName("replace")
    data class Replace(
        val media: MediaRef,
        val durationMs: Long,
        val name: String? = null,
    ) : PartChoice {
        companion object {
            const val MAX_FILE_NAME = 255
        }

        init {
            require(media !is MediaRef.Preset) { "A replacement is the user's own file" }
            require(durationMs in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) { "A replacement needs a usable length" }
            require(name == null || name.length <= MAX_FILE_NAME) { "Name too long" }
        }
    }
}

/** The review screen's choices for the intro, the frame and the ending. */
@Serializable
data class PresetChoices(
    val intro: PartChoice = PartChoice.UsePreset,
    val frame: PartChoice = PartChoice.UsePreset,
    val ending: PartChoice = PartChoice.UsePreset,
) {
    operator fun get(slot: PresetSlot): PartChoice =
        when (slot) {
            PresetSlot.INTRO -> intro
            PresetSlot.FRAME -> frame
            PresetSlot.ENDING -> ending
        }

    fun with(
        slot: PresetSlot,
        choice: PartChoice,
    ): PresetChoices =
        when (slot) {
            PresetSlot.INTRO -> copy(intro = choice)
            PresetSlot.FRAME -> copy(frame = choice)
            PresetSlot.ENDING -> copy(ending = choice)
        }

    /**
     * The files a new project plays, from the preset's [bound] lengths: a
     * skipped part has no length (so it is left out), a replaced one plays
     * the user's file with that file's length.
     */
    fun applyTo(bound: PresetMedia): PresetMedia {
        fun ms(
            choice: PartChoice,
            boundMs: Long?,
        ): Long? =
            when (choice) {
                PartChoice.UsePreset -> boundMs
                PartChoice.Skip -> null
                is PartChoice.Replace -> choice.durationMs
            }

        fun ref(
            choice: PartChoice,
            slot: PresetSlot,
        ): MediaRef = (choice as? PartChoice.Replace)?.media ?: MediaRef.Preset(slot)
        return PresetMedia(
            introMs = ms(intro, bound.introMs),
            frameMs = ms(frame, bound.frameMs),
            endingMs = ms(ending, bound.endingMs),
            intro = ref(intro, PresetSlot.INTRO),
            frame = ref(frame, PresetSlot.FRAME),
            ending = ref(ending, PresetSlot.ENDING),
        )
    }
}

/**
 * Rebuilds a project that has just been made from [preset] with new review
 * [choices], keeping its id and every photo, video and card already on MAIN
 * (the review screen runs before anything else is edited).
 */
fun Project.withPresetChoices(
    preset: Preset,
    bound: PresetMedia,
    choices: PresetChoices,
    ids: IdSource = RandomIds,
): Project {
    val content = mainClips.filter { it is PhotoClip || it is VideoClip || it is CardClip }
    val fresh = ProjectFactory.fromPreset(id, preset, choices.applyTo(bound), ids).copy(name = name)
    return if (content.isEmpty()) fresh else fresh.addToMain(content, ids = ids)
}
