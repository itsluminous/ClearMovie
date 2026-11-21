package com.itsluminous.clearmovie.data.preset

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.ProjectEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetParser
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.fileDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * ROADMAP cm-storage acceptance (Robolectric): preset binding with progress
 * and SHA-256, a deleted preset file shows as Missing(slot), the re-encoded
 * frame gets its key colour adjusted, and the user's own preset.json is
 * treated as untrusted.
 */
@RunWith(RobolectricTestRunner::class)
class PresetStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private val probe = FakeProbe()
    private var free = 10_000_000_000L

    /** Bind times close to the files' real modification times, as on a phone. */
    private var now = System.currentTimeMillis()
    private lateinit var store: PresetStore
    private val id = PresetId("event-frame")
    private val hashed = mutableListOf<String>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        phone = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, DOCS)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        var n = 0
        store =
            PresetStore(
                assets = context.assets,
                resolver = context.contentResolver,
                gateway = MediaStoreGateway(context),
                db = db,
                files = files,
                copier = MediaCopier(Dispatchers.IO, bufferBytes = 4096),
                probe = probe,
                freeBytes = { free },
                clock = { now },
                dispatcher = Dispatchers.IO,
                randomToken = { "tok" + (++n).toString().padStart(5, '0') },
                hash = {
                    hashed += it.name
                    MediaCopier.sha256(it)
                },
            )
        // As on an install made before cm-home-presets, which kept the shipped preset (PresetSeedTest covers new installs).
        runBlocking { check(store.adoptShipped(id)) }
        // File names below count from the first bind.
        n = 0
    }

    @After
    fun tearDown() = db.close()

    private fun file(
        name: String,
        bytes: ByteArray,
        mime: String,
    ): Uri {
        val uri = Uri.parse("content://$DOCS/document/${Uri.encode(name)}")
        phone.add(FakeMediaProvider.Row(uri, name, bytes.size.toLong(), mime, bytes = bytes))
        return uri
    }

    /** The preset's media files, without its kept preset.json. */
    private fun slotFiles() = files.presetDir(id).listFiles()!!.filterNot { it.name.startsWith("preset-") }

    private fun bindAll(frameFrames: String = "original") =
        runBlocking {
            now = System.currentTimeMillis() + 5_000
            probe.frames = frames(frameFrames)
            listOf(
                store.bindSlot(id, PresetSlot.INTRO, file("Intro.mp4", ByteArray(5000) { 1 }, "video/mp4")),
                store.bindSlot(id, PresetSlot.FRAME, file("Frame-White.mp4", ByteArray(7000) { 2 }, "video/mp4")),
                store.bindSlot(id, PresetSlot.ENDING, file("speech_last.mp3", ByteArray(900) { 3 }, "audio/mpeg")),
            )
        }

    @Test
    fun `the kept generic preset is offered and needs setup`() =
        runBlocking<Unit> {
            val definitions = store.definitions()
            assertThat(definitions.map { it.preset.id }).containsExactly(id)
            assertThat(definitions.single().source).isEqualTo(PresetDefinition.Source.USER)
            val status = store.status(id)
            assertThat(status.needsSetup).containsExactly(PresetSlot.INTRO, PresetSlot.FRAME, PresetSlot.ENDING)
            assertThat(status.isReady).isFalse()
        }

    @Test
    fun `binding copies each file in with its SHA-256, size, length and loudness`() =
        runBlocking<Unit> {
            val progress = mutableListOf<Long>()
            probe.frames = frames("original")
            val bytes = ByteArray(7000) { (it % 251).toByte() }
            val result =
                store.bindSlot(
                    id,
                    PresetSlot.FRAME,
                    file("Frame-White.mp4", bytes, "video/mp4"),
                ) { copied, _ -> progress += copied }

            val binding = (result as SlotBindResult.Bound).binding
            assertThat(binding.sha256).isEqualTo(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertThat(binding.sizeBytes).isEqualTo(7000)
            assertThat(binding.file!!.readBytes()).isEqualTo(bytes)
            assertThat(binding.file!!.parentFile).isEqualTo(files.presetDir(id))
            assertThat(binding.file!!.name).isEqualTo("frame-tok00001_Frame-White.mp4")
            assertThat(binding.originalName).isEqualTo("Frame-White.mp4")
            assertThat(binding.durationMs).isEqualTo(4000)
            assertThat(binding.loudnessLufs).isEqualTo(-14.5)
            assertThat(binding.keyAdjusted).isFalse()
            assertThat(progress.last()).isEqualTo(7000)
            assertThat(probe.lastFrameAt).isEqualTo(PresetStore.FRAME_SAMPLES)
        }

    @Test
    fun `with every file bound the preset is ready and gives its lengths`() =
        runBlocking<Unit> {
            assertThat(bindAll().all { it is SlotBindResult.Bound }).isTrue()
            assertThat(store.status(id).isReady).isTrue()
            assertThat(store.presetMedia(id)).isEqualTo(PresetMedia(4000, 4000, 4000))
        }

    @Test
    fun `a deleted preset file shows as Missing(slot) at launch`() =
        runBlocking<Unit> {
            bindAll()
            val frame = (store.status(id).slots.getValue(PresetSlot.FRAME) as SlotStatus.Ready).binding
            frame.file!!.delete()

            val status = store.status(id)

            assertThat(status.slots.getValue(PresetSlot.FRAME)).isEqualTo(SlotStatus.Missing(frame))
            assertThat(status.needsRepair).containsExactly(PresetSlot.FRAME)
            assertThat(status.isReady).isFalse()
            assertThat(store.presetMedia(id).frameMs).isNull()
        }

    @Test
    fun `a file whose size changed shows as Changed, and one edited in place is caught before export`() =
        runBlocking<Unit> {
            bindAll()
            val intro = (store.status(id).slots.getValue(PresetSlot.INTRO) as SlotStatus.Ready).binding
            intro.file!!.appendBytes(byteArrayOf(9))
            assertThat(store.status(id).slots.getValue(PresetSlot.INTRO)).isInstanceOf(SlotStatus.Changed::class.java)

            // Untouched files are not re-hashed: the bind time is after their last change.
            assertThat(store.verifyBeforeExport(id).slots.getValue(PresetSlot.FRAME)).isInstanceOf(SlotStatus.Ready::class.java)
            assertThat(hashed).isEmpty()
            val ending = (store.status(id).slots.getValue(PresetSlot.ENDING) as SlotStatus.Ready).binding
            ending.file!!.writeBytes(ByteArray(900) { 4 })
            ending.file!!.setLastModified(ending.boundAt + 60_000)
            assertThat(store.status(id).slots.getValue(PresetSlot.ENDING)).isInstanceOf(SlotStatus.Ready::class.java)
            assertThat(store.verifyBeforeExport(id).slots.getValue(PresetSlot.ENDING)).isInstanceOf(SlotStatus.Changed::class.java)
            assertThat(hashed).containsExactly(ending.file!!.name)
        }

    @Test
    fun `a re-encoded frame gets its key colour adjusted to the measured green`() =
        runBlocking<Unit> {
            val results = bindAll(frameFrames = "reencoded")
            val frame = (results[1] as SlotBindResult.Bound).binding
            assertThat(frame.keyAdjusted).isTrue()
            assertThat(frame.keyDeltaE!!).isWithin(1.0).of(9.0)
            assertThat(frame.keyColor).isEqualTo(frame.measuredKeyColor)

            val preset = store.effectivePreset(id)!!
            assertThat(preset.frame!!.key.color).isEqualTo(frame.keyColor)
            assertThat(preset.frame.key.color).isNotEqualTo(ArgbColor(0xFF00D922))
            // The tolerance and softness are the preset's own.
            assertThat(preset.frame.key.tolerance).isEqualTo(0.18)
        }

    @Test
    fun `binding again replaces the old copy, and choosing the file again repairs a missing slot`() =
        runBlocking<Unit> {
            bindAll()
            val old = (store.status(id).slots.getValue(PresetSlot.FRAME) as SlotStatus.Ready).binding
            old.file!!.delete()
            probe.frames = frames("original")
            val again = store.bindSlot(id, PresetSlot.FRAME, file("Frame-White (1).mp4", ByteArray(6000) { 5 }, "video/mp4"))
            val now = (again as SlotBindResult.Bound).binding
            assertThat(store.status(id).isReady).isTrue()
            assertThat(now.file!!.name).isNotEqualTo(old.file!!.name)
            assertThat(files.presetDir(id).listFiles()!!.map { it.name }).doesNotContain(old.file!!.name)
        }

    @Test
    fun `a file of the wrong kind, an unreadable link or a full phone is refused, keeping the old binding`() =
        runBlocking<Unit> {
            bindAll()
            val before = store.status(id)
            probe.hasVideo = false
            assertThat(
                store.bindSlot(id, PresetSlot.INTRO, file("song.mp3", ByteArray(10), "audio/mpeg")),
            ).isEqualTo(SlotBindResult.WrongKind)
            probe.hasVideo = true
            assertThat(store.bindSlot(id, PresetSlot.INTRO, Uri.parse("content://$DOCS/nothing-here"))).isEqualTo(SlotBindResult.Unreadable)
            assertThat(store.bindSlot(id, PresetSlot.INTRO, Uri.parse("file:///sdcard/Intro.mp4"))).isEqualTo(SlotBindResult.Unreadable)
            free = 100L
            assertThat(store.bindSlot(id, PresetSlot.INTRO, file("Intro2.mp4", ByteArray(10), "video/mp4")))
                .isInstanceOf(SlotBindResult.NotEnoughSpace::class.java)
            assertThat(
                store.bindSlot(PresetId("nope"), PresetSlot.INTRO, file("x.mp4", ByteArray(10), "video/mp4")),
            ).isEqualTo(SlotBindResult.UnknownPreset)
            assertThat(store.status(id)).isEqualTo(before)
            assertThat(slotFiles()).hasSize(3)
        }

    @Test
    fun `cancelling a bind leaves no partial file and the old binding in place`() =
        runBlocking<Unit> {
            bindAll()
            val before = store.status(id)
            val described = Uri.parse("content://$DOCS/document/slow.mp4")
            phone.add(FakeMediaProvider.Row(described, "slow.mp4", 50_000_000, "video/mp4"))
            shadowOf(context.contentResolver).registerInputStreamSupplier(described) { SlowStream(50_000_000) }
            var copied = 0L
            val job = async(Dispatchers.Default) { store.bindSlot(id, PresetSlot.FRAME, described) { c, _ -> copied = c } }
            withTimeout(30_000) { while (copied < 5_000_000 && job.isActive) delay(5) }
            assertThat(job.isActive).isTrue()
            job.cancelAndJoin()
            assertThat(store.status(id)).isEqualTo(before)
            assertThat(
                files
                    .presetDir(id)
                    .listFiles()!!
                    .map { it.name }
                    .filter { it.endsWith(".part") || "slow" in it },
            ).isEmpty()
        }

    @Test
    fun `a valid user preset json overrides the bundled one, with its own name`() =
        runBlocking<Unit> {
            val json = javaClass.classLoader!!.getResourceAsStream("presets/user-preset.json")!!.readBytes()
            val result = store.bindDefinition(file("preset.json", json, "application/json"))
            assertThat(result).isInstanceOf(DefinitionBindResult.Bound::class.java)
            val definition = store.definition(id)!!
            assertThat(definition.source).isEqualTo(PresetDefinition.Source.USER)
            assertThat(definition.preset.displayName!!.en).isEqualTo("My channel")
            assertThat(store.definitions()).hasSize(1)
        }

    @Test
    fun `an invalid, oversized or non-UTF-8 preset json is refused and nothing is stored`() =
        runBlocking<Unit> {
            val rows = db.presets().definitions()
            val stored =
                files.presetsRoot
                    .walkTopDown()
                    .filter { it.isFile }
                    .toList()
            val bad =
                store.bindDefinition(
                    file(
                        "preset.json",
                        """{"schemaVersion":1,"id":"../evil","displayName":{"en":"x"}}""".toByteArray(),
                        "application/json",
                    ),
                )
            assertThat(bad).isInstanceOf(DefinitionBindResult.Invalid::class.java)
            val huge =
                store.bindDefinition(
                    file("big.json", ByteArray(PresetParser.MAX_CHARS * 4 + 10) { ' '.code.toByte() }, "application/json"),
                )
            assertThat(huge).isInstanceOf(DefinitionBindResult.Invalid::class.java)
            val latin1 = store.bindDefinition(file("l1.json", byteArrayOf(0x7B, 0xE9.toByte(), 0x7D), "application/json"))
            assertThat(latin1).isEqualTo(DefinitionBindResult.Invalid(listOf("the file is not UTF-8 text")))
            assertThat(store.bindDefinition(Uri.parse("content://$DOCS/missing.json"))).isEqualTo(DefinitionBindResult.Unreadable)
            assertThat(db.presets().definitions()).isEqualTo(rows)
            assertThat(
                files.presetsRoot
                    .walkTopDown()
                    .filter { it.isFile }
                    .toList(),
            ).isEqualTo(stored)
        }

    @Test
    fun `a stored user preset that was damaged on disk is ignored, never trusted`() =
        runBlocking<Unit> {
            val json = javaClass.classLoader!!.getResourceAsStream("presets/user-preset.json")!!.readBytes()
            store.bindDefinition(file("preset.json", json, "application/json"))
            val row = db.presets().definition(id.value)!!
            files.presetFile(id, row.fileName).writeText("""{"schemaVersion":1,"id":"other-id","displayName":{"en":"x"}}""")
            assertThat(store.definitions()).isEmpty()
            assertThat(store.definition(id)).isNull()
            // A video made from it still gets the app's own copy.
            db.projects().upsert(ProjectEntity("v1", null, id.value, 2, "{}", null, now, now))
            assertThat(store.definition(id)!!.source).isEqualTo(PresetDefinition.Source.BUNDLED)
        }

    @Test
    fun `a preset json for a preset the app does not ship is refused`() =
        runBlocking<Unit> {
            val json =
                javaClass.classLoader!!
                    .getResourceAsStream("presets/user-preset.json")!!
                    .readBytes()
                    .decodeToString()
                    .replace("\"event-frame\"", "\"another-one\"")
            val result = store.bindDefinition(file("preset.json", json.toByteArray(), "application/json"))
            assertThat(result).isEqualTo(DefinitionBindResult.Invalid(listOf("id: ClearMovie has no preset called \"another-one\"")))
            assertThat(store.definitions().map { it.preset.id }).containsExactly(id)
        }

    @Test
    fun `cancelling while the file is being checked leaves the old binding`() =
        runBlocking<Unit> {
            bindAll()
            val before = store.status(id)
            probe.block = true
            val job =
                async(Dispatchers.Default) { store.bindSlot(id, PresetSlot.INTRO, file("Intro2.mp4", ByteArray(500) { 6 }, "video/mp4")) }
            withTimeout(10_000) { while (!probe.blocking) delay(5) }
            job.cancelAndJoin()
            assertThat(store.status(id)).isEqualTo(before)
            assertThat(slotFiles()).hasSize(3)
        }

    @Test
    fun `files no row names, left by a process killed after its copy, are swept once old`() =
        runBlocking<Unit> {
            bindAll()
            val dir = files.presetDir(id)
            val orphan = File(dir, "intro-dead_Intro.mp4").apply { writeBytes(ByteArray(10)) }
            val young = File(dir, "intro-busy_Intro.mp4").apply { writeBytes(ByteArray(10)) }
            orphan.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
            assertThat(store.sweepOrphans(System.currentTimeMillis())).isEqualTo(1)
            assertThat(orphan.exists()).isFalse()
            assertThat(young.exists()).isTrue()
            assertThat(store.status(id).isReady).isTrue()
        }

    private fun frames(name: String): List<FrameSample> =
        listOf(10, 50, 90).map { pct ->
            val image = javaClass.classLoader!!.getResourceAsStream("fixtures/media/frame-$name-$pct.png").use { ImageIO.read(it) }
            FrameSample(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }

    /** Decoding needs the phone's codecs; this answers like `AndroidMediaProbe` would. */
    class FakeProbe : MediaProbe {
        var frames: List<FrameSample> = emptyList()
        var hasVideo = true
        var lastFrameAt: List<Double>? = null

        /** The picture's size as shown (cm-aspect-ratio); 0 x 0 says nothing about its shape. */
        var shown: Pair<Int, Int> = 0 to 0

        /**
         * Makes the next probe run until its caller is cancelled and then return
         * normally anyway, like a decoder that swallows the cancellation: the
         * store itself must notice the Cancel.
         */
        @Volatile var block = false

        @Volatile var blocking = false

        override suspend fun probe(
            file: File,
            frameAt: List<Double>,
            measureLoudness: Boolean,
        ): ProbeResult {
            if (block) {
                blocking = true
                while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) Thread.sleep(5)
                block = false
            }
            lastFrameAt = frameAt.takeIf { it.isNotEmpty() } ?: lastFrameAt
            val audioOnly = file.name.endsWith(".mp3")
            return ProbeResult(
                durationUs = 4_000_000,
                mime = if (audioOnly) "audio/mpeg" else "video/mp4",
                hasVideo = hasVideo && !audioOnly,
                hasAudio = true,
                frames = if (frameAt.isEmpty()) emptyList() else frames,
                loudnessLufs = if (measureLoudness) -14.5 else null,
                shownWidth = if (audioOnly) 0 else shown.first,
                shownHeight = if (audioOnly) 0 else shown.second,
            )
        }
    }

    private class SlowStream(
        private val size: Int,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos++ < size) 0 else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (pos >= size) return -1
            val n = minOf(len, size - pos)
            Thread.sleep(1)
            pos += n
            return n
        }
    }

    internal companion object {
        const val DOCS = "com.android.externalstorage.documents"
    }
}
