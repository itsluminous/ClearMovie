package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.MediaResolver
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.PresetStoreTest
import com.itsluminous.clearmovie.data.project.LaunchDecision
import com.itsluminous.clearmovie.data.project.OpenResult
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.ui.navigation.Routes
import com.itsluminous.clearmovie.work.ExportsInProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * More options > Delete video (cm-delete-project), through the editor's view
 * model: it asks first, deletes only the project (its row and its own
 * folder), leaves the editor, and the app then starts at Home; an export of
 * the video still running blocks it; nothing written after the delete can
 * bring the video back.
 */
@RunWith(RobolectricTestRunner::class)
class EditorDeleteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val id = ProjectId("p-delete")

    /** What the editor told its screen: its events and Delete's prompts, in order. */
    private val events = CopyOnWriteArrayList<Any>()
    private val cancelled = CopyOnWriteArrayList<ProjectId>()

    @Before
    fun setUp() {
        db =
            Room
                .databaseBuilder(context, ClearMovieDatabase::class.java, File(tmp.root, "db").path)
                .addMigrations(*ClearMovieDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun editorOn(
        repository: ProjectRepository,
        exportBusy: () -> Boolean = { false },
    ): EditorViewModel {
        val gateway = MediaStoreGateway(context)
        val grants = FakeUriGrants()
        val presets =
            PresetStore(
                context.assets,
                context.contentResolver,
                gateway,
                db,
                files,
                MediaCopier(Dispatchers.IO, bufferBytes = 4096),
                PresetStoreTest.FakeProbe(),
                { 50_000_000_000L },
                dispatcher = Dispatchers.IO,
            )
        val access =
            MediaAccess(
                context.contentResolver,
                MediaResolver(gateway, grants, context.packageName),
                gateway,
                grants,
                MediaCopier(),
                files,
                db,
                { 50_000_000_000L },
            )
        return EditorViewModel(
            SavedStateHandle(mapOf(Routes.ARG_PROJECT to id.value)),
            context,
            repository,
            ProjectMedia(presets, files),
            access,
            MediaFactsProbe { _, _, _ -> null },
            VoiceRecordings(files, { error("no microphone in this test") }, exportBusy = { false }),
            exports =
                object : ExportsInProgress {
                    override fun of(projectId: ProjectId) = exportBusy()

                    override fun cancel(projectId: ProjectId) {
                        cancelled += projectId
                    }
                },
        ).also { vm ->
            scope.launch(Dispatchers.Main) { vm.events.collect { events += it } }
            scope.launch(Dispatchers.Main) { vm.deletePrompts.collect { events += it } }
        }
    }

    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** A named video with one photo copied into its own folder, open in the editor. */
    private fun openVideo(repository: ProjectRepository): File {
        val photo = MediaRef.Copied("photo.png")
        val copy = File(files.projectMediaDir(id).apply { mkdirs() }, "photo.png").apply { writeText("photo") }
        val project =
            ProjectFactory
                .blank(id)
                .addToMain(listOf(PhotoClip(ClipId("ph"), photo, 3000)))
                .rename("Party")
        runBlocking {
            assertThat(repository.createDraft(project)).isTrue()
            assertThat(repository.open(id)).isInstanceOf(OpenResult.Opened::class.java)
        }
        return copy
    }

    @Test
    fun `Delete asks first, then removes the video and its own files, and the app starts at Home`() {
        val repository = ProjectRepository(db, files, scope, debounceMs = 50, maxWaitMs = 100)
        val copy = openVideo(repository)
        val vm = editorOn(repository)
        idleUntil { vm.ui.value.project != null }

        vm.askDelete()
        idleUntil { events.isNotEmpty() }
        assertThat(events).containsExactly(DeletePrompt.ASK)
        // Asking alone changes nothing.
        assertThat(runBlocking { db.projects().get(id.value) }).isNotNull()

        vm.deleteProject()
        idleUntil { EditorEvent.Closed in events }
        assertThat(events).containsExactly(DeletePrompt.ASK, EditorEvent.Closed).inOrder()
        assertThat(runBlocking { db.projects().get(id.value) }).isNull()
        assertThat(copy.exists()).isFalse()
        assertThat(files.projectDir(id).exists()).isFalse()
        assertThat(runBlocking { repository.projectList() }).isEmpty()
        // Reopening the app: Home, not the deleted video.
        val fresh = ProjectRepository(db, files, scope)
        assertThat(runBlocking { fresh.launchDecision() }).isEqualTo(LaunchDecision.Home)
        assertThat(runBlocking { fresh.open(id) }).isEqualTo(OpenResult.NotFound)
        // An export asked for after the check is stopped too.
        assertThat(cancelled).containsExactly(id)
    }

    @Test
    fun `a Delete confirmed before the video has opened (a question brought back after a process death) does nothing`() {
        val repository = ProjectRepository(db, files, scope)
        val copy = openVideo(repository)
        // Opened elsewhere: this editor has not loaded it yet when Delete arrives.
        val vm = editorOn(repository)
        if (vm.ui.value.project == null) {
            vm.deleteProject()
            vm.askDelete()
        }
        idleUntil { vm.ui.value.project != null }
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertThat(events).isEmpty()
        assertThat(runBlocking { db.projects().get(id.value) }).isNotNull()
        assertThat(copy.readText()).isEqualTo("photo")
        // Once open, it deletes as usual.
        vm.deleteProject()
        idleUntil { EditorEvent.Closed in events }
        assertThat(runBlocking { db.projects().get(id.value) }).isNull()
    }

    @Test
    fun `nothing after the delete brings the video back, an undo or an edit included`() {
        val repository = ProjectRepository(db, files, scope, debounceMs = 50, maxWaitMs = 100)
        openVideo(repository)
        val vm = editorOn(repository)
        idleUntil { vm.ui.value.project != null }
        assertThat(vm.edit { rename("Party, edited") }).isTrue()

        vm.deleteProject()
        idleUntil { EditorEvent.Closed in events }
        assertThat(vm.edit { rename("Back again") }).isFalse()
        vm.undo()
        vm.redo()
        vm.close()
        // Past the autosave's debounce: nothing was written back.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertThat(runBlocking { db.projects().get(id.value) }).isNull()
        assertThat(repository.open.value).isNull()
        assertThat(events).containsExactly(EditorEvent.Closed)
    }

    @Test
    fun `an export of the video still running blocks the delete, and nothing is removed`() {
        val repository = ProjectRepository(db, files, scope)
        val copy = openVideo(repository)
        var busy = true
        val vm = editorOn(repository, exportBusy = { busy })
        idleUntil { vm.ui.value.project != null }

        vm.askDelete()
        idleUntil { events.isNotEmpty() }
        assertThat(events).containsExactly(DeletePrompt.BLOCKED)

        // Confirmed in the moment an export started: checked again, still blocked.
        events.clear()
        vm.deleteProject()
        idleUntil { events.isNotEmpty() }
        assertThat(events).containsExactly(DeletePrompt.BLOCKED)
        assertThat(runBlocking { db.projects().get(id.value) }).isNotNull()
        assertThat(copy.readText()).isEqualTo("photo")
        // The video stays open and editable.
        assertThat(vm.edit { rename("Still here") }).isTrue()

        assertThat(cancelled).isEmpty()
        // Once the export has finished, it can be deleted.
        busy = false
        events.clear()
        vm.deleteProject()
        idleUntil { EditorEvent.Closed in events }
        assertThat(runBlocking { db.projects().get(id.value) }).isNull()
    }
}
