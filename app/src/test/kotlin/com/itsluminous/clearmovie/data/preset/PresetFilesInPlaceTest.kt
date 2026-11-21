package com.itsluminous.clearmovie.data.preset

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.render.media3.FileMediaResolver
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * cm-frame-area (Robolectric): a preset made with "Keep a copy of the files"
 * off uses each file where it is (its read grant kept and recorded), one
 * with it on copies them as before, a moved or deleted file or a lost grant
 * asks for the file again, and the grant goes back when it is no longer
 * used. The frame's look (key colour and picture area) is stored on the
 * preset.
 */
@RunWith(RobolectricTestRunner::class)
class PresetFilesInPlaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private lateinit var store: PresetStore
    private val grants = FakeUriGrants()
    private val probe = UriProbe()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        phone = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, PresetStoreTest.DOCS)
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
                freeBytes = { 10_000_000_000L },
                dispatcher = Dispatchers.IO,
                randomToken = { "tok" + (++n).toString().padStart(5, '0') },
                grants = grants,
            )
    }

    @After
    fun tearDown() = db.close()

    private fun file(
        name: String,
        size: Int = 4000,
        mime: String = "video/mp4",
        persistable: Boolean = true,
    ): Uri {
        val uri = Uri.parse("content://${PresetStoreTest.DOCS}/document/${Uri.encode(name)}")
        phone.add(FakeMediaProvider.Row(uri, name, size.toLong(), mime, bytes = ByteArray(size) { 7 }))
        if (persistable) grants.persistable += uri.toString()
        return uri
    }

    private fun make(copy: Boolean): PresetId =
        runBlocking { requireNotNull(store.create("Wedding", PresetParts(intro = false, frame = true, ending = null, copyFiles = copy))) }

    private fun frame(id: PresetId): PresetBinding = runBlocking { (store.status(id).slots[PresetSlot.FRAME] as SlotStatus.Ready).binding }

    @Test
    fun `a new preset uses its files where they are by default, and keeps the link's grant on record`() =
        runBlocking<Unit> {
            assertThat(PresetParts().copyFiles).isFalse()
            val id = make(copy = false)
            assertThat(store.copiesFiles(id)).isFalse()
            val uri = file("Frame-White.mp4")
            assertThat(store.bindSlot(id, PresetSlot.FRAME, uri)).isInstanceOf(SlotBindResult.Bound::class.java)
            val b = frame(id)
            assertThat(b.isLinked).isTrue()
            assertThat(b.file).isNull()
            assertThat(b.uri).isEqualTo(uri)
            assertThat(b.sizeBytes).isEqualTo(4000)
            assertThat(probe.probedUris).containsExactly(uri)
            // Nothing was copied: only the preset's own definition file is in its folder.
            assertThat(
                files
                    .presetDir(id)
                    .listFiles()!!
                    .map { it.name }
                    .filter { it.startsWith("frame") },
            ).isEmpty()
            assertThat(grants.held).contains(uri.toString())
            assertThat(db.grants().usersOf(uri.toString())).isEqualTo(1)
            // The renderer reads it by its link.
            val resolver = FileMediaResolver(emptyMap(), null, mapOf(PresetSlot.FRAME to b.linkedUri!!))
            assertThat(resolver.resolve(MediaRef.Preset(PresetSlot.FRAME))).isEqualTo(uri)
        }

    @Test
    fun `with the switch on, the file is copied in as before`() =
        runBlocking<Unit> {
            val id = make(copy = true)
            assertThat(store.copiesFiles(id)).isTrue()
            assertThat(store.bindSlot(id, PresetSlot.FRAME, file("Frame-White.mp4"))).isInstanceOf(SlotBindResult.Bound::class.java)
            val b = frame(id)
            assertThat(b.isLinked).isFalse()
            assertThat(b.file!!.isFile).isTrue()
            assertThat(b.file!!.length()).isEqualTo(4000)
            assertThat(grants.held).isEmpty()
        }

    @Test
    fun `a link whose grant cannot be kept is copied instead`() =
        runBlocking<Unit> {
            val id = make(copy = false)
            store.bindSlot(id, PresetSlot.FRAME, file("Shared.mp4", persistable = false))
            assertThat(frame(id).isLinked).isFalse()
            assertThat(frame(id).file!!.isFile).isTrue()
        }

    @Test
    fun `presets the app ships keep copying their files`() =
        runBlocking<Unit> {
            assertThat(store.copiesFiles(PresetId("event-frame"))).isTrue()
            store.bindSlot(PresetId("event-frame"), PresetSlot.FRAME, file("Frame-White.mp4"))
            assertThat(frame(PresetId("event-frame")).isLinked).isFalse()
        }

    @Test
    fun `a moved or deleted file, a changed one, or a lost grant asks for the file again`() =
        runBlocking<Unit> {
            val id = make(copy = false)
            val uri = file("Frame-White.mp4")
            store.bindSlot(id, PresetSlot.FRAME, uri)
            assertThat(store.status(id).isReady).isTrue()
            // Its grant gone (the user cleared it, or the app was reinstalled).
            grants.held -= uri.toString()
            assertThat(store.status(id).slots[PresetSlot.FRAME]).isInstanceOf(SlotStatus.Missing::class.java)
            grants.held += uri.toString()
            // Replaced by a file of another size.
            phone.add(FakeMediaProvider.Row(uri, "Frame-White.mp4", 3000, "video/mp4", bytes = ByteArray(3000)))
            assertThat(store.status(id).slots[PresetSlot.FRAME]).isInstanceOf(SlotStatus.Changed::class.java)
            // Moved or deleted.
            phone.delete(uri)
            val status = store.status(id)
            assertThat(status.slots[PresetSlot.FRAME]).isInstanceOf(SlotStatus.Missing::class.java)
            assertThat(status.needsRepair).containsExactly(PresetSlot.FRAME)
            // Choosing it again repairs it.
            val again = file("Frame-White-2.mp4")
            store.bindSlot(id, PresetSlot.FRAME, again)
            assertThat(store.status(id).isReady).isTrue()
            assertThat(grants.released).contains(uri.toString())
        }

    @Test
    fun `a file chosen again gives the old link back, and deleting the preset gives its links back`() =
        runBlocking<Unit> {
            val id = make(copy = false)
            val first = file("A.mp4")
            val second = file("B.mp4")
            store.bindSlot(id, PresetSlot.FRAME, first)
            store.bindSlot(id, PresetSlot.FRAME, second)
            assertThat(grants.released).containsExactly(first.toString())
            assertThat(db.grants().usersOf(first.toString())).isEqualTo(0)
            assertThat(store.delete(id)).isEqualTo(PresetDeleteResult.Deleted)
            assertThat(grants.released).containsExactly(first.toString(), second.toString())
            assertThat(db.grants().usersOf(second.toString())).isEqualTo(0)
        }

    @Test
    fun `the frame's look is stored on the preset and replaces the measured green`() =
        runBlocking<Unit> {
            val id = make(copy = false)
            store.bindSlot(id, PresetSlot.FRAME, file("Frame-Magenta.mp4"))
            val key = KeySpec(ArgbColor(0xFFFF00FF), 0.2, 0.08)
            val area = NormRect(0.1, 0.1, 0.6, 0.7)
            assertThat(store.setFrameLook(id, key, area)).isInstanceOf(PresetSaveResult.Saved::class.java)
            val preset = store.effectivePreset(id)!!
            assertThat(preset.frame!!.key).isEqualTo(key)
            assertThat(preset.frame!!.contentRect).isEqualTo(area)
            assertThat(frame(id).keyColor).isNull()
            // Its files are still used where they are after the save.
            assertThat(store.copiesFiles(id)).isFalse()
            assertThat(store.setFrameLook(id, key, NormRect(0.0, 0.0, 0.02, 0.5))).isEqualTo(PresetSaveResult.Invalid)
        }

    @Test
    fun `a frame used in place gets its own green window measured, as a copy does`() =
        runBlocking<Unit> {
            val id = make(copy = false)
            // A white frame with a flat green window at x 0.25-0.75, y 0.2-0.6.
            probe.frames =
                List(3) {
                    FrameSample(
                        100,
                        50,
                        IntArray(5000) { i ->
                            if (i % 100 in 25 until 75 &&
                                i / 100 in 10 until 30
                            ) {
                                0xFF00D922.toInt()
                            } else {
                                -1
                            }
                        },
                    )
                }
            store.bindSlot(id, PresetSlot.FRAME, file("Frame-Small-Window.mp4"))
            assertThat(frame(id).isLinked).isTrue()
            val window =
                store
                    .definition(id)!!
                    .preset.frame!!
                    .contentRect
            assertThat(window.x).isWithin(1e-3).of(0.25)
            assertThat(window.y).isWithin(1e-3).of(0.2)
            assertThat(window.w).isWithin(1e-3).of(0.5)
            assertThat(window.h).isWithin(1e-3).of(0.4)
            assertThat(store.copiesFiles(id)).isFalse()
        }

    @Test
    fun `an unsafe link is never kept in place`() =
        runBlocking<Unit> {
            val id = make(copy = false)
            val uri = Uri.parse("content://${PresetStoreTest.DOCS}/document/a%20b " + "x".repeat(10))
            phone.add(FakeMediaProvider.Row(uri, "a b.mp4", 4000, "video/mp4", bytes = ByteArray(4000)))
            grants.persistable += uri.toString()
            store.bindSlot(id, PresetSlot.FRAME, uri)
            assertThat(grants.held).isEmpty()
        }

    /** Answers for links as `AndroidMediaProbe` would; files (copies) too. */
    class UriProbe : MediaProbe {
        val probedUris = mutableListOf<Uri>()
        var frames: List<FrameSample> = emptyList()

        private fun result(
            audio: Boolean,
            frameAt: List<Double> = emptyList(),
        ) = ProbeResult(
            durationUs = 4_000_000,
            mime = if (audio) "audio/mpeg" else "video/mp4",
            hasVideo = !audio,
            hasAudio = true,
            shownWidth = 1280,
            shownHeight = 720,
            frames = if (frameAt.isEmpty()) emptyList() else frames,
        )

        override suspend fun probe(
            file: File,
            frameAt: List<Double>,
            measureLoudness: Boolean,
        ): ProbeResult = result(file.name.endsWith(".mp3"), frameAt)

        override suspend fun probe(
            uri: Uri,
            frameAt: List<Double>,
            measureLoudness: Boolean,
        ): ProbeResult {
            probedUris += uri
            return result(uri.toString().endsWith(".mp3"), frameAt)
        }
    }
}
