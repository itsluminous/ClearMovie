package com.itsluminous.clearmovie.data.project

import android.database.SQLException
import androidx.room.withTransaction
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.ProjectEntity
import com.itsluminous.clearmovie.data.db.SessionEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectCodec
import com.itsluminous.clearmovie.domain.model.ProjectDecodeResult
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.ProjectMigrations
import com.itsluminous.clearmovie.domain.model.TextRules
import com.itsluminous.clearmovie.domain.model.validate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A row of "My videos". [name] is null for an unnamed draft. */
data class ProjectSummary(
    val id: ProjectId,
    val name: String?,
    val presetId: PresetId?,
    val updatedAt: Long,
) {
    val isDraft: Boolean get() = name == null
}

/** The outcome of [ProjectRepository.open]. */
sealed interface OpenResult {
    /**
     * [migratedFrom] is the schema version the stored JSON was written in when
     * it was older than the app's (the row has been saved back upgraded), else null.
     */
    data class Opened(
        val project: Project,
        val repaired: Boolean,
        val migratedFrom: Int?,
    ) : OpenResult

    data object NotFound : OpenResult

    /**
     * Nothing changed: the project open before has edits that could not be
     * written (the phone is full), so it stays open rather than lose them.
     */
    data object NotSaved : OpenResult

    /** The stored project could not be read; the row is left exactly as it was. */
    data class Unreadable(
        val reason: ProjectDecodeResult.Reason,
    ) : OpenResult
}

/** Where the app goes at launch (ARCHITECTURE section 10, review F11). */
sealed interface LaunchDecision {
    data object Home : LaunchDecision

    /**
     * The app stopped while this project was open in the editor, so the editor
     * opens again straight away, with no question asked. [broughtBack] is true
     * when it was edited after it was opened, which is when the editor shows
     * "We brought back your last video".
     */
    data class ReopenEditor(
        val id: ProjectId,
        val broughtBack: Boolean,
    ) : LaunchDecision
}

sealed interface SaveAsResult {
    data object Saved : SaveAsResult

    data object InvalidName : SaveAsResult

    /** The name is set, but could not be written yet (the phone is full); autosave keeps trying. */
    data object NotWritten : SaveAsResult

    data object NothingOpen : SaveAsResult
}

/** Gives back persisted read permissions that no project uses any more. */
fun interface GrantReleaser {
    fun release(uri: String)
}

/**
 * Projects in Room, with autosave (ARCHITECTURE section 10).
 *
 * The project open in the editor lives in [open]. Every change goes through
 * [update], which shows at once and is written after [debounceMs] without
 * further changes (and at least every [maxWaitMs] while changes keep coming).
 * [flush] writes at once; the app calls it when it goes to the background
 * (ON_STOP, through `ProcessLifecycleOwner`) and from its crash handler, so a
 * low-memory kill, which only happens in the background, loses nothing.
 *
 * Each write is one upsert of the whole row inside a transaction, so a kill
 * in the middle of a write leaves the previous version of the project, never
 * a half-written one. A write that fails (the phone is full, for example)
 * keeps the change pending, sets [saveFailed] and is tried again on the next
 * change or flush; it never crashes the app. A project the app cannot read
 * (damaged, or written by a newer version) is never overwritten. One written
 * by an older version is upgraded through [ProjectMigrations] and saved back
 * when it is opened.
 *
 * While a project is open in the editor, the session row names it, with the
 * time the user last saw it there ([markViewed], called when the app goes to
 * the background). Closing the editor clears it. If the app stops any other
 * way, [launchDecision] reopens the editor at that project.
 */
