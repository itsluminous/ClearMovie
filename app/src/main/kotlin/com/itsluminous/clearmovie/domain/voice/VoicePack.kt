package com.itsluminous.clearmovie.domain.voice

import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

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
    /** The repository folder under `voice-packs/` (cm-hindi-voices); null: `v<version>`. */
    val folder: String? = null,
) {
    /** This manifest's own folder under `voice-packs/`. */
    val folderName: String get() = folder ?: "v$version"

    /** The folder under `voice-packs/` that [f] is downloaded from. */
    fun folderOf(f: VoicePackFile): String = f.folder ?: folderName

    /**
     * This pack with an add-on pack's files (cm-hindi-voices: Hindi HD), each
     * marked with the add-on's folder; they unpack into this pack's folder on
     * the phone, so one Remove takes everything away.
     */
    operator fun plus(addOn: VoicePackManifest): VoicePackManifest {
        // An add-on brings models and voices, never native code, and never a file or part this pack has.
        require(addOn.pack == pack && addOn.folderName != folderName) { "not an add-on of this pack" }
        require(addOn.files.none { it.part == VoicePackFile.ENGINE }) { "an add-on may not bring an engine" }
        require(addOn.files.none { a -> files.any { it.file == a.file || it.part == a.part } }) { "an add-on file clashes with the pack" }
        return copy(files = files + addOn.files.map { it.copy(folder = it.folder ?: addOn.folderName) })
    }

    /**
     * The Hindi speech files for [model]: the small recognizer, or Hindi HD (its
     * model and the voices file with the extra Indian voices), with its rescoring
     * files for [HindiModel.FULL]. A pack without Hindi HD gives the small one.
     */
    fun hindiSpeech(model: HindiModel): List<VoicePackFile> {
        val hd = files.filter { it.part == VoicePackFile.VOICES_HI_HD || it.part == VoicePackFile.SPEECH_HI_HD }
        val full = files.filter { it.part == VoicePackFile.SPEECH_HI_HD_RESCORE }
        return when {
            model == HindiModel.SMALL || hd.size < 2 -> files.filter { it.part == VoicePackFile.SPEECH_HI }
            model == HindiModel.FULL -> hd + full
            else -> hd
        }
    }

    /** Whether [f] is for a phone with [abi] whose memory runs [model]: the engine of its processor type, Hindi HD only where it fits. */
    fun fitsPhone(
        f: VoicePackFile,
        abi: String,
        model: HindiModel,
    ): Boolean =
        when (f.part) {
            VoicePackFile.ENGINE -> f.abi == abi

            // The voices file is small (4 MB) and does not change Kokoro's memory: every phone takes it.
            VoicePackFile.VOICES_HI_HD -> true

            VoicePackFile.SPEECH_HI_HD -> model != HindiModel.SMALL

            VoicePackFile.SPEECH_HI_HD_RESCORE -> model == HindiModel.FULL

            else -> true
        }

    /** The listed split piece with these bytes' [sha256], and the file it belongs to, if any. */
    fun pieceBySha(sha256: String): Pair<VoicePackFile, VoicePackPiece>? =
        files.firstNotNullOfOrNull { f -> f.pieces.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }?.let { f to it } }

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

    /** The listed file with these bytes' [sha256], if any (a split file is taken in by its pieces only). */
    fun bySha(sha256: String): VoicePackFile? = files.firstOrNull { it.pieces.isEmpty() && it.sha256.equals(sha256, ignoreCase = true) }

    /** Whether the phone's processor type has an engine in this pack. */
    fun supports(abi: String): Boolean = files.any { it.part == VoicePackFile.ENGINE && it.abi == abi }

    companion object {
        const val NAME = "clearmovie-voice-pack"

        /** The page that lists the files of pack folder [folder] (`v1`, `hindi-hd-v1`). */
        fun folderUrl(folder: String): String = "https://github.com/itsluminous/ClearMovie/tree/${ref(folder)}/voice-packs/$folder"

        /** A file's link on its folder's page (the browser fallback). */
        fun downloadUrl(
            folder: String,
            file: String,
        ): String = "https://github.com/itsluminous/ClearMovie/raw/${ref(folder)}/voice-packs/$folder/$file"

        /** The folder's pinned commit (the same bytes the app downloads), or main for a folder without one. */
        private fun ref(folder: String): String = VoicePackSource.COMMITS[folder] ?: "main"

        private val json = Json { ignoreUnknownKeys = true }

        /** The manifest in [text], or null when it is not one. */
        fun parse(text: String): VoicePackManifest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { m ->
                m.pack == NAME && m.version > 0 && m.files.isNotEmpty() && m.files.all { it.valid() } &&
                    (m.folder == null || VoicePackFile.FOLDER.matches(m.folder))
            }
    }
}

