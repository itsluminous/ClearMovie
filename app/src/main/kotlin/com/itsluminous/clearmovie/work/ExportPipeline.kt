package com.itsluminous.clearmovie.work

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerUtil
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.transformer.Codec
import androidx.media3.transformer.InAppMp4Muxer
import com.google.common.collect.ImmutableList
import com.itsluminous.clearmovie.data.export.ExportStore
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccess.Companion.mediaRef
import com.itsluminous.clearmovie.data.media.MediaPermissions
import com.itsluminous.clearmovie.data.preset.AndroidMediaProbe
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.domain.export.ExportFileName
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.export.ExportSpace
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.render.media3.ColourCalibration
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.EncoderPreference
import com.itsluminous.clearmovie.render.media3.ExportOutput
import com.itsluminous.clearmovie.render.media3.MediaUnavailableException
import com.itsluminous.clearmovie.render.media3.VideoExport
import com.itsluminous.clearmovie.render.plan.LoudnessTable
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.decoderPeak
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why an export did not produce a video; each has its own plain message. */
enum class ExportFailure {
    /** The video was deleted while it waited, or cannot be read. */
    GONE,

    /** A photo or video in it was moved or deleted, or can no longer be read. */
    MISSING_MEDIA,

    /** A preset file (intro, frame, ending) was moved, deleted or changed. */
    PRESET_BROKEN,

    /** There is nothing to save yet. */
    NOTHING,

    /** The phone is too full; [ExportResultData.missingBytes] says by how much. */
    NO_SPACE,

    /** Anything else (a codec or gallery error); the log has the details. */
    FAILED,
}

/** A finished export. */
data class ExportDone(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val videoEncoder: String?,
    val audioEncoder: String?,
    /** Bits per second, as the muxer counted them (C.RATE_UNSET_INT when unknown). */
    val averageVideoBitrate: Int,
    val softwareFallback: Boolean,
    val direct: Boolean,
)

sealed interface ExportOutcome {
    data class Done(
        val done: ExportDone,
    ) : ExportOutcome

    data class Failed(
        val reason: ExportFailure,
        val missingBytes: Long = 0,
    ) : ExportOutcome
}

/**
 * Everything one export does, outside any screen (ARCHITECTURE section 12),
 * so [ExportWorker] stays a thin shell and tests can run it with a failing
 * encoder or a nearly full phone:
 * 1. leftovers of an export that was killed are removed (hidden gallery
 *    entries, temporary files);
 * 2. the project is read as it is now, and checked: every preset file it
 *    plays is still the one set up, and every photo and video can be opened;
 * 3. sounds without a measured loudness (a replaced ending, for example)
 *    are measured, so they are evened out like the preset's own;
 * 4. the plan and composition are built at the chosen height, with this
 *    phone's colour handling (measured again when the last try was unclear);
 * 5. the free-space rule picks the temporary file or the direct path;
 * 6. [VideoExport] encodes, hardware first, software if the hardware fails;
 * 7. the file goes into a hidden gallery entry, which is shown only once
 *    complete. Cancelling or failing at any point removes both.
 */
