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
    /** Crashes kept before this time (the app's last update) are not shown: that version is gone. */
    private val notBefore: () -> Long = { 0L },
) {
    private val text get() = File(dir, TEXT)
    private val mark get() = File(dir, MARK)

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
        }
    }

    /** Whether the app crashed since the last launch took the mark. */
    fun crashedLastTime(): Boolean = runCatching { mark.exists() }.getOrDefault(false)

    /** True once after a crash: the launch that takes it opens Home. */
    fun takeCrashMark(): Boolean = runCatching { mark.exists() && mark.delete() }.getOrDefault(false)

    /** The last crash's text, or null when none is kept, or it is from before the app was updated. */
    fun last(): Crash? =
        runCatching {
            val f = text
            if (!f.isFile) return null
            val at = f.lastModified()
            if (at < notBefore()) {
                f.delete()
                return null
            }
            Crash(atMs = at, text = f.readText())
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
            val gap = "\n... ${all.length - keep} characters left out ...\n"
            return all.take(keep / 2) + gap + all.takeLast(keep - keep / 2)
        }

        const val TEXT = "last-crash.txt"
        const val MARK = "open-home-next"

        /** A crash text is a few kilobytes; a deep Compose trace can reach tens. */
        const val MAX_CHARS = 64 * 1024
    }
}
