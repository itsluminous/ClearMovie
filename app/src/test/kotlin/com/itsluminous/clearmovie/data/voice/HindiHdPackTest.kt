package com.itsluminous.clearmovie.data.voice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.domain.voice.VoicePackPiece
import com.itsluminous.clearmovie.domain.voice.VoicePackSource
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

/**
 * Hindi HD (cm-hindi-voices): an add-on pack whose big zips are split into
 * pieces, taken in only when each piece matches, unpacked into one model
 * folder, and offered by what the phone's memory runs.
 */
class HindiHdPackTest {
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

    private class Built(
        val file: VoicePackFile,
        val bytes: Map<String, ByteArray>,
    )

    /** A pack file of [entries]; split into [pieces] pieces when more than one. */
    private fun built(
        name: String,
        part: String,
        entries: List<Pair<String, String>>,
        pieces: Int = 1,
        abi: String? = null,
        folder: String? = null,
    ): Built {
        val bytes = zip(*entries.toTypedArray())
        val unpacked = entries.sumOf { it.second.length.toLong() }
        if (pieces == 1) {
            return Built(VoicePackFile(name, part, bytes.size.toLong(), unpacked, entries.size, sha(bytes), abi, folder), mapOf(name to bytes))
        }
        val size = (bytes.size + pieces - 1) / pieces
        val cut = (0 until pieces).map { i -> bytes.copyOfRange(i * size, minOf(bytes.size, (i + 1) * size)) }
        val ps = cut.mapIndexed { i, b -> VoicePackPiece("$name.%03d".format(i + 1), b.size.toLong(), sha(b)) }
        return Built(
            VoicePackFile(name, part, bytes.size.toLong(), unpacked, entries.size, sha(bytes), abi, folder, ps),
            ps.zip(cut).associate { (p, b) -> p.file to b },
        )
    }

    private val voices = built("voices.zip", VoicePackFile.VOICES, listOf("tts/voices.bin" to "six", "tts/tokens.txt" to "a 1"))
    private val engine = built("engine-x86_64.zip", VoicePackFile.ENGINE, listOf("lib/x86_64/libvosk.so" to "elf"), abi = "x86_64")
    private val small = built("speech-hindi.zip", VoicePackFile.SPEECH_HI, listOf("asr/hi/am/final.mdl" to "small"))
    private val hdVoices = built("voices-hindi-hd.zip", VoicePackFile.VOICES_HI_HD, listOf("tts-hd/voices.bin" to "eight"))
    private val hd =
        built(
            "speech-hindi-hd.zip",
            VoicePackFile.SPEECH_HI_HD,
            listOf(
                "asr/hi-hd/am/final.mdl" to "big am, quite a lot of bytes",
                "asr/hi-hd/conf/model.conf" to "conf",
                "asr/hi-hd/graph/HCLG.fst" to "graph bytes, more than a few of them",
                "asr/hi-hd/ivector/final.ie" to "ie",
            ),
            pieces = 3,
        )
    private val rescore =
        built(
            "speech-hindi-hd-rescore.zip",
            VoicePackFile.SPEECH_HI_HD_RESCORE,
            listOf("asr/hi-hd/rescore/G.carpa" to "carpa bytes", "asr/hi-hd/rnnlm/final.raw" to "rnnlm"),
            pieces = 2,
        )
    private val base = VoicePackManifest(VoicePackManifest.NAME, 1, listOf(voices.file, engine.file, small.file))
    private val addOn = VoicePackManifest(VoicePackManifest.NAME, 1, listOf(hdVoices.file, hd.file, rescore.file), folder = "hindi-hd-v1")
    private val manifest = base + addOn
    private val all = listOf(voices, engine, small, hdVoices, hd, rescore).flatMap { it.bytes.entries }.associate { it.key to it.value }

    private fun listed(b: Built) = manifest.files.first { it.file == b.file.file }

