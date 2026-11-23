package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.preset.CopyReason
import com.itsluminous.clearmovie.data.preset.MediaProbe
import com.itsluminous.clearmovie.data.preset.PresetFilesInPlaceTest
import com.itsluminous.clearmovie.data.preset.PresetParts
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.PresetStoreTest
import com.itsluminous.clearmovie.data.preset.ProbeResult
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.NewVideos
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.project.ShareDrafts
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import com.itsluminous.clearmovie.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors

/**
 * cm-preset-link-copy (Robolectric): choosing a file in preset setup says
 * "Copying" only when the file is really copied. A preset with "Keep a copy
 * of the files" off shows Checking while a file it uses where it is is
 * measured, and a note with the reason when a file has to be copied after
 * all; a preset that keeps copies copies, with no note.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PresetSetupLinkCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val grants = FakeUriGrants()
    private lateinit var phone: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private lateinit var store: PresetStore
    private lateinit var vm: PresetSetupViewModel

    /** What the setup screen showed each time a file was measured. */
    private val workWhileMeasured = mutableListOf<SetupWork?>()

    private val probe =
        object : MediaProbe {
            private val inner = PresetFilesInPlaceTest.UriProbe()

            override suspend fun probe(
                file: File,
                frameAt: List<Double>,
                measureLoudness: Boolean,
            ): ProbeResult? {
                workWhileMeasured += vm.ui.value.work
                return inner.probe(file, frameAt, measureLoudness)
            }

            override suspend fun probe(
                uri: Uri,
                frameAt: List<Double>,
                measureLoudness: Boolean,
            ): ProbeResult? {
                workWhileMeasured += vm.ui.value.work
                return inner.probe(uri, frameAt, measureLoudness)
            }
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
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
    fun tearDown() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
        main.close()
    }

    private fun file(
        name: String,
        persistable: Boolean,
    ): Uri {
        val uri = Uri.parse("content://${PresetStoreTest.DOCS}/document/${Uri.encode(name)}")
        phone.add(FakeMediaProvider.Row(uri, name, 4000, "video/mp4", bytes = ByteArray(4000) { 7 }))
        if (persistable) grants.persistable += uri.toString()
        return uri
    }

    private fun setUpFor(copy: Boolean): PresetId {
        val id =
            runBlocking {
                requireNotNull(
                    store.create("Wedding", PresetParts(intro = true, frame = false, ending = null, copyFiles = copy)),
                )
            }
        val repo = ProjectRepository(db, files, scope)
        vm =
            runBlocking(main) {
                PresetSetupViewModel(
                    SavedStateHandle(mapOf(Routes.ARG_PRESET to id.value)),
                    store,
                    NewVideos(store, repo),
                    ShareDrafts(store, repo),
                ) { _, _, _ -> null }
            }
        // The preset's copy setting has been read.
        awaitUi { it.preset != null && it.copiesFiles == copy }
        return id
    }

    private fun awaitUi(done: (SetupUi) -> Boolean): SetupUi =
        runBlocking {
            withTimeout(10_000) {
                while (!done(vm.ui.value)) delay(10)
                vm.ui.value
            }
        }

    private fun choose(uri: Uri): SetupUi {
        runBlocking(main) { vm.choose(PresetSlot.INTRO, uri) }
        return awaitUi { it.work == null && it.status?.slots?.get(PresetSlot.INTRO) is SlotStatus.Ready }
    }

    private fun intro(id: PresetId) = runBlocking { (store.status(id).slots[PresetSlot.INTRO] as SlotStatus.Ready).binding }

    @Test
    fun `a file used where it is shows Checking the whole time, never Copying, and leaves no note`() {
        val id = setUpFor(copy = false)
        val ui = choose(file("Intro.mp4", persistable = true))
        assertThat(intro(id).isLinked).isTrue()
        assertThat(workWhileMeasured).containsExactly(SetupWork.Checking)
        assertThat(ui.message).isNull()
    }

    @Test
    fun `a file whose place gives no lasting grant is copied, with a note that says why`() {
        val id = setUpFor(copy = false)
        val ui = choose(file("FromAnotherApp.mp4", persistable = false))
        assertThat(intro(id).isLinked).isFalse()
        assertThat(intro(id).file!!.readBytes()).isEqualTo(ByteArray(4000) { 7 })
        assertThat(ui.message).isEqualTo(SetupMessage.KeptCopy(CopyReason.SOURCE))
    }

    @Test
    fun `near the grant limit a file is copied, with a note that says the limit is why`() {
        val id = setUpFor(copy = false)
        grants.room = false
        val ui = choose(file("Intro.mp4", persistable = true))
        assertThat(intro(id).isLinked).isFalse()
        assertThat(ui.message).isEqualTo(SetupMessage.KeptCopy(CopyReason.LIMIT))
    }

    @Test
    fun `a preset that keeps copies copies the file, with no note`() {
        val id = setUpFor(copy = true)
        val ui = choose(file("Intro.mp4", persistable = true))
        assertThat(intro(id).isLinked).isFalse()
        assertThat(intro(id).file!!.isFile).isTrue()
        assertThat(ui.message).isNull()
        assertThat(grants.held).isEmpty()
    }
}
