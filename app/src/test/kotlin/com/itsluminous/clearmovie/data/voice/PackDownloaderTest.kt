package com.itsluminous.clearmovie.data.voice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.voice.VoicePackSource
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/** The voice pack download (cm-voice-pack-download): only the pinned links, only the listed bytes, resumed where it stopped. */
class PackDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val body = ByteArray(1000) { (it % 251).toByte() }
    private val url = requireNotNull(VoicePackSource.url("v1", "voices.zip"))

    private fun downloader(answer: (URL) -> HttpURLConnection) = PackDownloader { answer(it) }

    @Test
    fun `a whole file is downloaded once, without following redirects or taking a compressed answer`() {
        val to = tmp.root.resolve("voices.zip.part")
        var seen: FakeHttps? = null
        downloader { u -> FakeHttps(u, 200, mapOf("Content-Length" to "1000"), body).also { seen = it } }.fetch(url, to, 1000)
        assertThat(to.readBytes()).isEqualTo(body)
        assertThat(seen!!.followed).isFalse()
        assertThat(seen!!.sent["Accept-Encoding"]).isEqualTo("identity")
        assertThat(seen!!.sent).doesNotContainKey("Range")
    }

    @Test
    fun `a part already here is resumed with a range request`() {
        val to = tmp.root.resolve("voices.zip.part")
        to.writeBytes(body.copyOfRange(0, 400))
        var seen: FakeHttps? = null
        downloader { u ->
            FakeHttps(u, 206, mapOf("Content-Range" to "bytes 400-999/1000", "Content-Length" to "600"), body.copyOfRange(400, 1000)).also {
                seen = it
            }
        }.fetch(url, to, 1000)
        assertThat(seen!!.sent["Range"]).isEqualTo("bytes=400-")
        assertThat(to.readBytes()).isEqualTo(body)
    }

    @Test
    fun `a server that ignores the range sends the whole file, which replaces the part`() {
        val to = tmp.root.resolve("voices.zip.part")
        to.writeBytes(ByteArray(400) { 9 })
        downloader { u -> FakeHttps(u, 200, mapOf("Content-Length" to "1000"), body) }.fetch(url, to, 1000)
        assertThat(to.readBytes()).isEqualTo(body)
    }

    @Test
    fun `a dropped connection keeps what came, and the next try carries on from there`() {
        val to = tmp.root.resolve("voices.zip.part")
        val e =
            assertThrows(DownloadFailure::class.java) {
                downloader { u ->
                    FakeHttps(u, 200, emptyMap(), body, failAfter = 300)
                }.fetch(url, to, 1000)
            }
        assertThat(e.reason).isEqualTo(DownloadFailure.Reason.NETWORK)
        assertThat(to.length()).isEqualTo(300)
        downloader { u ->
            FakeHttps(u, 206, mapOf("Content-Range" to "bytes 300-999/1000"), body.copyOfRange(300, 1000))
        }.fetch(url, to, 1000)
        assertThat(to.readBytes()).isEqualTo(body)
    }

    @Test
    fun `a redirect, an error, a wrong range or more bytes than listed is refused`() {
        val to = tmp.root.resolve("voices.zip.part")

        fun fails(
            code: Int,
            headers: Map<String, String> = emptyMap(),
            data: ByteArray = body,
        ) = assertThrows(DownloadFailure::class.java) { downloader { u -> FakeHttps(u, code, headers, data) }.fetch(url, to, 1000) }.reason
        assertThat(fails(302, mapOf("Location" to "https://example.com/x.zip"))).isEqualTo(DownloadFailure.Reason.REFUSED)
        assertThat(fails(404)).isEqualTo(DownloadFailure.Reason.SERVER)
        assertThat(fails(503)).isEqualTo(DownloadFailure.Reason.NETWORK)
        assertThat(fails(200, mapOf("Content-Length" to "5000"))).isEqualTo(DownloadFailure.Reason.REFUSED)
        assertThat(fails(200, data = ByteArray(1001)))
            .isEqualTo(DownloadFailure.Reason.REFUSED)
        assertThat(to.exists()).isFalse()
        to.writeBytes(ByteArray(10))
        assertThat(fails(206, mapOf("Content-Range" to "bytes 0-999/1000"))).isEqualTo(DownloadFailure.Reason.SERVER)
    }

    @Test
    fun `only HTTPS links to pack files at the pinned commit are opened`() {
        val to = tmp.root.resolve("x.part")
        var opened = 0
        val d =
            downloader { u ->
                opened++
                FakeHttps(u, 200, emptyMap(), body)
            }
        listOf(
            "http://raw.githubusercontent.com/itsluminous/ClearMovie/${VoicePackSource.COMMITS.getValue("v1")}/voice-packs/v1/voices.zip",
            "https://raw.githubusercontent.com/itsluminous/ClearMovie/main/voice-packs/v1/voices.zip",
            "https://raw.githubusercontent.com/someone/ClearMovie/${VoicePackSource.COMMITS.getValue("v1")}/voice-packs/v1/voices.zip",
            "https://example.com/itsluminous/ClearMovie/${VoicePackSource.COMMITS.getValue("v1")}/voice-packs/v1/voices.zip",
            url.replace("voices.zip", "../manifest.json"),
            "$url?x=1",
        ).forEach { bad ->
            val e = assertThrows(DownloadFailure::class.java) { d.fetch(bad, to, 1000) }
            assertThat(e.reason).isEqualTo(DownloadFailure.Reason.REFUSED)
        }
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `a stop leaves the part for later`() {
        val to = tmp.root.resolve("voices.zip.part")
        to.writeBytes(body.copyOfRange(0, 300))
        downloader { u -> FakeHttps(u, 206, mapOf("Content-Range" to "bytes 300-999/1000"), body.copyOfRange(300, 1000)) }
            .fetch(url, to, 1000, stopped = { true })
        assertThat(to.readBytes()).isEqualTo(body.copyOfRange(0, 300))
    }
}