    private fun store(
        model: HindiModel,
        free: Long = Long.MAX_VALUE,
    ) = VoicePackStore(tmp.root, manifest, "x86_64", model) { free }

    private var written = 0

    private fun write(name: String): File = File(tmp.newFolder("dl-${written++}"), name).also { it.writeBytes(all.getValue(name)) }

    private fun addAll(
        s: VoicePackStore,
        vararg b: Built,
    ) = b.forEach { x -> assertThat(s.addDownloaded(listed(x), listed(x).downloads.map(::write)).added).hasSize(1) }

    @Test
    fun `the add-on's files are marked with its folder and the pack's own keep theirs`() {
        assertThat(manifest.folderOf(listed(voices))).isEqualTo("v1")
        assertThat(manifest.folderOf(listed(hd))).isEqualTo("hindi-hd-v1")
        assertThat(listed(hd).downloads).containsExactly("speech-hindi-hd.zip.001", "speech-hindi-hd.zip.002", "speech-hindi-hd.zip.003").inOrder()
        assertThat(manifest.files.all { it.valid() }).isTrue()
        // A split file is only taken in by its pieces.
        assertThat(manifest.bySha(listed(hd).sha256)).isNull()
        assertThat(manifest.pieceBySha(listed(hd).pieces[1].sha256)?.second?.file).isEqualTo("speech-hindi-hd.zip.002")
    }

    @Test
    fun `a phone's memory picks its Hindi recognizer`() {
        assertThat(HindiModel.of(2_800_000_000)).isEqualTo(HindiModel.SMALL)
        assertThat(HindiModel.of(3_700_000_000)).isEqualTo(HindiModel.LIGHT)
        assertThat(HindiModel.of(5_600_000_000)).isEqualTo(HindiModel.LIGHT)
        assertThat(HindiModel.of(7_300_000_000)).isEqualTo(HindiModel.FULL)
        assertThat(manifest.hindiSpeech(HindiModel.SMALL).map { it.file }).containsExactly("speech-hindi.zip")
        assertThat(manifest.hindiSpeech(HindiModel.LIGHT).map { it.file }).containsExactly("voices-hindi-hd.zip", "speech-hindi-hd.zip")
        assertThat(manifest.hindiSpeech(HindiModel.FULL).map { it.file })
            .containsExactly("voices-hindi-hd.zip", "speech-hindi-hd.zip", "speech-hindi-hd-rescore.zip")
        // A pack without Hindi HD falls back to the small recognizer.
        assertThat(base.hindiSpeech(HindiModel.FULL).map { it.file }).containsExactly("speech-hindi.zip")
    }

    @Test
    fun `Hindi asks for the best recognizer this phone runs, and only when none is here`() {
        assertThat(store(HindiModel.SMALL).missing(SpokenLanguage.HINDI).map { it.file })
            .containsExactly("voices.zip", "engine-x86_64.zip", "speech-hindi.zip")
        assertThat(store(HindiModel.LIGHT).missing(SpokenLanguage.HINDI).map { it.file })
            .containsExactly("voices.zip", "engine-x86_64.zip", "voices-hindi-hd.zip", "speech-hindi-hd.zip")
        assertThat(store(HindiModel.FULL).missing(SpokenLanguage.HINDI).map { it.file })
            .containsExactly("voices.zip", "engine-x86_64.zip", "voices-hindi-hd.zip", "speech-hindi-hd.zip", "speech-hindi-hd-rescore.zip")
        // The small recognizer already here keeps working: Hindi asks for nothing more, and Settings offers Hindi HD.
        val s = store(HindiModel.FULL)
        addAll(s, voices, engine, small)
        assertThat(s.missing(SpokenLanguage.HINDI)).isEmpty()
        assertThat(s.asrDir(SpokenLanguage.HINDI).path).endsWith("asr/hi")
        assertThat(s.hindiHdMissing().map { it.file })
            .containsExactly("voices-hindi-hd.zip", "speech-hindi-hd.zip", "speech-hindi-hd-rescore.zip")
        assertThat(store(HindiModel.SMALL).hindiHdMissing()).isEmpty()
    }

