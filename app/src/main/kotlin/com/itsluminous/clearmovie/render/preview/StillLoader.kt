package com.itsluminous.clearmovie.render.preview

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads the pictures the preview draws itself: photos, decoded no larger
 * than [maxSide] on their long side (a 48 MP photo never reaches memory at
 * full size, review F8), and held video frames. A few are kept, so stepping
 * back and forth does not decode again ([cacheSize] pictures; the timeline's
 * thumbnail strips keep more, smaller ones). Null when the file can't be read.
 */
class StillLoader(
    private val context: Context,
    private val maxSide: Int = DEFAULT_MAX_SIDE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    cacheSize: Int = CACHE_SIZE,
) {
    private val cache = LruCache<Key, Bitmap>(cacheSize)

    private data class Key(
        val media: MediaRef,
        val atUs: Long?,
    )

    suspend fun load(
        source: Source,
        resolver: MediaResolver,
    ): Bitmap? {
        val key =
            when (source) {
                is Source.Photo -> Key(source.media, null)
                is Source.VideoFrame -> Key(source.media, source.atUs)
                else -> return null
            }
        cache.get(key)?.let { return it }
        val bitmap =
            withContext(dispatcher) {
                val uri = runCatching { resolver.resolve(key.media) }.getOrNull() ?: return@withContext null
                try {
                    if (key.atUs == null) photo(uri) else frame(uri, key.atUs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun photo(uri: Uri): Bitmap {
        val src =
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                ImageDecoder.createSource(File(requireNotNull(uri.path)))
            } else {
                ImageDecoder.createSource(context.contentResolver, uri)
            }
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val long = maxOf(w, h)
            if (long > maxSide) {
                val scale = maxSide.toDouble() / long
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
            // Software bitmaps can be drawn anywhere; hardware ones can't be read back by tests.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun frame(
        uri: Uri,
        atUs: Long,
    ): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == ContentResolver.SCHEME_FILE) r.setDataSource(uri.path) else r.setDataSource(context, uri)
            r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide)
        } finally {
            runCatching { r.release() }
        }
    }

    companion object {
        const val DEFAULT_MAX_SIDE = 1280
        const val CACHE_SIZE = 4
    }
}
