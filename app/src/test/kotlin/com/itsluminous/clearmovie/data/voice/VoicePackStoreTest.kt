package com.itsluminous.clearmovie.data.voice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Taking in downloaded voice pack files (cm-voice-choice): only the listed bytes, unzipped only inside the pack. */
class VoicePackStoreTest {
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

    private fun file(
        name: String,
        part: String,
        bytes: ByteArray,
        entries: List<Pair<String, String>>,
        abi: String? = null,
    ) = VoicePackFile(name, part, bytes.size.toLong(), entries.sumOf { it.second.length.toLong() }, entries.size, sha(bytes), abi)

    private val voicesEntries = listOf("tts/model.int8.onnx" to "model", "tts/tokens.txt" to "a 1")
    private val voices = zip(*voicesEntries.toTypedArray())
    private val engineEntries = listOf("lib/x86_64/libvosk.so" to "elf")
    private val engine = zip(*engineEntries.toTypedArray())
    private val armEntries = listOf("lib/arm64-v8a/libvosk.so" to "elf")
    private val armEngine = zip(*armEntries.toTypedArray())
    private val englishEntries = listOf("asr/en/am/final.mdl" to "am")
    private val english = zip(*englishEntries.toTypedArray())
    private val manifest =
        VoicePackManifest(
            VoicePackManifest.NAME,
            1,
            listOf(
                file("voices.zip", VoicePackFile.VOICES, voices, voicesEntries),
                file("engine-x86_64.zip", VoicePackFile.ENGINE, engine, engineEntries, "x86_64"),
                file("engine-arm64-v8a.zip", VoicePackFile.ENGINE, armEngine, armEntries, "arm64-v8a"),
                file("speech-english.zip", VoicePackFile.SPEECH_EN, english, englishEntries),
            ),
        )

    private fun store(free: Long = Long.MAX_VALUE) = VoicePackStore(tmp.root, manifest, "x86_64") { free }

    @Test
    fun `listed files are taken in, unzipped into their folders, and then nothing is missing`() {
        val s = store()
        assertThat(
            s.missing(SpokenLanguage.ENGLISH).map { it.file },
        ).containsExactly("voices.zip", "engine-x86_64.zip", "speech-english.zip")
        val r = s.add(listOf({ voices.inputStream() }, { engine.inputStream() }, { english.inputStream() }))
        assertThat(r.added.map { it.file }).containsExactly("voices.zip", "engine-x86_64.zip", "speech-english.zip")
        assertThat(r.rejected).isEqualTo(0)
        assertThat(s.missing(SpokenLanguage.ENGLISH)).isEmpty()
        assertThat(s.ttsDir.resolve("tokens.txt").readText()).isEqualTo("a 1")
        assertThat(s.libDir.resolve("libvosk.so").readText()).isEqualTo("elf")
        assertThat(s.libDir.resolve("libvosk.so").canWrite()).isFalse()
        assertThat(s.asrDir(SpokenLanguage.ENGLISH).resolve("am/final.mdl").readText()).isEqualTo("am")
        assertThat(s.root.resolve(".incoming").exists()).isFalse()
        assertThat(s.bytesOnDisk()).isGreaterThan(0)
        // Adding a file again changes nothing; Remove takes the whole pack away.
        assertThat(s.add(listOf({ voices.inputStream() })).added).isEmpty()
        s.remove()
        assertThat(s.anyAdded()).isFalse()
        assertThat(s.missing(null)).hasSize(2)
    }

    @Test
    fun `a file not in the manifest, another phone's engine, or a damaged download is refused`() {
        val s = store()
        val r =
            s.add(
                listOf(
                    { "not a zip".byteInputStream() },
                    { armEngine.inputStream() },
                    { voices.copyOf(voices.size - 1).inputStream() },
                    { null },
                    { throw java.io.IOException("gone") },
                ),
            )
        assertThat(r.added).isEmpty()
        assertThat(r.rejected).isEqualTo(5)
        assertThat(s.anyAdded()).isFalse()
    }

