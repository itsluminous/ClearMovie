package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PresetSlot
import java.io.File

/** A clip's media could not be opened: deleted, moved, or never bound. */
class MediaUnavailableException(
    message: String,
) : Exception(message)

/**
 * Turns a [MediaRef] into something Media3 can open. cm-storage supplies the
 * real one; [FileMediaResolver] covers files the app owns.
 */
fun interface MediaResolver {
    /** @throws MediaUnavailableException when the media is not there. */
    fun resolve(ref: MediaRef): Uri
}

/**
 * Resolves preset slots to the bound copies in [presetFiles], copied items
 * to files under [copiedRoot], and MediaStore items to their `content://`
 * uri. Paths are checked again here, after the model's own check: a copied
 * path must stay inside [copiedRoot] once symlinks and `..` are resolved.
 */
class FileMediaResolver(
    private val presetFiles: Map<PresetSlot, File>,
    private val copiedRoot: File?,
) : MediaResolver {
    override fun resolve(ref: MediaRef): Uri =
        when (ref) {
            is MediaRef.Store -> {
                if (!MediaRef.isSafeContentUri(ref.uri)) throw MediaUnavailableException("Not a content uri")
                ref.uri.toUri()
            }

            is MediaRef.Copied -> {
                val root = copiedRoot ?: throw MediaUnavailableException("This project has no copied media")
                Uri.fromFile(requireInside(root, File(root, ref.relPath)))
            }

            is MediaRef.Preset -> {
                val file = presetFiles[ref.slot] ?: throw MediaUnavailableException("The preset file is not set up")
                if (!file.isFile) throw MediaUnavailableException("The preset file is missing")
                Uri.fromFile(file)
            }
        }

    companion object {
        /** [file] if it exists inside [root] after canonicalisation; else [MediaUnavailableException]. */
        fun requireInside(
            root: File,
            file: File,
        ): File {
            val r = root.canonicalFile
            val f = file.canonicalFile
            if (!f.path.startsWith(r.path + File.separator)) throw MediaUnavailableException("Media path leaves its folder")
            if (!f.isFile) throw MediaUnavailableException("Media file is missing")
            return f
        }
    }
}

/**
 * Checks every uri the factory hands to Media3: `content://` uris, or
 * `file://` uris inside the app's own files or cache folders. Anything else
 * (another app's private file, `http`, a bare path) is refused, so a bad
 * resolver can never make the renderer read outside the app.
 */
internal class UriGuard(
    context: Context,
) {
    private val roots = listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir).map { it.canonicalFile }

    fun check(uri: Uri): Uri {
        when (uri.scheme) {
            "content" -> {
                if (!MediaRef.isSafeContentUri(uri.toString())) throw MediaUnavailableException("Unsafe content uri")
            }

            "file" -> {
                val path = uri.path ?: throw MediaUnavailableException("File uri without a path")
                val f = File(path).canonicalFile
                if (roots.none { f.path.startsWith(it.path + File.separator) }) {
                    throw MediaUnavailableException("File is outside the app's folders")
                }
            }

            else -> {
                throw MediaUnavailableException("Unsupported uri scheme")
            }
        }
        return uri
    }
}
