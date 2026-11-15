package com.itsluminous.clearmovie.data.preset

import android.content.ContentResolver
import android.content.res.AssetManager
import android.database.SQLException
import android.net.Uri
import androidx.room.withTransaction
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.PresetBindingEntity
import com.itsluminous.clearmovie.data.db.PresetDefinitionEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.files.SafeFileName
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.data.media.CopyFailedException
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetParseResult
import com.itsluminous.clearmovie.domain.model.PresetParser
import com.itsluminous.clearmovie.domain.model.PresetSlot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** A preset the app can offer. [source] says whether it ships in the app or came from the user's own `preset.json`. */
data class PresetDefinition(
    val preset: Preset,
    val source: Source,
) {
    enum class Source { BUNDLED, USER }
}

/** A preset file copied into app storage. */
data class PresetBinding(
    val slot: PresetSlot,
    val file: File,
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
) {
    val durationMs: Long get() = durationUs / 1000

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

    /** The copy in app storage is gone: the repair screen asks for the file again. */
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
    data class Bound(
        val binding: PresetBinding,
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
 * Definitions: the generic preset that ships in `assets/presets/<id>/preset.json`,
 * plus, optionally, the user's own `preset.json` chosen at setup. The user's
 * file is untrusted: it is read with a size limit, must be UTF-8, goes
 * through the strict `PresetParser`, and is copied into app storage only when
 * valid. It overrides the bundled preset with the same id.
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
) {
    /** Every preset, the user's own overriding a bundled one with the same id, by id. */
    suspend fun definitions(): List<PresetDefinition> =
        withContext(dispatcher) {
            val byId = linkedMapOf<PresetId, PresetDefinition>()
            bundled().forEach { byId[it.id] = PresetDefinition(it, PresetDefinition.Source.BUNDLED) }
            db.presets().definitions().forEach { row ->
                userDefinition(row)?.let {
                    byId[it.id] =
                        PresetDefinition(it, PresetDefinition.Source.USER)
                }
            }
            byId.values.sortedBy { it.preset.id.value }
        }

    suspend fun definition(id: PresetId): PresetDefinition? = definitions().firstOrNull { it.preset.id == id }

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
                        !binding.file.isFile -> SlotStatus.Missing(binding)
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
                    if (b.file.lastModified() <= b.boundAt) return@mapValues s
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
                val check = frame?.let { KeyColorCheck.measure(facts.frames, it.contentRect, it.key.color) }
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
                        if (previous != null && previous.fileName != name) runCatching { files.presetFile(id, previous.fileName).delete() }
                        true
                    }
                if (!committed) {
                    copied.file.delete()
                    return@withContext SlotBindResult.NotEnoughSpace(size + CopyBudget.RESERVE_BYTES, freeBytes())
                }
                SlotBindResult.Bound(toBinding(id, entity)!!)
            } catch (e: Throwable) {
                copied.file.delete()
                throw e
            }
        }

    /**
     * Reads the user's own `preset.json` from [uri] and, if it is a valid
     * preset, copies it in. It then overrides the bundled preset with its id.
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
        val file = runCatching { files.presetFile(id, row.fileName) }.getOrNull() ?: return null
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
        )
    }

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

    private fun Preset.needs(slot: PresetSlot): Boolean =
        when (slot) {
            PresetSlot.INTRO -> intro != null
            PresetSlot.FRAME -> frame != null
            PresetSlot.ENDING -> ending != null
        }

    companion object {
        const val ASSET_DIR = "presets"
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
