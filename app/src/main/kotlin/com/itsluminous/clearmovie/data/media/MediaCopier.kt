package com.itsluminous.clearmovie.data.media

import com.itsluminous.clearmovie.data.files.AppFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The result of a finished copy. */
data class CopiedFile(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

/** A copy that could not be made. The partial file is already gone. */
class CopyFailedException(
    val reason: Reason,
    cause: Throwable? = null,
) : IOException(reason.name, cause) {
    enum class Reason {
        /** The source could not be opened or read (deleted, or the grant ended). */
        UNREADABLE,

        /** The source is larger than allowed. */
        TOO_LARGE,

        /** Writing failed, usually because the phone is full. */
        WRITE_FAILED,
    }
}

/**
 * Streams a file into app storage with progress and a SHA-256, and never
 * leaves a partial file: it writes `<target>.part`, syncs it to disk, then
 * renames it over [target]. If the copy fails or its coroutine is cancelled
 * (the user tapped Cancel), the `.part` file is deleted before the exception
 * goes on. A `.part` file left by a killed process is removed by
 * [AppFiles.sweep] on a later launch.
 */
class MediaCopier(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferBytes: Int = BUFFER_BYTES,
) {
    /**
     * @param open opens the source; it may throw for an unreadable source.
     * @param expectedBytes the size the source reported, or null; used for progress.
     * @param maxBytes the copy fails with [CopyFailedException.Reason.TOO_LARGE]
     *   past this many bytes, so a provider can never fill the phone.
     * @param onProgress called with the bytes copied so far and [expectedBytes],
     *   at most about once per percent (and at the end).
     */
    suspend fun copy(
        open: () -> InputStream?,
        target: File,
        expectedBytes: Long?,
        maxBytes: Long,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): CopiedFile =
        withContext(dispatcher) {
            target.parentFile?.mkdirs()
            val part = AppFiles.partFileFor(target)
            try {
                val input =
                    try {
                        open()
                    } catch (e: Exception) {
                        // FileNotFoundException, SecurityException (an ended grant) and
                        // IllegalArgumentException (an unknown provider) all mean the same.
                        throw CopyFailedException(CopyFailedException.Reason.UNREADABLE, e)
                    } ?: throw CopyFailedException(CopyFailedException.Reason.UNREADABLE)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                input.use { source ->
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(bufferBytes)
                        val step = expectedBytes?.takeIf { it > 0 }?.let { maxOf(it / 100, 1L) } ?: (1L shl 20)
                        var nextReport = step
                        while (true) {
                            ensureActive()
                            val n =
                                try {
                                    source.read(buffer)
                                } catch (e: IOException) {
                                    throw CopyFailedException(CopyFailedException.Reason.UNREADABLE, e)
                                } catch (e: SecurityException) {
                                    throw CopyFailedException(CopyFailedException.Reason.UNREADABLE, e)
                                }
                            if (n < 0) break
                            if (copied + n > maxBytes) throw CopyFailedException(CopyFailedException.Reason.TOO_LARGE)
                            try {
                                out.write(buffer, 0, n)
                            } catch (e: IOException) {
                                throw CopyFailedException(CopyFailedException.Reason.WRITE_FAILED, e)
                            }
                            digest.update(buffer, 0, n)
                            copied += n
                            if (copied >= nextReport) {
                                onProgress(copied, expectedBytes)
                                nextReport = copied + step
                            }
                        }
                        ensureActive()
                        try {
                            out.flush()
                            out.fd.sync()
                        } catch (e: IOException) {
                            throw CopyFailedException(CopyFailedException.Reason.WRITE_FAILED, e)
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw CopyFailedException(CopyFailedException.Reason.WRITE_FAILED)
                }
                onProgress(copied, expectedBytes)
                CopiedFile(target, copied, digest.digest().toHex())
            } catch (e: Throwable) {
                // Cancellation included: no partial file survives a failed copy.
                part.delete()
                throw e
            }
        }

    companion object {
        const val BUFFER_BYTES = 256 * 1024

        /** No single gallery item or preset file is this large. */
        const val MAX_FILE_BYTES = 8_000_000_000L

        /**
         * The most a copy may write: never more than leaves [CopyBudget.RESERVE_BYTES]
         * free, and never much more than the source said it was, so a provider
         * that under-reports a file's size cannot fill the phone.
         */
        fun limitFor(
            declaredBytes: Long?,
            freeBytes: Long,
        ): Long {
            val space = (freeBytes - CopyBudget.RESERVE_BYTES).coerceAtLeast(0)
            val declared = declaredBytes?.takeIf { it >= 0 }?.let { it + it / 10 + SIZE_SLACK_BYTES } ?: Long.MAX_VALUE
            return minOf(MAX_FILE_BYTES, space, declared)
        }

        /** Allowed growth over a declared size (headers rewritten by the provider, for example). */
        const val SIZE_SLACK_BYTES = 1_000_000L

        private const val HEX = "0123456789abcdef"

        /** Lower-case hex, independent of the phone's locale. */
        fun ByteArray.toHex(): String =
            buildString(size * 2) {
                for (b in this@toHex) {
                    append(HEX[(b.toInt() shr 4) and 0xF])
                    append(HEX[b.toInt() and 0xF])
                }
            }

        /** The SHA-256 of [file], for re-checking a preset file whose size changed. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }
    }
}
