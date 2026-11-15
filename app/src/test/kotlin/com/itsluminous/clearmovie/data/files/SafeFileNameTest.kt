package com.itsluminous.clearmovie.data.files

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Safe file names from untrusted text, and paths that never leave their folder. */
class SafeFileNameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `hostile names become one safe segment`() {
        val hostile =
            listOf(
                "../../databases/clearmovie.db",
                "..",
                ".",
                "/etc/passwd",
                "a\\..\\b",
                "name\u0000.jpg",
                ".hidden",
                "con:stream",
                "x\u202Egpj.exe",
                "  ",
                "",
                null,
                "शादी का वीडियो.mp4",
                "a".repeat(5000),
            )
        for (name in hostile) {
            val built = SafeFileName.build("abcd1234", name, "image/jpeg")
            assertThat(built).matches(SafeFileName.SAFE_NAME.pattern)
            assertThat(built).doesNotContain("..")
            assertThat(built).doesNotContain("/")
            assertThat(built).endsWith(".jpg")
        }
    }

    @Test
    fun `readable names keep their letters and accents fold to ASCII`() {
        assertThat(SafeFileName.build("t1", "IMG_0042.JPG", "image/jpeg")).isEqualTo("t1_IMG_0042.jpg")
        assertThat(SafeFileName.build("t1", "Café au lait.mov", "video/quicktime")).isEqualTo("t1_Cafe_au_lait.mov")
        assertThat(SafeFileName.build("t1", "शादी.mp4", "video/mp4")).isEqualTo("t1_media.mp4")
        assertThat(SafeFileName.stem("a".repeat(100))).hasLength(SafeFileName.MAX_STEM)
    }

    @Test
    fun `the extension comes from the MIME type, never from the name`() {
        assertThat(SafeFileName.build("t1", "photo.apk", "image/png")).isEqualTo("t1_photo.png")
        assertThat(SafeFileName.build("t1", "page.html", "text/html")).isEqualTo("t1_page.bin")
        assertThat(SafeFileName.build("t1", "clip", "VIDEO/MP4; codecs=avc1")).isEqualTo("t1_clip.mp4")
        assertThat(SafeFileName.build("t1", "clip", null)).isEqualTo("t1_clip.bin")
    }

    @Test
    fun `an unsafe prefix is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SafeFileName.build("../x", "a", "image/jpeg") }
        assertThrows(IllegalArgumentException::class.java) { SafeFileName.build(".x", "a", "image/jpeg") }
    }

    @Test
    fun `app paths stay under their roots, symbolic links included`() {
        val files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        val p = ProjectId("p1")
        assertThat(files.copiedFile(p, MediaRef.Copied("media/a.jpg")).path).endsWith("files/projects/p1/media/media/a.jpg")
        assertThat(files.presetFile(PresetId("event-frame"), "frame-x_a.mp4").path).endsWith("files/presets/event-frame/frame-x_a.mp4")
        assertThrows(IllegalArgumentException::class.java) { files.presetFile(PresetId("event-frame"), "../x") }

        // A link planted inside a project's media folder that points outside it is refused.
        val media = files.projectMediaDir(p).apply { mkdirs() }
        val outside = File(tmp.root, "secret").apply { mkdirs() }
        Files.createSymbolicLink(File(media, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { files.copiedFile(p, MediaRef.Copied("link/x.jpg")) }
        assertThrows(IllegalArgumentException::class.java) { AppFiles.inside(media, File(media, "../../x")) }
    }

    @Test
    fun `sweep removes only old part files and counts sizes without following links`() {
        val files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        val media = files.projectMediaDir(ProjectId("p1")).apply { mkdirs() }
        val old = File(media, "a.jpg.part").apply { writeText("123") }
        val fresh = File(media, "b.jpg.part").apply { writeText("45") }
        val done = File(media, "c.jpg").apply { writeText("6789") }
        old.setLastModified(1_000)
        fresh.setLastModified(10_000_000)
        assertThat(files.sweep(nowMs = 10_000_000 + 1, olderThanMs = 3_600_000)).isEqualTo(1)
        assertThat(old.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(done.exists()).isTrue()

        val big = File(tmp.root, "elsewhere").apply { mkdirs() }
        File(big, "huge").writeBytes(ByteArray(10_000))
        Files.createSymbolicLink(File(media, "out").toPath(), big.toPath())
        assertThat(files.sizeOf(files.projectMediaDir(ProjectId("p1")))).isEqualTo(6)
    }
}
