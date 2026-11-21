package com.itsluminous.clearmovie.data.preset

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.PresetBindingEntity
import com.itsluminous.clearmovie.data.db.ProjectEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
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
 * cm-home-presets (Robolectric): a new install starts with no presets, while
 * an install made before keeps the Event frame as an ordinary preset it can
 * edit or delete, and a video made from it always finds it.
 */
@RunWith(RobolectricTestRunner::class)
class PresetSeedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private val probe = PresetStoreTest.FakeProbe()
    private var free = 10_000_000_000L
    private var n = 0
    private val eventFrame = PresetId("event-frame")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        phone = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, PresetStoreTest.DOCS)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
    }

    @After
    fun tearDown() = db.close()

    /** The store as one launch of the app makes it: [updated] says the app was updated, not newly installed. */
    private fun launch(updated: Boolean) =
        PresetStore(
            assets = context.assets,
            resolver = context.contentResolver,
            gateway = MediaStoreGateway(context),
            db = db,
            files = files,
            copier = MediaCopier(Dispatchers.IO, bufferBytes = 4096),
            probe = probe,
            freeBytes = { free },
            clock = { 1_000L },
            dispatcher = Dispatchers.IO,
            randomToken = { "tok" + (++n).toString().padStart(5, '0') },
            installedBefore = { updated },
        )

    private fun ids(store: PresetStore) = runBlocking { store.definitions().map { it.preset.id } }

    private suspend fun videoFrom(id: PresetId) = db.projects().upsert(ProjectEntity("v1", null, id.value, 2, "{}", null, 1, 1))

    private fun file(
        name: String,
        bytes: ByteArray,
    ): Uri {
        val uri = Uri.parse("content://${PresetStoreTest.DOCS}/document/${Uri.encode(name)}")
        phone.add(FakeMediaProvider.Row(uri, name, bytes.size.toLong(), "video/mp4", bytes = bytes))
        return uri
    }

    @Test
    fun `a new install has no presets, and a new preset still starts from the shipped template`() =
        runBlocking<Unit> {
            val store = launch(updated = false)
            assertThat(store.definitions()).isEmpty()
            assertThat(store.definition(eventFrame)).isNull()
            assertThat(files.presetSeedMarker.exists()).isTrue()
            val id = store.create("Mine", PresetParts())!!
            assertThat(ids(store)).containsExactly(id)
            // A later update of the app does not bring the Event frame back.
            assertThat(ids(launch(updated = true))).containsExactly(id)
        }

    @Test
    fun `an updated install keeps the Event frame first, as a preset it can edit and delete for good`() =
        runBlocking<Unit> {
            val store = launch(updated = true)
            val own = store.create("Mine", PresetParts())!!
            assertThat(ids(store)).containsExactly(eventFrame, own).inOrder()
            val kept = store.definition(eventFrame)!!
            assertThat(kept.source).isEqualTo(PresetDefinition.Source.USER)
            assertThat(store.save(kept.preset.copy(displayName = LocalizedText.same("Shaadi")))).isInstanceOf(
                PresetSaveResult.Saved::class.java,
            )
            assertThat(store.definition(eventFrame)!!.preset.displayName).isEqualTo(LocalizedText.same("Shaadi"))
            assertThat(store.delete(eventFrame)).isEqualTo(PresetDeleteResult.Deleted)
            assertThat(ids(store)).containsExactly(own)
            // The next launches leave it deleted.
            assertThat(ids(launch(updated = true))).containsExactly(own)
        }

    @Test
    fun `an install with the Event frame set up or in a video keeps it, whatever the update signal says`() =
        runBlocking<Unit> {
            db.presets().upsertBinding(
                PresetBindingEntity(
                    "event-frame",
                    "intro",
                    "intro-a.mp4",
                    "00",
                    5,
                    null,
                    "video/mp4",
                    4_000_000,
                    null,
                    null,
                    null,
                    null,
                    1,
                ),
            )
            assertThat(ids(launch(updated = false))).containsExactly(eventFrame)
        }

    @Test
    fun `deleting the kept Event frame that a video uses only hides it, and the video keeps it`() =
        runBlocking<Unit> {
            videoFrom(eventFrame)
            val store = launch(updated = false)
            assertThat(ids(store)).containsExactly(eventFrame)
            assertThat(store.delete(eventFrame)).isEqualTo(PresetDeleteResult.Retired(1))
            assertThat(store.definitions()).isEmpty()
            assertThat(store.definition(eventFrame)!!.retired).isTrue()
            assertThat(ids(launch(updated = true))).isEmpty()
        }

    @Test
    fun `a video made from the Event frame on an install with no copy of it gets the app's own, never put back on the list`() =
        runBlocking<Unit> {
            val store = launch(updated = false)
            assertThat(store.definitions()).isEmpty()
            videoFrom(eventFrame)
            val fallback = store.definition(eventFrame)!!
            assertThat(fallback.source).isEqualTo(PresetDefinition.Source.BUNDLED)
            assertThat(fallback.retired).isTrue()
            assertThat(store.save(fallback.preset)).isEqualTo(PresetSaveResult.NotSaved)
            assertThat(store.status(eventFrame).needsSetup).containsExactly(PresetSlot.INTRO, PresetSlot.FRAME, PresetSlot.ENDING)
            // Repairing it for the video binds its files without listing it.
            assertThat(store.bindSlot(eventFrame, PresetSlot.INTRO, file("Intro.mp4", ByteArray(500) { 1 })))
                .isInstanceOf(SlotBindResult.Bound::class.java)
            assertThat(store.definitions()).isEmpty()
        }

    @Test
    fun `a first launch marked before the database existed keeps no preset, even if a later signal says otherwise`() =
        runBlocking<Unit> {
            // What InstallHistory does on the first launch ever: the answer is recorded before anything else.
            files.presetSeedMarker.parentFile?.mkdirs()
            files.presetSeedMarker.createNewFile()
            assertThat(launch(updated = true).definitions()).isEmpty()
        }

    @Test
    fun `an Event frame an older version could not store is kept at a later launch`() =
        runBlocking<Unit> {
            free = 0
            assertThat(launch(updated = true).definitions()).isEmpty()
            assertThat(files.presetSeedMarker.exists()).isFalse()
            free = 10_000_000_000L
            assertThat(ids(launch(updated = true))).containsExactly(eventFrame)
            assertThat(files.presetSeedMarker.exists()).isTrue()
        }

    @Test
    fun `an install that had edited the Event frame keeps its own copy, once`() =
        runBlocking<Unit> {
            val before = launch(updated = false)
            check(before.adoptShipped(eventFrame))
            val edited = before.definition(eventFrame)!!.preset.copy(displayName = LocalizedText.same("Mine"))
            before.save(edited)
            files.presetSeedMarker.delete()
            val after = launch(updated = true)
            assertThat(after.definitions().map { it.preset }).containsExactly(edited)
            assertThat(after.adoptShipped(eventFrame)).isTrue()
            assertThat(after.definitions().map { it.preset }).containsExactly(edited)
            assertThat(after.adoptShipped(PresetId("no-such-preset"))).isFalse()
        }
}
