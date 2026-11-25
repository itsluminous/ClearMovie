package com.itsluminous.clearmovie.data.voice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A finished download without room to unpack it is kept, so Try again does not download it again (review F2, F3). */
class PackDownloadSpaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a verified download is kept when the phone has no room to unpack it, and says how much is missing`() {
        val bytes =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { z ->
                        z.putNextEntry(ZipEntry("tts/tokens.txt"))
                        z.write("a 1".toByteArray())
                        z.closeEntry()
                    }
                }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val f = VoicePackFile("voices.zip", VoicePackFile.VOICES, bytes.size.toLong(), 3, 1, sha)
        var free = 10L
        val store = VoicePackStore(tmp.root, VoicePackManifest(VoicePackManifest.NAME, 1, listOf(f)), "x86_64") { free }
        val zip = store.downloadFile(f).apply { parentFile.mkdirs() }
        zip.writeBytes(bytes)
        val r = store.addDownloaded(f, listOf(zip))
        assertThat(r.outOfSpace).isTrue()
        assertThat(r.missingBytes).isEqualTo(3 + VoicePackStore.SPARE_BYTES - 10)
        assertThat(zip.readBytes()).isEqualTo(bytes)
        free = Long.MAX_VALUE
        assertThat(store.addDownloaded(f, listOf(zip)).added).containsExactly(f)
        assertThat(zip.exists()).isFalse()
    }
}
