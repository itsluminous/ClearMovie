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
 * creation time. A file that cannot be read has no time. A shared file can
 * be built to break a reader, so EXIF is read only from a photo, from at
 * most [MAX_EXIF_BYTES] of it, and any failure (even running out of memory
 * on a crafted size) just means no time.
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
        CaptureDates.provider(item.info?.dateTakenMs)?.let { return it }
        val ref = item.ref ?: return null
        val mime = item.info?.mime.orEmpty()
        return withContext(dispatcher) {
            try {
                when {
                    mime.startsWith("video/") -> videoDate(project, ref)
                    mime.startsWith("image/") -> photoDate(project, ref)
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                // A crafted chunk size: the parse gave up before using the memory.
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
        return BoundedInputStream(stream, MAX_EXIF_BYTES).use {
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

/** At most this much of a photo is read for its EXIF date (EXIF sits at the start of a photo). */
private const val MAX_EXIF_BYTES = 16L * 1024 * 1024

/** [input], ending after [limit] bytes. */
private class BoundedInputStream(
    private val input: java.io.InputStream,
    private var limit: Long,
) : java.io.FilterInputStream(input) {
    override fun read(): Int = if (limit <= 0) -1 else input.read().also { if (it >= 0) limit-- }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (limit <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }

    override fun skip(n: Long): Long = input.skip(minOf(n, limit)).also { limit -= it }

    override fun markSupported(): Boolean = false
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

    /**
     * A provider's `DATE_TAKEN`: milliseconds, as MediaStore gives it. Some
     * apps give seconds (a value below 10^11 ms would be before 1973, so it is
     * read as seconds); 0 and other unbelievable values mean no date.
     */
    fun provider(value: Long?): Long? {
        val v = value?.takeIf { it > 0 } ?: return null
        return believable(if (v < SECONDS_LIMIT) v * 1000 else v)
    }

    private const val SECONDS_LIMIT = 100_000_000_000L

    private fun believable(ms: Long): Long? = ms.takeIf { it >= EARLIEST_MS }
}
