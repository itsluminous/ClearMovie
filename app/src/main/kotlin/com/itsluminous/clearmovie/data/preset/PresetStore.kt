package com.itsluminous.clearmovie.data.preset

import android.content.ContentResolver
import android.content.res.AssetManager
import android.database.SQLException
import android.net.Uri
import androidx.room.withTransaction
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.MediaGrantEntity
import com.itsluminous.clearmovie.data.db.PresetBindingEntity
import com.itsluminous.clearmovie.data.db.PresetDefinitionEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.files.SafeFileName
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.data.media.CopyFailedException
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.media.UriGrants
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.EndingSpec
import com.itsluminous.clearmovie.domain.model.IntroSpec
import com.itsluminous.clearmovie.domain.model.KeyMath
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetParseResult
import com.itsluminous.clearmovie.domain.model.PresetParser
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.PresetWriter
import com.itsluminous.clearmovie.domain.model.refitBox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * A preset the app can offer. [source] is [Source.USER] for every preset on
 * the list (one the user made, edited or chose a `preset.json` for, and the
 * one an older version shipped, kept on an install made before
 * cm-home-presets), and [Source.BUNDLED] only for the app's own copy of a
 * preset it once shipped, read back for the videos made from it when the
 * install has no copy of its own. [retired] is true for a preset the user
 * deleted that videos still use.
 */
data class PresetDefinition(
    val preset: Preset,
    val source: Source,
    val retired: Boolean = false,
) {
    enum class Source { BUNDLED, USER }
}

/** The parts a new preset has. Fixed once it is made: videos made from it play these parts. */
data class PresetParts(
    val intro: Boolean = true,
    val frame: Boolean = true,
    /** Null for no ending. */
    val ending: EndingKind? = EndingKind.AUDIO,
    /** The shape of the videos it makes (cm-aspect-ratio). */
    val shape: CanvasShape = CanvasShape.LANDSCAPE,
    /** Whether its files are copied into the app, or used where they are (cm-frame-area, the default). */
    val copyFiles: Boolean = false,
)

sealed interface PresetSaveResult {
    data class Saved(
        val preset: Preset,
    ) : PresetSaveResult

    /** Not a preset the app can store (a value out of range, a name that shows nothing). */
    data object Invalid : PresetSaveResult

    /** The phone is full, or the preset is unknown. */
    data object NotSaved : PresetSaveResult
}

sealed interface PresetDeleteResult {
    /** Gone, with its files: no video used it. */
    data object Deleted : PresetDeleteResult

    /** No longer offered; its files stay for the [videos] videos made from it, until they are deleted. */
    data class Retired(
        val videos: Int,
    ) : PresetDeleteResult

    data object Unknown : PresetDeleteResult
}

/**
 * A preset file: a copy in app storage ([file]), or a file used where it is
 * ([linkedUri], a `content://` link whose read grant the app keeps;
 * cm-frame-area). Exactly one of the two is set.
 */
data class PresetBinding(
    val slot: PresetSlot,
    val file: File?,
    val sha256: String,
    val sizeBytes: Long,
    val originalName: String?,
    val mime: String?,
    val durationUs: Long,
    val loudnessLufs: Double?,
    /** The green this binding keys out instead of the preset's, or null to use the preset's. */
    val keyColor: ArgbColor?,
    val measuredKeyColor: ArgbColor?,
    val keyDeltaE: Double?,
    val boundAt: Long,
    val linkedUri: String? = null,
) {
    val durationMs: Long get() = durationUs / 1000

    /** True for a file used where it is, not copied. */
    val isLinked: Boolean get() = linkedUri != null

    /** Where the file is read from: the copy, or the user's own file. */
    val uri: Uri get() = linkedUri?.let(Uri::parse) ?: Uri.fromFile(requireNotNull(file))

    /** True when the frame was re-measured and keys out its own green: "Frame adjusted to this file". */
    val keyAdjusted: Boolean get() = keyColor != null
}

/** One slot at launch (ARCHITECTURE section 8: cheap checks only). */
sealed interface SlotStatus {
    /** The preset has no such part. */
    data object NotNeeded : SlotStatus

    /** Never chosen: setup. */
    data object NotSet : SlotStatus

    data class Ready(
        val binding: PresetBinding,
    ) : SlotStatus

    /** The copy in app storage is gone, or the file used where it is was moved or deleted: the repair screen asks for it again. */
    data class Missing(
        val binding: PresetBinding,
    ) : SlotStatus

    /** The copy's size no longer matches what was bound: also repaired by choosing the file again. */
    data class Changed(
        val binding: PresetBinding,
    ) : SlotStatus
}

/** Every slot of one preset. */
data class PresetStatus(
    val presetId: PresetId,
    val slots: Map<PresetSlot, SlotStatus>,
) {
    val isReady: Boolean get() = slots.values.all { it is SlotStatus.Ready || it is SlotStatus.NotNeeded }

    /** Slots that were set up but need choosing again. */
    val needsRepair: List<PresetSlot> get() = slots.filterValues { it is SlotStatus.Missing || it is SlotStatus.Changed }.keys.toList()

    val needsSetup: List<PresetSlot> get() = slots.filterValues { it is SlotStatus.NotSet }.keys.toList()
}

