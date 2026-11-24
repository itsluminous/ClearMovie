package com.itsluminous.clearmovie.domain.voice

import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.VoicePhrase

/** A word the recognizer heard, with where it starts and ends in the recording. */
data class HeardWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * The phrases of a recording (cm-voice-choice): its words grouped into
 * phrases wherever the speaker paused for more than [GAP_MS], and at most
 * [MAX_PHRASE_MS] each, so a long stretch of speech is still voiced in step
 * with the picture. Each phrase starts at its first word.
 */
object Phrases {
    const val GAP_MS = 300L
    const val MAX_PHRASE_MS = 12_000L

    /** The gap a typed phrase leaves before the next one. */
    const val TYPED_GAP_MS = 300L

    fun fromWords(words: List<HeardWord>): List<VoicePhrase> {
        val groups = mutableListOf<MutableList<HeardWord>>()
        for (w in words.filter { it.text.isNotBlank() }.sortedBy { it.startMs }) {
            val current = groups.lastOrNull()
            val joins = current != null && w.startMs - current.last().endMs <= GAP_MS && w.endMs - current.first().startMs <= MAX_PHRASE_MS
            if (joins) current.add(w) else groups.add(mutableListOf(w))
        }
        return groups.map { g -> VoicePhrase(g.first().startMs.coerceAtLeast(0), clip(g.joinToString(" ") { it.text.trim() })) }
    }

    /**
     * Typed words as phrases: one per sentence (a full stop, question mark,
     * exclamation mark or Hindi danda ends one), or per line. Their times are
     * set when they are voiced ([placeTyped]).
     */
    fun fromTyped(text: String): List<VoicePhrase> =
        text
            .split('\n')
            .flatMap { line -> SENTENCE_END.split(line).map { it.trim() } }
            .filter { it.any(Char::isLetterOrDigit) }
            .map { VoicePhrase(0, clip(it)) }

    /** Typed phrases of [lengthsMs] each, one after another with [TYPED_GAP_MS] between: their start times. */
    fun placeTyped(lengthsMs: List<Long>): List<Long> {
        var at = 0L
        return lengthsMs.map { len ->
            val start = at
            at += len + TYPED_GAP_MS
            start
        }
    }

    /** Edited words of an existing phrase, kept to the stored limit; blank phrases are dropped by the caller. */
    fun clip(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(Limits.MAX_TEXT_LENGTH)

    private val SENTENCE_END = Regex("(?<=[.!?\u0964])\\s+")
}

/** How one phrase is fitted into the time it has before the next one starts. */
data class PhraseFit(
    /** The speed to voice it at: 1, or faster (at most [MAX_SPEED]) when it would run into the next phrase. */
    val speed: Float,
    /** True when it still does not fit at [MAX_SPEED]: it ends where the next phrase starts, with a short fade. */
    val cut: Boolean,
) {
    companion object {
        const val MAX_SPEED = 1.3f

        /** Silence kept before the next phrase. */
        const val BREATH_MS = 80L

        /** The fit for a phrase that takes [lengthMs] at normal speed, with [roomMs] before the next phrase. */
        fun of(
            lengthMs: Long,
            roomMs: Long,
        ): PhraseFit {
            if (lengthMs <= roomMs || roomMs <= 0) return PhraseFit(1f, lengthMs > roomMs && roomMs <= 0)
            val speed = (lengthMs.toFloat() / roomMs * 1.02f).coerceAtMost(MAX_SPEED)
            return PhraseFit(speed, lengthMs / speed > roomMs)
        }
    }
}
