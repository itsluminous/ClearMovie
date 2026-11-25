package com.itsluminous.clearmovie.data.voice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One voice pack download as the worker runs it (cm-voice-pack-download). */
class PackDownloadJobTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val zips =
        mapOf(
            "voices.zip" to zip("tts/model.int8.onnx" to "model", "tts/tokens.txt" to "a 1"),
            "engine-x86_64.zip" to zip("lib/x86_64/libvosk.so" to "elf"),
            "engine-arm64-v8a.zip" to zip("lib/arm64-v8a/libvosk.so" to "elf"),
            "speech-english.zip" to zip("asr/en/am/final.mdl" to "am"),
            "speech-hindi.zip" to zip("asr/hi/am/final.mdl" to "hi"),
        )
    private val parts =
        mapOf(
            "voices.zip" to (VoicePackFile.VOICES to null),
            "engine-x86_64.zip" to (VoicePackFile.ENGINE to "x86_64"),
            "engine-arm64-v8a.zip" to (VoicePackFile.ENGINE to "arm64-v8a"),
            "speech-english.zip" to (VoicePackFile.SPEECH_EN to null),
            "speech-hindi.zip" to (VoicePackFile.SPEECH_HI to null),
        )
    private val entrySizes =
        mapOf(
            "voices.zip" to (2 to 8L),
            "engine-x86_64.zip" to (1 to 3L),
            "engine-arm64-v8a.zip" to (1 to 3L),
            "speech-english.zip" to (1 to 2L),
        ).plus("speech-hindi.zip" to (1 to 2L))
    private val manifest =
        VoicePackManifest(
            VoicePackManifest.NAME,
            1,
            zips.map { (name, bytes) ->
                val (part, abi) = parts.getValue(name)
                val (entries, unpacked) = entrySizes.getValue(name)
                VoicePackFile(name, part, bytes.size.toLong(), unpacked, entries, sha(bytes), abi)
            },
        )

    /** [VoiceTools] over a real [VoicePackStore]: what the worker uses. */
    private class StoreTools(
        val store: VoicePackStore,
    ) : VoiceTools {
        override val manifest = store.manifest
        override val supported = true

        override fun missing(
            speech: SpokenLanguage?,
            voice: VoiceChoice?,
        ) = store.missing(speech, voice)

        override fun downloadFile(f: VoicePackFile): File = store.downloadFile(f)

        override fun pieceFile(name: String): File = store.pieceFile(name)

        override fun checkPiece(
            f: VoicePackFile,
            index: Int,
            file: File,
        ) = store.checkPiece(f, index, file)

        override fun notAdded() = store.manifest.files.filter { store.fits(it) && !store.isAdded(it) }

        override fun addDownloaded(
            f: VoicePackFile,
            files: List<File>,
            stopped: () -> Boolean,
        ) = if (stopped()) PackAddResult(emptyList(), 0) else store.addDownloaded(f, files)

        override fun freeBytes() = store.freeBytes()

        override fun addPackFiles(
            sources: List<() -> InputStream?>,
            progress: (Float) -> Unit,
        ) = store.add(sources, progress)

        override fun packBytes() = store.bytesOnDisk()

        override fun removePack() = store.remove()

        override fun recognize(
            projectId: ProjectId,
            media: MediaRef.Copied,
            language: SpokenLanguage,
            progress: (Float) -> Unit,
        ): List<VoicePhrase> = error("unused")

        override fun speak(
            projectId: ProjectId,
            phrases: List<VoicePhrase>,
            voice: VoiceChoice,
            language: SpokenLanguage,
            lengthMs: Long?,
            progress: (Float) -> Unit,
        ): SpokenVoice = error("unused")
    }

    private fun tools(free: Long = Long.MAX_VALUE) = StoreTools(VoicePackStore(tmp.root, manifest, "x86_64") { free })

    /** A server that serves the zips (with [damage] applied the first time each is asked for) and counts requests. */
    private inner class Server(
        val damage: Set<String> = emptySet(),
        val dropFirst: Set<String> = emptySet(),
    ) {
        val asked = mutableListOf<String>()

        fun open(u: URL): HttpURLConnection {
            val name = u.path.substringAfterLast('/')
            val first = name !in asked
            asked += name
            val bytes =
                zips.getValue(name).let {
                    if (first &&
                        name in damage
                    ) {
                        it.copyOf().also { b -> b[b.size / 2] = (b[b.size / 2] + 1).toByte() }
                    } else {
                        it
                    }
                }
            return FakeHttps(
                u,
                200,
                mapOf("Content-Length" to bytes.size.toString()),
                bytes,
                failAfter =
                    if (first &&
                        name in dropFirst
                    ) {
                        10
                    } else {
                        null
                    },
            )
        }
    }

    private val pauses = mutableListOf<Long>()

    private fun job(
        t: VoiceTools,
        server: Server,
    ) = PackDownloadJob(t, PackDownloader(server::open), pause = { pauses += it })

    @Test
    fun `the phone's files are downloaded, checked and added, and nothing else is fetched`() {
        val t = tools()
        val server = Server()
        val seen = mutableListOf<PackProgress>()
        val names = t.missing(SpokenLanguage.ENGLISH).map { it.file }
        val out = job(t, server).run(names, { seen += it }, { false })
        assertThat(out).isEqualTo(PackDownloadOutcome.Done)
        assertThat(server.asked).containsExactly("voices.zip", "engine-x86_64.zip", "speech-english.zip").inOrder()
        assertThat(t.missing(SpokenLanguage.ENGLISH)).isEmpty()
        assertThat(t.missing(SpokenLanguage.HINDI).map { it.file }).containsExactly("speech-hindi.zip")
        assertThat(
            t.store.downloadDir
                .listFiles()
                .orEmpty(),
        ).isEmpty()
        val total = names.sumOf { n -> zips.getValue(n).size.toLong() }
        assertThat(seen.last()).isEqualTo(PackProgress(total, total))
        assertThat(seen.any { it.checking }).isTrue()
        // Asked again, nothing is downloaded: the files are there.
        assertThat(job(t, server).run(names, {}, { false })).isEqualTo(PackDownloadOutcome.Done)
        assertThat(server.asked).hasSize(3)
    }

    @Test
    fun `another phone's engine or a name not in the app's manifest is never fetched`() {
        val t = tools()
        val server = Server()
        job(t, server).run(listOf("engine-arm64-v8a.zip", "manifest.json", "voices.zip"), {}, { false })
        assertThat(server.asked).containsExactly("voices.zip")
    }

    @Test
    fun `a damaged file is downloaded again from the start, and twice damaged is an error`() {
        val t = tools()
        assertThat(
            job(t, Server(damage = setOf("voices.zip"))).run(listOf("voices.zip"), {}, { false }),
        ).isEqualTo(PackDownloadOutcome.Done)
        assertThat(t.missing(null).map { it.file }).containsExactly("engine-x86_64.zip")

        val t2 = StoreTools(VoicePackStore(tmp.newFolder(), manifest, "x86_64") { Long.MAX_VALUE })
        val always =
            object {
                fun open(u: URL): HttpURLConnection {
                    val b = zips.getValue("voices.zip").copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                    return FakeHttps(u, 200, emptyMap(), b)
                }
            }
        val out = PackDownloadJob(t2, PackDownloader(always::open), pause = {}).run(listOf("voices.zip"), {}, { false })
        assertThat(out).isEqualTo(PackDownloadOutcome.Failed(PackDownloadError.CHECK))
        assertThat(t2.missing(null).map { it.file }).contains("voices.zip")
    }

    @Test
    fun `a dropped connection is tried again after a pause and resumed`() {
        val t = tools()
        val server = Server(dropFirst = setOf("voices.zip"))
        assertThat(job(t, server).run(listOf("voices.zip"), {}, { false })).isEqualTo(PackDownloadOutcome.Done)
        assertThat(pauses).containsExactly(PackDownloadJob.PAUSES_MS.first())
        assertThat(server.asked).containsExactly("voices.zip", "voices.zip")
    }

    @Test
    fun `a connection that keeps dropping ends as a network error after three pauses`() {
        val t = tools()
        val dropping =
            object {
                fun open(u: URL): HttpURLConnection = FakeHttps(u, 200, emptyMap(), zips.getValue("voices.zip"), failAfter = 0)
            }
        val out = PackDownloadJob(t, PackDownloader(dropping::open), pause = { pauses += it }).run(listOf("voices.zip"), {}, { false })
        assertThat(out).isEqualTo(PackDownloadOutcome.Failed(PackDownloadError.NETWORK))
        assertThat(pauses).isEqualTo(PackDownloadJob.PAUSES_MS)
    }

    @Test
    fun `a connection that drops after making progress keeps going, and Cancel stops before the next file`() {
        val t = tools()
        var calls = 0
        val flaky =
            object {
                fun open(u: URL): HttpURLConnection {
                    val name = u.path.substringAfterLast('/')
                    val b = zips.getValue(name)
                    val part = t.store.downloadFile(manifest.files.first { it.file == name })
                    val have = if (part.isFile) part.length().toInt() else 0
                    calls++
                    // Every answer drops after a tenth of the file: about ten drops in a row, each with progress.
                    val rest = b.copyOfRange(have, b.size)
                    val headers = if (have > 0) mapOf("Content-Range" to "bytes $have-${b.size - 1}/${b.size}") else emptyMap()
                    return FakeHttps(u, if (have > 0) 206 else 200, headers, rest, failAfter = (b.size / 10).takeIf { rest.size > it })
                }
            }
        val out = PackDownloadJob(t, PackDownloader(flaky::open), pause = {}).run(listOf("engine-x86_64.zip"), {}, { false })
        assertThat(out).isEqualTo(PackDownloadOutcome.Done)
        assertThat(calls).isGreaterThan(PackDownloadJob.PAUSES_MS.size + 1)
        assertThat(calls).isAtMost(PackDownloadJob.MAX_TRIES)

        val server = Server()
        var stop = false
        val two = job(tools(), server).run(listOf("voices.zip", "speech-english.zip"), { if (it.checking) stop = true }, { stop })
        assertThat(two).isEqualTo(PackDownloadOutcome.Stopped)
        assertThat(server.asked).doesNotContain("speech-english.zip")
    }

    @Test
    fun `without room nothing is downloaded`() {
        val t = tools(free = 10)
        val server = Server()
        val out = job(t, server).run(listOf("voices.zip"), {}, { false })
        assertThat(out).isInstanceOf(PackDownloadOutcome.Failed::class.java)
        assertThat((out as PackDownloadOutcome.Failed).error).isEqualTo(PackDownloadError.NO_SPACE)
        assertThat(out.missingBytes).isGreaterThan(0)
        assertThat(server.asked).isEmpty()
    }

    @Test
    fun `a stop ends the job without adding the file`() {
        val t = tools()
        assertThat(job(t, Server()).run(listOf("voices.zip"), {}, { true })).isEqualTo(PackDownloadOutcome.Stopped)
        assertThat(t.missing(null).map { it.file }).contains("voices.zip")
    }
}
