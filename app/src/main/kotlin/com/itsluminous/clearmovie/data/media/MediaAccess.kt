package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.MediaGrantEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.files.SafeFileName
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MediaClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One link of an intake and what will happen to it. */
data class PlannedItem(
    val source: Uri,
    val resolution: Resolution,
)

/** What an intake will do, before anything is copied. */
data class IntakePlan(
    val items: List<PlannedItem>,
    val budget: CopyBudget.Verdict,
) {
    val toCopy: List<Resolution.NeedsCopy> get() = items.mapNotNull { it.resolution as? Resolution.NeedsCopy }
    val copyBytes: Long get() = toCopy.sumOf { it.info.sizeBytes ?: CopyBudget.UNKNOWN_SIZE_GUESS }
    val unreadableCount: Int get() = items.count { it.resolution is Resolution.Unreadable }
}

/** One item after the intake: its [ref], or null when it could not be kept. */
data class IntakeItem(
    val source: Uri,
    val ref: MediaRef?,
    val info: SourceInfo?,
)

/** Whether a clip's media can be opened right now. */
enum class MediaStatus {
    AVAILABLE,

    /** Deleted or moved: the clip shows a missing tile with Replace, and export waits. */
    MISSING,

    /** It may exist, but access was withdrawn (for example photo access turned off). */
    NO_ACCESS,
}

/**
 * Gallery media for projects (ARCHITECTURE section 9): resolving picked and
 * shared links ([plan]), copying what must be copied within the copy budget
 * ([execute]), and finding missing media when a project opens ([check]).
 *
 * The flow for the Photo Picker today, and for shares from cm-share on:
 * `plan` resolves every link at once, while its grant is fresh, and works out
 * the [CopyBudget]; the caller asks the user when the verdict says so; then
 * `execute` persists the grants it used and copies the rest into the
 * project's media folder, with progress. Cancelling `execute` (cancelling its
 * coroutine) deletes every copy that intake made, finished or not.
 */
