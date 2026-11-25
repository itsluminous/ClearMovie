package com.itsluminous.clearmovie.data.project

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.MediaGrantEntity
import com.itsluminous.clearmovie.data.db.ProjectEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectCodec
import com.itsluminous.clearmovie.domain.model.ProjectDecodeResult
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.ProjectMigrations
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ROADMAP cm-storage acceptance (Robolectric): autosave, a kill during a
 * write, the low-memory-kill reopen, named save, unreadable rows and model
 * schema migrations.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File
    private lateinit var files: AppFiles
    private val scopes = mutableListOf<CoroutineScope>()
    private val databases = mutableListOf<ClearMovieDatabase>()

    @Before
    fun setUp() {
        dbFile = File(tmp.root, "clearmovie.db")
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        databases.forEach { it.close() }
    }

    private fun db(): ClearMovieDatabase = fileDatabase(context, dbFile).also { databases += it }

    /** A repository as a fresh process would build it, on the same database file. */
    private fun repository(
        db: ClearMovieDatabase = db(),
        debounceMs: Long = 150,
        maxWaitMs: Long = 600,
        migrations: ProjectMigrations = ProjectMigrations(),
        grants: GrantReleaser = GrantReleaser {},
        clock: () -> Long = System::currentTimeMillis,
    ): ProjectRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return ProjectRepository(db, files, scope, migrations, grants, clock, debounceMs = debounceMs, maxWaitMs = maxWaitMs)
    }

    private fun draft(id: String = "p1"): Project = Fixtures.presetProject(SequentialIds("x")).copy(id = ProjectId(id))

    private fun photo(n: Int) =
        PhotoClip(
            com.itsluminous.clearmovie.domain.model
                .ClipId("ph$n"),
            MediaRef.Store("content://media/external/images/media/$n"),
            3000,
        )

    /** This project as first created, with [n] photos added to MAIN. */
    private fun Project.withPhotos(n: Int): Project = draft(id.value).addToMain((1..n).map(::photo), ids = SequentialIds("n"))

    @Test
    fun `edits show at once and are written once after the debounce`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 300, maxWaitMs = 5000)
            repo.createDraft(draft())
            val writesAfterCreate = repo.writeCount
            for (i in 1..5) {
                assertThat(repo.update { it.withPhotos(i) }).isTrue()
                assertThat(
                    repo.open.value!!
                        .mainClips
                        .count { it is PhotoClip },
                ).isEqualTo(i)
                delay(20)
            }
            assertThat(repo.writeCount).isEqualTo(writesAfterCreate)
            delay(800)
            assertThat(repo.writeCount).isEqualTo(writesAfterCreate + 1)
            assertThat(repository().open(ProjectId("p1"))).isInstanceOf(OpenResult.Opened::class.java)
            val reread = (repository().open(ProjectId("p1")) as OpenResult.Opened).project
            assertThat(reread.mainClips.count { it is PhotoClip }).isEqualTo(5)
        }

    @Test
    fun `continuous edits are still written within the maximum wait`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 200, maxWaitMs = 500)
            repo.createDraft(draft())
            val before = repo.writeCount
            val start = System.nanoTime()
            var i = 0
            while (System.nanoTime() - start < 1_200_000_000L) {
                repo.update { it.withPhotos(++i % 6 + 1) }
                delay(50)
            }
            assertThat(repo.writeCount - before).isAtLeast(2)
        }

    @Test
    fun `a save that dies before its commit leaves the previous row intact`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db)
            repo.createDraft(draft())
            repo.update { it.withPhotos(2) }
            repo.flush()
            val before = db.projects().get("p1")!!

            // The next save dies after its UPDATE ran but before the commit.
            val next = before.copy(modelJson = ProjectCodec().encode(repo.open.value!!.withPhotos(5)), updatedAt = before.updatedAt + 1)
            val killed =
                runCatching {
                    db.withTransaction {
                        db.projects().upsert(next)
                        throw ProcessKilled()
                    }
                }
            assertThat(killed.exceptionOrNull()).isInstanceOf(ProcessKilled::class.java)
            db.close()

            val after = db().projects().get("p1")!!
            assertThat(after).isEqualTo(before)
            val decoded = ProjectCodec().decode(after.modelJson) as ProjectDecodeResult.Ok
            assertThat(decoded.project.mainClips.count { it is PhotoClip }).isEqualTo(2)
        }

    @Test
    fun `a kill during an autosave write leaves the previous project on disk`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db)
            repo.createDraft(draft())
            repo.update { it.withPhotos(2) }
            repo.flush()
            val before = db.projects().get("p1")!!
            // Make the write bigger than SQLite's page cache, so pages reach the disk before the commit.
            db.openHelper.writableDatabase
                .query("PRAGMA cache_size = 4")
                .close()
            val snapshot = File(tmp.root, "snapshot").apply { mkdirs() }
            val sizesAtKill = mutableMapOf<String, Long>()
            // The process dies inside the repository's own write, after the row is
            // written and before the commit: copy the database files as they are on disk then.
            repo.beforeCommit = {
                for (suffix in listOf("", "-wal", "-journal", "-shm")) {
                    val f = File(dbFile.path + suffix)
                    if (f.exists()) {
                        f.copyTo(File(snapshot, dbFile.name + suffix), overwrite = true)
                        sizesAtKill[suffix] = f.length()
                    }
                }
            }
            // A large project: its JSON spills out of the page cache.
            assertThat(repo.update { it.addToMainBig() }).isTrue()
            repo.flush()
            assertThat(sizesAtKill).isNotEmpty()

            val recovered = fileDatabase(context, File(snapshot, dbFile.name)).also { databases += it }
            assertThat(recovered.projects().get("p1")).isEqualTo(before)
        }

    /** Many photos, so the project's JSON is several hundred KB. */
    private fun Project.addToMainBig(): Project = draft(id.value).addToMain((1..1500).map(::photo), ids = SequentialIds("big"))

    @Test
    fun `an edit made while a write is running is written too, without another edit`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 100, maxWaitMs = 400)
            repo.createDraft(draft())
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            repo.beforeCommit = {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            repo.update { it.withPhotos(1) }
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            repo.beforeCommit = {}
            repo.update { it.withPhotos(2) }
            release.countDown()
            waitFor {
                db()
                    .projects()
                    .get("p1")!!
                    .modelJson
                    .contains("ph2")
            }
        }

    @Test
    fun `with the phone full, leaving or switching projects keeps the unsaved one open`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft("p1"))
            repo.createDraft(draft("p2"))
            repo.open(ProjectId("p1"))
            var fail = true
            repo.beforeCommit = { if (fail) throw android.database.sqlite.SQLiteFullException("disk full") }
            repo.update { it.withPhotos(2) }

            assertThat(repo.close()).isFalse()
            assertThat(repo.open(ProjectId("p2"))).isEqualTo(OpenResult.NotSaved)
            assertThat(repo.createDraft(draft("p3"))).isFalse()
            assertThat(repo.saveAs("Trip")).isEqualTo(SaveAsResult.NotWritten)
            assertThat(repo.open.value!!.id).isEqualTo(ProjectId("p1"))
            assertThat(
                repo.open.value!!
                    .mainClips
                    .count { it is PhotoClip },
            ).isEqualTo(2)
            assertThat(repo.open.value!!.name).isEqualTo("Trip")

            fail = false
            assertThat(repo.close()).isTrue()
            val reopened = repository().open(ProjectId("p1")) as OpenResult.Opened
            assertThat(reopened.project.mainClips.count { it is PhotoClip }).isEqualTo(2)
            assertThat(reopened.project.name).isEqualTo("Trip")
            assertThat(repo.projectList().map { it.id.value }).doesNotContain("p3")
        }

    @Test
    fun `a failing autosave is not retried in a loop while the phone stays full`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 30, maxWaitMs = 100)
            repo.createDraft(draft())
            var attempts = 0
            repo.beforeCommit = {
                attempts++
                throw android.database.sqlite.SQLiteFullException("disk full")
            }
            repo.update { it.withPhotos(1) }
            delay(1_000)
            assertThat(attempts).isEqualTo(1)
            assertThat(repo.saveFailed.value).isTrue()
            // The next edit tries again.
            repo.update { it.withPhotos(2) }
            delay(500)
            assertThat(attempts).isEqualTo(2)
        }

    @Test
    fun `opening the project that is already open keeps its newest edits`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft())
            // The newest edit is in memory only: the write fails.
            repo.beforeCommit = { throw android.database.sqlite.SQLiteFullException("disk full") }
            repo.update { it.withPhotos(4) }
            val again = repo.open(ProjectId("p1")) as OpenResult.Opened
            assertThat(again.project.mainClips.count { it is PhotoClip }).isEqualTo(4)
            assertThat(repo.open.value).isEqualTo(again.project)
        }

    @Test
    fun `deleting the open project removes it for good, even with an autosave pending`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db, debounceMs = 50, maxWaitMs = 100)
            repo.createDraft(draft())
            repo.update { it.withPhotos(1) }
            assertThat(repo.delete(ProjectId("p1"))).isTrue()
            delay(400)
            assertThat(db.projects().get("p1")).isNull()
            assertThat(repo.open.value).isNull()
            assertThat(repository(db).launchDecision()).isEqualTo(LaunchDecision.Home)
        }

    @Test
    fun `a new draft that cannot be opened is taken back out`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db, debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft("p1"))
            repo.update { it.withPhotos(1) }
            var writes = 0
            // The old project's first write works; the edit arriving during the
            // new draft's insert cannot be written.
            repo.beforeCommit = {
                writes++
                if (writes == 2) repo.update { it.withPhotos(2) }
                if (writes >= 3) throw android.database.sqlite.SQLiteFullException("disk full")
            }
            assertThat(repo.createDraft(draft("p2"))).isFalse()
            assertThat(db.projects().get("p2")).isNull()
            assertThat(db.session().get()!!.openProjectId).isEqualTo("p1")
            assertThat(repo.open.value!!.id).isEqualTo(ProjectId("p1"))
        }

    @Test
    fun `the crash flush stops waiting for a wedged database`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft())
            val release = java.util.concurrent.CountDownLatch(1)
            repo.beforeCommit = { release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            repo.update { it.withPhotos(1) }
            val start = System.nanoTime()
            assertThat(repo.flushBlocking(200)).isFalse()
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000)
            release.countDown()
        }

    @Test
    fun `a write that fails keeps the edit, says so, and succeeds on the next try`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft())
            var fail = true
            repo.beforeCommit = { if (fail) throw android.database.sqlite.SQLiteFullException("disk full") }
            repo.update { it.withPhotos(3) }
            assertThat(repo.flush()).isFalse()
            assertThat(repo.saveFailed.value).isTrue()
            assertThat(
                repo.open.value!!
                    .mainClips
                    .count { it is PhotoClip },
            ).isEqualTo(3)
            fail = false
            assertThat(repo.flush()).isTrue()
            assertThat(repo.saveFailed.value).isFalse()
            databases.forEach { it.close() }
            val opened = repository().open(ProjectId("p1")) as OpenResult.Opened
            assertThat(opened.project.mainClips.count { it is PhotoClip }).isEqualTo(3)
        }

    @Test
    fun `reopening after a low-memory kill goes straight back to the editor with every edit`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft())
            repo.update { it.withPhotos(3) }
            // The user switches apps: ON_STOP of the process lifecycle writes the pending edit.
            val owner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry.createUnsafe(this)
                    override val lifecycle: Lifecycle get() = registry
                }
            val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            owner.registry.addObserver(AutosaveGuard(repo, guardScope))
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            waitFor { repo.writeCount >= 2 && db().session().get()!!.lastViewedAt >= db().projects().get("p1")!!.updatedAt }
            // Android kills the process for memory: nothing else runs. A new process starts.
            databases.forEach { it.close() }
            val relaunched = repository()
            val decision = relaunched.launchDecision()
            // Everything was saved and seen before the kill: no note, no question.
            assertThat(decision).isEqualTo(LaunchDecision.ReopenEditor(ProjectId("p1"), broughtBack = false))
            val opened = relaunched.open(ProjectId("p1")) as OpenResult.Opened
            assertThat(opened.project.mainClips.count { it is PhotoClip }).isEqualTo(3)
        }

    @Test
    fun `after a crash with edits since the user last left, the editor says it brought the video back`() =
        runBlocking<Unit> {
            var now = 1_000L
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000, clock = { now })
            repo.createDraft(draft())
            now = 2_000L
            repo.update { it.withPhotos(2) }
            assertThat(repo.flushBlocking(5000)).isTrue()
            databases.forEach { it.close() }
            assertThat(
                repository(clock = { now }).launchDecision(),
            ).isEqualTo(LaunchDecision.ReopenEditor(ProjectId("p1"), broughtBack = true))
        }

    @Test
    fun `a session naming a project that cannot be read starts at Home`() =
        runBlocking<Unit> {
            val db = db()
            repository(db).createDraft(draft())
            val row = db.projects().get("p1")!!
            db.projects().upsert(row.copy(modelJson = "{ damaged"))
            assertThat(repository(db).launchDecision()).isEqualTo(LaunchDecision.Home)
            assertThat(db.session().get()).isNull()
            assertThat(db.projects().get("p1")!!.modelJson).isEqualTo("{ damaged")
        }

    @Test
    fun `a crash writes pending edits through the blocking flush`() =
        runBlocking<Unit> {
            val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
            repo.createDraft(draft())
            repo.update { it.withPhotos(4) }
            assertThat(repo.flushBlocking(5000)).isTrue()
            databases.forEach { it.close() }
            val opened = repository().open(ProjectId("p1")) as OpenResult.Opened
            assertThat(opened.project.mainClips.count { it is PhotoClip }).isEqualTo(4)
        }

    @Test
    fun `the crash handler flushes and then hands the crash on`() {
        val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
        runBlocking { repo.createDraft(draft()) }
        repo.update { it.withPhotos(1) }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var handedOn: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn = e }
            AutosaveGuard(repo, CoroutineScope(Dispatchers.IO).also { scopes += it }).installCrashHandler()
            val crash = IllegalStateException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
            assertThat(handedOn).isSameInstanceAs(crash)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
        val opened = runBlocking { repository().open(ProjectId("p1")) } as OpenResult.Opened
        assertThat(opened.project.mainClips.count { it is PhotoClip }).isEqualTo(1)
    }

    @Test
    fun `after a crash in the editor the next launch opens Home, keeps the video, and says what crashed`() {
        val crashes = CrashLog(File(tmp.root, "crash"), about = { "ClearMovie test" })
        val repo = repository(debounceMs = 60_000, maxWaitMs = 60_000)
        runBlocking {
            repo.createDraft(draft())
            repo.open(ProjectId("p1"))
        }
        repo.update { it.withPhotos(2) }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            AutosaveGuard(repo, CoroutineScope(Dispatchers.IO).also { scopes += it }, crashLog = crashes).installCrashHandler()
            val crash = IllegalArgumentException("Can't represent a width of 300447 and height of 0 in Constraints")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
        databases.forEach { it.close() }
        runBlocking {
            // The session still names the video that crashed, until the app clears it at start.
            assertThat(repository().launchDecision()).isInstanceOf(LaunchDecision.ReopenEditor::class.java)
            // Even if the app could not, the launch itself opens Home while the mark is there.
            assertThat(repository().launchDecision(crashes)).isEqualTo(LaunchDecision.Home)
            assertThat(crashes.crashedLastTime()).isFalse()
            // A share asking which video is open (it passes no crash log) finds none now, and the next launch is Home.
            assertThat(repository().launchDecision()).isEqualTo(LaunchDecision.Home)
            assertThat(repository().launchDecision(crashes)).isEqualTo(LaunchDecision.Home)
            // Its edits were written and it can still be opened.
            val opened = repository().open(ProjectId("p1")) as OpenResult.Opened
            assertThat(opened.project.mainClips.count { it is PhotoClip }).isEqualTo(2)
        }
        val kept = crashes.last()!!.text
        assertThat(kept).startsWith("ClearMovie test\n")
        assertThat(kept).contains("IllegalArgumentException: Can't represent a width of 300447")
        assertThat(kept).contains("ProjectRepositoryTest")
    }

    @Test
    fun `the app clears the crashed editor at start, before a share asks which video is open`() =
        runBlocking<Unit> {
            val crashes = CrashLog(File(tmp.root, "crash"))
            val repo = repository()
            repo.createDraft(draft())
            repo.open(ProjectId("p1"))
            crashes.record(Thread.currentThread(), IllegalStateException("boom"))
            databases.forEach { it.close() }
            repository().forgetEditorAfterCrash(crashes)
            assertThat(crashes.crashedLastTime()).isFalse()
            assertThat(repository().launchDecision()).isEqualTo(LaunchDecision.Home)
            // Without a crash it does nothing: a video left open by a kill is reopened as before.
            val again = repository()
            again.open(ProjectId("p1"))
            databases.forEach { it.close() }
            repository().forgetEditorAfterCrash(crashes)
            assertThat(repository().launchDecision()).isInstanceOf(LaunchDecision.ReopenEditor::class.java)
        }

    @Test
    fun `an editor that was only looked at reopens without the brought-back note`() =
        runBlocking<Unit> {
            var now = 1_000L
            val repo = repository(clock = { now })
            repo.createDraft(draft())
            repo.close()
            now = 2_000L
            repo.open(ProjectId("p1"))
            databases.forEach { it.close() }
            assertThat(
                repository(clock = { now }).launchDecision(),
            ).isEqualTo(LaunchDecision.ReopenEditor(ProjectId("p1"), broughtBack = false))
        }

    @Test
    fun `leaving the editor normally starts at Home next time`() =
        runBlocking<Unit> {
            val repo = repository()
            repo.createDraft(draft())
            repo.update { it.withPhotos(1) }
            repo.close()
            assertThat(repo.open.value).isNull()
            assertThat(repository().launchDecision()).isEqualTo(LaunchDecision.Home)
        }

    @Test
    fun `a session pointing at a deleted project starts at Home`() =
        runBlocking<Unit> {
            val repo = repository()
            repo.createDraft(draft())
            db().projects().delete("p1")
            assertThat(repository().launchDecision()).isEqualTo(LaunchDecision.Home)
            assertThat(db().session().get()).isNull()
        }

    @Test
    fun `save with a name keeps the same project, and a name with path characters never reaches the disk`() =
        runBlocking<Unit> {
            val repo = repository()
            repo.createDraft(draft())
            assertThat(repo.projectList().single().isDraft).isTrue()
            val hostile = "../../evil/..\\name/: *?"
            assertThat(repo.saveAs("  $hostile  ")).isEqualTo(SaveAsResult.Saved)
            val list = repo.projectList()
            assertThat(list.map { it.id.value }).containsExactly("p1")
            assertThat(list.single().name).isEqualTo(hostile)
            assertThat(list.single().isDraft).isFalse()
            // Everything the app wrote lives under its own folders, named by id.
            val written =
                tmp.root
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(tmp.root).path }
                    .toList()
            assertThat(written.filter { "evil" in it || "name" in it }).isEmpty()
            assertThat(files.projectDir(ProjectId("p1")).path).endsWith("projects/p1")
        }

    @Test
    fun `an invalid name or no open project is refused`() =
        runBlocking<Unit> {
            val repo = repository()
            assertThat(repo.saveAs("My video")).isEqualTo(SaveAsResult.NothingOpen)
            repo.createDraft(draft())
            assertThat(repo.saveAs("   ")).isEqualTo(SaveAsResult.InvalidName)
            assertThat(repo.saveAs("a\u202Eb")).isEqualTo(SaveAsResult.InvalidName)
            assertThat(repo.saveAs("x".repeat(101))).isEqualTo(SaveAsResult.InvalidName)
        }

    @Test
    fun `an update that breaks the project or changes its id is refused`() =
        runBlocking<Unit> {
            val repo = repository()
            repo.createDraft(draft())
            assertThat(repo.update { it.copy(stacking = emptyList()) }).isFalse()
            assertThat(repo.update { it.copy(id = ProjectId("other")) }).isFalse()
            assertThat(repo.open.value).isEqualTo(draft())
        }

    @Test
    fun `a project written by a newer app is reported and never overwritten`() =
        runBlocking<Unit> {
            val db = db()
            val current = "\"schemaVersion\": ${ProjectMigrations.CURRENT_VERSION}"
            val newer = ProjectCodec().encode(draft()).replaceFirst(current, "\"schemaVersion\": 99")
            assertThat(newer).contains("\"schemaVersion\": 99")
            val row = ProjectEntity("p1", null, null, 99, newer, null, 1, 1)
            db.projects().upsert(row)
            val repo = repository(db)
            assertThat(repo.open(ProjectId("p1"))).isEqualTo(OpenResult.Unreadable(ProjectDecodeResult.Reason.NEWER_VERSION))
            repo.flush()
            assertThat(db.projects().get("p1")).isEqualTo(row)
            assertThat(repo.open(ProjectId("nope"))).isEqualTo(OpenResult.NotFound)
        }

    @Test
    fun `a project from an older model schema is upgraded and saved back when opened`() =
        runBlocking<Unit> {
            // Saved by an app at schema 1; opened by this one, at schema 3 (cm-captions' attachedTo, cm-aspect-ratio's shape).
            val v1 = ProjectMigrations(currentVersion = 1, steps = emptyMap())
            val db = db()
            repository(db, migrations = v1).createDraft(draft())
            assertThat(db.projects().get("p1")!!.schemaVersion).isEqualTo(1)

            val upgraded = repository(db)
            val opened = upgraded.open(ProjectId("p1")) as OpenResult.Opened
            assertThat(opened.migratedFrom).isEqualTo(1)
            val row = db.projects().get("p1")!!
            assertThat(row.schemaVersion).isEqualTo(ProjectMigrations.CURRENT_VERSION)
            assertThat(row.modelJson).startsWith("{\n    \"schemaVersion\": ${ProjectMigrations.CURRENT_VERSION}")
            // Opening again is not a migration any more.
            assertThat((upgraded.open(ProjectId("p1")) as OpenResult.Opened).migratedFrom).isNull()
        }

    @Test
    fun `a video stored by 0_0_3 (schema 2) opens as landscape and is stored back at schema 3`() =
        runBlocking<Unit> {
            // cm-aspect-ratio: the row exactly as the released app stored it, read from the golden file.
            val v2 = requireNotNull(javaClass.getResource("/fixtures/project/blank-v2.json")).readText()
            val db = db()
            db.projects().upsert(ProjectEntity("blank", null, null, 2, v2, null, 1L, 1L))
            val opened = repository(db).open(ProjectId("blank")) as OpenResult.Opened
            assertThat(opened.migratedFrom).isEqualTo(2)
            assertThat(opened.project.shape).isEqualTo(CanvasShape.LANDSCAPE)
            assertThat(opened.project.askShape).isFalse()
            val row = db.projects().get("blank")!!
            assertThat(row.schemaVersion).isEqualTo(ProjectMigrations.CURRENT_VERSION)
            assertThat(row.modelJson).contains("\"shape\": \"landscape\"")
        }

    @Test
    fun `deleting a project removes its files and gives back link permissions no other project uses`() =
        runBlocking<Unit> {
            val db = db()
            val grants = FakeUriGrants()
            val repo = repository(db, grants = grants)
            repo.createDraft(draft("p1"))
            repo.createDraft(draft("p2"))
            db.grants().add(MediaGrantEntity("content://docs/shared", "p1"))
            db.grants().add(MediaGrantEntity("content://docs/shared", "p2"))
            db.grants().add(MediaGrantEntity("content://docs/only-p1", "p1"))
            val media = files.projectMediaDir(ProjectId("p1")).apply { mkdirs() }
            File(media, "a_photo.jpg").writeText("x")

            repo.delete(ProjectId("p1"))

            assertThat(db.projects().get("p1")).isNull()
            assertThat(files.projectDir(ProjectId("p1")).exists()).isFalse()
            assertThat(grants.released).containsExactly("content://docs/only-p1")
            assertThat(db.grants().urisFor("p2")).containsExactly("content://docs/shared")
            assertThat(repo.projectList().map { it.id.value }).containsExactly("p2")
        }

    @Test
    fun `deleting a project leaves its preset's files, other projects and files outside the app alone`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db)
            repo.createDraft(draft("p1"))
            repo.createDraft(draft("p2"))
            val own = File(files.projectMediaDir(ProjectId("p1")).apply { mkdirs() }, "a_photo.jpg").apply { writeText("mine") }
            val others = File(files.projectMediaDir(ProjectId("p2")).apply { mkdirs() }, "b_photo.jpg").apply { writeText("theirs") }
            val presetDir =
                files
                    .presetDir(
                        draft("p1").presetId!!,
                    ).apply { mkdirs() }
            val presetFile = File(presetDir, "frame.mp4").apply { writeText("frame") }
            // Stands in for a video saved to the gallery: outside the app's folders.
            val exported = File(tmp.root, "Movies/ClearMovie/Party.mp4").apply { parentFile!!.mkdirs() }.apply { writeText("movie") }

            assertThat(repo.delete(ProjectId("p1"))).isTrue()

            assertThat(own.exists()).isFalse()
            assertThat(others.readText()).isEqualTo("theirs")
            assertThat(presetFile.readText()).isEqualTo("frame")
            assertThat(exported.readText()).isEqualTo("movie")
            assertThat(db.projects().get("p2")).isNotNull()
            // p2, made last, is the one open: it is still the one the app goes back to.
            assertThat(repository(db).launchDecision()).isEqualTo(LaunchDecision.ReopenEditor(ProjectId("p2"), broughtBack = false))
        }

    @Test
    fun `My videos lists drafts and named projects, last edited first`() =
        runBlocking<Unit> {
            var now = 1_000L
            val repo = repository(clock = { now })
            repo.createDraft(draft("old"))
            now = 2_000L
            repo.createDraft(draft("new"))
            repo.saveAs("Wedding")
            assertThat(repo.projectList().map { it.id.value to it.name }).containsExactly("new" to "Wedding", "old" to null).inOrder()
        }

    @Test
    fun `Storage lists copied media per project, presets and the thumbnail cache`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db)
            repo.createDraft(draft("p1"))
            repo.createDraft(draft("p2"))
            repo.saveAs("Big one")
            File(files.projectMediaDir(ProjectId("p1")).apply { mkdirs() }, "a_x.jpg").writeBytes(ByteArray(100))
            File(files.projectMediaDir(ProjectId("p2")).apply { mkdirs() }, "b_x.mp4").writeBytes(ByteArray(300))
            File(files.presetsRoot, "event-frame").apply { mkdirs() }.resolve("intro-1_a.mp4").writeBytes(ByteArray(50))
            File(files.thumbsRoot.apply { mkdirs() }, "p1.jpg").writeBytes(ByteArray(7))

            val report =
                com.itsluminous.clearmovie.data.files
                    .StorageUsage(db, files, Dispatchers.IO)
                    .report()

            assertThat(report.projects.map { Triple(it.id.value, it.name, it.copiedBytes) })
                .containsExactly(Triple("p2", "Big one", 300L), Triple("p1", null, 100L))
                .inOrder()
            assertThat(report.presetBytes).isEqualTo(50)
            assertThat(report.cacheBytes).isEqualTo(7)
            assertThat(report.totalBytes).isEqualTo(457)
        }

    private class ProcessKilled : RuntimeException()

    private suspend fun waitFor(condition: suspend () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out" }
            delay(20)
        }
    }

    @Test
    fun `a new video still on its review or photo screen is not reopened in the editor after a restart`() =
        runBlocking<Unit> {
            val db = db()
            val repo = repository(db)
            assertThat(repo.createDraft(draft(), inEditor = false)).isTrue()
            assertThat(repo.open(ProjectId("p1"), inEditor = false)).isInstanceOf(OpenResult.Opened::class.java)
            assertThat(repository(db).launchDecision()).isEqualTo(LaunchDecision.Home)
            // The editor shows it (already open, as after the photo step): now a restart brings it back.
            repo.open(ProjectId("p1"))
            assertThat(repository(db).launchDecision()).isInstanceOf(LaunchDecision.ReopenEditor::class.java)
        }
}
