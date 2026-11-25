package com.itsluminous.clearmovie.data.voice

import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackSource
import com.itsluminous.clearmovie.domain.voice.roomToDownload
import java.io.File

/** How far a voice pack download is: [done] of [total] bytes, and whether a file is being checked and added now. */
data class PackProgress(
    val done: Long,
    val total: Long,
    val checking: Boolean = false,
)

/** Why a voice pack download stopped without the pack. */
enum class PackDownloadError {
    /** No connection, or it kept dropping. */
    NETWORK,

    /** GitHub did not send the file. */
    SERVER,

    /** A file did not match the app's own manifest, twice. */
    CHECK,

    /** Not enough room on the phone. */
    NO_SPACE,
    FAILED,
}

/** What a voice pack download did. */
sealed interface PackDownloadOutcome {
    data object Done : PackDownloadOutcome

    data object Stopped : PackDownloadOutcome

    data class Failed(
        val error: PackDownloadError,
        /** For [PackDownloadError.NO_SPACE]: how many more bytes are needed. */
        val missingBytes: Long = 0,
    ) : PackDownloadOutcome
}

/**
 * One voice pack download (cm-voice-pack-download), as the download worker
 * runs it: each listed file this phone still needs is downloaded (resumed
 * when part of it is already here), checked against the app's own manifest
 * and added, one at a time, so the phone never holds more than one zip. A
 * dropped connection is tried again a few times, after a pause; a file that
 * does not match is downloaded once more from the start. A split zip
 * (cm-hindi-voices) is downloaded piece by piece, each checked as it ends, and
 * added once all its pieces are here; the phone then holds one zip's pieces.
 */
class PackDownloadJob(
    private val tools: VoiceTools,
    private val downloader: PackDownloader,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Downloads and adds [names] (files of the app's manifest). Call off the main thread. */
    fun run(
        names: List<String>,
        progress: (PackProgress) -> Unit,
        stopped: () -> Boolean,
    ): PackDownloadOutcome {
        val manifest = tools.manifest ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED)
        val missing = tools.notAdded().toSet()
        val todo = manifest.files.filter { it.file in names && it in missing }
        val total = todo.sumOf { it.bytes }
        // Each file's downloads on disk: the zip's part file, or its checked pieces (cm-hindi-voices).
        val parts =
            todo.associateWith { f ->
                if (f.pieces.isEmpty()) {
                    listOf(tools.downloadFile(f) ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED))
                } else {
                    f.pieces.map { tools.pieceFile(it.file) ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED) }
                }
            }

        // A piece still downloading is in its part file ([VoiceTools.piecePart]) until it is checked.
        fun onDisk(fs: Collection<VoicePackFile>) =
            fs.sumOf { f ->
                parts.getValue(f).sumOf { file ->
                    val part = if (f.pieces.isEmpty()) null else tools.piecePart(file.name)
                    (if (file.isFile) file.length() else 0L) + (part?.takeIf { it.isFile }?.length() ?: 0L)
                }
            }
        val need = roomToDownload(todo, VoicePackStore.SPARE_BYTES) - onDisk(todo)
        val free = tools.freeBytes()
        if (free < need) return PackDownloadOutcome.Failed(PackDownloadError.NO_SPACE, need - free)
        var before = 0L
        for (f in todo) {
            if (stopped()) return PackDownloadOutcome.Stopped
            val folder = manifest.folderOf(f)
            val files = parts.getValue(f)
            val pieces = f.pieces.ifEmpty { null }
            var checks = 0
            // Connections per downloaded file (each piece counts on its own), across the file's checks.
            val tries = Array(f.downloads.size) { IntArray(1) }
            while (true) {
                // The zip, or each piece in turn: a piece that does not match is downloaded once more before giving up.
                var at = 0L
                for ((i, name) in f.downloads.withIndex()) {
                    val bytes = pieces?.get(i)?.bytes ?: f.bytes
                    val url = VoicePackSource.url(folder, name) ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED)
                    var pieceChecks = 0
                    while (true) {
                        // A piece already checked (its own name) is kept; one still to finish downloads to its part file.
                        if (pieces != null && files[i].isFile && files[i].length() == bytes) break
                        val into =
                            if (pieces ==
                                null
                            ) {
                                files[i]
                            } else {
                                tools.piecePart(name) ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED)
                            }
                        val failed = fetch(url, into, bytes, stopped, tries[i]) { progress(PackProgress(before + at + it, total)) }
                        if (failed != null) return failed
                        if (stopped()) return PackDownloadOutcome.Stopped
                        if (pieces == null || tools.checkPiece(f, i, into)) break
                        if (++pieceChecks >= CHECKS) return PackDownloadOutcome.Failed(PackDownloadError.CHECK)
                    }
                    at += bytes
                }
                progress(PackProgress(before + f.bytes, total, checking = true))
                val added = tools.addDownloaded(f, files, stopped)
                if (stopped()) return PackDownloadOutcome.Stopped
                when {
                    added.outOfSpace -> {
                        // Room for this file and the ones after it, less what is already downloaded.
                        val rest = todo.dropWhile { it != f }
                        val missingBytes = roomToDownload(rest, VoicePackStore.SPARE_BYTES) - onDisk(rest) - tools.freeBytes()
                        return PackDownloadOutcome.Failed(PackDownloadError.NO_SPACE, missingBytes.coerceAtLeast(added.missingBytes))
                    }

                    added.rejected == 0 -> {
                        break
                    }

                    ++checks >= CHECKS -> {
                        return PackDownloadOutcome.Failed(PackDownloadError.CHECK)
                    }
                }
                // Damaged on the way (or a resumed part that did not fit): once more, from the start.
            }
            before += f.bytes
        }
        progress(PackProgress(total, total, checking = false))
        return PackDownloadOutcome.Done
    }

    /**
     * Downloads [url] ([bytes] long) into [part], trying a dropped connection again after a pause; a try
     * that got some bytes counts as a fresh start, so only a connection that drops without progress ends
     * it. Null when the file is all there, or on the stop.
     */
    private fun fetch(
        url: String,
        part: File,
        bytes: Long,
        stopped: () -> Boolean,
        /** Connections made for this file so far, across its checks (at most [MAX_TRIES]). */
        tries: IntArray,
        progress: (Long) -> Unit,
    ): PackDownloadOutcome.Failed? {
        var attempt = 0
        while (true) {
            if (++tries[0] > MAX_TRIES) return PackDownloadOutcome.Failed(PackDownloadError.NETWORK)
            val had = part.length()
            try {
                downloader.fetch(url, part, bytes, progress, stopped)
                return null
            } catch (e: DownloadFailure) {
                when (e.reason) {
                    DownloadFailure.Reason.NETWORK -> {
                        if (part.length() > had) attempt = 0
                        if (++attempt >= PAUSES_MS.size + 1) return PackDownloadOutcome.Failed(PackDownloadError.NETWORK)
                        pause(PAUSES_MS[attempt - 1])
                        if (stopped()) return null
                    }

                    DownloadFailure.Reason.SERVER, DownloadFailure.Reason.REFUSED -> {
                        part.delete()
                        return PackDownloadOutcome.Failed(PackDownloadError.SERVER)
                    }
                }
            }
        }
    }

    companion object {
        /** Pauses before each new try of a dropped connection. */
        val PAUSES_MS = listOf(2_000L, 5_000L, 10_000L)

        /** How many times a file is downloaded when it does not match. */
        const val CHECKS = 2

        /** At most this many connections for one file (or one piece) in one run, however many of them made progress. */
        const val MAX_TRIES = 30
    }
}
