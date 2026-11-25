package com.itsluminous.clearmovie.data.voice

import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
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
 */
class VoicePackStore(
    filesDir: File,
    val manifest: VoicePackManifest,
    val abi: String,
    private val freeSpace: () -> Long,
) {
    val root: File = File(filesDir, "voice-pack/v${manifest.version}")
    private val incoming = File(root, ".incoming")

    /** Whether this pack has an engine for the phone's processor type. */
    val supported: Boolean get() = manifest.supports(abi)

    val ttsDir: File get() = File(root, VoicePackFile.folderOf(VoicePackFile.VOICES))
    val libDir: File get() = File(root, "lib/$abi")

    fun asrDir(language: SpokenLanguage): File =
        File(root, VoicePackFile.folderOf(if (language == SpokenLanguage.HINDI) VoicePackFile.SPEECH_HI else VoicePackFile.SPEECH_EN))

    private fun marker(f: VoicePackFile) = File(root, ".added-${f.file}")

    fun isAdded(f: VoicePackFile): Boolean = marker(f).let { it.isFile && it.readText().trim().equals(f.sha256, ignoreCase = true) }

    /** The files still to add to voice words, and to recognize speech in [speech] (null: typed words only). */
    fun missing(speech: SpokenLanguage?): List<VoicePackFile> =
        if (!supported) emptyList() else manifest.needed(abi, speech).filterNot(::isAdded)

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
                val f = manifest.bySha(sha)?.takeIf { it.part != VoicePackFile.ENGINE || it.abi == abi }
                if (f == null || copy.length() != f.bytes) {
                    rejected++
                } else if (!isAdded(f)) {
                    if (freeSpace() < f.unpackedBytes + SPARE_BYTES) return PackAddResult(added, rejected, outOfSpace = true)
                    if (unpack(f, copy)) added += f else rejected++
                }
                copy.delete()
                progress((i + 1f) / sources.size)
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

    /** Removes downloads that were stopped (Cancel). */
    fun clearDownloads() {
        downloadDir.deleteRecursively()
    }

    /**
     * Takes in [zip], a download of [f] in the app's own storage: its SHA-256
     * is worked out over the file and it is unzipped only when it is [f] as
     * the app's own [manifest] lists it (never a manifest from the network).
     * The zip is deleted once it is added or found not to match; without room
     * to unpack it, it is kept, so Try again does not download it again.
     */
    fun addDownloaded(
        f: VoicePackFile,
        zip: File,
    ): PackAddResult {
        val listed = manifest.files.firstOrNull { it == f }?.takeIf { it.part != VoicePackFile.ENGINE || it.abi == abi }
        val sha = if (listed != null && zip.isFile && zip.length() == listed.bytes) sha256Of(zip) else null
        if (listed == null || sha == null || !sha.equals(listed.sha256, ignoreCase = true)) {
            zip.delete()
            return PackAddResult(emptyList(), 1)
        }
        val need = listed.unpackedBytes + SPARE_BYTES
        val free = freeSpace()
        if (!isAdded(listed) && free < need) {
            return PackAddResult(emptyList(), 0, outOfSpace = true, missingBytes = need - free)
        }
        if (isAdded(listed)) {
            zip.delete()
            return PackAddResult(emptyList(), 0)
        }
        val ok = unpack(listed, zip)
        zip.delete()
        return if (ok) PackAddResult(listOf(listed), 0) else PackAddResult(emptyList(), 1)
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

    /** Unzips verified [zip] into its part's folder through a staging folder; false (nothing changed) when it breaks a rule. */
    private fun unpack(
        f: VoicePackFile,
        zip: File,
    ): Boolean {
        val staging = File(root, ".staging-${f.part}")
        staging.deleteRecursively()
        val ok =
            runCatching {
                var entries = 0
                var bytes = 0L
                ZipInputStream(zip.inputStream().buffered()).use { z ->
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
        val folder = if (f.part == VoicePackFile.ENGINE) "lib/$abi" else VoicePackFile.folderOf(f.part)
        val target = File(root, folder)
        marker(f).delete()
        target.walkTopDown().forEach { it.setWritable(true, true) }
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        val moved = File(staging, folder).renameTo(target)
        staging.deleteRecursively()
        if (!moved) return false
        // Native code is loaded only from files the app cannot write (Android warns, and will refuse, otherwise).
        target.walkTopDown().filter { it.isFile }.forEach { it.setWritable(false, false) }
        marker(f).writeText(f.sha256)
        return true
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** Room left on the phone after a part is added. */
        const val SPARE_BYTES = 50L * 1000 * 1000

        /** The app's own copy of the pack's manifest, in its assets. */
        const val MANIFEST_ASSET = "voice-pack/manifest-v1.json"
    }
}
