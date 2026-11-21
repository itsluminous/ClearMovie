package com.itsluminous.clearmovie.data.record

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.deleteClip
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Where recordings are written, and which ones go when the editor opens or leaves a video (cm-record-voice). */
class VoiceRecordingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val id = ProjectId("p1")
    private val files by lazy { AppFiles(tmp.newFolder("files"), tmp.newFolder("cache")) }
    private var busy = false
    private val recordings by lazy { VoiceRecordings(files, { error("no microphone in this test") }, { busy }) }

    private fun project(vararg sounds: MediaRef): Project {
        val ids = SequentialIds("s")
        var p =
            ProjectFactory
                .blank(id, ids)
                .addToMain(listOf(PhotoClip(ClipId("photo"), MediaRef.Store("content://media/external/images/media/1"), 20_000)), ids = ids)
        sounds.forEachIndexed { i, ref ->
            p = p.addTimedClip(AudioClip(ClipId("a$i"), ref, i * 2_000L, 1_000, 1_000), AudioRole.VOICE, ids)
        }
        return p
    }

    private fun write(name: String): File = File(files.projectMediaDir(id).apply { mkdirs() }, name).apply { writeText("x") }

    @Test
    fun `a new recording is written beside its final name in the project's own folder, and kept by a rename`() {
        val pending = recordings.newRecording(id)
        assertThat(pending.file.parentFile).isEqualTo(files.projectMediaDir(id))
        assertThat(pending.file.name).matches("voice-[a-z0-9]{16}\\.m4a")
        assertThat(pending.part.name).isEqualTo(pending.file.name + AppFiles.PART)
        assertThat(pending.ref.relPath).isEqualTo(pending.file.name)
        // Each is new.
        assertThat(recordings.newRecording(id).ref).isNotEqualTo(pending.ref)
        pending.part.parentFile!!.mkdirs()
        pending.part.writeText("aac")
        assertThat(recordings.keep(pending)).isTrue()
        assertThat(pending.file.readText()).isEqualTo("aac")
        assertThat(pending.part.exists()).isFalse()
    }

    @Test
    fun `nothing is kept when the recording was not written, and discard removes both files`() {
        val missing = recordings.newRecording(id)
        assertThat(recordings.keep(missing)).isFalse()
        val made = recordings.newRecording(id)
        made.part.parentFile!!.mkdirs()
        made.part.writeText("a")
        made.file.writeText("b")
        recordings.discard(made)
        assertThat(made.part.exists()).isFalse()
        assertThat(made.file.exists()).isFalse()
    }

    @Test
    fun `a recording the video no longer plays goes, one it plays stays, and other files are never touched`() {
        val used = MediaRef.Copied("voice-aaaaaaaa11111111.m4a")
        val deleted = MediaRef.Copied("voice-bbbbbbbb22222222.m4a")
        val kept = write(used.relPath)
        val gone = write(deleted.relPath)
        val half = write("voice-cccccccc33333333.m4a.part").apply { setLastModified(System.currentTimeMillis() - 11 * 60 * 1000) }
        val writing = write("voice-eeeeeeee55555555.m4a.part")
        val photo = write("photo-1.jpg")
        val song = write("song.m4a")
        val p = project(used, deleted).deleteClip(ClipId("a1"))
        assertThat(recordings.sweepUnused(p)).isEqualTo(2)
        assertThat(kept.exists()).isTrue()
        assertThat(gone.exists()).isFalse()
        assertThat(half.exists()).isFalse()
        // A recording still being written (a part file just made) is left alone.
        assertThat(writing.exists()).isTrue()
        assertThat(photo.exists()).isTrue()
        assertThat(song.exists()).isTrue()
    }

    @Test
    fun `nothing goes while an export may be reading the files`() {
        val unused = write("voice-dddddddd44444444.m4a")
        busy = true
        assertThat(recordings.sweepUnused(project())).isEqualTo(0)
        assertThat(unused.exists()).isTrue()
        busy = false
        assertThat(recordings.sweepUnused(project())).isEqualTo(1)
        assertThat(unused.exists()).isFalse()
    }

    @Test
    fun `a video without a media folder has nothing to sweep`() {
        assertThat(recordings.sweepUnused(project())).isEqualTo(0)
    }
}
