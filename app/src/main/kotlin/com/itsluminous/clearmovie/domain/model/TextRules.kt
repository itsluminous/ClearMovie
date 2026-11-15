package com.itsluminous.clearmovie.domain.model

/**
 * Which characters user text (project names, captions, card text, a preset's
 * name and caption) may contain. Control characters are refused (a newline
 * only where the text can have lines), and so are invisible characters that
 * reorder, split or hide text: the bidirectional overrides, isolates and
 * marks, the zero width space, the byte-order mark, the line and paragraph
 * separators and the annotation marks. The zero width joiner and non-joiner
 * stay allowed, because Devanagari spelling uses them.
 */
object TextRules {
    private val invisible: Set<Char> =
        buildSet {
            addAll('\u202A'..'\u202E')
            addAll('\u2066'..'\u2069')
            addAll(listOf('\u200B', '\u200E', '\u200F', '\u061C', '\u2060', '\uFEFF'))
            // Line and paragraph separators, and the interlinear annotation marks.
            addAll(listOf('\u2028', '\u2029', '\uFFF9', '\uFFFA', '\uFFFB'))
        }

    fun isAllowed(
        text: String,
        allowNewlines: Boolean,
    ): Boolean = text.none { c -> (c.isISOControl() && !(allowNewlines && c == '\n')) || c in invisible }

    /**
     * A project name: one line of allowed characters with at least one that
     * shows (not only spaces, joiners or combining marks), at most
     * [Limits.MAX_NAME_LENGTH] characters. Emoji and punctuation are fine. It
     * is for display only; anything that turns it into a file name must still
     * make it safe.
     */
    fun isValidName(name: String): Boolean =
        name.length <= Limits.MAX_NAME_LENGTH && isAllowed(name, allowNewlines = false) && name.codePoints().anyMatch(::isVisible)

    /** A code point that shows something: assigned, and not a control, format, space or combining character. */
    private fun isVisible(codePoint: Int): Boolean =
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR, Character.UNASSIGNED, Character.NON_SPACING_MARK,
            -> false

            else -> true
        }

    /** Caption or card text: lines allowed, at most [Limits.MAX_TEXT_LENGTH] characters. */
    fun isValidText(text: String): Boolean = text.length <= Limits.MAX_TEXT_LENGTH && isAllowed(text, allowNewlines = true)
}
