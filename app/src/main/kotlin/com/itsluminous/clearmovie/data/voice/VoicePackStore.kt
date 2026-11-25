package com.itsluminous.clearmovie.data.voice

import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipInputStream

/** What adding voice pack files did. */
data class PackAddResult(
    /** The pack files that were added. */
    val added: List<VoicePackFile>,
    /** Chosen files that are not part of this pack (another file, or a damaged download). */
    val rejected: Int,
    /** True when the phone did not have room; nothing more was added. */
    val outOfSpace: Boolean = false,
    /** With [outOfSpace], how many more bytes were needed, when known. */
    val missingBytes: Long = 0,
)

/**
 * The voice pack on this phone (cm-voice-choice): `filesDir/voice-pack/v<n>/`,
 * private to the app. A file the user downloaded is taken in only when its
 * SHA-256 matches one in the app's own [manifest], checked over a private
 * copy before anything is unzipped; it is then unzipped into a staging
 * folder (each entry a plain path inside its part's folder, at most the
 * listed number of entries and bytes) and moved into place, and a marker
 * says that part is whole. A half-added part (the app killed mid-way) has
 * no marker, so it counts as missing and is added again over the old one.
 *
 * Hindi HD (cm-hindi-voices) lives in the same folder: a split zip is taken in
 * piece by piece (each checked), then unzipped from its pieces read in order.
 * [hindiModel] is what this phone's memory runs; files it cannot run are never
 * taken in.
 */
