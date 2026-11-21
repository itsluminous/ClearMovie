package com.itsluminous.clearmovie.data.preset

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One picture of a frame video, for the frame look screen (cm-frame-area):
 * the colour picker samples it and the picture area is drawn on it. It is
 * decoded with the stream's own colour matrix ([YuvFrameDecoder], as the
 * key-colour check at setup is), so a tapped colour is the one the export's
 * keyer compares against; `getFrameAtTime` ignores the BT.709 tag on some
 * devices and would shift a green by Delta E 16.
 */
fun interface FrameStills {
    /** The picture at [atUs] of the video at [uri] (`file://` or `content://`), at most [maxWidth] across; null if unreadable. */
    suspend fun still(
        uri: Uri,
        atUs: Long,
        maxWidth: Int,
    ): FrameSample?
}

class AndroidFrameStills(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FrameStills {
    override suspend fun still(
        uri: Uri,
        atUs: Long,
        maxWidth: Int,
    ): FrameSample? =
        withContext(dispatcher) {
            try {
                val open: (MediaExtractor) -> Unit =
                    when (uri.scheme) {
                        ContentResolver.SCHEME_FILE -> {
                            val path = requireNotNull(uri.path)
                            val byPath: (MediaExtractor) -> Unit = { it.setDataSource(path) }
                            byPath
                        }

                        ContentResolver.SCHEME_CONTENT -> {
                            val byLink: (MediaExtractor) -> Unit = { it.setDataSource(context, uri, null) }
                            byLink
                        }

                        else -> {
                            return@withContext null
                        }
                    }
                YuvFrameDecoder.frames(open, listOf(atUs.coerceAtLeast(0)), maxWidth).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
}