sealed interface SlotBindResult {
    /**
     * [fileShape] is set when a video was chosen (the intro, the frame or a
     * video ending) whose own shape is not the preset's (cm-aspect-ratio): it
     * is kept, and setup says it will look stretched.
     */
    data class Bound(
        val binding: PresetBinding,
        val fileShape: CanvasShape? = null,
    ) : SlotBindResult

    data object UnknownPreset : SlotBindResult

    /** The preset has no such part. */
    data object NotNeeded : SlotBindResult

    /** The file could not be opened or read. */
    data object Unreadable : SlotBindResult

    /** Not a video (intro, frame, video ending) or not audio (spoken ending), or too short to use. */
    data object WrongKind : SlotBindResult

    data class NotEnoughSpace(
        val neededBytes: Long,
        val freeBytes: Long,
    ) : SlotBindResult
}

/**
 * The outcome of reading the user's `preset.json`. [Invalid.errors] are
 * diagnostics in English that name the field (for the log and for the
 * person who wrote the file); the setup screen shows its own translated
 * message, never these.
 */
sealed interface DefinitionBindResult {
    data class Bound(
        val preset: Preset,
        val warnings: List<String>,
    ) : DefinitionBindResult

    /** Not a usable preset; [errors] name each field. */
    data class Invalid(
        val errors: List<String>,
    ) : DefinitionBindResult

    data object Unreadable : DefinitionBindResult
}

/**
 * Presets and their files (ARCHITECTURE section 8).
 *
 * Definitions: every preset on the list is a stored `preset.json`. A new
 * install has none (cm-home-presets): the generic preset in
 * `assets/presets/<id>/preset.json` is only the template a new preset starts
 * from, and the definition the videos made from it fall back to. An install
 * made before that keeps it as a preset of its own ([seedOnce]), which the
 * user can edit or delete like any other. The user's own `preset.json`,
 * chosen at setup, is untrusted: it is read with a size limit, must be
 * UTF-8, goes through the strict `PresetParser`, and is copied into app
 * storage only when valid; it takes the place of the stored preset with the
 * same id. Presets the user makes or edits in the app ([create], [save]) are
 * stored the same way, as a `preset.json` that `PresetWriter` writes and the
 * parser reads back.
 *
 * Bindings: each slot's file (intro, frame, ending) is copied into
 * `filesDir/presets/<id>/` once, with progress, its SHA-256 and size, its
 * length and loudness, and for the frame the key-colour check (more than
 * ΔE 6 away from the preset's green, and the binding keys out the measured
 * green instead). At launch [status] makes only cheap checks: the file is
 * there and its size matches. A copied file's name is new each time, so the
 * database row always names a complete file: a kill during a copy leaves the
 * old binding in place.
 */
