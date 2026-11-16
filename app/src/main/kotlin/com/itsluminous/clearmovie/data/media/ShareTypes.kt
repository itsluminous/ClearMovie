package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

/**
 * Quick questions about a shared link, asked of the provider that sent it
 * (any app's). Every answer may be missing; a provider that throws counts as
 * giving none. Behind an interface so JVM tests can fake it.
 */
interface ShareProbe {
    /** The MIME type the provider gives the link. */
    fun typeOf(uri: Uri): String?

    /** The length a provider such as MediaStore gives a video, in ms, without opening the file. */
    fun durationMs(uri: Uri): Long?
}

/** [ShareProbe] on a [ContentResolver]. */
class ResolverShareProbe(
    private val resolver: ContentResolver,
) : ShareProbe {
    override fun typeOf(uri: Uri): String? =
        try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }

    override fun durationMs(uri: Uri): Long? =
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DURATION), null, null, null)?.use { c ->
                val i = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i).takeIf { it > 0 } else null
            }
        } catch (e: Exception) {
            null
        }
}

/** Which shared links are photos or videos (ARCHITECTURE section 11.12). */
object ShareTypes {
    enum class Kind {
        /** `image/...` or `video/...`. */
        MEDIA,

        /** A type that is clearly something else (a PDF, text, a sound): skipped and counted. */
        OTHER,

        /** No usable type: kept, and the item's own contents decide later. */
        UNKNOWN,
    }

    fun kindOf(mime: String?): Kind {
        val type = mime?.trim()?.lowercase().orEmpty()
        return when {
            type.isEmpty() || type == "*/*" || type == "application/octet-stream" -> Kind.UNKNOWN
            type.startsWith("image/") || type.startsWith("video/") -> Kind.MEDIA
            else -> Kind.OTHER
        }
    }
}
