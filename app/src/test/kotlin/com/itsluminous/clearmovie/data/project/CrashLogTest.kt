package com.itsluminous.clearmovie.data.project

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The last crash and the next launch's mark (cm-crash-after-media-pick). */
class CrashLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun log(dir: File = File(tmp.root, "crash")) =
        CrashLog(dir, about = {
            "ClearMovie 0.0.6 (60), Android 16 (API 36), samsung SM-G990E"
        }, clock = { 0L })

    @Test
    fun `a crash is kept with the app, the phone, the thread and the whole trace`() {
        val crashes = log()
        assertThat(crashes.last()).isNull()
        assertThat(crashes.crashedLastTime()).isFalse()
        crashes.record(Thread("main"), IllegalStateException("outer", IllegalArgumentException("inner")))
        val text = crashes.last()!!.text
        assertThat(text).startsWith("ClearMovie 0.0.6 (60), Android 16 (API 36), samsung SM-G990E\n1970-01-01T00:00:00Z\nThread: main\n")
        assertThat(text).contains("java.lang.IllegalStateException: outer")
        assertThat(text).contains("Caused by: java.lang.IllegalArgumentException: inner")
    }

    @Test
    fun `the mark is taken once, the text stays until cleared, and a newer crash replaces it`() {
        val crashes = log()
        crashes.record(Thread("main"), IllegalStateException("first"))
        assertThat(crashes.crashedLastTime()).isTrue()
        assertThat(crashes.takeCrashMark()).isTrue()
        assertThat(crashes.takeCrashMark()).isFalse()
        assertThat(crashes.crashedLastTime()).isFalse()
        crashes.record(Thread("main"), IllegalStateException("second"))
        assertThat(crashes.last()!!.text).contains("second")
        assertThat(crashes.last()!!.text).doesNotContain("first")
        crashes.clear()
        assertThat(crashes.last()).isNull()
    }

    @Test
    fun `a very long trace is cut, and a folder that cannot be written never throws`() {
        val crashes = log()
        crashes.record(Thread("main"), IllegalStateException("x".repeat(200_000)))
        assertThat(crashes.last()!!.text.length).isEqualTo(CrashLog.MAX_CHARS)
        // A file where the folder should be: nothing is kept, and nothing throws.
        val blocked = File(tmp.root, "blocked").apply { writeText("") }
        val broken = log(blocked)
        broken.record(Thread("main"), IllegalStateException("x"))
        assertThat(broken.last()).isNull()
        assertThat(broken.takeCrashMark()).isFalse()
    }
}
