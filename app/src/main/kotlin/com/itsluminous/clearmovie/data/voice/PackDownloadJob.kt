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
 * does not match is downloaded once more from the start.
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
        val missing = (tools.missing(SpokenLanguage.ENGLISH) + tools.missing(SpokenLanguage.HINDI)).toSet()
        val todo = manifest.files.filter { it.file in names && it in missing }
        val total = todo.sumOf { it.bytes }
        val parts = todo.associateWith { tools.downloadFile(it) ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED) }
        val onDisk = parts.values.sumOf { if (it.isFile) it.length() else 0L }
        val need = roomToDownload(todo, VoicePackStore.SPARE_BYTES) - onDisk
        val free = tools.freeBytes()
        if (free < need) return PackDownloadOutcome.Failed(PackDownloadError.NO_SPACE, need - free)
        var before = 0L
        for (f in todo) {
            if (stopped()) return PackDownloadOutcome.Stopped
            val part = parts.getValue(f)
            val url = VoicePackSource.url(manifest.version, f.file) ?: return PackDownloadOutcome.Failed(PackDownloadError.FAILED)
            var checks = 0
            while (true) {
                val failed = fetch(url, part, f, stopped) { progress(PackProgress(before + it, total)) }
                if (failed != null) return failed
                if (stopped()) return PackDownloadOutcome.Stopped
                progress(PackProgress(before + f.bytes, total, checking = true))
                val added = tools.addDownloaded(f, part, stopped)
                if (stopped()) return PackDownloadOutcome.Stopped
                when {
                    added.outOfSpace -> {
                        // Room for this file and the ones after it, less what is already downloaded.
                        val rest = todo.dropWhile { it != f }
                        val onDiskNow = rest.sumOf { r -> parts.getValue(r).let { if (it.isFile) it.length() else 0L } }
                        val missing = roomToDownload(rest, VoicePackStore.SPARE_BYTES) - onDiskNow - tools.freeBytes()
                        return PackDownloadOutcome.Failed(PackDownloadError.NO_SPACE, missing.coerceAtLeast(added.missingBytes))
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
     * Downloads [f] into [part], trying a dropped connection again after a pause; a try that got some
     * bytes counts as a fresh start, so only a connection that drops without progress ends it. Null
     * when the file is all there, or on the stop.
     */
    private fun fetch(
        url: String,
        part: File,
        f: VoicePackFile,
        stopped: () -> Boolean,
        progress: (Long) -> Unit,
    ): PackDownloadOutcome.Failed? {
        var attempt = 0
        var tries = 0
        while (true) {
            if (++tries > MAX_TRIES) return PackDownloadOutcome.Failed(PackDownloadError.NETWORK)
            val had = part.length()
            try {
                downloader.fetch(url, part, f.bytes, progress, stopped)
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

        /** At most this many connections for one file, however many of them made progress. */
        const val MAX_TRIES = 30
    }
}
