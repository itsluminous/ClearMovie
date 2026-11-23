package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import com.itsluminous.clearmovie.data.project.GrantReleaser

/**
 * Persisted read permissions ("grants") for links that are not MediaStore
 * links: a document chosen with `OPEN_DOCUMENT` (the Photo Picker's fallback
 * on Android 10 and 11), or a Photo Picker item under partial access. Only
 * read access is ever taken. Android keeps a limited number per app, so the
 * app stops persisting [HEADROOM] short of the limit and copies instead, and
 * releases a grant when the last project using it is deleted.
 */
interface UriGrants : GrantReleaser {
    /** Persists read access to [uri]; false when the link's grant cannot be persisted (a share) or the limit is near. */
    fun persist(uri: Uri): Boolean

    /** Whether the app still holds a persisted read grant for [uri]. */
    fun isPersisted(uri: String): Boolean

    /**
     * False when fewer than [HEADROOM] grants are left below Android's limit,
     * the same test [persist] refuses on. A caller uses it to tell [persist]
     * failing at the limit from failing because the link has no persistable
     * grant. True when the held grants cannot be read, and by default.
     */
    fun hasRoom(): Boolean = true

    companion object {
        const val HEADROOM = 16
    }
}

class AndroidUriGrants(
    private val resolver: ContentResolver,
    private val limit: () -> Int = { MediaStoreGateway.PERSISTED_GRANT_LIMIT },
) : UriGrants {
    override fun persist(uri: Uri): Boolean {
        val held = runCatching { resolver.persistedUriPermissions }.getOrNull() ?: return false
        if (held.any { it.uri == uri && it.isReadPermission }) return true
        if (held.size >= limit() - UriGrants.HEADROOM) return false
        return try {
            resolver.takePersistableUriPermission(uri, MediaStoreGateway.READ_GRANT)
            true
        } catch (e: SecurityException) {
            // No persistable grant came with the link (a share, for example).
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun hasRoom(): Boolean {
        val held = runCatching { resolver.persistedUriPermissions }.getOrNull() ?: return true
        return held.size < limit() - UriGrants.HEADROOM
    }

    override fun isPersisted(uri: String): Boolean =
        runCatching { resolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission } }.getOrDefault(false)

    override fun release(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(uri.toUri(), MediaStoreGateway.READ_GRANT) }
    }
}
