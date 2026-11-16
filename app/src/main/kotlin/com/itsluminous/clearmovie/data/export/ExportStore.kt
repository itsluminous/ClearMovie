package com.itsluminous.clearmovie.data.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/** A finished video in the gallery, as MediaStore reports it. */
data class GalleryVideo(
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long?,
    val pending: Boolean,
    val mime: String?,
)

/**
 * The gallery side of an export (ARCHITECTURE section 12): a hidden
 * (`IS_PENDING`) video entry in `Movies/ClearMovie` that the export fills,
 * then shows. A pending entry is visible to this app only, and Android
 * removes one left behind after about a week, so a crash mid-export never
 * leaves a broken video in the gallery. [sweepPending] removes this app's
 * leftovers at the start of the next export.
 *
 * The display name must already be clean ([com.itsluminous.clearmovie.domain.export.ExportFileName]);
 * it is checked again here, because MediaStore treats a `/` in it as a
 * folder. Nothing outside [RELATIVE_PATH] is ever written or deleted.
 */
class ExportStore(
    private val resolver: ContentResolver,
) {
    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** A new hidden entry named [displayName]; MediaStore adds " (1)" when the name is taken. */
    fun createPending(displayName: String): Uri {
        require(isSafeDisplayName(displayName)) { "unsafe display name" }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return resolver.insert(collection, values) ?: throw IOException("MediaStore refused the new video")
    }

    /** Opens [uri] (one of ours) for writing from the start. */
    fun openForWrite(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(requireOurs(uri), "rwt") ?: throw IOException("The new video could not be opened")

    /** Copies [file] into [uri], replacing anything written before. */
    fun copyInto(
        uri: Uri,
        file: File,
    ) {
        val out = resolver.openOutputStream(requireOurs(uri), "wt") ?: throw IOException("The new video could not be opened")
        out.use { stream -> file.inputStream().use { it.copyTo(stream, COPY_BUFFER) } }
    }

    /** Shows the finished [uri] in the gallery. */
    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(requireOurs(uri), values, null, null) != 1) throw IOException("The new video could not be shown in the gallery")
    }

    /** Removes [uri]; quietly does nothing when it is already gone. */
    fun delete(uri: Uri) {
        runCatching { resolver.delete(requireOurs(uri), null, null) }.onFailure { Log.w(TAG, "Could not remove an unfinished video", it) }
    }

    /** What MediaStore says about [uri], pending or not; null when it is gone. */
    fun describe(uri: Uri): GalleryVideo? =
        query(
            ContentUris.withAppendedId(collection, ContentUris.parseId(requireOurs(uri))),
            null,
            null,
        ).firstOrNull()

    /** This app's hidden, unfinished entries in [RELATIVE_PATH]. */
    fun pendingEntries(): List<GalleryVideo> =
        query(
            collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1",
            arrayOf(RELATIVE_PATH),
        )

    /** Every entry (pending ones included) in [RELATIVE_PATH] named [displayName]. */
    fun entriesNamed(displayName: String): List<GalleryVideo> =
        query(
            collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(RELATIVE_PATH, displayName),
        )

    /** Removes the entries an export killed mid-way left behind. Returns how many. */
    fun sweepPending(): Int = pendingEntries().onEach { delete(it.uri) }.size

    private fun query(
        uri: Uri,
        selection: String?,
        args: Array<String>?,
    ): List<GalleryVideo> {
        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.MIME_TYPE,
            )
        val cursor =
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    val extras =
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        }
                    resolver.query(uri, projection, extras, null)
                } else {
                    @Suppress("DEPRECATION")
                    resolver.query(MediaStore.setIncludePending(uri), projection, selection, args, null)
                }
            }.getOrNull() ?: return emptyList()
        return cursor.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pending = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            buildList {
                while (c.moveToNext()) {
                    add(
                        GalleryVideo(
                            uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                            displayName = c.getString(name).orEmpty(),
                            relativePath = c.getString(path),
                            sizeBytes = if (c.isNull(size)) null else c.getLong(size),
                            pending = c.getInt(pending) == 1,
                            mime = c.getString(mime),
                        ),
                    )
                }
            }
        }
    }

    /** Only entries of the video collection this store writes to, never another app's file or provider. */
    private fun requireOurs(uri: Uri): Uri {
        require(uri.authority == MediaStore.AUTHORITY && uri.pathSegments.contains("video")) { "not a gallery video" }
        return uri
    }

    companion object {
        private const val TAG = "ExportStore"
        const val MIME = "video/mp4"
        const val COPY_BUFFER = 1 shl 16

        /** Where exports go, as MediaStore writes it (with the trailing slash). */
        const val RELATIVE_PATH = "Movies/ClearMovie/"

        /** One file name, never a path or hidden: what ExportFileName produces. */
        fun isSafeDisplayName(name: String): Boolean =
            name.isNotBlank() &&
                name.endsWith(".mp4") &&
                !name.startsWith(".") &&
                name.none { it == '/' || it == '\\' || it == '\u0000' || Character.isISOControl(it) } &&
                name.length <= 128
    }
}
