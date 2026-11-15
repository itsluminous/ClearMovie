package com.itsluminous.clearmovie.testing

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * An in-memory content provider for Robolectric, standing in for MediaStore
 * (authority `media`) or for another app's provider (Google Photos, a chat
 * app). Rows are added by the test; queries answer the shapes the app sends:
 * one item by uri, or a collection filtered by display name and size.
 */
open class FakeMediaProvider : ContentProvider() {
    data class Row(
        val uri: Uri,
        val name: String?,
        val size: Long?,
        val mime: String?,
        val dateTaken: Long? = null,
        val bytes: ByteArray = ByteArray(0),
    )

    val rows = linkedMapOf<String, Row>()

    /** Document uri -> MediaStore uri, answered for `MediaStore.getMediaUri`. */
    val documentToMedia = mutableMapOf<String, Uri>()

    /** Uris whose queries throw SecurityException, as a picker or document link does once its grant is gone. */
    val forbidden = mutableSetOf<String>()

    /**
     * Uris the app may not see: an empty answer, as the real MediaProvider
     * gives for an item the app has no permission to read.
     */
    val hidden = mutableSetOf<String>()

    /** How many times a file was opened (a check must not open other apps' files it holds no grant for). */
    var opens = 0
        private set

    /** Answer queries with no cursor at all, like some third-party providers. */
    var nullCursor = false

    var queries = 0
        private set

    fun add(row: Row): Uri {
        rows[row.uri.toString()] = row
        return row.uri
    }

    /** Adds a MediaStore image or video with the next free id. */
    fun addMedia(
        name: String,
        size: Long,
        video: Boolean = false,
        dateTaken: Long? = null,
        id: Long = (rows.size + 1).toLong(),
    ): Uri {
        val collection = if (video) VIDEO else IMAGES
        val uri = ContentUris.withAppendedId(collection, id)
        add(Row(uri, name, size, if (video) "video/mp4" else "image/jpeg", dateTaken))
        return uri
    }

    fun delete(uri: Uri) {
        rows.remove(uri.toString())
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queries++
        if (uri.toString() in forbidden) throw SecurityException("no access")
        if (nullCursor) return null
        val columns = projection ?: ALL
        val cursor = MatrixCursor(columns)
        val exact = rows[uri.toString()]?.takeUnless { uri.toString() in hidden }
        val matches =
            when {
                exact != null -> {
                    listOf(exact)
                }

                selection != null && selectionArgs != null && selectionArgs.size == 2 -> {
                    rows.values.filter {
                        it.uri.toString().startsWith("$uri/") && it.name == selectionArgs[0] && it.size?.toString() == selectionArgs[1]
                    }
                }

                else -> {
                    emptyList()
                }
            }
        for (row in matches) {
            cursor.addRow(
                columns.map { c ->
                    when (c) {
                        MediaStore.MediaColumns._ID -> row.uri.lastPathSegment?.toLongOrNull()
                        MediaStore.MediaColumns.DISPLAY_NAME -> row.name
                        MediaStore.MediaColumns.SIZE -> row.size
                        MediaStore.MediaColumns.MIME_TYPE -> row.mime
                        MediaStore.MediaColumns.DATE_TAKEN -> row.dateTaken
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = rows[uri.toString()]?.mime

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        opens++
        val row = rows[uri.toString()] ?: throw java.io.FileNotFoundException(uri.toString())
        val file = File.createTempFile("fake", ".bin").apply { writeBytes(row.bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != "get_media_uri") return null
        @Suppress("DEPRECATION")
        val document = extras?.getParcelable<Uri>("uri") ?: return null
        return Bundle().apply { putParcelable("uri", documentToMedia[document.toString()]) }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        val IMAGES: Uri = Uri.parse("content://media/external/images/media")
        val VIDEO: Uri = Uri.parse("content://media/external/video/media")
        val ALL =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_TAKEN,
            )
    }
}

/** A second fake, so a test can register MediaStore and another app's provider at once. */
class FakeOtherAppProvider : FakeMediaProvider()

/** A third fake for the media documents provider (the `OPEN_DOCUMENT` path). */
class FakeDocumentsProvider : FakeMediaProvider()
