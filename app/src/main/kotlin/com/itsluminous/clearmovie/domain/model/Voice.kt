package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The voices a voice item can be spoken in (cm-voice-choice). [NONE] is "No
 * change": the item plays the recording as it was made. Every other voice is
 * one of the bundled voice pack's speakers ([speaker], a Kokoro voice), so an
 * id sounds the same on every phone; a preset can store one as its default.
 * The labels live in the app's strings, keyed by [id].
 */
@Serializable
enum class VoiceChoice(
    val id: String,
    val gender: Gender?,
    val accent: Accent?,
    /** The speaker in the voice pack, or -1 for [NONE]. */
    val speaker: Int,
) {
    @SerialName("none")
    NONE("none", null, null, -1),

    @SerialName("british_male")
    BRITISH_MALE("british_male", Gender.MALE, Accent.BRITISH, 26),

    @SerialName("british_female")
    BRITISH_FEMALE("british_female", Gender.FEMALE, Accent.BRITISH, 21),

    @SerialName("indian_male")
    INDIAN_MALE("indian_male", Gender.MALE, Accent.INDIAN, 33),

    @SerialName("indian_female")
    INDIAN_FEMALE("indian_female", Gender.FEMALE, Accent.INDIAN, 31),

    @SerialName("american_male")
    AMERICAN_MALE("american_male", Gender.MALE, Accent.AMERICAN, 16),

    @SerialName("american_female")
    AMERICAN_FEMALE("american_female", Gender.FEMALE, Accent.AMERICAN, 3),
    ;

    enum class Gender { MALE, FEMALE }

    /** Indian voices speak English and Hindi; British and American voices speak English only. */
    enum class Accent(
        val languages: Set<SpokenLanguage>,
    ) {
        BRITISH(setOf(SpokenLanguage.ENGLISH)),
        INDIAN(setOf(SpokenLanguage.ENGLISH, SpokenLanguage.HINDI)),
        AMERICAN(setOf(SpokenLanguage.ENGLISH)),
    }

    /** Whether this voice can say words in [language] ("No change" always can: it says nothing new). */
    fun speaks(language: SpokenLanguage): Boolean = accent?.languages?.contains(language) ?: true

    companion object {
        /** The voices to offer, in menu order: No change first. */
        val MENU: List<VoiceChoice> = entries.toList()

        /** The voice with [id], or null for an id this version does not know. */
        fun fromId(id: String?): VoiceChoice? = entries.firstOrNull { it.id == id }

        /** The voices that can say words in [language], No change first. */
        fun forLanguage(language: SpokenLanguage): List<VoiceChoice> = MENU.filter { it.speaks(language) }
    }
}

/** The language the words of a voice item are in. Hinglish words count as Hindi. */
@Serializable
enum class SpokenLanguage(
    val id: String,
) {
    @SerialName("en")
    ENGLISH("en"),

    @SerialName("hi")
    HINDI("hi"),
    ;

    companion object {
        fun fromId(id: String?): SpokenLanguage? = entries.firstOrNull { it.id == id }
    }
}

/** Where a voice item's words came from. */
@Serializable
enum class WordsSource {
    /** Said into the microphone, then recognized (and checked by the user). */
    @SerialName("spoken")
    SPOKEN,

    /** Typed by the user: there is no recording, so "No change" does not apply. */
    @SerialName("typed")
    TYPED,
}

/** One phrase of a voice item: its words, said from [startMs] of the item's own file. */
@Serializable
data class VoicePhrase(
    val startMs: Long,
    val text: String,
)

/**
 * A voice item spoken in another voice (cm-voice-choice). [phrases] are the
 * words, each at its time in the item's file; [generated] is the file made
 * by speaking them in [voice], as long as the item's own file, so trims,
 * splits and handles stay right. With [voice] "No change" there is no
 * [generated] file and the item plays its recording: the words are kept, so
 * choosing a voice again need not recognize them again. The recording itself
 * ([AudioClip.media]) is never touched.
 */
