package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The small images the factory makes for a composition: the transparent fill,
 * caption and card bitmaps, and held video frames. Each is a PNG in
 * `noBackupFilesDir/render/`, named by a SHA-256 of what it shows, so an
 * unchanged caption is drawn once. The folder is not `cacheDir` because the
 * system may empty that while an export is reading from it; instead
 * [trim] keeps it under [maxBytes], dropping the least recently used files.
 * Files are written to a temporary name first and renamed, so a half-written
 * PNG is never used.
 */
internal class RenderAssets(
    private val context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val dir = File(context.noBackupFilesDir, "render").apply { mkdirs() }

    /** A 16x9 fully transparent PNG; `PlacementEffect` stretches it to the canvas. */
    fun transparent(): Uri =
        png("transparent-16x9") {
            createBitmap(16, 9)
        }

    /** The PNG for [key], drawn by [draw] only when it is not stored yet. */
    fun png(
        key: String,
        draw: () -> Bitmap,
    ): Uri {
        val file = File(dir, sha256(key) + ".png")
        if (file.isFile) {
            // Marks it recently used for trim().
            file.setLastModified(System.currentTimeMillis())
        } else {
            val bitmap = draw()
            try {
                val tmp = File.createTempFile("draw", ".tmp", dir)
                try {
                    FileOutputStream(tmp).use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" } }
                    if (!tmp.renameTo(file)) check(file.isFile) { "Could not store a render image" }
                } finally {
                    tmp.delete()
                }
            } finally {
                bitmap.recycle()
            }
        }
        return Uri.fromFile(file)
    }

    /**
     * Deletes the least recently used PNGs until the folder is under
     * [maxBytes], and any temporary file left by a crash, but never a file in
     * [keep] (the images of the composition being built).
     */
    fun trim(keep: Set<Uri>) {
        val keepPaths = keep.mapNotNull { it.path }.toSet()
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        files.filter { it.name.endsWith(".tmp") && it.path !in keepPaths }.forEach { it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        for (f in files.filter { it.exists() && it.name.endsWith(".png") }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f.path in keepPaths) continue
            total -= f.length()
            f.delete()
        }
    }

    /**
     * The frame of [uri] at [atUs], decoded no larger than [maxWidth] x
     * [maxHeight], for the held last frame of a spoken ending. For a file the
     * cache key includes its size and change time, so an edited file is read
     * again.
     *
     * @throws MediaUnavailableException when the video can't be read.
     */
    fun videoFrame(
        uri: Uri,
        atUs: Long,
        maxWidth: Int,
        maxHeight: Int,
    ): Uri {
        val identity =
            uri.path
                ?.takeIf { uri.scheme == "file" }
                ?.let { File(it) }
                ?.let { "${it.length()}|${it.lastModified()}" } ?: ""
        return png("frame|$uri|$identity|$atUs|${maxWidth}x$maxHeight") {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, maxWidth, maxHeight)
                    ?: throw MediaUnavailableException("Could not read the last frame")
            } catch (e: RuntimeException) {
                throw MediaUnavailableException("Could not read the last frame: ${e.javaClass.simpleName}")
            } finally {
                retriever.release()
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> HEX[(b.toInt() shr 4) and 0xF].toString() + HEX[b.toInt() and 0xF] }

    companion object {
        private const val HEX = "0123456789abcdef"

        /** About 30 held 1080p frames, or thousands of captions. */
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
    }
}
