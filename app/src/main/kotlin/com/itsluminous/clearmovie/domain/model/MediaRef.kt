package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The files a preset brings. Each is copied into app storage at setup. */
@Serializable
enum class PresetSlot {
    @SerialName("intro")
    INTRO,

    @SerialName("frame")
    FRAME,

    @SerialName("ending")
    ENDING,
}

/**
 * Where a clip's media lives (ARCHITECTURE section 9). The model never opens
 * a file: `data` resolves these. Because project JSON can be corrupted or
 * hand-edited, every reference is checked when it is built, so a bad one can
 * never reach the code that opens files.
 */
@Serializable
sealed interface MediaRef {
    /** A MediaStore (or Photo Picker) item, by `content://` uri. */
    @Serializable
    @SerialName("store")
    data class Store(
        val uri: String,
    ) : MediaRef {
        init {
            require(isSafeContentUri(uri)) { "Media uri must be a content:// uri" }
        }
    }

    /**
     * A file copied into this project's own media folder
     * (`filesDir/projects/<id>/media/`). [relPath] is relative to that folder
     * and may never leave it.
     */
    @Serializable
    @SerialName("copied")
    data class Copied(
        val relPath: String,
    ) : MediaRef {
        init {
            require(isSafeRelativePath(relPath)) { "Copied media path must stay inside the project folder" }
        }
    }

    /** One of the preset's bound files. */
    @Serializable
    @SerialName("preset")
    data class Preset(
        val slot: PresetSlot,
    ) : MediaRef

    companion object {
        const val MAX_URI_LENGTH = 2048
        const val MAX_PATH_LENGTH = 255
        private val SEGMENT = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$")

        /**
         * A `content://` uri with an authority, at most [MAX_URI_LENGTH]
         * characters, with no whitespace or control characters. `file://` and
         * every other scheme are refused, so a project can never point at an
         * arbitrary file path.
         */
        fun isSafeContentUri(uri: String): Boolean =
            uri.length <= MAX_URI_LENGTH &&
                uri.startsWith("content://") &&
                uri.length > "content://".length &&
                uri[10] != '/' &&
                uri.none { it.isWhitespace() || it.isISOControl() }

        /**
         * One to four segments separated by `/`. Each segment starts with a
         * letter, digit or `_` (so never `.`, `..` or a hidden name) and uses
         * only letters, digits, `.`, `_` and `-`. No absolute paths, no
         * backslashes, no empty segments.
         */
        fun isSafeRelativePath(path: String): Boolean {
            if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
            val segments = path.split('/')
            return segments.size <= 4 && segments.all { SEGMENT.matches(it) }
        }
    }
}
