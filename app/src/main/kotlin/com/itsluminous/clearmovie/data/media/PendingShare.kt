package com.itsluminous.clearmovie.data.media

import android.net.Uri
import androidx.core.net.toUri
import com.itsluminous.clearmovie.domain.model.MediaRef
import java.io.File
import java.io.IOException

/**
 * The links of the share being taken in, kept in a small file so a process
 * death before they are on a timeline can take them in again (the share's
 * grants belong to the activity, which Android keeps). A file rather than the
 * saved state, because 500 long links would not fit in a saved-state bundle.
 * One share at a time; [clear] once its items are on a timeline or dropped.
 */
class PendingShare(
    private val file: File,
) {
    fun save(uris: List<Uri>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(uris.joinToString("\n") { it.toString() })
            tmp.renameTo(file)
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    /** The saved links (only ones the model accepts, at most [ShareLinks.MAX_ITEMS]); empty when there are none. */
    fun load(): List<Uri> =
        try {
            if (!file.isFile || file.length() > MAX_BYTES) {
                emptyList()
            } else {
                file
                    .readLines()
                    .filter(MediaRef::isSafeContentUri)
                    .distinct()
                    .take(ShareLinks.MAX_ITEMS)
                    .map { it.toUri() }
            }
        } catch (e: IOException) {
            emptyList()
        }

    fun clear() {
        file.delete()
    }

    private companion object {
        /** 500 links of the longest length the model accepts, with room to spare. */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
