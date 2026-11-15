package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The small images the factory makes for a composition: the transparent fill,
 * caption and card bitmaps, and held video frames. Each is a PNG in
 * `cacheDir/render/`, named by a SHA-256 of what it shows, so an unchanged
 * caption is drawn once and preview and export share it. Files are written to
 * a temporary name first and renamed, so a half-written PNG is never used.
 * The folder is a cache: deleting it only costs a redraw.
 */
internal class RenderAssets(
    private val context: Context,
) {
    private val dir = File(context.cacheDir, "render").apply { mkdirs() }

    /** A 16x9 fully transparent PNG; `PlacementEffect` stretches it to the canvas. */
    fun transparent(): Uri =
        png("transparent-16x9") {
            Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        }

    /** The PNG for [key], drawn by [draw] only when it is not cached yet. */
    fun png(
        key: String,
        draw: () -> Bitmap,
    ): Uri {
        val file = File(dir, sha256(key) + ".png")
        if (!file.isFile) {
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
     * The frame of [uri] at [atUs], no larger than [maxDimension] on its long
     * side, for the held last frame of a spoken ending.
     *
     * @throws MediaUnavailableException when the video can't be read.
     */
    fun videoFrame(
        uri: Uri,
        atUs: Long,
        maxDimension: Int,
    ): Uri =
        png("frame|$uri|$atUs|$maxDimension") {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame =
                    retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: throw MediaUnavailableException("Could not read the last frame")
                val longSide = maxOf(frame.width, frame.height)
                if (longSide <= maxDimension) {
                    frame
                } else {
                    val s = maxDimension.toFloat() / longSide
                    Bitmap.createScaledBitmap(frame, (frame.width * s).toInt().coerceAtLeast(1), (frame.height * s).toInt().coerceAtLeast(1), true).also {
                        if (it !== frame) frame.recycle()
                    }
                }
            } catch (e: RuntimeException) {
                throw MediaUnavailableException("Could not read the last frame: ${e.javaClass.simpleName}")
            } finally {
                retriever.release()
            }
        }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> HEX[(b.toInt() shr 4) and 0xF].toString() + HEX[b.toInt() and 0xF] }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