class VoicePackStore(
    filesDir: File,
    val manifest: VoicePackManifest,
    val abi: String,
    val hindiModel: HindiModel = HindiModel.SMALL,
    private val freeSpace: () -> Long,
) {
    val root: File = File(filesDir, "voice-pack/v${manifest.version}")
    private val incoming = File(root, ".incoming")

    /** Whether this pack has an engine for the phone's processor type. */
    val supported: Boolean get() = manifest.supports(abi)

    val ttsDir: File get() = File(root, VoicePackFile.folderOf(VoicePackFile.VOICES))
    val libDir: File get() = File(root, "lib/$abi")

    /** The voices file: Hindi HD's, with the two extra Indian voices, once it is added. */
    val voicesFile: File
        get() =
            if (partAdded(VoicePackFile.VOICES_HI_HD)) {
                File(root, "${VoicePackFile.folderOf(VoicePackFile.VOICES_HI_HD)}/voices.bin")
            } else {
                File(ttsDir, "voices.bin")
            }

    /** The recognizer for [language]: for Hindi, Hindi HD once its model is added (with its rescoring files, when they are). */
    fun asrDir(language: SpokenLanguage): File =
        when {
            language == SpokenLanguage.HINDI && partAdded(VoicePackFile.SPEECH_HI_HD) -> File(root, VoicePackFile.HD_MODEL)
            language == SpokenLanguage.HINDI -> File(root, VoicePackFile.folderOf(VoicePackFile.SPEECH_HI))
            else -> File(root, VoicePackFile.folderOf(VoicePackFile.SPEECH_EN))
        }

    /**
     * Readies [asrDir] for [language]: Hindi HD's rescoring folders are read whenever they
     * are there, so ones left without their marker (an add cut short) are removed first.
     */
    fun prepareAsr(language: SpokenLanguage) {
        if (language != SpokenLanguage.HINDI || partAdded(VoicePackFile.SPEECH_HI_HD_RESCORE)) return
        VoicePackFile.foldersOf(VoicePackFile.SPEECH_HI_HD_RESCORE).map { File(root, it) }.filter { it.exists() }.forEach { d ->
            d.walkTopDown().forEach { it.setWritable(true, true) }
            d.deleteRecursively()
        }
    }

    private fun marker(f: VoicePackFile) = File(root, ".added-${f.file}")

    fun isAdded(f: VoicePackFile): Boolean = marker(f).let { it.isFile && it.readText().trim().equals(f.sha256, ignoreCase = true) }

    private fun partAdded(part: String): Boolean = manifest.files.any { it.part == part && isAdded(it) }

    /** Whether [f] is for this phone: its processor type's engine, and Hindi HD only where its memory runs it. */
    fun fits(f: VoicePackFile): Boolean = manifest.fitsPhone(f, abi, hindiModel)

    /** Whether Hindi HD's voices file, with the two extra Indian voices, is here. */
    fun hdVoicesReady(): Boolean = partAdded(VoicePackFile.VOICES_HI_HD)

    /** Whether a Hindi recognizer is here: the small one or Hindi HD. */
    fun hindiReady(): Boolean = partAdded(VoicePackFile.SPEECH_HI) || partAdded(VoicePackFile.SPEECH_HI_HD)

    /**
     * The files still to add to voice words (in [voice], when it needs Hindi HD's voices file),
     * and to recognize speech in [speech] (null: typed words only). Hindi asks for the best
     * recognizer this phone runs, unless one is already here.
     */
    fun missing(
        speech: SpokenLanguage?,
        voice: VoiceChoice? = null,
    ): List<VoicePackFile> {
        if (!supported) return emptyList()
        val want =
            manifest.needed(abi, if (speech == SpokenLanguage.HINDI) null else speech).toMutableSet()
        if (voice?.needsHd == true) want += manifest.files.filter { it.part == VoicePackFile.VOICES_HI_HD }
        if (speech == SpokenLanguage.HINDI && !hindiReady()) want += manifest.hindiSpeech(hindiModel)
        return manifest.files.filter { it in want && fits(it) && !isAdded(it) }
    }

    /** Hindi HD's files this phone runs and does not have yet, with the base files it needs; empty where its memory runs only the small model. */
    fun hindiHdMissing(): List<VoicePackFile> {
        if (!supported || hindiModel == HindiModel.SMALL) return emptyList()
        val hd = manifest.hindiSpeech(hindiModel).filter { it.part != VoicePackFile.SPEECH_HI }
        if (hd.isEmpty() || hd.all(::isAdded)) return emptyList()
        val want = (manifest.needed(abi, null) + hd).toSet()
        return manifest.files.filter { it in want && fits(it) && !isAdded(it) }
    }

    /** Free bytes on the phone's storage, where the pack goes. */
    fun freeBytes(): Long = freeSpace()

    /** Bytes the pack takes on this phone. */
    fun bytesOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Whether any part of the pack is here. */
    fun anyAdded(): Boolean = manifest.files.any(::isAdded)

    /** Removes the whole pack from the phone. Voices already made stay in their videos. */
    fun remove() {
        root.walkTopDown().forEach { it.setWritable(true, true) }
        root.deleteRecursively()
    }

    /**
     * Takes in the chosen [sources] (opened one at a time): each is copied
     * privately while its SHA-256 is worked out, and kept only if it is a
     * file of this pack, for this phone. [progress] runs from 0 to 1.
     */
    fun add(
        sources: List<() -> InputStream?>,
        progress: (Float) -> Unit = {},
    ): PackAddResult {
        incoming.deleteRecursively()
        incoming.mkdirs()
        val added = mutableListOf<VoicePackFile>()
        var rejected = 0
        try {
            sources.forEachIndexed { i, open ->
                val copy = File(incoming, "file-$i.zip")
                val sha =
                    runCatching { open()?.use { copyHashing(it, copy) } }.getOrNull()
                        ?: run {
                            rejected++
                            copy.delete()
                            return@forEachIndexed
                        }
                val f = manifest.bySha(sha)?.takeIf(::fits)
                val piece = if (f == null) manifest.pieceBySha(sha)?.takeIf { fits(it.first) } else null
                when {
                    piece != null && copy.length() == piece.second.bytes -> {
                        // Kept with the downloads until all its zip's pieces are here.
                        val to = pieceFile(piece.second.file)
                        to.parentFile?.mkdirs()
                        to.delete()
                        if (!copy.renameTo(to)) rejected++
                    }

                    f == null || copy.length() != f.bytes -> {
                        rejected++
                    }

                    !isAdded(f) -> {
                        if (freeSpace() < f.unpackedBytes + SPARE_BYTES) return PackAddResult(added, rejected, outOfSpace = true)
                        if (unpack(f) { copy.inputStream() }) added += f else rejected++
                    }
                }
                copy.delete()
                progress((i + 1f) / sources.size)
            }
            // Split zips whose pieces are all here now.
            for (f in manifest.files.filter { it.pieces.isNotEmpty() && fits(it) && !isAdded(it) }) {
                val pieces = f.pieces.map { pieceFile(it.file) }
                if (!pieces.all { it.isFile }) continue
                val r = addDownloaded(f, pieces)
                if (r.outOfSpace) return PackAddResult(added, rejected, outOfSpace = true, missingBytes = r.missingBytes)
                added += r.added
                rejected += r.rejected
            }
        } finally {
            incoming.deleteRecursively()
        }
        return PackAddResult(added, rejected)
    }

    /** Where downloads of pack files go until they are added (cm-voice-pack-download); inside the pack's folder, so Remove clears them too. */
    val downloadDir: File get() = File(root, ".download")

    /** The download of [f] in progress, resumed from where it stopped. */
    fun downloadFile(f: VoicePackFile): File = File(downloadDir, f.file + ".part")

    /** Where a piece of a split zip ([VoicePackFile.pieces]) is downloaded to, and kept until its zip is added. */
    fun pieceFile(name: String): File = File(downloadDir, name)

    /** Where piece [name] is downloaded to until it is checked; only a checked piece gets its own name ([pieceFile]). */
    fun piecePart(name: String): File = File(downloadDir, "$name.part")

    /**
     * Whether [file] is piece [index] of [f] as the app's own manifest lists it. A match is moved to the
     * piece's own name ([pieceFile]), so only checked pieces wait there; a file that does not match is deleted.
     */
    fun checkPiece(
        f: VoicePackFile,
        index: Int,
        file: File,
    ): Boolean {
        val p =
            manifest.files
                .firstOrNull { it == f }
                ?.pieces
                ?.getOrNull(index)
        if (p == null || !pieceMatches(file, p.bytes, p.sha256)) {
            file.delete()
            return false
        }
        val to = pieceFile(p.file)
        if (file != to) {
            to.parentFile?.mkdirs()
            to.delete()
            if (!file.renameTo(to)) {
                file.delete()
                return false
            }
        }
        return true
    }

    private fun pieceMatches(
        file: File,
        bytes: Long,
        sha256: String,
    ) = file.isFile && file.length() == bytes && sha256Of(file)?.equals(sha256, ignoreCase = true) == true

    /** Removes downloads that were stopped (Cancel). */
    fun clearDownloads() {
        downloadDir.deleteRecursively()
    }

    /**
     * Takes in [files], a download of [f] in the app's own storage: the zip, or
     * its pieces in order. Each file's SHA-256 is worked out over it, and [f]
     * is unzipped only when every one is as the app's own [manifest] lists it
     * (never a manifest from the network). The files are deleted once added or
     * found not to match; without room to unpack, they are kept, so Try again
     * does not download them again.
     */
    fun addDownloaded(
        f: VoicePackFile,
        files: List<File>,
    ): PackAddResult {
        val listed = manifest.files.firstOrNull { it == f }?.takeIf(::fits)
        val expected = listed?.let { l -> if (l.pieces.isEmpty()) listOf(l.bytes to l.sha256) else l.pieces.map { it.bytes to it.sha256 } }
        // Each file is checked on its own and only the ones that do not match are deleted, so one bad
        // piece never costs the good ones (they may be most of 1.2 GB).
        val bad =
            if (expected != null && files.size == expected.size) {
                files.zip(expected).filterNot { (file, e) -> pieceMatches(file, e.first, e.second) }.map { it.first }
            } else {
                files
            }
        if (listed == null || bad.isNotEmpty()) {
            bad.forEach { it.delete() }
            return PackAddResult(emptyList(), 1)
        }

        // Once the zip is in, its pieces waiting with the downloads are not needed, wherever these copies came from.
        fun clearPieces() = listed.pieces.forEach { pieceFile(it.file).delete() }
        if (isAdded(listed)) {
            files.forEach { it.delete() }
            clearPieces()
            return PackAddResult(emptyList(), 0)
        }
        val need = listed.unpackedBytes + SPARE_BYTES
        val free = freeSpace()
        if (free < need) return PackAddResult(emptyList(), 0, outOfSpace = true, missingBytes = need - free)
        val added = unpack(listed) { SequenceInputStream(Collections.enumeration(files.map { it.inputStream() })) }
        files.forEach { it.delete() }
        if (added) clearPieces()
        return if (added) PackAddResult(listOf(listed), 0) else PackAddResult(emptyList(), 1)
    }

    private fun sha256Of(file: File): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    /** Copies [input] to [to], refusing more than the largest pack file; the copy's SHA-256, or null. */
    private fun copyHashing(
        input: InputStream,
        to: File,
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        to.outputStream().use { out ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > VoicePackFile.MAX_FILE_BYTES) return null
                if (freeSpace() < SPARE_BYTES) return null
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Unzips verified [open] (the zip's bytes) into its part's folders through a staging folder; false (nothing changed) when it breaks a rule. */
    private fun unpack(
        f: VoicePackFile,
        open: () -> InputStream,
    ): Boolean {
        val staging = File(root, ".staging-${f.part}")
        staging.deleteRecursively()
        val ok =
            runCatching {
                var entries = 0
                var bytes = 0L
                ZipInputStream(open().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (!VoicePackFile.safeEntry(f.part, e.name)) return@runCatching false
                        if (f.part == VoicePackFile.ENGINE && !e.name.startsWith("lib/$abi/")) return@runCatching false
                        if (++entries > f.entries) return@runCatching false
                        val out = File(staging, e.name)
                        if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) return@runCatching false
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                val n = z.read(buf)
                                if (n < 0) break
                                bytes += n
                                if (bytes > f.unpackedBytes) return@runCatching false
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
                entries == f.entries && bytes == f.unpackedBytes
            }.getOrDefault(false)
        if (!ok) {
            staging.deleteRecursively()
            return false
        }
        val folders = if (f.part == VoicePackFile.ENGINE) listOf("lib/$abi") else VoicePackFile.foldersOf(f.part)
        if (!folders.all { File(staging, it).isDirectory }) {
            staging.deleteRecursively()
            return false
        }
        marker(f).delete()
        var moved = true
        for (folder in folders) {
            val target = File(root, folder)
            target.walkTopDown().forEach { it.setWritable(true, true) }
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            moved = File(staging, folder).renameTo(target)
            // A move that fails stops here, so no folder after it is deleted for nothing; the part stays unmarked.
            if (!moved) break
            // Native code is loaded only from files the app cannot write (Android warns, and will refuse, otherwise).
            target.walkTopDown().filter { it.isFile }.forEach { it.setWritable(false, false) }
        }
        staging.deleteRecursively()
        if (!moved) return false
        marker(f).writeText(f.sha256)
        return true
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** Room left on the phone after a part is added. */
        const val SPARE_BYTES = 50L * 1000 * 1000

        /** The app's own copy of the pack's manifest, in its assets. */
        const val MANIFEST_ASSET = "voice-pack/manifest-v1.json"

        /** The app's own copy of Hindi HD's manifest (cm-hindi-voices), an add-on to the pack above. */
        const val HINDI_HD_MANIFEST_ASSET = "voice-pack/manifest-hindi-hd-v1.json"
    }
}
