package com.itsluminous.clearmovie.domain.model

/**
 * Guards for untrusted JSON text, checked before it reaches the parser.
 * kotlinx.serialization's tree reader recurses once per nested array, so
 * a file of a few hundred kilobytes of `[` would overflow the stack and
 * crash the app instead of being refused.
 */
internal object JsonGuard {
    /** Far deeper than any project or preset; real files nest about 8 levels. */
    const val MAX_DEPTH = 64

    /** True when arrays and objects in [text] nest deeper than [max], outside strings. */
    fun nestsDeeperThan(
        text: String,
        max: Int = MAX_DEPTH,
    ): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > max) return true
                ']', '}' -> depth--
            }
        }
        return false
    }
}