@Serializable
data class VoiceOver(
    val voice: VoiceChoice,
    val language: SpokenLanguage,
    val source: WordsSource,
    val phrases: List<VoicePhrase>,
    val generated: MediaRef.Copied? = null,
) {
    /** All the words, phrase after phrase. */
    val text: String get() = phrases.joinToString(" ") { it.text }

    companion object {
        /** At most this many phrases, and [Limits.MAX_TEXT_LENGTH] letters in each (the JSON is untrusted). */
        const val MAX_PHRASES = 500
    }
}

/** The file this sound plays: the voice made for it when it has one, otherwise its own. */
val AudioClip.playedMedia: MediaRef get() = voiceOver?.generated ?: media

/**
 * Sets the words and voice of voice item [id] (one undo step). A null
 * [voiceOver] makes it a plain recording again. Refused (unchanged) for an
 * item that is not a sound, or a typed item set to "No change".
 */
fun Project.setVoiceOver(
    id: ClipId,
    voiceOver: VoiceOver?,
): Project {
    val clip = clip(id) as? AudioClip ?: return this
    if (voiceOver != null && voiceOver.source == WordsSource.TYPED && voiceOver.voice == VoiceChoice.NONE) return this
    if (clip.voiceOver?.source == WordsSource.TYPED && voiceOver == null) return this
    return replaceClip(clip.copy(voiceOver = voiceOver))
}

/** "No change": voice item [id] plays its recording again; its words are kept. */
fun Project.useOwnVoice(id: ClipId): Project {
    val clip = clip(id) as? AudioClip ?: return this
    val over = clip.voiceOver ?: return this
    if (over.source == WordsSource.TYPED) return this
    return replaceClip(clip.copy(voiceOver = over.copy(voice = VoiceChoice.NONE, generated = null)))
}

/**
 * A typed voice item spoken again (another voice or other words): it plays
 * [media], [lengthMs] long, from its own start, cut where the next item on
 * its row or the video ends. Refused (unchanged) for an item that is not typed.
 */
fun Project.setTypedVoice(
    id: ClipId,
    media: MediaRef.Copied,
    lengthMs: Long,
    voiceOver: VoiceOver,
): Project {
    val clip = clip(id) as? AudioClip ?: return this
    if (clip.voiceOver?.source != WordsSource.TYPED || voiceOver.source != WordsSource.TYPED ||
        voiceOver.voice == VoiceChoice.NONE
    ) {
        return this
    }
    val track = trackOf(id) ?: return this
    val next = track.clips.filter { it.id != id && it.startMs >= clip.startMs }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
    val videoEnd = mainLayout().lastOrNull()?.endMs ?: Long.MAX_VALUE
    val length = minOf(lengthMs, next - clip.startMs, videoEnd - clip.startMs, Limits.MAX_MEDIA_MS)
    if (length < Limits.MIN_CLIP_MS) return this
    return replaceClip(
        clip.copy(
            media = media,
            sourceDurationMs = lengthMs,
            trimStartMs = 0,
            trimEndMs = length,
            durationMs = length,
            fadeInMs = 0,
            fadeOutMs = 0,
            voiceOver = voiceOver.copy(generated = null),
        ),
    )
}

private fun Project.replaceClip(clip: AudioClip): Project {
    val track = trackOf(clip.id) ?: return this
    return withTrackClips(track.id, track.clips.map { if (it.id == clip.id) clip else it }).normalized()
}

/** Why a [VoiceOver] read from storage is not valid, or null when it is. */
internal fun VoiceOver.problem(clip: AudioClip): String? =
    when {
        phrases.size > VoiceOver.MAX_PHRASES -> "too many phrases"
        phrases.any { it.text.length > Limits.MAX_TEXT_LENGTH } -> "phrase too long"
        phrases.any { it.startMs !in 0..clip.sourceDurationMs } -> "phrase outside the item"
        phrases.zipWithNext().any { (a, b) -> b.startMs < a.startMs } -> "phrases out of order"
        voice == VoiceChoice.NONE && generated != null -> "a voice file with No change"
        voice != VoiceChoice.NONE && !voice.speaks(language) -> "a voice that cannot say the words"
        source == WordsSource.TYPED && voice == VoiceChoice.NONE -> "typed words with No change"
        generated != null && !VoiceRecordingFiles.isRecording(generated.relPath) -> "voice file name"
        else -> null
    }
