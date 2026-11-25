package com.itsluminous.clearmovie.data.project

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * The last crash, kept on the phone (cm-crash-after-media-pick). ClearMovie
 * has no internet permission and no crash reporting, so this is the only
 * record of what went wrong: Settings offers to share it, and nothing leaves
 * the phone unless the user shares it.
 *
 * A crash also leaves a mark that the next launch takes ([crashedLastTime], [takeCrashMark]):
 * that launch opens Home instead of the editor, so a video whose editor
 * crashes does not crash the app again at every launch. Its edits were
 * written by the crash handler, and it stays in My videos.
 *
 * Both are plain files in [dir] (`noBackupFilesDir/crash`), written on the
 * crashing thread, so they never wait for anything that may have caused the
 * crash. Every call swallows its own failures: a full phone must not turn
 * a crash into a second one, or Settings into a crash.
 */
class CrashLog(
    private val dir: File,
    private val about: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    /** This build's version code: a crash kept by another version is not offered, since that version is gone. */
    private val version: () -> Long = { 0L },
) {
    private val text get() = File(dir, TEXT)
    private val mark get() = File(dir, MARK)
    private val versionFile get() = File(dir, VERSION)

    /** Keeps [error] as the last crash, replacing the one before, and marks the next launch. */
    fun record(
        thread: Thread,
        error: Throwable,
    ) {
        runCatching {
            dir.mkdirs()
            mark.writeText("")
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val header =
                buildString {
                    about().takeIf { it.isNotBlank() }?.let { appendLine(it) }
                    appendLine(Instant.ofEpochMilli(clock()).toString())
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                }
            text.writeText(cut(header + trace))
            versionFile.writeText(version().toString())
        }
    }

    /** Whether the app crashed since the last launch took the mark. */
    fun crashedLastTime(): Boolean = runCatching { mark.exists() }.getOrDefault(false)

    /** True once after a crash: the launch that takes it opens Home. */
    fun takeCrashMark(): Boolean = runCatching { mark.exists() && mark.delete() }.getOrDefault(false)

    /**
     * The last crash's text, or null when none is kept or it was kept by another version of the app (by
     * version code, never by time: an offline phone's clock can be wrong). It is not deleted: the next crash
     * replaces it.
     */
    fun last(): Crash? =
        runCatching {
            val f = text
            if (!f.isFile) return null
            val keptBy =
                versionFile
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
                    ?.toLongOrNull()
            if (keptBy != null && keptBy != version()) return null
            Crash(atMs = f.lastModified(), text = f.readText())
        }.getOrNull()

    /** Forgets the last crash. */
    fun clear() {
        runCatching { text.delete() }
    }

    /** A kept crash: when it was written and what it says. */
    data class Crash(
        val atMs: Long,
        val text: String,
    )

    companion object {
        /**
         * [all] at most [MAX_CHARS] long: its start (where it happened) and its end (the `Caused by` lines,
         * which come last), with how much was left out between them.
         */
        internal fun cut(all: String): String {
            if (all.length <= MAX_CHARS) return all
            // The note takes at most 48 characters, so the whole stays within MAX_CHARS.
            val keep = MAX_CHARS - 48
            // Never between the two halves of a character outside the BMP (an emoji in a caption).
            var head = keep / 2
            if (all[head - 1].isHighSurrogate()) head--
            var tailStart = all.length - (keep - keep / 2)
            if (all[tailStart].isLowSurrogate()) tailStart++
            val gap = "\n... ${tailStart - head} characters left out ...\n"
            return all.substring(0, head) + gap + all.substring(tailStart)
        }

        const val TEXT = "last-crash.txt"
        const val MARK = "open-home-next"
        const val VERSION = "last-crash-version"

        /** A crash text is a few kilobytes; a deep Compose trace can reach tens. */
        const val MAX_CHARS = 64 * 1024
    }
}