/**
 * One zip of the voice pack. A zip over GitHub's 100 MB limit (cm-hindi-voices)
 * is stored as [pieces]: its bytes cut in order into files `<file>.001`,
 * `<file>.002` and so on, each with its own size and SHA-256; the phone takes
 * each piece in only when it matches, then unzips the pieces read one after
 * another. [sha256] is the whole zip's.
 */
@Serializable
data class VoicePackFile(
    val file: String,
    val part: String,
    val bytes: Long,
    val unpackedBytes: Long,
    val entries: Int,
    val sha256: String,
    val abi: String? = null,
    /** The folder under `voice-packs/` this file is in; null: its manifest's own. */
    val folder: String? = null,
    val pieces: List<VoicePackPiece> = emptyList(),
) {
    /** The files to download for this zip: itself, or its pieces. */
    val downloads: List<String> get() = if (pieces.isEmpty()) listOf(file) else pieces.map { it.file }

    fun valid(): Boolean =
        FILE_NAME.matches(file) && part in PARTS && unpackedBytes in 1..maxUnpacked(part) &&
            entries in 1..MAX_ENTRIES && SHA.matches(sha256) && (part != ENGINE || abi != null) &&
            (folder == null || FOLDER.matches(folder)) &&
            if (pieces.isEmpty()) {
                bytes in 1..MAX_FILE_BYTES
            } else {
                bytes in 1..MAX_SPLIT_BYTES && pieces.size <= MAX_PIECES && pieces.sumOf { it.bytes } == bytes &&
                    pieces.withIndex().all { (i, p) ->
                        p.file == String.format(Locale.ROOT, "%s.%03d", file, i + 1) && p.bytes in 1..MAX_FILE_BYTES &&
                            SHA.matches(p.sha256)
                    }
            }

    companion object {
        const val VOICES = "voices"
        const val ENGINE = "engine"
        const val SPEECH_EN = "speech-en"
        const val SPEECH_HI = "speech-hi"

        /** Hindi HD (cm-hindi-voices): the voices file with two more Indian voices, the big Hindi recognizer, and its rescoring files. */
        const val VOICES_HI_HD = "voices-hi-hd"
        const val SPEECH_HI_HD = "speech-hi-hd"
        const val SPEECH_HI_HD_RESCORE = "speech-hi-hd-rescore"
        private val PARTS = setOf(VOICES, ENGINE, SPEECH_EN, SPEECH_HI, VOICES_HI_HD, SPEECH_HI_HD, SPEECH_HI_HD_RESCORE)
        private val HD_PARTS = setOf(SPEECH_HI_HD, SPEECH_HI_HD_RESCORE)

        /** Hindi HD's own parts. */
        val HINDI_HD_PARTS = setOf(VOICES_HI_HD, SPEECH_HI_HD, SPEECH_HI_HD_RESCORE)

        /** GitHub refuses files over 100 MB. */
        const val MAX_FILE_BYTES = 100L * 1000 * 1000
        const val MAX_UNPACKED_BYTES = 400L * 1000 * 1000

        /** Hindi HD's recognizer and its rescoring files unpack to about 0.9 and 2 GB. */
        const val MAX_HD_UNPACKED_BYTES = 2_500L * 1000 * 1000

        /** A split zip: at most this many bytes, in at most [MAX_PIECES] pieces. */
        const val MAX_SPLIT_BYTES = 2_000L * 1000 * 1000
        const val MAX_PIECES = 40
        const val MAX_ENTRIES = 2000
        private val FILE_NAME = Regex("^[a-z0-9_-]{1,40}\\.zip$")
        private val SHA = Regex("^[0-9a-fA-F]{64}$")
        val FOLDER = Regex("^(v[1-9][0-9]{0,3}|hindi-hd-v[1-9][0-9]{0,3})$")

        private fun maxUnpacked(part: String) = if (part in HD_PARTS) MAX_HD_UNPACKED_BYTES else MAX_UNPACKED_BYTES

        /** The folders a part unpacks into, inside the pack's own folder; Hindi HD's two speech parts share one model folder. */
        fun foldersOf(part: String): List<String> =
            when (part) {
                SPEECH_HI_HD -> listOf("am", "conf", "graph", "ivector").map { "$HD_MODEL/$it" }
                SPEECH_HI_HD_RESCORE -> listOf("rescore", "rnnlm").map { "$HD_MODEL/$it" }
                else -> listOf(folderOf(part))
            }

        /** The folder Hindi HD's recognizer is read from. */
        const val HD_MODEL = "asr/hi-hd"

        /** The folder a one-folder part unpacks into, inside the pack's own folder. */
        fun folderOf(part: String): String =
            when (part) {
                VOICES -> "tts"
                ENGINE -> "lib"
                SPEECH_EN -> "asr/en"
                SPEECH_HI -> "asr/hi"
                VOICES_HI_HD -> "tts-hd"
                else -> error("a part of more than one folder")
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
            return foldersOf(part).any { f ->
                val folder = f.split('/')
                segments.size > folder.size && segments.take(folder.size) == folder
            }
        }
    }
}

