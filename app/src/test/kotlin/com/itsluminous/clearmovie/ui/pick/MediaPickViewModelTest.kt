package com.itsluminous.clearmovie.ui.pick

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaFacts
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.MediaResolver
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import com.itsluminous.clearmovie.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * Requirement 6 for the photo step: links the picker returned are saved the
 * moment they arrive, so after a process death (a new view model and a new
 * repository on the same database, nothing open) they are taken in and put on
 * MAIN, without the picker opening again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaPickViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMediaProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        store = Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
        Dispatchers.resetMain()
        main.close()
    }

    private fun repository() = ProjectRepository(db, files, CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it })

    private fun access(): MediaAccess {
        val grants = FakeUriGrants()
        val gateway = MediaStoreGateway(context)
        return MediaAccess(
            context.contentResolver,
            MediaResolver(
                gateway,
                grants,
                context.packageName,
            ),
            gateway,
            grants,
            MediaCopier(),
            files,
            db,
            {
                50_000_000_000L
            },
        )
    }

    private val photos = MediaFactsProbe { _, _, _ -> MediaFacts(null, hasVideo = false, hasAudio = false, isImage = true) }

    private val settings =
        object : SettingsRepository {
            override val settings = flowOf(AppSettings())
            override val mediaAccessAsked = flowOf(true)

            override suspend fun setMediaAccessAsked() = Unit

            override suspend fun setThemeMode(mode: ThemeMode) = Unit

            override suspend fun setDynamicColor(enabled: Boolean) = Unit
        }

    @Test
    fun `picked links saved before a process death are taken in afterwards`() =
        runBlocking<Unit> {
            val id = ProjectId("p1")
            assertThat(repository().createDraft(ProjectFactory.blank(id), inEditor = false)).isTrue()
            val a = store.addMedia("a.jpg", 10, id = 1)
            val b = store.addMedia("b.jpg", 10, id = 2)
            // The new process: nothing open, the saved state as Android hands it back.
            val repo = repository()
            val saved =
                SavedStateHandle(
                    mapOf(
                        Routes.ARG_PROJECT to id.value,
                        MediaPickViewModel.KEY_PENDING to arrayListOf(a.toString(), b.toString()),
                    ),
                )
            val vm = runBlocking(main) { MediaPickViewModel(saved, repo, access(), photos, settings) }

            runBlocking(main) { vm.start(MediaAccessState.Full) }
            val event = withTimeout(TIMEOUT_MS) { vm.events.first() }

            assertThat(event).isEqualTo(PickEvent.Added(id, skipped = 0))
            val project = repo.open.value!!
            assertThat(project.mainClips.filterIsInstance<PhotoClip>().map { it.media.toString() }).hasSize(2)
            assertThat(vm.pending).isNull()
        }

    @Test
    fun `nothing picked goes back and does not keep an empty video`() =
        runBlocking<Unit> {
            val id = ProjectId("p2")
            val repo = repository()
            assertThat(repo.createDraft(ProjectFactory.blank(id), inEditor = false)).isTrue()
            val vm =
                runBlocking(
                    main,
                ) { MediaPickViewModel(SavedStateHandle(mapOf(Routes.ARG_PROJECT to id.value)), repo, access(), photos, settings) }

            runBlocking(main) { vm.picked(emptyList(), MediaAccessState.Full) }
            val event = withTimeout(TIMEOUT_MS) { vm.events.first() }

            assertThat(event).isEqualTo(PickEvent.Back)
            assertThat(repo.projectList()).isEmpty()
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
