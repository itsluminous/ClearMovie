package com.itsluminous.clearmovie.domain.model

/**
 * Which characters user text (project names, captions, card text, a preset's
 * name and caption) may contain. Control characters are refused (a newline
 * only where the text can have lines), and so are invisible characters that
 * reorder or hide text: the bidirectional overrides and isolates, the zero
 * width space and the byte-order mark. The zero width joiner and non-joiner
 * stay allowed, because Devanagari spelling uses them.
 */
object TextRules {
    private val invisible: Set<Char> =
        buildSet {
            addAll('\u202A'..'\u202E')
            addAll('\u2066'..'\u2069')
            add('\u200B')
            add('\u2060')
            add('\uFEFF')
        }

    fun isAllowed(
        text: String,
        allowNewlines: Boolean,
    ): Boolean = text.none { c -> (c.isISOControl() && !(allowNewlines && c == '\n')) || c in invisible }

    /**
     * A project name: one line, allowed characters, at least one letter or
     * digit, and at most [Limits.MAX_NAME_LENGTH] characters. It is for display
     * only; anything that turns it into a file name must still make it safe.
     */
    fun isValidName(name: String): Boolean =
        name.length <= Limits.MAX_NAME_LENGTH && isAllowed(name, allowNewlines = false) && name.any { it.isLetterOrDigit() }
}