class MediaAccess(
    private val resolver: ContentResolver,
    private val mediaResolver: MediaResolver,
    private val gateway: MediaStoreGateway,
    private val grants: UriGrants,
    private val copier: MediaCopier,
    private val files: AppFiles,
    private val db: ClearMovieDatabase,
    private val freeBytes: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val randomToken: () -> String = {
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(TOKEN_LENGTH)
    },
) {
    /** Grants persisted by a [plan] whose [execute] or [abandon] has not run yet. */
    private val pendingGrants: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [plan] and [reconcileGrants] never overlap, so a grant just persisted is never released as an orphan. */
    private val grantMutex = Mutex()

    suspend fun plan(
        uris: List<Uri>,
        access: MediaAccessState,
    ): IntakePlan {
        var resolved: List<PlannedItem>? = null
        try {
            return withContext(dispatcher) {
                val items =
                    grantMutex.withLock {
                        uris.map { PlannedItem(it, mediaResolver.resolve(it, access)) }.also { planned ->
                            planned.forEach {
                                (it.resolution as? Resolution.Linked)?.takeIf { l -> l.persistedGrant }?.let { l ->
                                    pendingGrants +=
                                        l.uri
                                }
                            }
                            resolved = planned
                        }
                    }
                val copies = items.mapNotNull { (it.resolution as? Resolution.NeedsCopy)?.let { c -> c.info.sizeBytes ?: -1L } }
                IntakePlan(items, CopyBudget.evaluate(copies, freeBytes(), partialAccess = access == MediaAccessState.Partial))
            }
        } catch (e: CancellationException) {
            // Cancelled after links were resolved: give back the grants they took.
            resolved?.let { withContext(NonCancellable) { abandon(IntakePlan(it, CopyBudget.Verdict.Fits)) } }
            throw e
        }
    }

    /**
     * The user turned [plan] down (Choose fewer, or Back): gives back the
     * grants it persisted that no project uses.
     */
    suspend fun abandon(plan: IntakePlan) {
        withContext(dispatcher) {
            plan.items.forEach { item ->
                val linked = item.resolution as? Resolution.Linked ?: return@forEach
                if (!linked.persistedGrant) return@forEach
                pendingGrants -= linked.uri
                if (db.grants().usersOf(linked.uri) == 0) grants.release(linked.uri)
            }
        }
    }

    /**
     * Releases persisted grants that no project uses and no intake is about
     * to use: left over when the app stopped between [plan] and [execute].
     * Every grant the app persists is for project media, so this is safe.
     */
    suspend fun reconcileGrants(persisted: List<String>) {
        withContext(dispatcher) {
            grantMutex.withLock {
                persisted.filter { it !in pendingGrants && db.grants().usersOf(it) == 0 }.forEach(grants::release)
            }
        }
    }

    /**
     * Carries out [plan] for project [projectId], which must already have a
     * row (see `ProjectRepository.createDraft`). [onProgress] gets the bytes
     * copied so far and the total to copy. Items that cannot be read any more
     * come back with a null ref. Throws `CancellationException` when cancelled,
     * after removing this intake's copies.
     */
    suspend fun execute(
        projectId: ProjectId,
        plan: IntakePlan,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): List<IntakeItem> =
        withContext(dispatcher) {
            val total = plan.copyBytes
            var done = 0L
            val made = mutableListOf<java.io.File>()
            val addedGrants = mutableListOf<String>()
            try {
                plan.items.map { item ->
                    when (val r = item.resolution) {
                        is Resolution.Linked -> {
                            if (r.persistedGrant) {
                                if (db.grants().usersOf(r.uri, projectId.value) == 0) {
                                    db.grants().add(MediaGrantEntity(r.uri, projectId.value))
                                    addedGrants += r.uri
                                }
                                pendingGrants -= r.uri
                            }
                            IntakeItem(item.source, MediaRef.Store(r.uri), r.info)
                        }

                        is Resolution.Unreadable -> {
                            IntakeItem(item.source, null, null)
                        }

                        is Resolution.NeedsCopy -> {
                            val name = SafeFileName.build(randomToken(), r.info.displayName, r.info.mime)
                            val ref = MediaRef.Copied(name)
                            val target = files.copiedFile(projectId, ref)
                            val base = done
                            try {
                                val copied =
                                    copier.copy(
                                        open = { resolver.openInputStream(r.source) },
                                        target = target,
                                        expectedBytes = r.info.sizeBytes,
                                        maxBytes = MediaCopier.limitFor(r.info.sizeBytes, freeBytes()),
                                    ) { bytes, _ -> onProgress(base + bytes, total) }
                                made += copied.file
                                done = base + (r.info.sizeBytes ?: copied.sizeBytes)
                                IntakeItem(item.source, ref, r.info)
                            } catch (e: CopyFailedException) {
                                if (e.reason == CopyFailedException.Reason.WRITE_FAILED) throw e
                                done = base + (r.info.sizeBytes ?: 0L)
                                IntakeItem(item.source, null, r.info)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // Cancelled, or the phone is full: this intake leaves nothing behind.
                made.forEach { it.delete() }
                withContext(NonCancellable) {
                    for (uri in addedGrants) {
                        db.grants().remove(uri, projectId.value)
                        if (db.grants().usersOf(uri) == 0) grants.release(uri)
                    }
                    abandon(plan)
                }
                throw e
            }
        }

    /**
     * The status of every clip in [project] that plays gallery or copied
     * media, given the current photo and video [access].
     */
    suspend fun check(
        project: Project,
        access: MediaAccessState,
    ): Map<ClipId, MediaStatus> =
        withContext(dispatcher) {
            project.tracks
                .flatMap { it.clips }
                .mapNotNull { clip -> clip.mediaRef()?.let { clip.id to status(project.id, it, access) } }
                .toMap()
        }

    /** The clips of [project] whose media is not [MediaStatus.AVAILABLE]. */
    suspend fun missing(
        project: Project,
        access: MediaAccessState,
    ): Map<ClipId, MediaStatus> = check(project, access).filterValues { it != MediaStatus.AVAILABLE }

    /**
     * Whether [ref] in project [projectId] can be opened. Preset slots are
     * checked by `PresetStore`. MediaStore hides items the app may not read
     * instead of refusing, so an empty answer for a MediaStore link without
     * full access (and without a persisted grant) means "no access", not "deleted".
     */
    fun status(
        projectId: ProjectId,
        ref: MediaRef,
        access: MediaAccessState,
    ): MediaStatus =
        when (ref) {
            is MediaRef.Store -> {
                val uri = ref.uri.toUri()
                val granted = grants.isPersisted(ref.uri)
                // Another app's file is opened only when the app holds a grant for it.
                when (gateway.state(uri, mayOpen = granted)) {
                    LinkState.PRESENT -> {
                        MediaStatus.AVAILABLE
                    }

                    LinkState.NO_ACCESS -> {
                        MediaStatus.NO_ACCESS
                    }

                    LinkState.GONE -> {
                        if (MediaStoreGateway.isMediaStore(uri) && !access.isFull &&
                            !granted
                        ) {
                            MediaStatus.NO_ACCESS
                        } else {
                            MediaStatus.MISSING
                        }
                    }
                }
            }

            is MediaRef.Copied -> {
                if (runCatching {
                        files
                            .copiedFile(
                                projectId,
                                ref,
                            ).isFile
                    }.getOrDefault(false)
                ) {
                    MediaStatus.AVAILABLE
                } else {
                    MediaStatus.MISSING
                }
            }

            is MediaRef.Preset -> {
                MediaStatus.AVAILABLE
            }
        }

    companion object {
        const val TOKEN_LENGTH = 8

        /** The media a clip plays, if any (an intro or ending plays its preset slot unless it was replaced). */
        fun Clip.mediaRef(): MediaRef? =
            when (this) {
                is MediaClip -> media
                is OverlayClip -> media
                is AudioClip -> media
                is IntroClip -> media
                is EndingClip -> media
                else -> null
            }
    }
}
