package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.net.Uri

/** The MIME type a provider gives a link; null when it gives none or fails. Behind an interface so JVM tests can fake it. */
fun interface MimeLookup {
    fun typeOf(uri: Uri): String?
}

/** [MimeLookup] on a [ContentResolver]; a provider that throws counts as giving no type. */
class ResolverMimeLookup(
    private val resolver: ContentResolver,
) : MimeLookup {
    override fun typeOf(uri: Uri): String? =
        try {
            resolver.getType(uri)
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