    @Test
    fun `a voice of Hindi HD needs only its voices file`() {
        val s = store(HindiModel.LIGHT)
        addAll(s, voices, engine)
        assertThat(s.missing(null, VoiceChoice.INDIAN_MALE)).isEmpty()
        assertThat(s.missing(null, VoiceChoice.INDIAN_FEMALE_2).map { it.file }).containsExactly("voices-hindi-hd.zip")
        assertThat(s.voicesFile.path).endsWith("tts/voices.bin")
        addAll(s, hdVoices)
        assertThat(s.missing(null, VoiceChoice.INDIAN_FEMALE_2)).isEmpty()
        assertThat(s.voicesFile.readText()).isEqualTo("eight")
    }

    @Test
    fun `pieces are checked one by one, joined in order and unpacked into the one model folder`() {
        val s = store(HindiModel.FULL)
        val f = listed(hd)
        val pieces = f.downloads.map(::write)
        assertThat(s.checkPiece(f, 0, pieces[0])).isTrue()
        assertThat(s.checkPiece(f, 1, pieces[0])).isFalse()
        assertThat(pieces[0].exists()).isFalse()
        val again = f.downloads.map(::write)
        // Out of order: refused, and the files are deleted.
        assertThat(s.addDownloaded(f, listOf(again[1], again[0], again[2])).rejected).isEqualTo(1)
        assertThat(again.none { it.exists() }).isTrue()
        addAll(s, hd)
        val model = s.root.resolve(VoicePackFile.HD_MODEL)
        assertThat(model.resolve("graph/HCLG.fst").readText()).startsWith("graph bytes")
        assertThat(s.asrDir(SpokenLanguage.HINDI)).isEqualTo(model)
        // The rescoring files go into the same folder, and adding the model again keeps them.
        addAll(s, rescore)
        assertThat(model.resolve("rescore/G.carpa").readText()).isEqualTo("carpa bytes")
        s.root.resolve(".added-speech-hindi-hd.zip").delete()
        addAll(s, hd)
        assertThat(model.resolve("rnnlm/final.raw").readText()).isEqualTo("rnnlm")
        assertThat(s.downloadDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a phone without 8 GB never takes the rescoring files in, by download or by hand`() {
        val s = store(HindiModel.LIGHT)
        val f = listed(rescore)
        assertThat(s.addDownloaded(f, f.downloads.map(::write)).rejected).isEqualTo(1)
        val r = s.add(f.downloads.map { n -> { all.getValue(n).inputStream() } })
        assertThat(r.added).isEmpty()
        assertThat(r.rejected).isEqualTo(2)
        assertThat(s.root.resolve("${VoicePackFile.HD_MODEL}/rescore").exists()).isFalse()
    }

    @Test
    fun `pieces chosen by hand, in any order, are kept until all are here and then added`() {
        val s = store(HindiModel.LIGHT)
        val names = listed(hd).downloads
        val first = s.add(listOf({ all.getValue(names[2]).inputStream() }, { all.getValue(names[0]).inputStream() }))
        assertThat(first.added).isEmpty()
        assertThat(first.rejected).isEqualTo(0)
        assertThat(s.missing(SpokenLanguage.HINDI).map { it.file }).contains("speech-hindi-hd.zip")
        val second = s.add(listOf({ all.getValue(names[1]).inputStream() }))
        assertThat(second.added.map { it.file }).containsExactly("speech-hindi-hd.zip")
        assertThat(s.isAdded(listed(hd))).isTrue()
    }

    @Test
    fun `rescoring folders left without their marker are removed before recognizing`() {
        val s = store(HindiModel.FULL)
        addAll(s, hd)
        val left = s.root.resolve("${VoicePackFile.HD_MODEL}/rescore").also { it.mkdirs() }
        left.resolve("G.carpa").writeText("half")
        s.prepareAsr(SpokenLanguage.HINDI)
        assertThat(left.exists()).isFalse()
        addAll(s, rescore)
        s.prepareAsr(SpokenLanguage.HINDI)
        assertThat(left.resolve("G.carpa").readText()).isEqualTo("carpa bytes")
    }

    @Test
    fun `a split file whose pieces do not add up, or are misnamed, is not a valid listing`() {
        val f = listed(hd)
        assertThat(f.copy(bytes = f.bytes + 1).valid()).isFalse()
        assertThat(f.copy(pieces = f.pieces.reversed()).valid()).isFalse()
        assertThat(f.copy(pieces = f.pieces.map { it.copy(file = it.file.replace(".zip.", ".bin.")) }).valid()).isFalse()
        assertThat(f.copy(folder = "../v1").valid()).isFalse()
        assertThat(f.copy(bytes = VoicePackFile.MAX_SPLIT_BYTES + 1).valid()).isFalse()
    }

    /** [VoiceTools] over a real store, as the download worker uses it. */
    private class StoreTools(
        val store: VoicePackStore,
    ) : VoiceTools {
        override val manifest = store.manifest
        override val supported = true

        override fun missing(
            speech: SpokenLanguage?,
            voice: VoiceChoice?,
        ) = store.missing(speech, voice)

        override fun notAdded() = store.manifest.files.filter { store.fits(it) && !store.isAdded(it) }

        override fun downloadFile(f: VoicePackFile): File = store.downloadFile(f)

        override fun pieceFile(name: String): File = store.pieceFile(name)

        override fun checkPiece(
            f: VoicePackFile,
            index: Int,
            file: File,
        ) = store.checkPiece(f, index, file)

        override fun addDownloaded(
            f: VoicePackFile,
            files: List<File>,
            stopped: () -> Boolean,
        ) = store.addDownloaded(f, files)

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

    @Test
    fun `the download fetches each piece from the add-on's folder, fetches a damaged piece again, then adds the zip`() {
        val asked = mutableListOf<String>()
        val damaged = mutableSetOf("speech-hindi-hd.zip.002")
        val opener =
            PackDownloader.Opener { u: URL ->
                val name = u.path.substringAfterLast('/')
                val folder = if (name.contains("hindi-hd")) "hindi-hd-v1" else "v1"
                assertThat(u.toString()).contains("/${VoicePackSource.COMMITS.getValue(folder)}/voice-packs/$folder/")
                asked += name
                val body = all.getValue(name).let { if (damaged.remove(name)) it.copyOf().also { b -> b[0] = (b[0] + 1).toByte() } else it }
                FakeHttps(u, 200, mapOf("Content-Length" to body.size.toString()), body) as HttpURLConnection
            }
        val t = StoreTools(store(HindiModel.LIGHT))
        val seen = mutableListOf<PackProgress>()
        val names = t.store.missing(SpokenLanguage.HINDI).map { it.file } + "speech-hindi-hd-rescore.zip"
        val out = PackDownloadJob(t, PackDownloader(opener), pause = {}).run(names, { seen += it }, { false })
        assertThat(out).isEqualTo(PackDownloadOutcome.Done)
        // The rescoring files are not for this phone, so they are never fetched; the damaged piece is fetched twice.
        assertThat(asked.filter { it.startsWith("speech-hindi-hd") })
            .containsExactly("speech-hindi-hd.zip.001", "speech-hindi-hd.zip.002", "speech-hindi-hd.zip.002", "speech-hindi-hd.zip.003")
            .inOrder()
        assertThat(t.store.missing(SpokenLanguage.HINDI)).isEmpty()
        assertThat(t.store.asrDir(SpokenLanguage.HINDI).path).endsWith(VoicePackFile.HD_MODEL)
        val total = listOf(voices, engine, hdVoices, hd).sumOf { it.file.bytes }
        assertThat(seen.last()).isEqualTo(PackProgress(total, total))
        assertThat(seen.all { it.done <= it.total }).isTrue()
    }
}
