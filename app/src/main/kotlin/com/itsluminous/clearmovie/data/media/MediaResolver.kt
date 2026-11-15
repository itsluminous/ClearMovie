package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.net.Uri
import com.itsluminous.clearmovie.domain.model.MediaRef

/** How a picked or shared link will be kept in a project. */
sealed interface Resolution {
    /** Kept as a link: a MediaStore uri, or a link whose read grant is persisted. */
    data class Linked(
        val uri: String,
        val persistedGrant: Boolean,
        val info: SourceInfo?,
    ) : Resolution

    /** Readable now, but not later: it must be copied while the grant lasts. */
    data class NeedsCopy(
        val source: Uri,
        val info: SourceInfo,
    ) : Resolution

    /** Not a link the app reads (another scheme, the app's own provider), or it cannot be read now. */
    data class Unreadable(
        val source: Uri,
    ) : Resolution
}

/**
 * Turns a link from the Photo Picker, `OPEN_DOCUMENT` or a share into what a
 * project keeps (ARCHITECTURE section 9, review F4), in this order:
 *
 * 1. A MediaStore item uri is kept as it is, given full access.
 * 2. A Photo Picker uri carries the MediaStore id: with full access, the
 *    MediaStore item with that id and the same size is kept.
 * 3. A media-provider document (Android 10 and 11, where the picker falls
 *    back to `OPEN_DOCUMENT`) goes through `MediaStore.getMediaUri`.
 * 4. Any other provider (Google Photos, chat apps, Files): with full access,
 *    a MediaStore item with the same name and size (and capture date, when
 *    several match) is kept instead.
 * 5. Otherwise, a link whose read grant can be persisted is kept with that
 *    grant (documents, and picker items under partial access).
 * 6. Otherwise the item is copied, while the grant from the picker or share
 *    is still valid.
 *
 * Only `content://` links are accepted. `file://` and other schemes, and
 * links to the app's own providers (a share could otherwise make the app read
 * its own private files), are refused.
 */
class MediaResolver(
    private val gateway: MediaStoreGateway,
    private val grants: UriGrants,
    private val ownAuthorityPrefix: String,
) {
    fun resolve(
        uri: Uri,
        access: MediaAccessState,
    ): Resolution {
        val authority = uri.authority
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || authority.isNullOrEmpty()) return Resolution.Unreadable(uri)
        if (authority == ownAuthorityPrefix || authority.startsWith("$ownAuthorityPrefix.")) return Resolution.Unreadable(uri)
        if (!MediaRef.isSafeContentUri(uri.toString())) return copyOrRefuse(uri, gateway.describe(uri))
        // A whole MediaStore collection, or any other non-item MediaStore path, is never one photo.
        if (MediaStoreGateway.isMediaStore(uri) && !MediaStoreGateway.isMediaStoreItem(uri) && !MediaStoreGateway.isPickerUri(uri)) {
            return Resolution.Unreadable(uri)
        }

        val info = gateway.describe(uri)
        if (access.isFull) linkedInMediaStore(uri, info)?.let { return it }
        if (grants.persist(uri)) return Resolution.Linked(uri.toString(), persistedGrant = true, info = info)
        return copyOrRefuse(uri, info)
    }

    private fun linkedInMediaStore(
        uri: Uri,
        info: SourceInfo?,
    ): Resolution.Linked? {
        val found: Uri? =
            when {
                MediaStoreGateway.isPickerUri(uri) -> pickerItem(uri, info) ?: byNameAndSize(info)
                MediaStoreGateway.isMediaStoreItem(uri) -> uri.takeIf { info != null }
                MediaStoreGateway.isMediaStore(uri) -> null
                uri.authority == MediaStoreGateway.MEDIA_DOCUMENTS_AUTHORITY -> gateway.mediaUriForDocument(uri) ?: byNameAndSize(info)
                else -> byNameAndSize(info)
            }
        return found
            ?.toString()
            ?.takeIf(MediaRef::isSafeContentUri)
            ?.let { Resolution.Linked(it, persistedGrant = false, info = info) }
    }

    private fun pickerItem(
        uri: Uri,
        info: SourceInfo?,
    ): Uri? {
        // Only the local picker's ids are MediaStore ids; a cloud item's are not.
        if (MediaStoreGateway.LOCAL_PICKER !in uri.pathSegments) return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val video = (info?.mime ?: "").startsWith("video/")
        return gateway.itemById(id, video, info?.sizeBytes)
    }

    /**
     * The one MediaStore item with the same name and size. When several match,
     * the capture date picks one; if it cannot, nothing is matched and the item
     * is copied rather than risk linking the wrong file.
     */
    private fun byNameAndSize(info: SourceInfo?): Uri? {
        val name = info?.displayName ?: return null
        val size = info.sizeBytes?.takeIf { it > 0 } ?: return null
        val matches = gateway.findByNameAndSize(name, size)
        if (matches.size == 1) return matches.single().first
        val date = info.dateTakenMs ?: return null
        return matches.filter { it.second == date }.singleOrNull()?.first
    }

    private fun copyOrRefuse(
        uri: Uri,
        info: SourceInfo?,
    ): Resolution = if (info == null) Resolution.Unreadable(uri) else Resolution.NeedsCopy(uri, info)
}