class ProjectRepository(
    private val db: ClearMovieDatabase,
    private val files: AppFiles,
    private val scope: CoroutineScope,
    private val migrations: ProjectMigrations = ProjectMigrations(),
    private val grants: GrantReleaser = GrantReleaser {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val codecDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val codec = ProjectCodec(migrations)
    private val lock = Any()
    private val writeMutex = Mutex()

    private val openState = MutableStateFlow<Project?>(null)

    /** The project open in the editor, or null. */
    val open: StateFlow<Project?> = openState.asStateFlow()

    private val saveFailedState = MutableStateFlow(false)

    /** True while the latest change could not be written (the editor says so; nothing is lost while the app runs). */
    val saveFailed: StateFlow<Boolean> = saveFailedState.asStateFlow()

    // Guarded by [lock].
    private var generation = 0L
    private var savedGeneration = 0L
    private var timer: Job? = null
    private var firstPendingAt = 0L

    @Volatile private var lastUpdateAt = 0L

    /** How many times a project row was written; for tests of the debounce. */
    @Volatile
    var writeCount: Int = 0
        private set

    /** Runs inside each project write transaction after the row is written; tests use it to stop the world there. */
    @Volatile
    internal var beforeCommit: () -> Unit = {}

    /** "My videos": drafts and named projects, last edited first. */
    val projects: Flow<List<ProjectSummary>> = db.projects().summaries().map { rows -> rows.mapNotNull(::toSummary) }

    suspend fun projectList(): List<ProjectSummary> = db.projects().summaryList().mapNotNull(::toSummary)

    /**
     * Stores [project] as a new draft, written at once, and opens it in the
     * editor. Whatever was open before is written first; if that fails (the
     * phone is full) nothing changes and this returns false. Its media folder
     * may be filled after this: copies go into a project that already has a row.
     */
    suspend fun createDraft(project: Project): Boolean {
        require(project.validate().isEmpty()) { "the project is not valid" }
        val json = encode(project)
        writeMutex.withLock {
            if (!settleLocked()) return false
            val now = wallClock()
            try {
                db.withTransaction {
                    require(db.projects().get(project.id.value) == null) { "a project with this id exists" }
                    db.projects().upsert(entity(project, json, createdAt = now, updatedAt = now))
                    beforeCommit()
                }
            } catch (e: SQLException) {
                saveFailedState.value = true
                return false
            }
            writeCount++
            if (!settleAndSwitchLocked(project)) {
                // The old project could not be saved after all: take the new draft back out.
                runCatching { db.projects().delete(project.id.value) }
                return false
            }
            setSession(project.id, now)
            return true
        }
    }

    /**
     * Opens [id] in the editor. Whatever was open before is written first
     * ([OpenResult.NotSaved] when it cannot be). A project from an older app
     * version, or one whose derived values were stale, is saved back in the
     * current form; one that cannot be read is left untouched and reported.
     * Opening the project that is already open keeps it as it is.
     */
    suspend fun open(id: ProjectId): OpenResult {
        openState.value?.takeIf { it.id == id }?.let { return OpenResult.Opened(it, repaired = false, migratedFrom = null) }
        val row = db.projects().get(id.value) ?: return OpenResult.NotFound
        val decoded = withContext(codecDispatcher) { codec.decode(row.modelJson) }
        if (decoded !is ProjectDecodeResult.Ok) return OpenResult.Unreadable((decoded as ProjectDecodeResult.Failed).reason)
        if (decoded.project.id != id) return OpenResult.Unreadable(ProjectDecodeResult.Reason.INVALID)
        val project = decoded.project
        val upgrade = decoded.repaired || decoded.fromVersion < migrations.currentVersion || row.schemaVersion != migrations.currentVersion
        val json = if (upgrade) encode(project) else null
        writeMutex.withLock {
            if (!settleLocked()) return OpenResult.NotSaved
            val now = wallClock()
            if (json != null) {
                try {
                    db.withTransaction { db.projects().upsert(entity(project, json, row.createdAt, row.updatedAt, row.thumbPath)) }
                    writeCount++
                } catch (e: SQLException) {
                    saveFailedState.value = true
                    return OpenResult.NotSaved
                }
            }
            if (!settleAndSwitchLocked(project)) return OpenResult.NotSaved
            // The session names a project only once the editor really shows it.
            setSession(id, now)
        }
        return OpenResult.Opened(project, decoded.repaired, decoded.fromVersion.takeIf { it < migrations.currentVersion })
    }

    /**
     * Applies [transform] to the open project and schedules the write. Returns
     * false, changing nothing, when no project is open or the result is not a
     * valid project of the same id (a project that could not be read back must
     * never be stored).
     */
    fun update(transform: (Project) -> Project): Boolean {
        synchronized(lock) {
            val current = openState.value ?: return false
            val next = transform(current)
            if (next == current) return true
            if (next.id != current.id || next.validate().isNotEmpty()) return false
            openState.value = next
            generation++
            scheduleLocked()
        }
        return true
    }

    /**
     * Names the open project (Save). A draft becomes a named project; later
     * edits keep autosaving into it. The name is written at once.
     */
    suspend fun saveAs(name: String): SaveAsResult {
        val trimmed = name.trim()
        if (!TextRules.isValidName(trimmed)) return SaveAsResult.InvalidName
        if (!update { it.copy(name = trimmed) }) return SaveAsResult.NothingOpen
        return if (flush()) SaveAsResult.Saved else SaveAsResult.NotWritten
    }

    /** Writes any pending change now. Returns false when it could not be written. */
    suspend fun flush(): Boolean {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
        return writeMutex.withLock { writeLatestLocked() }
    }

    /**
     * [flush] from a thread that cannot suspend (the crash handler). The write
     * runs on the app scope and is never cut off half-way; this call stops
     * waiting for it after [timeoutMs], so a wedged database can never hang
     * the crashing thread. Returns true when everything was written in time.
     */
    fun flushBlocking(timeoutMs: Long = 1500): Boolean {
        val write = scope.async { withContext(NonCancellable) { flush() } }
        return runBlocking { withTimeoutOrNull(timeoutMs) { write.await() } } == true
    }

    /**
     * The app went to the background with the editor open: records that the
     * user has seen everything saved so far, so a later low-memory kill brings
     * the editor back without the "We brought back your last video" note.
     */
    suspend fun markViewed() {
        writeMutex.withLock {
            if (!writeLatestLocked()) return
            val id = openState.value?.id ?: return
            runCatching { db.session().set(SessionEntity(openProjectId = id.value, lastViewedAt = wallClock())) }
        }
    }

    /**
     * Leaves the editor: writes, then clears the session so the next launch
     * opens Home. Returns false, leaving the project open, when its edits
     * cannot be written (the phone is full), so the editor can say so instead
     * of losing them.
     */
    suspend fun close(): Boolean {
        writeMutex.withLock {
            if (!settleLocked()) return false
            // Cleared first: if that fails the project stays open rather than come back at the next launch.
            if (runCatching { db.session().clear() }.isFailure) return false
            if (!settleAndSwitchLocked(null)) return false
            return true
        }
    }

    /**
     * Where to go at launch (see [LaunchDecision]). A session that names a
     * project that is gone or cannot be read is cleared, so the app never
     * starts in an editor that cannot open.
     */
    suspend fun launchDecision(): LaunchDecision {
        val session = db.session().get() ?: return LaunchDecision.Home
        val id = session.openProjectId?.let { runCatching { ProjectId(it) }.getOrNull() }
        val row = id?.let { db.projects().get(it.value) }
        val readable = row != null && withContext(codecDispatcher) { codec.decode(row.modelJson) is ProjectDecodeResult.Ok }
        if (id == null || row == null || !readable) {
            runCatching { db.session().clear() }
            return LaunchDecision.Home
        }
        return LaunchDecision.ReopenEditor(id, broughtBack = row.updatedAt > session.lastViewedAt)
    }

    /**
     * Deletes [id] with its copied media, thumbnail and persisted link
     * permissions. Returns false, changing nothing on disk, when the database
     * cannot be written.
     */
    suspend fun delete(id: ProjectId): Boolean {
        val released = mutableListOf<String>()
        // Under the write lock, so no autosave of this project can write it back.
        writeMutex.withLock {
            try {
                db.withTransaction {
                    val uris = db.grants().urisFor(id.value)
                    db.grants().deleteFor(id.value)
                    db.projects().delete(id.value)
                    if (db.session().get()?.openProjectId == id.value) db.session().clear()
                    uris.filterTo(released) { db.grants().usersOf(it) == 0 }
                }
            } catch (e: SQLException) {
                return false
            }
            // Its pending edits do not matter any more.
            if (open.value?.id == id) switchLocked(null)
        }
        released.forEach { runCatching { grants.release(it) } }
        files.projectDir(id).deleteRecursively()
        files.thumbFile(id).delete()
        return true
    }

    /** Names [id] as open in the editor, seen at [now]. A failure only loses the reopen-after-a-crash hint. */
    private suspend fun setSession(
        id: ProjectId,
        now: Long,
    ) {
        runCatching { db.session().set(SessionEntity(openProjectId = id.value, lastViewedAt = now)) }
    }

    /**
     * With [writeMutex] held: writes what is pending for the open project until
     * nothing is. False when a write failed ([saveFailed] is then set), or, rarely,
     * when edits kept arriving during [MAX_SETTLE_WRITES] writes in a row.
     */
    private suspend fun settleLocked(): Boolean {
        repeat(MAX_SETTLE_WRITES) {
            if (!writeLatestLocked()) return false
            synchronized(lock) { if (generation == savedGeneration) return true }
        }
        return false
    }

    /**
     * With [writeMutex] held: writes what is pending, then switches to [next]
     * in the same step as the check that nothing is left unwritten, so no edit
     * is ever marked saved without being written. False, switching nothing,
     * when the old project's edits could not be written.
     */
    private suspend fun settleAndSwitchLocked(next: Project?): Boolean {
        repeat(MAX_SETTLE_WRITES) {
            if (!writeLatestLocked()) return false
            synchronized(lock) {
                if (generation == savedGeneration) {
                    switchLocked(next)
                    return true
                }
            }
        }
        return false
    }

    /** With [writeMutex] (or [lock]) held: makes [next] the open project, with nothing pending. */
    private fun switchLocked(next: Project?) {
        synchronized(lock) {
            timer?.cancel()
            timer = null
            openState.value = next
            generation++
            savedGeneration = generation
            saveFailedState.value = false
        }
    }

    private fun scheduleLocked() {
        lastUpdateAt = monotonicMs()
        if (timer?.isActive == true) return
        firstPendingAt = lastUpdateAt
        timer =
            scope.launch {
                while (true) {
                    val deadline = minOf(lastUpdateAt + debounceMs, firstPendingAt + maxWaitMs)
                    val wait = deadline - monotonicMs()
                    if (wait <= 0) break
                    delay(wait)
                }
                val written = writeMutex.withLock { writeLatestLocked() }
                val self = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (timer === self) timer = null
                    // An edit made while this write ran: schedule it as well. After a
                    // failed write, wait for the next edit or flush instead of retrying
                    // in a loop while the phone stays full.
                    if (written && generation > savedGeneration && openState.value != null) scheduleLocked()
                }
            }
    }

    /** With [writeMutex] held: writes the open project if it changed. False when the write failed. */
    private suspend fun writeLatestLocked(): Boolean {
        val (project, gen) = synchronized(lock) { openState.value to generation }
        if (project == null || gen == savedGeneration) return true
        val json = encode(project)
        val now = wallClock()
        try {
            withContext(NonCancellable) {
                db.withTransaction {
                    val existing = db.projects().get(project.id.value)
                    db.projects().upsert(entity(project, json, existing?.createdAt ?: now, now, existing?.thumbPath))
                    beforeCommit()
                }
            }
        } catch (e: SQLException) {
            // Full storage or a damaged database: keep the change pending, never crash.
            saveFailedState.value = true
            return false
        }
        writeCount++
        saveFailedState.value = false
        synchronized(lock) { if (gen > savedGeneration) savedGeneration = gen }
        return true
    }

    private suspend fun encode(project: Project): String = withContext(codecDispatcher) { codec.encode(project) }

    private fun entity(
        project: Project,
        json: String,
        createdAt: Long,
        updatedAt: Long,
        thumbPath: String? = null,
    ) = ProjectEntity(
        id = project.id.value,
        name = project.name,
        presetId = project.presetId?.value,
        schemaVersion = migrations.currentVersion,
        modelJson = json,
        thumbPath = thumbPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun toSummary(row: com.itsluminous.clearmovie.data.db.ProjectSummaryRow): ProjectSummary? =
        runCatching {
            ProjectSummary(ProjectId(row.id), row.name, row.presetId?.let(::PresetId), row.updatedAt)
        }.getOrNull()

    companion object {
        const val DEBOUNCE_MS = 500L
        const val MAX_WAIT_MS = 2000L
        private const val MAX_SETTLE_WRITES = 5
    }
}
