package com.itsluminous.clearmovie.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** What a picked or chosen file is, once the app can open it. */
data class MediaFacts(
    val durationMs: Long?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val isImage: Boolean,
)

/**
 * Reads [MediaFacts] for a project's media: a gallery link or a copy in the
 * project's folder. Behind an interface so JVM tests can fake it.
 */
fun interface MediaFactsProbe {
    /** Null when the file can't be opened or isn't a photo, video or sound. */
    suspend fun probe(
        project: ProjectId,
        ref: MediaRef,
        mime: String?,
    ): MediaFacts?

    /**
     * A photo's or video's size as it is shown (its rotation or EXIF
     * orientation applied), width to height; null when it can't be read.
     * Only the editor's first shape question uses it (cm-aspect-ratio).
     */
    suspend fun shownSize(
        project: ProjectId,
        ref: MediaRef,
        isPhoto: Boolean,
    ): Pair<Int, Int>? = null
}

/** [MediaFactsProbe] with `MediaMetadataRetriever`; photos are told apart by their MIME type. */
class AndroidMediaFactsProbe(
    private val context: Context,
    private val files: AppFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaFactsProbe {
    override suspend fun probe(
        project: ProjectId,
        ref: MediaRef,
        mime: String?,
    ): MediaFacts? =
        withContext(dispatcher) {
            if (mime?.startsWith("image/") == true) return@withContext MediaFacts(null, hasVideo = false, hasAudio = false, isImage = true)
            val r = MediaMetadataRetriever()
            try {
                when (ref) {
                    is MediaRef.Store -> r.setDataSource(context, ref.uri.toUri())
                    is MediaRef.Copied -> r.setDataSource(copied(project, ref).path)
                    is MediaRef.Preset -> return@withContext null
                }
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val image = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_IMAGE) == "yes" && !hasVideo && !hasAudio
                MediaFacts(duration, hasVideo, hasAudio, image)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                null
            } finally {
                runCatching { r.release() }
            }
        }

    override suspend fun shownSize(
        project: ProjectId,
        ref: MediaRef,
        isPhoto: Boolean,
    ): Pair<Int, Int>? =
        withContext(dispatcher) {
            try {
                if (isPhoto) photoSize(project, ref) else videoSize(project, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable item only means no suggestion: the question still shows.
                null
            }
        }

    private fun open(
        project: ProjectId,
        ref: MediaRef,
    ): InputStream? =
        when (ref) {
            is MediaRef.Store -> context.contentResolver.openInputStream(ref.uri.toUri())
            is MediaRef.Copied -> copied(project, ref).inputStream()
            is MediaRef.Preset -> null
        }

    private fun photoSize(
        project: ProjectId,
        ref: MediaRef,
    ): Pair<Int, Int>? {
        // Only the header is read: the size, then the EXIF orientation, never the pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(project, ref)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val turned =
            open(project, ref)?.use {
                ExifInterface(it).rotationDegrees % 180 != 0
            } ?: false
        return if (turned) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    private fun videoSize(
        project: ProjectId,
        ref: MediaRef,
    ): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        try {
            when (ref) {
                is MediaRef.Store -> r.setDataSource(context, ref.uri.toUri())
                is MediaRef.Copied -> r.setDataSource(copied(project, ref).path)
                is MediaRef.Preset -> return null
            }
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rotation % 180 != 0) h to w else w to h
        } finally {
            runCatching { r.release() }
        }
    }

    private fun copied(
        project: ProjectId,
        ref: MediaRef.Copied,
    ): File = files.copiedFile(project, ref)
}

/** New MAIN items from an intake, and how many could not be used. */
data class NewItems(
    val clips: List<MainClip>,
    val skipped: Int,
)

/**
 * Turns the items of a finished intake into photos and videos for MAIN, in
 * the order given: a photo lasts [photoDurationMs], a video plays whole. An
 * item that could not be kept, or that turns out to be neither a photo nor a
 * video the app can play, is counted in [NewItems.skipped].
 */
suspend fun newMainItems(
    project: ProjectId,
    items: List<IntakeItem>,
    photoDurationMs: Long,
    fit: Fit,
    probe: MediaFactsProbe,
): NewItems {
    val clips = mutableListOf<MainClip>()
    var skipped = 0
    for (item in items) {
        val ref = item.ref
        if (ref == null) {
            skipped++
            continue
        }
        val mime = item.info?.mime
        val facts = probe.probe(project, ref, mime)
        when {
            facts == null -> {
                skipped++
            }

            facts.isImage || mime?.startsWith("image/") == true -> {
                clips += PhotoClip(RandomIds.clipId(), ref, photoDurationMs, fit = fit)
            }

            facts.hasVideo && facts.durationMs != null && facts.durationMs in Limits.MIN_CLIP_MS..Limits.MAX_MEDIA_MS -> {
                clips += VideoClip(RandomIds.clipId(), ref, sourceDurationMs = facts.durationMs, fit = fit)
            }

            else -> {
                skipped++
            }
        }
    }
    return NewItems(clips, skipped)
}
