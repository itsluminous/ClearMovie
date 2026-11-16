package com.itsluminous.clearmovie.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** When a shared item was taken, in ms since 1970, or null when nothing says. Behind an interface so JVM tests can fake it. */
fun interface CaptureTimeReader {
    suspend fun takenAt(
        project: ProjectId,
        item: IntakeItem,
    ): Long?
}

/**
 * [CaptureTimeReader] on Android: the provider's `DATE_TAKEN` (MediaStore
 * has it), else the photo's EXIF `DateTimeOriginal`, else the video's own
 * creation time. A file that cannot be read has no time.
 */
class AndroidCaptureTimeReader(
    private val context: Context,
    private val files: AppFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CaptureTimeReader {
    override suspend fun takenAt(
        project: ProjectId,
        item: IntakeItem,
    ): Long? {
        item.info?.dateTakenMs?.let { return it }
        val ref = item.ref ?: return null
        val mime = item.info?.mime.orEmpty()
        return withContext(dispatcher) {
            try {
                if (mime.startsWith("video/")) videoDate(project, ref) else photoDate(project, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun photoDate(
        project: ProjectId,
        ref: MediaRef,
    ): Long? {
        val stream =
            when (ref) {
                is MediaRef.Store -> context.contentResolver.openInputStream(ref.uri.toUri())
                is MediaRef.Copied -> files.copiedFile(project, ref).inputStream()
                is MediaRef.Preset -> null
            } ?: return null
        return stream.use {
            val exif = ExifInterface(it)
            val taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            CaptureDates.exif(taken, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        }
    }

    private fun videoDate(
        project: ProjectId,
        ref: MediaRef,
    ): Long? {
        val r = MediaMetadataRetriever()
        try {
            when (ref) {
                is MediaRef.Store -> r.setDataSource(context, ref.uri.toUri())
                is MediaRef.Copied -> r.setDataSource(files.copiedFile(project, ref).path)
                is MediaRef.Preset -> return null
            }
            return CaptureDates.mp4(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        } finally {
            runCatching { r.release() }
        }
    }
}

/** Parsing of the capture dates photos and videos carry; pure, so it is tested on the JVM. */
object CaptureDates {
    private val EXIF = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val MP4 = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[.SSS]X")

    /** The earliest believable capture time: an unset MP4 date reads as 1904, an unset clock as 1970. */
    private const val EARLIEST_MS = 31_536_000_000L

    /**
     * EXIF `DateTimeOriginal` ("2024:11:14 18:30:05"), in [offset] ("+05:30")
     * when the photo has one, else in the phone's time zone.
     */
    fun exif(
        value: String?,
        offset: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val local = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val time = LocalDateTime.parse(local, EXIF)
            val zoned = offset?.trim()?.let { runCatching { time.atOffset(ZoneOffset.of(it)) }.getOrNull() }
            believable((zoned?.toInstant() ?: time.atZone(zone).toInstant()).toEpochMilli())
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** A video's creation time as `MediaMetadataRetriever` gives it ("20241114T130005.000Z"). */
    fun mp4(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            believable(OffsetDateTime.parse(text, MP4).toInstant().toEpochMilli())
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun believable(ms: Long): Long? = ms.takeIf { it >= EARLIEST_MS }
}
