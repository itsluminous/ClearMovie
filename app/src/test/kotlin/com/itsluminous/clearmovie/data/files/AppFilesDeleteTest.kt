package com.itsluminous.clearmovie.data.files

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Deleting a video's files (cm-delete-project) removes its own folder and
 * thumbnail and nothing else: another video's folder, the preset files and
 * anything a link inside the folder points to stay.
 */
class AppFilesDeleteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { AppFiles(File(tmp.root, "files"), File(tmp.root, "cache")) }
    private val mine = ProjectId("mine")
    private val other = ProjectId("other")

    private fun put(
        dir: File,
        name: String,
    ): File = File(dir.apply { mkdirs() }, name).apply { writeText(name) }

    @Test
    fun `only the video's own folder and thumbnail go`() {
        val copy = put(files.projectMediaDir(mine), "a_photo.jpg")
        val recording = put(files.projectMediaDir(mine), "voice-abc.m4a")
        val thumb = put(files.thumbsRoot, "mine.jpg")
        val otherCopy = put(files.projectMediaDir(other), "b_photo.jpg")
        val otherThumb = put(files.thumbsRoot, "other.jpg")
        val presetFile = put(files.presetDir(PresetId("event-frame")), "frame.mp4")
        val gallery = put(File(tmp.root, "sdcard/Movies/ClearMovie"), "Party.mp4")

        assertThat(files.deleteProjectFiles(mine)).isTrue()

        assertThat(copy.exists()).isFalse()
        assertThat(recording.exists()).isFalse()
        assertThat(thumb.exists()).isFalse()
        assertThat(files.projectDir(mine).exists()).isFalse()
        assertThat(otherCopy.readText()).isEqualTo("b_photo.jpg")
        assertThat(otherThumb.exists()).isTrue()
        assertThat(presetFile.readText()).isEqualTo("frame.mp4")
        assertThat(gallery.readText()).isEqualTo("Party.mp4")
        assertThat(files.projectsRoot.list()!!.toList()).containsExactly("other")
    }

    @Test
    fun `a link in the folder is removed as a link, never followed`() {
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        val kept = put(outside, "keep.mp4")
        val keptFile = put(File(tmp.root, "elsewhere"), "single.mp4")
        val media = files.projectMediaDir(mine).apply { mkdirs() }
        Files.createSymbolicLink(File(media, "dir-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(media, "file-link.mp4").toPath(), keptFile.toPath())
        // A link left pointing at nothing goes too.
        Files.createSymbolicLink(File(media, "broken.mp4").toPath(), File(tmp.root, "gone.mp4").toPath())

        assertThat(files.deleteProjectFiles(mine)).isTrue()

        assertThat(files.projectDir(mine).exists()).isFalse()
        assertThat(kept.readText()).isEqualTo("keep.mp4")
        assertThat(keptFile.readText()).isEqualTo("single.mp4")
    }

    @Test
    fun `a project folder that is itself a link elsewhere is left alone`() {
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        val kept = put(outside, "keep.mp4")
        files.projectsRoot.mkdirs()
        Files.createSymbolicLink(File(files.projectsRoot, "mine").toPath(), outside.toPath())

        assertThat(files.deleteProjectFiles(mine)).isFalse()

        assertThat(kept.readText()).isEqualTo("keep.mp4")
    }

    @Test
    fun `a video with no files of its own deletes cleanly`() {
        assertThat(files.deleteProjectFiles(mine)).isTrue()
        assertThat(files.projectsRoot.exists()).isFalse()
    }
}