@UnstableApi
class ExportPipeline(
    private val context: Context,
    private val repository: ProjectRepository,
    private val projectMedia: ProjectMedia,
    private val presets: PresetStore,
    private val mediaAccess: MediaAccess,
    private val store: ExportStore,
    private val freeBytes: () -> Long,
    private val baseEncoders: ((EncodeSpec, EncoderPreference) -> Codec.EncoderFactory)? = null,
    private val probe: AndroidMediaProbe = AndroidMediaProbe(),
) {
    private val tempDir = File(context.cacheDir, TEMP_DIR)

    /** The temporary files of this and earlier exports (tests check it is empty after a cancel). */
    fun tempFiles(): List<File> = tempDir.listFiles()?.toList().orEmpty()

    suspend fun run(
        projectId: ProjectId,
        quality: ExportQuality,
        fallbackName: String,
        onRestart: suspend () -> Unit = {},
        onProgress: suspend (Int) -> Unit,
    ): ExportOutcome {
        withContext(Dispatchers.IO) { sweep() }
        val project = repository.read(projectId) ?: return ExportOutcome.Failed(ExportFailure.GONE)
        val set = projectMedia.forProject(project)
        checkInputs(project)?.let { return ExportOutcome.Failed(it) }

        val loudness = withContext(Dispatchers.IO) { measuredLoudness(project, set.loudness, set.resolver) }
        val plan =
            runCatching { RenderPlanBuilder(set.preset, loudness).build(project, shortSide = quality.shortSide) }
                .onFailure { Log.w(TAG, "The plan could not be built", it) }
                .getOrNull() ?: return ExportOutcome.Failed(ExportFailure.FAILED)
        if (plan.durationUs <= 0 || plan.visual.isEmpty()) return ExportOutcome.Failed(ExportFailure.NOTHING)
        logDecoders(plan)

        val estimate = quality.estimatedBytes(plan.durationUs)
        val space = ExportSpace.decide(estimate, freeBytes())
        if (space is ExportSpace.NotEnough) return ExportOutcome.Failed(ExportFailure.NO_SPACE, space.missingBytes)
        val direct = space == ExportSpace.Direct
        Log.i(
            TAG,
            "Export ${quality.name} ${plan.width}x${plan.height}, ${plan.durationUs / 1000} ms, about $estimate bytes, direct=$direct",
        )

        val colour = runInterruptible(Dispatchers.IO) { ColourCalibration.of(context, retryUnclear = true) }
        val composition =
            try {
                withContext(Dispatchers.IO) { CompositionFactory(context, set.resolver) { colour }.build(plan) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaUnavailableException) {
                Log.w(TAG, "Media went missing", e)
                return ExportOutcome.Failed(ExportFailure.MISSING_MEDIA)
            } catch (e: Exception) {
                Log.w(TAG, "The composition could not be built", e)
                return ExportOutcome.Failed(ExportFailure.FAILED)
            }

        val name = ExportFileName.displayName(project.name, fallbackName)
        // Created, opened, copied and shown without a cancellation check in between, so a Stop
        // can never lose track of an entry it must remove, or report "nothing saved" for one
        // that was just shown (review F5, F6). The finally below removes an unshown entry.
        val entry =
            try {
                withContext(NonCancellable + Dispatchers.IO) { store.createPending(name) }
            } catch (e: Exception) {
                Log.w(TAG, "The gallery refused a new video", e)
                return ExportOutcome.Failed(ExportFailure.FAILED)
            }
        var temp: File? = null
        var fd: ParcelFileDescriptor? = null
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            val output: ExportOutput =
                if (direct) {
                    val opened = withContext(NonCancellable + Dispatchers.IO) { store.openForWrite(entry) }
                    fd = opened
                    currentCoroutineContext().ensureActive()
                    DirectOutput(opened, File(tempDir.apply { mkdirs() }, "direct.unused"))
                } else {
                    val file = File(tempDir.apply { mkdirs() }, "export-${System.nanoTime()}.mp4")
                    temp = file
                    TempOutput(file)
                }
            val spec = EncodeSpec(quality.videoBitrate, quality.audioBitrate)
            val done =
                when (
                    val outcome =
                        VideoExport(
                            context,
                            colour,
                            baseEncoders,
                        ).run(composition, maxOf(plan.width, plan.height), spec, output) { onProgress(it) }
                ) {
                    is VideoExport.Outcome.Failed -> {
                        Log.w(TAG, "Export failed: ${outcome.error.errorCodeName}", outcome.error)
                        val reason = if (outcome.error.isMissingFile()) ExportFailure.MISSING_MEDIA else ExportFailure.FAILED
                        return ExportOutcome.Failed(reason)
                    }

                    is VideoExport.Outcome.Done -> {
                        outcome
                    }
                }
            val info =
                withContext(NonCancellable + Dispatchers.IO) {
                    val written = fd?.let { runCatching { Os.fstat(it.fileDescriptor).st_size }.getOrNull() }
                    fd?.close()
                    fd = null
                    temp?.let { store.copyInto(entry, it) }
                    store.publish(entry)
                    published = true
                    store.describe(entry)?.let { if (it.sizeBytes == null || it.sizeBytes == 0L) it.copy(sizeBytes = written) else it }
                }
            val r = done.result
            val saved =
                ExportDone(
                    uri = entry,
                    displayName = info?.displayName ?: name,
                    sizeBytes = info?.sizeBytes ?: r.fileSizeBytes,
                    durationUs = r.approximateDurationMs * 1000,
                    width = r.width,
                    height = r.height,
                    videoEncoder = r.videoEncoderName,
                    audioEncoder = r.audioEncoderName,
                    averageVideoBitrate = r.averageVideoBitrate,
                    softwareFallback = done.hardwareFailure != null,
                    direct = direct,
                )
            // The name is the user's own text: the log gets the gallery id and the facts only (review F10).
            Log.i(
                TAG,
                "Saved gallery video ${ContentUris.parseId(entry)}: ${saved.width}x${saved.height}, ${saved.sizeBytes} bytes, " +
                    "video encoder ${saved.videoEncoder}, audio encoder ${saved.audioEncoder}, " +
                    "average video ${saved.averageVideoBitrate} b/s, software fallback ${saved.softwareFallback}, direct ${saved.direct}",
            )
            return ExportOutcome.Done(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "The gallery refused the video", e)
            return ExportOutcome.Failed(ExportFailure.FAILED)
        } catch (e: RuntimeException) {
            Log.w(TAG, "The export stopped", e)
            return ExportOutcome.Failed(ExportFailure.FAILED)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { fd?.close() }
                temp?.delete()
                if (!published) store.delete(entry)
            }
        }
    }

    /** Removes what a killed export left: hidden gallery entries and temporary files. Only one export runs at a time. */
    fun sweep() {
        val swept = runCatching { store.sweepPending() }.getOrDefault(0)
        val files = tempFiles().count { it.delete() }
        if (swept + files > 0) Log.i(TAG, "Removed $swept unfinished gallery entries and $files temporary files")
    }

    /** The reason this project cannot be exported now, or null when it can. */
    private suspend fun checkInputs(project: Project): ExportFailure? {
        val refs = project.tracks.flatMap { it.clips }.mapNotNull { it.mediaRef() }
        val slots = refs.filterIsInstance<MediaRef.Preset>().map { it.slot }.toSet()
        val presetId = project.presetId
        if (slots.isNotEmpty()) {
            if (presetId == null) return ExportFailure.PRESET_BROKEN
            val status = presets.verifyBeforeExport(presetId)
            if (slots.any { status.slots[it] !is SlotStatus.Ready }) return ExportFailure.PRESET_BROKEN
        }
        val missing = mediaAccess.missing(project, accessState())
        return if (missing.isNotEmpty()) ExportFailure.MISSING_MEDIA else null
    }

    private fun accessState() =
        MediaPermissions.state(
            Build.VERSION.SDK_INT,
            MediaPermissions
                .toRequest(Build.VERSION.SDK_INT)
                .filterTo(mutableSetOf()) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
            askedBefore = true,
            canAskAgain = false,
        )

    /**
     * [known] plus a measurement of every sound in [project] that has none
     * (cm-flow: a replaced ending was played at its own level). A sound that
     * cannot be measured keeps its own level.
     */
    internal suspend fun measuredLoudness(
        project: Project,
        known: LoudnessTable,
        resolver: com.itsluminous.clearmovie.render.media3.MediaResolver,
    ): LoudnessTable {
        val unknown =
            project.tracks
                .flatMap { it.clips }
                .filterIsInstance<AudioClip>()
                .map { it.media }
                .distinct()
                .filter { known.lufs(it) == null }
        if (unknown.isEmpty()) return known
        val measured =
            unknown
                .mapNotNull { ref ->
                    val uri = runCatching { resolver.resolve(ref) }.getOrNull() ?: return@mapNotNull null
                    probe.loudnessOf(context, uri)?.takeIf { it.isFinite() }?.let { ref to it }
                }.toMap()
        Log.i(TAG, "Measured the loudness of ${measured.size} of ${unknown.size} sounds")
        return LoudnessTable { ref -> known.lufs(ref) ?: measured[ref] }
    }

    private fun logDecoders(plan: com.itsluminous.clearmovie.render.plan.RenderPlan) {
        val peak = plan.decoderPeak()
        val limits =
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.VIDEO_H264, ignoreCase = true) } }
                    .joinToString { "${it.name}=${it.getCapabilitiesForType(MimeTypes.VIDEO_H264).maxSupportedInstances}" }
            }.getOrDefault("unknown")
        Log.i(
            TAG,
            "Decoders at once: ${peak.video} video, ${peak.audio} audio (at ${peak.atUs / 1000} ms); H.264 decoder instances: $limits",
        )
    }

    private fun androidx.media3.transformer.ExportException.isMissingFile(): Boolean =
        errorCode == androidx.media3.transformer.ExportException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            errorCode == androidx.media3.transformer.ExportException.ERROR_CODE_IO_NO_PERMISSION

    /** The normal path: a temporary file in the app's cache, copied into the gallery when done. */
    private class TempOutput(
        private val file: File,
    ) : ExportOutput {
        override val path: String = file.absolutePath

        override fun reset() {
            file.delete()
        }
    }

    /**
     * The low-space path: Transformer's MP4 muxer writes straight into the
     * gallery entry's file descriptor (each attempt from the start), and
     * [placeholder], the path Transformer is given, is never written.
     */
    private class DirectOutput(
        private val fd: ParcelFileDescriptor,
        placeholder: File,
    ) : ExportOutput {
        override val path: String = placeholder.absolutePath

        override val muxerFactory: Muxer.Factory =
            object : Muxer.Factory {
                private val formats = InAppMp4Muxer.Factory()

                override fun create(path: String): Muxer {
                    reset()
                    val stream = ParcelFileDescriptor.AutoCloseOutputStream(fd.dup())
                    return GalleryMuxer(Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).setAttemptStreamableOutputEnabled(true).build())
                }

                override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
                    formats.getSupportedSampleMimeTypes(trackType)
            }

        override fun reset() {
            runCatching {
                Os.ftruncate(fd.fileDescriptor, 0)
                Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
            }
        }
    }

    /**
     * Media3's MP4 muxer as its own file-path muxer uses it (`InAppMp4Muxer`):
     * only the metadata MP4 can hold is passed on, and a video track's
     * rotation is written as its orientation.
     */
    private class GalleryMuxer(
        private val mp4: Mp4Muxer,
    ) : Muxer by mp4 {
        override fun addTrack(format: Format): Int {
            val id = mp4.addTrack(format)
            if (MimeTypes.isVideo(format.sampleMimeType)) mp4.addMetadataEntry(Mp4OrientationData(format.rotationDegrees))
            return id
        }

        override fun addMetadataEntry(entry: Metadata.Entry) {
            if (MuxerUtil.isMetadataSupported(entry)) mp4.addMetadataEntry(entry)
        }
    }

    companion object {
        private const val TAG = "ExportPipeline"
        const val TEMP_DIR = "export"
    }
}
