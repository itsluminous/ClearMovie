package com.itsluminous.clearmovie.data.files

import java.text.Normalizer
import java.util.Locale

/**
 * File names made from text the app does not control: a project's name, or
 * the display name another app gives a shared file. Such text may hold `/`,
 * `..`, a backslash, control characters or any script, so it is never used
 * as a path. Every name written to disk is built here from a random or
 * validated prefix plus a cleaned-up stem, and always matches [SAFE_NAME]:
 * one path segment of ASCII letters, digits, `.`, `_` and `-` that starts
 * with a letter or digit. Display names are kept in the database, not on disk.
 */
object SafeFileName {
    /** One segment, never `.`, `..` or hidden, at most 128 characters. */
    val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    const val MAX_STEM = 40
    const val FALLBACK_STEM = "media"

    private val nonAscii = Regex("[^A-Za-z0-9._-]+")
    private val dots = Regex("\\.{2,}")
    private val marks = Regex("\\p{M}+")

    /**
     * Extensions the app writes, by MIME type. Anything else gets [UNKNOWN_EXTENSION],
     * so a hostile name such as `x.apk` or `x.html` never chooses one.
     */
    private val extensions =
        mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/gif" to "gif",
            "video/mp4" to "mp4",
            "video/3gpp" to "3gp",
            "video/webm" to "webm",
            "video/x-matroska" to "mkv",
            "video/quicktime" to "mov",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/flac" to "flac",
            "application/json" to "json",
        )
    const val UNKNOWN_EXTENSION = "bin"

    /**
     * A stem from [text]: accents folded, every run of other characters turned
     * into one `_`, `..` collapsed, leading and trailing `.`, `_` and `-`
     * dropped, cut to [maxLength]. Text with nothing usable (for example a
     * name only in Devanagari) gives [FALLBACK_STEM].
     */
    fun stem(
        text: String?,
        maxLength: Int = MAX_STEM,
    ): String {
        if (text.isNullOrBlank()) return FALLBACK_STEM
        val withoutExtension = text.substringBeforeLast('.', text).ifBlank { text }
        val folded = marks.replace(Normalizer.normalize(withoutExtension, Normalizer.Form.NFKD), "")
        val cleaned =
            dots
                .replace(nonAscii.replace(folded, "_"), ".")
                .trim('.', '_', '-')
                .take(maxLength)
                .trimEnd('.', '_', '-')
        return cleaned.ifEmpty { FALLBACK_STEM }
    }

    /** The extension for [mime], from the fixed list above. */
    fun extensionFor(mime: String?): String =
        mime
            ?.lowercase(Locale.ROOT)
            ?.substringBefore(';')
            ?.trim()
            ?.let(extensions::get) ?: UNKNOWN_EXTENSION

    /**
     * `<prefix>_<stem>.<ext>`. [prefix] must already be safe (a random token or
     * an id the model validated); it is checked, and so is the result.
     */
    fun build(
        prefix: String,
        text: String?,
        mime: String?,
    ): String {
        require(Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").matches(prefix)) { "unsafe prefix" }
        val name = "${prefix}_${stem(text)}.${extensionFor(mime)}"
        check(SAFE_NAME.matches(name)) { "unsafe file name" }
        return name
    }
}
