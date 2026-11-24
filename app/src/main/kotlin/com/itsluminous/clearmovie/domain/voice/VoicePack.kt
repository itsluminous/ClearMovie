package com.itsluminous.clearmovie.domain.voice

import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The voice pack (cm-voice-choice): the engine, its voices and the speech
 * recognizers, kept out of the app because they are about 150 MB. Its files
 * live in one folder of the app's GitHub repository, `voice-packs/v<version>/`,
 * each a zip under GitHub's 100 MB limit, listed with its size and SHA-256 in
 * that folder's `manifest.json`. The app carries the same manifest and takes
 * in a file only when its bytes match it.
 */
@Serializable
data class VoicePackManifest(
    val pack: String,
    val version: Int,
    val files: List<VoicePackFile>,
) {
    /** The files a phone with [abi] needs to voice words, and to recognize speech in [speech] (null: typed words only). */
    fun needed(
        abi: String,
        speech: SpokenLanguage?,
    ): List<VoicePackFile> =
        files.filter { f ->
            when (f.part) {
                VoicePackFile.VOICES -> true
                VoicePackFile.ENGINE -> f.abi == abi
                VoicePackFile.SPEECH_EN -> speech == SpokenLanguage.ENGLISH
                VoicePackFile.SPEECH_HI -> speech == SpokenLanguage.HINDI
                else -> false
            }
        }

    /** The listed file with these bytes' [sha256], if any. */
    fun bySha(sha256: String): VoicePackFile? = files.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }

    /** Whether the phone's processor type has an engine in this pack. */
    fun supports(abi: String): Boolean = files.any { it.part == VoicePackFile.ENGINE && it.abi == abi }

    companion object {
        const val NAME = "clearmovie-voice-pack"

        /** The folder page that lists the pack's files. */
        fun folderUrl(version: Int): String = "https://github.com/itsluminous/ClearMovie/tree/main/voice-packs/v$version"

        /** A file's direct download link. */
        fun downloadUrl(
            version: Int,
            file: String,
        ): String = "https://github.com/itsluminous/ClearMovie/raw/main/voice-packs/v$version/$file"

        private val json = Json { ignoreUnknownKeys = true }

        /** The manifest in [text], or null when it is not one. */
        fun parse(text: String): VoicePackManifest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { m ->
                m.pack == NAME && m.version > 0 && m.files.isNotEmpty() && m.files.all { it.valid() }
            }
    }
}

/** One zip of the voice pack. */
@Serializable
data class VoicePackFile(
    val file: String,
    val part: String,
    val bytes: Long,
    val unpackedBytes: Long,
    val entries: Int,
    val sha256: String,
    val abi: String? = null,
) {
    fun valid(): Boolean =
        FILE_NAME.matches(file) && part in PARTS && bytes in 1..MAX_FILE_BYTES && unpackedBytes in 1..MAX_UNPACKED_BYTES &&
            entries in 1..MAX_ENTRIES && SHA.matches(sha256) && (part != ENGINE || abi != null)

    companion object {
        const val VOICES = "voices"
        const val ENGINE = "engine"
        const val SPEECH_EN = "speech-en"
        const val SPEECH_HI = "speech-hi"
        private val PARTS = setOf(VOICES, ENGINE, SPEECH_EN, SPEECH_HI)

        /** GitHub refuses files over 100 MB. */
        const val MAX_FILE_BYTES = 100L * 1000 * 1000
        const val MAX_UNPACKED_BYTES = 400L * 1000 * 1000
        const val MAX_ENTRIES = 2000
        private val FILE_NAME = Regex("^[a-z0-9_-]{1,40}\\.zip$")
        private val SHA = Regex("^[0-9a-fA-F]{64}$")

        /** The folder each part unpacks into, inside the pack's own folder. */
        fun folderOf(part: String): String =
            when (part) {
                VOICES -> "tts"
                ENGINE -> "lib"
                SPEECH_EN -> "asr/en"
                SPEECH_HI -> "asr/hi"
                else -> error("unknown part")
            }

        /**
         * Whether a zip entry may be written: a relative path of plain names
         * under the part's own folder ([folderOf]), never "..", an absolute
         * path, a backslash or an empty name, so nothing lands outside it.
         */
        fun safeEntry(
            part: String,
            name: String,
        ): Boolean {
            if (name.isEmpty() || name.length > 200 || name.startsWith("/") || '\\' in name || '\u0000' in name) return false
            val segments = name.trimEnd('/').split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return false
            val folder = folderOf(part).split('/')
            return segments.size > folder.size && segments.take(folder.size) == folder
        }
    }
}