/** One piece of a split zip ([VoicePackFile.pieces]). */
@Serializable
data class VoicePackPiece(
    val file: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * The best Hindi recognizer a phone's memory runs (cm-hindi-voices): the small
 * one, Hindi HD without its rescoring files (about 0.9 GB in use), or all of
 * it (about 3.5 GB in use, so only on phones sold with 8 GB).
 */
enum class HindiModel {
    SMALL,
    LIGHT,
    FULL,
    ;

    companion object {
        /** Phones sold with 4 GB report about 3.6 to 3.8 GB in all. */
        const val LIGHT_MIN_MEMORY = 3_400L * 1000 * 1000

        /** Phones sold with 8 GB report about 7 to 7.8 GB; those with 6 GB, about 5.5 to 5.8. */
        const val FULL_MIN_MEMORY = 6_500L * 1000 * 1000

        /** The model for a phone with [totalMemory] bytes of memory in all. */
        fun of(totalMemory: Long): HindiModel =
            when {
                totalMemory >= FULL_MIN_MEMORY -> FULL
                totalMemory >= LIGHT_MIN_MEMORY -> LIGHT
                else -> SMALL
            }
    }
}

/** The commit that added `voice-packs/hindi-hd-v1/` ("feat(voice): Add the Hindi HD voice pack"). */
internal const val HINDI_HD_COMMIT = "4ffa7dae44bf6bac7af2e2928dbdeb78abf596f0"

/**
 * Where the app itself downloads the voice pack from (cm-voice-pack-download):
 * the repository's raw file host over HTTPS, at one fixed commit, so a later
 * push to the repository cannot change the bytes under the app. The files are
 * still checked against the app's own manifest before anything is unzipped.
 * This host is the only one the app's network security config trusts.
 */
object VoicePackSource {
    const val HOST = "raw.githubusercontent.com"
    const val REPOSITORY = "itsluminous/ClearMovie"

    /**
     * The commit each pack folder is downloaded at. `v1`: the commit that added
     * `voice-packs/v1/` ("feat(voice): Add the voice pack, version 1").
     * `hindi-hd-v1`: the commit that added `voice-packs/hindi-hd-v1/`
     * ("feat(voice): Add the Hindi HD voice pack").
     */
    val COMMITS: Map<String, String> =
        mapOf(
            "v1" to "33f2ac362e5196752fc5a084d9944eb225ff05d5",
            "hindi-hd-v1" to HINDI_HD_COMMIT,
        )

    /** The link the app downloads [file] (a zip, or a piece of one) of pack folder [folder] from, or null for a folder without a pinned commit. */
    fun url(
        folder: String,
        file: String,
    ): String? {
        val commit = COMMITS[folder] ?: return null
        if (!FILE.matches(file)) return null
        return "https://$HOST/$REPOSITORY/$commit/voice-packs/$folder/$file"
    }

    /** Whether [url] is a pack file at its folder's pinned commit: the only links the downloader opens. */
    fun allowed(url: String): Boolean {
        val m = LINK.matchEntire(url) ?: return false
        return COMMITS[m.groupValues[2]] == m.groupValues[1]
    }

    private val FILE = Regex("^[a-z0-9_-]{1,40}\\.zip(\\.[0-9]{3})?$")
    private val LINK =
        Regex(
            "^https://raw\\.githubusercontent\\.com/itsluminous/ClearMovie/([0-9a-f]{40})/voice-packs/" +
                "(v[1-9][0-9]{0,3}|hindi-hd-v[1-9][0-9]{0,3})/[a-z0-9_-]{1,40}\\.zip(\\.[0-9]{3})?$",
        )
}

/** The room a download of [files] needs: everything unpacked, the largest zip while it is unpacked, and [spare] left over. */
fun roomToDownload(
    files: List<VoicePackFile>,
    spare: Long,
): Long = files.sumOf { it.unpackedBytes } + (files.maxOfOrNull { it.bytes } ?: 0L) + spare
