package com.itsluminous.clearmovie.domain.export

import java.text.Normalizer

/**
 * The three export choices (ARCHITECTURE section 7, Export): Small is
 * 480p30 at about 2.5 Mb/s, Good 720p30 at about 6 Mb/s and Best 1080p30 at
 * about 12 Mb/s, each with AAC at 192 kb/s. The user sees only the friendly
 * name and the estimated size, never these numbers.
 */
enum class ExportQuality(
    val height: Int,
    val videoBitrate: Int,
    val audioBitrate: Int = AUDIO_BITRATE,
) {
    SMALL(480, 2_500_000),
    GOOD(720, 6_000_000),
    BEST(1080, 12_000_000),
    ;

    /**
     * About how many bytes an export of [durationUs] takes: both bit rates
     * over the length, plus [CONTAINER_OVERHEAD] for the MP4 boxes. Hardware
     * encoders land within about 10% of the requested rate on still-heavy
     * videos, so the estimate errs high.
     */
    fun estimatedBytes(durationUs: Long): Long {
        val seconds = durationUs.coerceAtLeast(0) / 1_000_000.0
        return ((videoBitrate + audioBitrate) / 8.0 * seconds * CONTAINER_OVERHEAD).toLong()
    }

    companion object {
        const val AUDIO_BITRATE = 192_000
        const val CONTAINER_OVERHEAD = 1.03

        /** The order the sheet shows them in, and the one it suggests. */
        val shown: List<ExportQuality> = listOf(BEST, GOOD, SMALL)
        val suggested: ExportQuality = GOOD

        /** [name] back to a quality; null for anything else (a value from WorkManager input data). */
        fun parse(name: String?): ExportQuality? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Where an export writes while it runs (ARCHITECTURE section 12, review F10).
 * Normally into a temporary file that is then copied into the gallery, which
 * needs room for both at once ([TEMP_FACTOR] times the estimate plus
 * [TEMP_MARGIN_BYTES]). With less room it writes straight into the gallery
 * entry, which stays hidden until it is finished ([DIRECT_FACTOR]). With
 * less still, it says how much to free.
 */
sealed interface ExportSpace {
    data object TempThenCopy : ExportSpace

    data object Direct : ExportSpace

    /** [missingBytes] more must be freed before even the direct path fits. */
    data class NotEnough(
        val missingBytes: Long,
    ) : ExportSpace

    companion object {
        const val TEMP_FACTOR = 2.2
        const val TEMP_MARGIN_BYTES = 200L * 1024 * 1024
        const val DIRECT_FACTOR = 1.2

        fun decide(
            estimatedBytes: Long,
            freeBytes: Long,
        ): ExportSpace {
            val estimate = estimatedBytes.coerceAtLeast(0)
            val free = freeBytes.coerceAtLeast(0)
            val temp = (estimate * TEMP_FACTOR).toLong() + TEMP_MARGIN_BYTES
            val direct = (estimate * DIRECT_FACTOR).toLong()
            return when {
                free >= temp -> TempThenCopy
                free >= direct -> Direct
                else -> NotEnough(direct - free)
            }
        }
    }
}

/**
 * The gallery file name of an export, from the project's name, which the
 * user typed and the app does not control. MediaStore takes a display name,
 * not a path, but the name is still cleaned so it can never act as one, or
 * look like something it is not:
 * - path and reserved characters (`/ \ : * ? " < > |`) become a space;
 * - control and invisible formatting characters (including right-to-left
 *   overrides, U+202E, that could make a name end in a fake `.mp4`) are dropped;
 * - leading dots and spaces go (no hidden file, no `..`), and so does a
 *   trailing dot;
 * - runs of spaces collapse; the result is cut to [MAX_LENGTH] characters
 *   without splitting a letter and its marks.
 * Letters of every script stay, so a Hindi name stays Hindi in the gallery.
 * An unnamed draft, or a name with nothing left, gets [fallback]. The
 * extension is always `.mp4`, chosen here, never by the name.
 */
object ExportFileName {
    const val MAX_LENGTH = 60
    const val EXTENSION = ".mp4"

    private val reserved = Regex("""[/\\:*?"<>|]""")
    private val spaces = Regex("""\s+""")

    fun displayName(
        projectName: String?,
        fallback: String,
    ): String = (stem(projectName) ?: stem(fallback) ?: FALLBACK_STEM) + EXTENSION

    /** The cleaned name without an extension, or null when nothing usable is left. */
    fun stem(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val normal = Normalizer.normalize(text, Normalizer.Form.NFC)
        val visible =
            buildString {
                var i = 0
                while (i < normal.length) {
                    val cp = normal.codePointAt(i)
                    i += Character.charCount(cp)
                    when {
                        reserved.matches(String(Character.toChars(cp))) -> append(' ')
                        Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> append(' ')
                        isInvisible(cp) -> Unit
                        else -> appendCodePoint(cp)
                    }
                }
            }
        val trimmed =
            spaces
                .replace(visible, " ")
                .trim()
                .trimStart('.', ' ')
                .let(::cut)
                .trimEnd('.', ' ')
        return trimmed.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** At most [MAX_LENGTH] characters, never cutting a letter off from its marks (a matra) or a surrogate pair in two. */
    private fun cut(text: String): String {
        if (text.codePointCount(0, text.length) <= MAX_LENGTH) return text
        var end = text.offsetByCodePoints(0, MAX_LENGTH)
        // The next character is a mark of the letter before it: cut before that letter instead.
        while (end > 0 && isMark(text.codePointAt(end))) end = text.offsetByCodePoints(end, -1)
        return text.substring(0, end)
    }

    private fun isMark(cp: Int): Boolean =
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }

    /** Control, format (bidi overrides, zero-width), private-use, unassigned and lone surrogate code points. */
    private fun isInvisible(cp: Int): Boolean =
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> true
            else -> false
        }

    const val FALLBACK_STEM = "ClearMovie"
}

/**
 * "About N minutes left" from how long the export has run and how far it
 * is: null until there is enough to go on (at least [MIN_PERCENT] done and
 * [MIN_ELAPSED_MS] gone), so the first guess is not wild.
 */
object ExportTimeLeft {
    const val MIN_PERCENT = 3
    const val MIN_ELAPSED_MS = 3_000L

    fun estimateMs(
        elapsedMs: Long,
        percent: Int,
    ): Long? {
        if (percent !in MIN_PERCENT..99 || elapsedMs < MIN_ELAPSED_MS) return null
        return elapsedMs * (100 - percent) / percent
    }

    /** Whole minutes to show, rounded up, at least 1. */
    fun minutes(ms: Long): Int = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)
}