class PresetStore(
    private val assets: AssetManager,
    private val resolver: ContentResolver,
    private val gateway: MediaStoreGateway,
    private val db: ClearMovieDatabase,
    private val files: AppFiles,
    private val copier: MediaCopier,
    private val probe: MediaProbe,
    private val freeBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val randomToken: () -> String = {
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
    },
    private val hash: (File) -> String = MediaCopier::sha256,
    /** True when the app had run on this phone before this launch: see [seedOnce]. */
    private val installedBefore: () -> Boolean = { false },
    /** Read grants for files used where they are; without it every file is copied. */
    private val grants: UriGrants? = null,
) {
    private val seedLock = Mutex()

    @Volatile private var seeded = false

    /**
     * Every preset that is offered, in the order they were made (a kept
     * preset of an older version first). A deleted preset that videos still
     * use is left out. A new install has none.
     */
    suspend fun definitions(): List<PresetDefinition> =
        withContext(dispatcher) {
            seedOnce()
            all().filterNot { it.retired }
        }

    /**
     * Preset [id], including one the user deleted that videos still use, so
     * those videos keep working; and for a video made from a preset the app
     * once shipped that this install has no copy of, the app's own.
     */
    suspend fun definition(id: PresetId): PresetDefinition? =
        withContext(dispatcher) {
            seedOnce()
            all().firstOrNull { it.preset.id == id }
                ?: bundled().firstOrNull { it.id == id }?.takeIf { db.presets().projectsUsing(id.value) > 0 }?.let {
                    PresetDefinition(it, PresetDefinition.Source.BUNDLED, retired = true)
                }
        }

    private suspend fun all(): List<PresetDefinition> =
        db
            .presets()
            .definitions()
            .mapNotNull { row ->
                userDefinition(row)?.let { row to PresetDefinition(it, PresetDefinition.Source.USER, retired = row.retiredAt != null) }
            }.sortedWith(compareBy({ it.first.createdAt }, { it.first.presetId }))
            .map { it.second }

    /**
     * Once per install (cm-home-presets): an install made before presets
     * stopped shipping keeps each preset the app shipped, as an ordinary
     * preset of its own; a new install starts with none. It is an older
     * install when the app ran before this launch and had not decided this
     * yet (only a version from before this one leaves no answer), or when the
     * preset has files chosen for it or videos made from it. The answer
     * is kept in [AppFiles.presetSeedMarker], so a preset the user deletes
     * never comes back; a preset that could not be stored is tried again at
     * the next launch.
     */
    private suspend fun seedOnce() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            val marker = files.presetSeedMarker
            if (!marker.exists()) {
                val older = runCatching(installedBefore).getOrDefault(false)
                var stored = true
                for (preset in bundled()) {
                    val id = preset.id.value
                    if (db.presets().definition(id) != null) continue
                    val used = db.presets().bindings(id).isNotEmpty() || db.presets().projectsUsing(id) > 0
                    if ((older || used) && !adopt(preset)) stored = false
                }
                if (stored) {
                    runCatching {
                        marker.parentFile?.mkdirs()
                        marker.createNewFile()
                    }
                }
            }
            seeded = true
        }
    }

    /**
     * Puts the preset the app ships as [id] on the list as the user's own,
     * first in order; it then is an ordinary preset (edit, delete). For
     * [seedOnce], and for tests that need it on the list. False when the app
     * ships no such preset, or it could not be stored.
     */
    suspend fun adoptShipped(id: PresetId): Boolean =
        withContext(dispatcher) {
            if (db.presets().definition(id.value) != null) return@withContext true
            val preset = bundled().firstOrNull { it.id == id } ?: return@withContext false
            adopt(preset)
        }

    private suspend fun adopt(preset: Preset): Boolean = write(preset, createdAt = 0L) is PresetSaveResult.Saved

    /**
     * Makes a new preset called [name] with [parts], and the frame layout and
     * caption style of [from] (or the template the app ships). It has no
     * files yet: setup chooses them. Null when it could not be stored.
     */
    suspend fun create(
        name: String,
        parts: PresetParts,
        from: PresetId? = null,
    ): PresetId? =
        withContext(dispatcher) {
            val template = from?.let { definition(it)?.preset } ?: bundled().firstOrNull() ?: return@withContext null
            val frame = template.frame ?: bundled().firstNotNullOfOrNull { it.frame }
            if (parts.frame && frame == null) return@withContext null
            var id: PresetId
            do {
                id = PresetId("$OWN_PREFIX${randomToken()}")
            } while (definition(id) != null)
            val preset =
                template.copy(
                    id = id,
                    displayNameRes = null,
                    displayName = LocalizedText.same(name.trim()),
                    intro = IntroSpec.takeIf { parts.intro },
                    frame = frame.takeIf { parts.frame },
                    ending = parts.ending?.let(::EndingSpec),
                    shape = parts.shape,
                    // The template's caption place, on the new shape (its frame's window is measured when the frame is chosen).
                    caption = template.caption.copy(box = refitBox(template.caption.box, template.shape, parts.shape)),
                )
            (write(preset, createdAt = clock(), copyFiles = parts.copyFiles) as? PresetSaveResult.Saved)?.preset?.id
        }

    /**
     * Stores [preset] in place of the preset with its id: its name, caption
     * style and words, and anything else a preset holds. The parts (intro,
     * frame, ending) must stay as they are: videos made from it play them.
     * Its shape may change only while no video uses it (cm-aspect-ratio).
     */
    suspend fun save(preset: Preset): PresetSaveResult =
        withContext(dispatcher) {
            val old = definition(preset.id) ?: return@withContext PresetSaveResult.NotSaved
            // The app's own copy, kept only for the videos made from it, is never put back on the list.
            if (old.source == PresetDefinition.Source.BUNDLED) return@withContext PresetSaveResult.NotSaved
            val same = old.preset
            if ((same.intro == null) != (preset.intro == null) ||
                (same.frame == null) != (preset.frame == null) ||
                same.ending?.kind != preset.ending?.kind
            ) {
                return@withContext PresetSaveResult.Invalid
            }
            if (same.shape != preset.shape && db.presets().projectsUsing(preset.id.value) > 0) {
                return@withContext PresetSaveResult.Invalid
            }
            val row = db.presets().definition(preset.id.value)
            write(preset, createdAt = row?.createdAt ?: 0L, retiredAt = row?.retiredAt, copyFiles = row?.copyFiles ?: true)
        }

    /**
     * Whether preset [id] copies the files chosen for it into the app (the
     * presets the app ships, and every preset from before cm-frame-area), or
     * uses them where they are.
     */
    suspend fun copiesFiles(id: PresetId): Boolean = withContext(dispatcher) { db.presets().definition(id.value)?.copyFiles ?: true }

    /**
     * The frame's look, chosen by the user (cm-frame-area): the colour keyed
     * out of it and where photos and videos go while it shows ([area], in
     * units of the frame's picture). It replaces any green the key-colour
     * check measured. For a preset the app ships this is an edit, which
     * Reset removes.
     */
    suspend fun setFrameLook(
        id: PresetId,
        key: KeySpec,
        area: NormRect,
    ): PresetSaveResult =
        withContext(dispatcher) {
            if (!KeyMath.isUsableArea(area) || key.tolerance !in 0.0..1.0 || key.softness !in 0.0..1.0) {
                return@withContext PresetSaveResult.Invalid
            }
            val preset = definition(id)?.preset ?: return@withContext PresetSaveResult.NotSaved
            val frame = preset.frame ?: return@withContext PresetSaveResult.Invalid
            val result = save(preset.copy(frame = frame.copy(key = key, contentRect = area)))
            if (result is PresetSaveResult.Saved) {
                db.presets().binding(id.value, PresetSlot.FRAME.fileStem)?.let { row ->
                    if (row.keyColor != null) db.presets().upsertBinding(row.copy(keyColor = null))
                }
            }
            result
        }

    /** How many videos were made from [id]: while any is, its shape stays (cm-aspect-ratio). */
    suspend fun videosUsing(id: PresetId): Int = withContext(dispatcher) { db.presets().projectsUsing(id.value) }

    /**
     * Deletes a preset, the one an older version shipped included. A video
     * made from it keeps working: while any video uses it, it is only taken
     * off the list (its definition and files stay, and [sweepRetired] removes
     * them once no video does).
     */
    suspend fun delete(id: PresetId): PresetDeleteResult =
        withContext(dispatcher) {
            db.presets().definition(id.value) ?: return@withContext PresetDeleteResult.Unknown
            val outcome =
                db.withTransaction {
                    val videos = db.presets().projectsUsing(id.value)
                    if (videos > 0) {
                        db.presets().retire(id.value, clock())
                        PresetDeleteResult.Retired(videos)
                    } else {
                        dropLinks(id)
                        db.presets().deleteBindings(id.value)
                        db.presets().deleteDefinition(id.value)
                        PresetDeleteResult.Deleted
                    }
                }
            if (outcome == PresetDeleteResult.Deleted) runCatching { files.presetDir(id).deleteRecursively() }
            outcome
        }

    /** Removes deleted presets that no video uses any more, with their files. Run at launch. */
    suspend fun sweepRetired(): Int =
        withContext(dispatcher) {
            var removed = 0
            for (row in db.presets().definitions().filter { it.retiredAt != null }) {
                val id = runCatching { PresetId(row.presetId) }.getOrNull() ?: continue
                val gone =
                    db.withTransaction {
                        if (db.presets().projectsUsing(row.presetId) > 0) return@withTransaction false
                        dropLinks(id)
                        db.presets().deleteBindings(row.presetId)
                        db.presets().deleteDefinition(row.presetId)
                        true
                    }
                if (gone) {
                    runCatching { files.presetDir(id).deleteRecursively() }
                    removed++
                }
            }
            removed
        }

    /** Writes [preset] as its definition file and row, replacing the old ones. */
    private suspend fun write(
        preset: Preset,
        createdAt: Long,
        retiredAt: Long? = null,
        copyFiles: Boolean = true,
    ): PresetSaveResult {
        val text = PresetWriter.write(preset)
        val parsed = PresetParser.parse(text) as? PresetParseResult.Ok ?: return PresetSaveResult.Invalid
        // What is stored is what the parser reads back, so the preset in use is exactly the file.
        if (parsed.preset != preset) return PresetSaveResult.Invalid
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size + CopyBudget.RESERVE_BYTES > freeBytes()) return PresetSaveResult.NotSaved
        val id = preset.id
        val name = SafeFileName.build("preset-${randomToken()}", "preset", "application/json")
        val copied =
            try {
                copier.copy({ bytes.inputStream() }, files.presetFile(id, name), bytes.size.toLong(), bytes.size.toLong())
            } catch (e: CopyFailedException) {
                return PresetSaveResult.NotSaved
            }
        val previous =
            try {
                db.withTransaction {
                    val old = db.presets().definition(id.value)
                    db.presets().upsertDefinition(
                        PresetDefinitionEntity(
                            presetId = id.value,
                            fileName = name,
                            sha256 = copied.sha256,
                            sizeBytes = copied.sizeBytes,
                            originalName = null,
                            boundAt = clock(),
                            createdAt = createdAt,
                            retiredAt = retiredAt,
                            copyFiles = copyFiles,
                        ),
                    )
                    old
                }
            } catch (e: SQLException) {
                copied.file.delete()
                return PresetSaveResult.NotSaved
            }
        if (previous != null && previous.fileName != name) runCatching { files.presetFile(id, previous.fileName).delete() }
        return PresetSaveResult.Saved(parsed.preset)
    }

    /**
     * The preset as a project should use it: with the frame's key colour
     * replaced by the bound file's measured green when the check adjusted it.
     */
    suspend fun effectivePreset(id: PresetId): Preset? {
        val preset = definition(id)?.preset ?: return null
        val frame = preset.frame ?: return preset
        val adjusted = bindings(id)[PresetSlot.FRAME]?.keyColor ?: return preset
        return preset.copy(frame = frame.copy(key = frame.key.copy(color = adjusted)))
    }

    /** The lengths of the bound files that are ready, for `ProjectFactory.fromPreset`. */
    suspend fun presetMedia(id: PresetId): PresetMedia {
        val ready = status(id).slots.mapNotNull { (slot, s) -> (s as? SlotStatus.Ready)?.let { slot to it.binding.durationMs } }.toMap()
        return PresetMedia(introMs = ready[PresetSlot.INTRO], frameMs = ready[PresetSlot.FRAME], endingMs = ready[PresetSlot.ENDING])
    }

    /** The launch check for [id]: each slot's file exists and has its bound size. */
    suspend fun status(id: PresetId): PresetStatus =
        withContext(dispatcher) {
            val preset = definition(id)?.preset
            val bound = bindings(id)
            val slots =
                PresetSlot.entries.associateWith { slot ->
                    val binding = bound[slot]
                    when {
                        preset == null || !preset.needs(slot) -> SlotStatus.NotNeeded
                        binding == null -> SlotStatus.NotSet
                        binding.isLinked -> linkStatus(binding)
                        binding.file?.isFile != true -> SlotStatus.Missing(binding)
                        binding.file.length() != binding.sizeBytes -> SlotStatus.Changed(binding)
                        else -> SlotStatus.Ready(binding)
                    }
                }
            PresetStatus(id, slots)
        }

    /**
     * The check before an export: a file edited in place since it was bound
     * (newer than its binding, though the size still matches) has its SHA-256
     * worked out again, and reports [SlotStatus.Changed] if it differs.
     */
    suspend fun verifyBeforeExport(id: PresetId): PresetStatus =
        withContext(dispatcher) {
            val quick = status(id)
            PresetStatus(
                id,
                quick.slots.mapValues { (_, s) ->
                    val ready = s as? SlotStatus.Ready ?: return@mapValues s
                    val b = ready.binding
                    // A file used where it is was checked by its size; reading all of it again before each export costs too much.
                    if (b.file == null || b.file.lastModified() <= b.boundAt) return@mapValues s
                    if (runCatching { hash(b.file) }.getOrNull() == b.sha256) s else SlotStatus.Changed(b)
                },
            )
        }

    /**
     * Copies [uri] in as [slot] of preset [id], checks it, and records it.
     * [onProgress] gets the bytes copied and the total, when known. A failed
     * or cancelled bind leaves the earlier binding (if any) untouched and no
     * partial file.
     */
    suspend fun bindSlot(
        id: PresetId,
        slot: PresetSlot,
        uri: Uri,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): SlotBindResult =
        withContext(dispatcher) {
            val preset = definition(id)?.preset ?: return@withContext SlotBindResult.UnknownPreset
            if (!preset.needs(slot)) return@withContext SlotBindResult.NotNeeded
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext SlotBindResult.Unreadable
            val info = gateway.describe(uri) ?: return@withContext SlotBindResult.Unreadable
            // Used where it is (cm-frame-area) when the preset says so and the link's read grant can be kept;
            // otherwise copied, as before.
            if (!copiesFiles(id) && MediaRef.isSafeContentUri(uri.toString()) && grants?.persist(uri) == true) {
                return@withContext bindLinked(id, slot, preset, uri, info)
            }
            val free = freeBytes()
            val size = info.sizeBytes ?: 0L
            if (size + CopyBudget.RESERVE_BYTES >
                free
            ) {
                return@withContext SlotBindResult.NotEnoughSpace(size + CopyBudget.RESERVE_BYTES, free)
            }

            val name = SafeFileName.build("${slot.fileStem}-${randomToken()}", info.displayName, info.mime)
            val target = files.presetFile(id, name)
            val copied =
                try {
                    copier.copy(
                        { resolver.openInputStream(uri) },
                        target,
                        info.sizeBytes,
                        MediaCopier.limitFor(info.sizeBytes, free),
                        onProgress,
                    )
                } catch (e: CopyFailedException) {
                    return@withContext when (e.reason) {
                        CopyFailedException.Reason.WRITE_FAILED -> {
                            SlotBindResult.NotEnoughSpace(
                                size + CopyBudget.RESERVE_BYTES,
                                freeBytes(),
                            )
                        }

                        else -> {
                            SlotBindResult.Unreadable
                        }
                    }
                }
            try {
                val frame = preset.frame.takeIf { slot == PresetSlot.FRAME }
                val facts = probe.probe(copied.file, if (frame != null) FRAME_SAMPLES else emptyList(), measureLoudness = true)
                if (facts == null || !fits(slot, preset, facts)) {
                    copied.file.delete()
                    return@withContext SlotBindResult.WrongKind
                }
                // A Cancel while the file was being checked must not record it.
                currentCoroutineContext().ensureActive()
                // A preset the user made has the shipped frame's window until its own frame is measured.
                // The kept Event frame keeps the window its definition names.
                val (ownWindow, check) = measureFrame(id, frame, facts)
                val entity =
                    PresetBindingEntity(
                        presetId = id.value,
                        slot = slot.fileStem,
                        fileName = name,
                        sha256 = copied.sha256,
                        sizeBytes = copied.sizeBytes,
                        originalName = info.displayName?.take(MAX_ORIGINAL_NAME),
                        mime = facts.mime ?: info.mime,
                        durationUs = facts.durationUs,
                        loudnessLufs = facts.loudnessLufs?.takeIf { it.isFinite() },
                        keyColor = check?.takeIf { it.needsAdjusting }?.measured?.toString(),
                        measuredKeyColor = check?.measured?.toString(),
                        keyDeltaE = check?.deltaE,
                        boundAt = clock(),
                    )
                // Past this point the bind completes: a Cancel arriving during the commit
                // must not delete the file the new row names.
                val committed =
                    withContext(NonCancellable) {
                        val previous =
                            try {
                                db.withTransaction {
                                    val old = db.presets().binding(id.value, slot.fileStem)
                                    db.presets().upsertBinding(entity)
                                    old
                                }
                            } catch (e: SQLException) {
                                return@withContext false
                            }
                        if (previous != null) forget(id, slot, previous, keepFile = name)
                        true
                    }
                if (!committed) {
                    copied.file.delete()
                    return@withContext SlotBindResult.NotEnoughSpace(size + CopyBudget.RESERVE_BYTES, freeBytes())
                }
                if (ownWindow != null && frame != null) {
                    // The frame plays either way; a window that could not be stored keeps the copied one.
                    withContext(NonCancellable) {
                        val row = db.presets().definition(id.value)
                        write(
                            preset.copy(frame = frame.copy(contentRect = ownWindow)),
                            row?.createdAt ?: 0L,
                            row?.retiredAt,
                            row?.copyFiles ?: true,
                        )
                    }
                }
                val fileShape =
                    CanvasShape.suiting(facts.shownWidth, facts.shownHeight)?.takeIf { facts.hasVideo && it != preset.shape }
                SlotBindResult.Bound(toBinding(id, entity)!!, fileShape)
            } catch (e: Throwable) {
                copied.file.delete()
                throw e
            }
        }

    /**
     * Reads the user's own `preset.json` from [uri] and, if it is a valid
     * preset with the id of one the app ships, copies it in. It then is the
     * stored preset with that id (on the list, like any other).
     */
    suspend fun bindDefinition(uri: Uri): DefinitionBindResult =
        withContext(dispatcher) {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext DefinitionBindResult.Unreadable
            val bytes =
                try {
                    resolver.openInputStream(uri)?.use { readAtMost(it, PresetParser.MAX_CHARS * 4L + 1) }
                } catch (e: Exception) {
                    null
                } ?: return@withContext DefinitionBindResult.Unreadable
            if (bytes.size >
                PresetParser.MAX_CHARS * 4
            ) {
                return@withContext DefinitionBindResult.Invalid(listOf("the file is too large to be a preset"))
            }
            val text = decodeUtf8(bytes) ?: return@withContext DefinitionBindResult.Invalid(listOf("the file is not UTF-8 text"))
            val parsed = PresetParser.parse(text)
            if (parsed !is PresetParseResult.Ok) {
                return@withContext DefinitionBindResult.Invalid(
                    (parsed as PresetParseResult.Invalid).errors,
                )
            }
            val id = parsed.preset.id
            // The optional file adjusts a preset the app ships (ARCHITECTURE section 8);
            // importing new presets is out of scope for v1.
            if (bundled().none { it.id == id }) {
                return@withContext DefinitionBindResult.Invalid(listOf("id: ClearMovie has no preset called \"${id.value}\""))
            }
            val name = SafeFileName.build("preset-${randomToken()}", "preset", "application/json")
            val target = files.presetFile(id, name)
            val copied =
                try {
                    copier.copy({ bytes.inputStream() }, target, bytes.size.toLong(), bytes.size.toLong())
                } catch (e: CopyFailedException) {
                    return@withContext DefinitionBindResult.Unreadable
                }
            val description = gateway.describe(uri)
            val previous =
                db.withTransaction {
                    val old = db.presets().definition(id.value)
                    db.presets().upsertDefinition(
                        PresetDefinitionEntity(
                            id.value,
                            name,
                            copied.sha256,
                            copied.sizeBytes,
                            description?.displayName?.take(MAX_ORIGINAL_NAME),
                            clock(),
                            createdAt = old?.createdAt ?: 0L,
                            retiredAt = old?.retiredAt,
                            copyFiles = old?.copyFiles ?: true,
                        ),
                    )
                    old
                }
            if (previous != null && previous.fileName != name) runCatching { files.presetFile(id, previous.fileName).delete() }
            DefinitionBindResult.Bound(parsed.preset, parsed.warnings)
        }

    /** Bytes used by every preset's files. */
    fun storageBytes(): Long = files.sizeOf(files.presetsRoot)

    /**
     * Removes files in the preset folders that no binding or definition names
     * and that are older than [AppFiles.PART_MAX_AGE_MS]: left by a process
     * killed after a copy finished but before its row was written. Recent ones
     * may belong to a bind still running, so they stay.
     */
    suspend fun sweepOrphans(nowMs: Long = clock()): Int =
        withContext(dispatcher) {
            val dirs = files.presetsRoot.listFiles()?.filter { it.isDirectory } ?: return@withContext 0
            var removed = 0
            for (dir in dirs) {
                val id = runCatching { PresetId(dir.name) }.getOrNull() ?: continue
                val named =
                    db
                        .presets()
                        .bindings(id.value)
                        .map { it.fileName }
                        .toSet() +
                        listOfNotNull(db.presets().definition(id.value)?.fileName)
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name !in named && nowMs - f.lastModified() > AppFiles.PART_MAX_AGE_MS && f.delete()) removed++
                }
            }
            removed
        }

    private suspend fun bindings(id: PresetId): Map<PresetSlot, PresetBinding> =
        db
            .presets()
            .bindings(id.value)
            .mapNotNull { toBinding(id, it) }
            .associateBy { it.slot }

    private fun toBinding(
        id: PresetId,
        row: PresetBindingEntity,
    ): PresetBinding? {
        val slot = PresetSlot.entries.firstOrNull { it.fileStem == row.slot } ?: return null
        val link = row.sourceUri?.takeIf(MediaRef::isSafeContentUri)
        if (row.sourceUri != null && link == null) return null
        val file = if (link != null) null else runCatching { files.presetFile(id, row.fileName) }.getOrNull() ?: return null
        return PresetBinding(
            slot = slot,
            file = file,
            sha256 = row.sha256,
            sizeBytes = row.sizeBytes,
            originalName = row.originalName,
            mime = row.mime,
            durationUs = row.durationUs,
            loudnessLufs = row.loudnessLufs,
            keyColor = row.keyColor?.let(ArgbColor::parse),
            measuredKeyColor = row.measuredKeyColor?.let(ArgbColor::parse),
            keyDeltaE = row.keyDeltaE,
            boundAt = row.boundAt,
            linkedUri = link,
        )
    }

    /**
     * A file used where it is: Missing once its read grant is gone or its
     * provider no longer finds it (moved, renamed or deleted), Changed when
     * its size is not what was bound. One query, no reading.
     */
    private fun linkStatus(binding: PresetBinding): SlotStatus {
        val link = binding.linkedUri ?: return SlotStatus.Missing(binding)
        if (grants?.isPersisted(link) != true) return SlotStatus.Missing(binding)
        val info = gateway.describe(Uri.parse(link)) ?: return SlotStatus.Missing(binding)
        val size = info.sizeBytes
        return if (binding.sizeBytes >= 0 && size != null &&
            size != binding.sizeBytes
        ) {
            SlotStatus.Changed(binding)
        } else {
            SlotStatus.Ready(binding)
        }
    }

    /**
     * Binds [uri] as [slot] of [preset] where it is, without copying: its
     * read grant is already persisted. The grant is recorded under this
     * preset slot (in `media_grants`, as a project's links are), so the
     * launch sweep keeps it and the last user releases it.
     */
    private suspend fun bindLinked(
        id: PresetId,
        slot: PresetSlot,
        preset: Preset,
        uri: Uri,
        info: com.itsluminous.clearmovie.data.media.SourceInfo,
    ): SlotBindResult {
        val link = uri.toString()
        val owner = grantOwner(id, slot)

        // Released only when nothing else (another preset slot, a project) uses the same link.
        suspend fun giveBack() {
            runCatching { if (db.grants().usersOf(link) == 0) grants?.release(link) }
        }
        val frame = preset.frame.takeIf { slot == PresetSlot.FRAME }
        val facts = probe.probe(uri, if (frame != null) FRAME_SAMPLES else emptyList(), measureLoudness = true)
        if (facts == null || !fits(slot, preset, facts)) {
            giveBack()
            return SlotBindResult.WrongKind
        }
        currentCoroutineContext().ensureActive()
        val (ownWindow, check) = measureFrame(id, frame, facts)
        val entity =
            PresetBindingEntity(
                presetId = id.value,
                slot = slot.fileStem,
                fileName = "",
                sha256 = "",
                sizeBytes = info.sizeBytes ?: -1L,
                originalName = info.displayName?.take(MAX_ORIGINAL_NAME),
                mime = facts.mime ?: info.mime,
                durationUs = facts.durationUs,
                loudnessLufs = facts.loudnessLufs?.takeIf { it.isFinite() },
                keyColor = check?.takeIf { it.needsAdjusting }?.measured?.toString(),
                measuredKeyColor = check?.measured?.toString(),
                keyDeltaE = check?.deltaE,
                boundAt = clock(),
                sourceUri = link,
            )
        val committed =
            withContext(NonCancellable) {
                val previous =
                    try {
                        db.withTransaction {
                            val old = db.presets().binding(id.value, slot.fileStem)
                            db.presets().upsertBinding(entity)
                            if (db.grants().usersOf(link, owner) == 0) db.grants().add(MediaGrantEntity(link, owner))
                            old
                        }
                    } catch (e: SQLException) {
                        giveBack()
                        return@withContext false
                    }
                if (previous != null) forget(id, slot, previous, keepLink = link)
                if (ownWindow != null && frame != null) {
                    // As for a copy: the frame plays either way; a window that could not be stored keeps the old one.
                    val row = db.presets().definition(id.value)
                    write(
                        preset.copy(frame = frame.copy(contentRect = ownWindow)),
                        row?.createdAt ?: 0L,
                        row?.retiredAt,
                        row?.copyFiles ?: true,
                    )
                }
                true
            }
        if (!committed) return SlotBindResult.Unreadable
        val fileShape = CanvasShape.suiting(facts.shownWidth, facts.shownHeight)?.takeIf { facts.hasVideo && it != preset.shape }
        return SlotBindResult.Bound(toBinding(id, entity)!!, fileShape)
    }

    /**
     * A frame file's own green window (for a preset the user made, whose
     * window is the built-in frame's until its own is measured) and the
     * key-colour check against it; both null for any other slot.
     */
    private fun measureFrame(
        id: PresetId,
        frame: com.itsluminous.clearmovie.domain.model.FrameSpec?,
        facts: ProbeResult,
    ): Pair<NormRect?, KeyColorCheck?> {
        frame ?: return null to null
        val ownWindow =
            KeyColorCheck
                .window(facts.frames)
                ?.takeIf { bundled().none { b -> b.id == id } }
                ?.takeIf { w -> !w.sameAs(frame.contentRect) }
        return ownWindow to KeyColorCheck.measure(facts.frames, ownWindow ?: frame.contentRect, frame.key.color)
    }

    /**
     * Lets go of what the [previous] binding of [slot] held, now that a new
     * one replaced it: its copy (unless it is [keepFile]), or its link's
     * grant record and, when nothing else uses the link, the grant itself
     * (unless it is [keepLink]).
     */
    private suspend fun forget(
        id: PresetId,
        slot: PresetSlot,
        previous: PresetBindingEntity,
        keepFile: String? = null,
        keepLink: String? = null,
    ) {
        val link = previous.sourceUri
        if (link == null) {
            if (previous.fileName.isNotEmpty() && previous.fileName != keepFile) {
                runCatching { files.presetFile(id, previous.fileName).delete() }
            }
            return
        }
        if (link == keepLink) return
        runCatching {
            db.grants().remove(link, grantOwner(id, slot))
            if (db.grants().usersOf(link) == 0) grants?.release(link)
        }
    }

    /** Releases every link preset [id] holds, before its bindings are deleted. */
    private suspend fun dropLinks(id: PresetId) {
        for (row in db.presets().bindings(id.value)) {
            val slot = PresetSlot.entries.firstOrNull { it.fileStem == row.slot } ?: continue
            if (row.sourceUri != null) forget(id, slot, row)
        }
    }

    /** The presets the app ships: templates for a new preset, never offered on their own. */
    private fun bundled(): List<Preset> =
        // A device lists the folder's children ("event-frame"); Robolectric lists
        // nested paths ("event-frame/preset.json"). The first segment is the id either way.
        (
            runCatching {
                assets.list(
                    ASSET_DIR,
                )
            }.getOrNull() ?: emptyArray()
        ).map { it.substringBefore('/') }.distinct().sorted().mapNotNull { dir ->
            val text = runCatching { assets.open("$ASSET_DIR/$dir/preset.json").use { it.readBytes().decodeToString() } }.getOrNull()
            (text?.let(PresetParser::parse) as? PresetParseResult.Ok)?.preset
        }

    /** The user's stored definition, re-read and re-checked each time; a damaged file is skipped, never trusted. */
    private fun userDefinition(row: PresetDefinitionEntity): Preset? {
        val id = runCatching { PresetId(row.presetId) }.getOrNull() ?: return null
        val file = runCatching { files.presetFile(id, row.fileName) }.getOrNull() ?: return null
        if (!file.isFile || file.length() > PresetParser.MAX_CHARS * 4L) return null
        val text = runCatching { decodeUtf8(file.readBytes()) }.getOrNull() ?: return null
        return (PresetParser.parse(text) as? PresetParseResult.Ok)?.preset?.takeIf { it.id == id }
    }

    private fun fits(
        slot: PresetSlot,
        preset: Preset,
        facts: ProbeResult,
    ): Boolean {
        val ms = facts.durationUs / 1000
        if (ms !in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS) return false
        return when (slot) {
            PresetSlot.INTRO, PresetSlot.FRAME -> facts.hasVideo
            PresetSlot.ENDING -> if (preset.ending?.kind == EndingKind.AUDIO) facts.hasAudio else facts.hasVideo
        }
    }

    /** Within half a percent on every edge: the same window, give or take how the file was made. */
    private fun NormRect.sameAs(other: NormRect): Boolean =
        listOf(x - other.x, y - other.y, w - other.w, h - other.h).all { kotlin.math.abs(it) < WINDOW_TOLERANCE }

    private fun Preset.needs(slot: PresetSlot): Boolean =
        when (slot) {
            PresetSlot.INTRO -> intro != null
            PresetSlot.FRAME -> frame != null
            PresetSlot.ENDING -> ending != null
        }

    companion object {
        const val ASSET_DIR = "presets"

        /** Who holds a preset file's link in `media_grants`: never a project id, which has no colon. */
        fun grantOwner(
            id: PresetId,
            slot: PresetSlot,
        ): String = "preset:${id.value}:${slot.fileStem}"

        /** The start of every id the app gives a preset the user makes, so it never takes a bundled preset's id. */
        const val OWN_PREFIX = "my-"
        private const val WINDOW_TOLERANCE = 0.005
        const val MAX_ORIGINAL_NAME = 255

        /** Where the key-colour check samples the frame: three points across its length. */
        val FRAME_SAMPLES = listOf(0.1, 0.5, 0.9)

        /** The slot's name in file names and database rows; fixed, unlike the enum's. */
        val PresetSlot.fileStem: String
            get() =
                when (this) {
                    PresetSlot.INTRO -> "intro"
                    PresetSlot.FRAME -> "frame"
                    PresetSlot.ENDING -> "ending"
                }

        private fun readAtMost(
            input: InputStream,
            limit: Long,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (total < limit) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        /** Strict UTF-8 (a leading byte-order mark is allowed); null for anything else. */
        internal fun decodeUtf8(bytes: ByteArray): String? =
            try {
                val text =
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                text.removePrefix("\uFEFF")
            } catch (e: CharacterCodingException) {
                null
            } catch (e: IOException) {
                null
            }
    }
}