    @Test
    fun `a listed file whose entries leave the pack folder or break the listing is refused and leaves nothing behind`() {
        for (entries in listOf(
            listOf("tts/../../evil" to "x", "tts/tokens.txt" to "a 1"),
            listOf("/tts/abs" to "x", "tts/tokens.txt" to "a 1"),
            listOf("asr/en/x" to "x", "tts/tokens.txt" to "a 1"),
            listOf("tts/model.int8.onnx" to "model", "tts/tokens.txt" to "a 1", "tts/extra" to ""),
        )) {
            val bytes = zip(*entries.toTypedArray())
            // The manifest lists these exact bytes, but says the part has two entries of 8 bytes: they must still obey the rules.
            val listed = VoicePackFile("voices.zip", VoicePackFile.VOICES, bytes.size.toLong(), 8, 2, sha(bytes))
            val s = VoicePackStore(tmp.newFolder(), manifest.copy(files = listOf(listed)), "x86_64") { Long.MAX_VALUE }
            val r = s.add(listOf({ bytes.inputStream() }))
            assertThat(r.added).isEmpty()
            assertThat(r.rejected).isEqualTo(1)
            assertThat(
                s.root
                    .walkTopDown()
                    .filter { it.isFile }
                    .toList(),
            ).isEmpty()
            assertThat(tmp.root.resolve("evil").exists()).isFalse()
        }
    }

    @Test
    fun `without room on the phone nothing is added`() {
        val s = store(free = 10)
        val r = s.add(listOf({ voices.inputStream() }))
        assertThat(r.added).isEmpty()
        assertThat(s.anyAdded()).isFalse()
    }

    @Test
    fun `a phone the pack has no engine for needs nothing and is not supported`() {
        val s = VoicePackStore(tmp.root, manifest, "mips") { Long.MAX_VALUE }
        assertThat(s.supported).isFalse()
        assertThat(s.missing(SpokenLanguage.ENGLISH)).isEmpty()
    }

    @Test
    fun `a download is unzipped only when its bytes are the file the app's own manifest lists`() {
        val s = store()
        val listed = manifest.files.first { it.file == "voices.zip" }
        // Another listed file's bytes under this name, a damaged copy, and another phone's engine are refused.
        for ((f, bytes) in listOf(
            listed to engine,
            listed to voices.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
            manifest.files.first { it.abi == "arm64-v8a" } to armEngine,
            listed.copy(sha256 = sha(engine), bytes = engine.size.toLong()) to engine,
        )) {
            val zip = s.downloadFile(f).apply { parentFile.mkdirs() }
            zip.writeBytes(bytes)
            val r = s.addDownloaded(f, listOf(zip))
            assertThat(r.added).isEmpty()
            assertThat(r.rejected).isEqualTo(1)
            assertThat(zip.exists()).isFalse()
        }
        assertThat(s.anyAdded()).isFalse()
        val zip = s.downloadFile(listed).apply { parentFile.mkdirs() }
        zip.writeBytes(voices)
        assertThat(s.addDownloaded(listed, listOf(zip)).added).containsExactly(listed)
        assertThat(zip.exists()).isFalse()
        assertThat(s.ttsDir.resolve("tokens.txt").readText()).isEqualTo("a 1")
        // Partial downloads go with Cancel, and with Remove.
        s.downloadFile(listed).apply { parentFile.mkdirs() }.writeBytes(ByteArray(3))
        s.clearDownloads()
        assertThat(s.downloadDir.exists()).isFalse()
    }

    @Test
    fun `a download needs room for what it unpacks`() {
        val s = store(free = 10)
        val listed = manifest.files.first { it.file == "voices.zip" }
        val zip = s.downloadFile(listed).apply { parentFile.mkdirs() }
        zip.writeBytes(voices)
        assertThat(s.addDownloaded(listed, listOf(zip)).outOfSpace).isTrue()
        assertThat(s.anyAdded()).isFalse()
    }
}
