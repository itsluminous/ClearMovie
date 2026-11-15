package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns

/** What a provider says about one of its files. Every field may be missing. */
data class SourceInfo(
    val displayName: String?,
    val sizeBytes: Long?,
    val mime: String?,
    val dateTakenMs: Long?,
)

/** Whether a file behind a link is still there. */
enum class LinkState {
    PRESENT,

    /** The row or file is gone (deleted or moved). */
    GONE,

    /** It may exist, but the app may no longer read it (permission withdrawn). */
    NO_ACCESS,
}

/**
 * The provider queries [MediaResolver] and [MediaAccess] need, on a
 * [ContentResolver]. Every call treats the provider as untrusted: a crash,
 * a refusal or a missing column becomes a null or an empty answer, never an
 * exception.
 */
class MediaStoreGateway(
    private val context: Context,
    private val resolver: ContentResolver = context.contentResolver,
) {
    /** Name, size, type and capture date of [uri], or null when it cannot be read. */
    fun describe(uri: Uri): SourceInfo? {
        val mime = safely { resolver.getType(uri) }
        val cursor =
            safely { resolver.query(uri, null, null, null, null) }
                ?: safely { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null) }
                ?: return null
        return cursor.use { c ->
            if (!safely { c.moveToFirst() }.orFalse()) return null
            SourceInfo(
                displayName = c.string(OpenableColumns.DISPLAY_NAME),
                sizeBytes = c.long(OpenableColumns.SIZE)?.takeIf { it >= 0 },
                mime = mime ?: c.string(MediaStore.MediaColumns.MIME_TYPE),
                dateTakenMs = c.long(MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 },
            )
        }
    }

    /** The MediaStore uri behind a media-provider document (the API 29-32 `OPEN_DOCUMENT` path). */
    fun mediaUriForDocument(uri: Uri): Uri? = safely { MediaStore.getMediaUri(context, uri) }

    /**
     * The MediaStore item with id [id] in the image or video collection, if
     * it exists and has [sizeBytes] (a Photo Picker id is the MediaStore id
     * for items on the phone; a cloud item's is not, and the size check
     * catches that).
     */
    fun itemById(
        id: Long,
        video: Boolean,
        sizeBytes: Long?,
    ): Uri? {
        val collection = collection(video)
        val uri = ContentUris.withAppendedId(collection, id)
        val cursor = safely { resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE), null, null, null) }
        return cursor?.use { c ->
            if (!safely { c.moveToFirst() }.orFalse()) return null
            val size = c.long(MediaStore.MediaColumns.SIZE)
            uri.takeIf { sizeBytes == null || size == sizeBytes }
        }
    }

    /**
     * MediaStore items (images and videos) named [displayName] with exactly
     * [sizeBytes] bytes, each with its capture date.
     */
    fun findByNameAndSize(
        displayName: String,
        sizeBytes: Long,
    ): List<Pair<Uri, Long?>> =
        listOf(false, true).flatMap { video ->
            val collection = collection(video)
            val cursor =
                safely {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                        arrayOf(displayName, sizeBytes.toString()),
                        null,
                    )
                }
            cursor
                ?.use { c ->
                    buildList {
                        while (safely { c.moveToNext() }.orFalse() && size < MAX_MATCHES) {
                            val id = c.long(MediaStore.MediaColumns._ID) ?: continue
                            add(ContentUris.withAppendedId(collection, id) to c.long(MediaStore.MediaColumns.DATE_TAKEN))
                        }
                    }
                }.orEmpty()
        }

    /**
     * Whether the file behind [uri] is still there and readable. A provider
     * that does not answer queries is asked to open the file only when
     * [mayOpen] (the app holds a grant for it), so checking a project never
     * starts a download from, or a call into, a provider the app no longer trusts.
     */
    fun state(
        uri: Uri,
        mayOpen: Boolean = false,
    ): LinkState {
        val cursor =
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            } catch (e: SecurityException) {
                return LinkState.NO_ACCESS
            } catch (e: Exception) {
                // An uninstalled provider throws IllegalArgumentException; others may throw anything.
                return LinkState.GONE
            } ?: return if (isMediaStore(uri) || !mayOpen) LinkState.GONE else probeOpen(uri)
        return cursor.use { c -> if (safely { c.moveToFirst() }.orFalse()) LinkState.PRESENT else LinkState.GONE }
    }

    /** Providers that do not answer queries: try to open the file instead. */
    private fun probeOpen(uri: Uri): LinkState =
        try {
            resolver.openFileDescriptor(uri, "r")?.use { LinkState.PRESENT } ?: LinkState.GONE
        } catch (e: SecurityException) {
            LinkState.NO_ACCESS
        } catch (e: Exception) {
            LinkState.GONE
        }

    private fun collection(video: Boolean): Uri =
        if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }

    private fun Cursor.string(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || safely { isNull(i) } != false) null else safely { getString(i) }
    }

    private fun Cursor.long(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i < 0 || safely { isNull(i) } != false) null else safely { getLong(i) }
    }

    private fun Boolean?.orFalse() = this == true

    companion object {
        const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"
        private const val MAX_MATCHES = 8

        fun isMediaStore(uri: Uri): Boolean = uri.authority == MediaStore.AUTHORITY

        /** A Photo Picker link (`content://media/picker/...`, or `picker_get_content`). */
        fun isPickerUri(uri: Uri): Boolean = isMediaStore(uri) && uri.pathSegments.firstOrNull()?.startsWith("picker") == true

        /** The local photo picker's provider segment; a cloud provider's ids are not MediaStore ids. */
        const val LOCAL_PICKER = "com.android.providers.media.photopicker"

        /** A MediaStore link to one item (it ends in a numeric id), not a whole collection. */
        fun isMediaStoreItem(uri: Uri): Boolean = isMediaStore(uri) && !isPickerUri(uri) && (uri.lastPathSegment?.toLongOrNull() ?: -1) >= 0

        /** The flag a persistable grant needs; kept here so tests do not need an Intent. */
        const val READ_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION

        /** The most persisted grants Android keeps per app (128 before Android 11, 512 after). */
        val PERSISTED_GRANT_LIMIT: Int get() = if (Build.VERSION.SDK_INT >= 30) 512 else 128

        private inline fun <T> safely(block: () -> T): T? =
            try {
                block()
            } catch (e: Exception) {
                null
            }
    }
}
