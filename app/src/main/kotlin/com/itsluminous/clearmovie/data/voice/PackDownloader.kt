package com.itsluminous.clearmovie.data.voice

import com.itsluminous.clearmovie.domain.voice.VoicePackSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

/** Why a pack file could not be downloaded. */
class DownloadFailure(
    val reason: Reason,
    message: String,
) : IOException(message) {
    enum class Reason {
        /** The connection failed or dropped: worth trying again. */
        NETWORK,

        /** The server answered with something other than the file. */
        SERVER,

        /** The link is not a pack file at its pinned commit, or the answer was not the listed file. */
        REFUSED,
    }
}

/**
 * The one place ClearMovie uses the internet (cm-voice-pack-download): it
 * downloads a voice pack file, and nothing else. `NetworkUseConventionTest`
 * fails the build if any other source file uses networking, and the network
 * security config trusts no other host.
 *
 * Only an HTTPS link to a pack file at its pinned commit is opened
 * ([VoicePackSource.allowed]); a redirect is refused rather than followed,
 * so the bytes always come from that host. A part already on disk is
 * resumed with a Range request; a server that sends the whole file instead
 * starts it again. More bytes than the listed size are refused. The file is
 * only bytes here: [VoicePackStore.addDownloaded] checks its SHA-256 against
 * the app's own manifest before it is unzipped.
 */
class PackDownloader internal constructor(
    private val opener: Opener?,
) {
    /** The app's downloader. */
    constructor() : this(null)

    /** Opens connections in tests, in place of the network. */
    internal fun interface Opener {
        fun open(url: URL): HttpURLConnection
    }

    // A method of this class, not a lambda, so the release build keeps the network call here
    // (proguard-rules.pro keeps this class whole), where scripts/verify-release-apks.sh looks for it.
    private fun connect(url: URL): URLConnection = opener?.open(url) ?: url.openConnection()

    /**
     * Downloads [url] into [to] until it holds [bytes] bytes, resuming what is
     * there. [progress] gets the bytes on disk; [stopped] is checked between
     * reads, and a stop leaves the part for a later resume.
     */
    fun fetch(
        url: String,
        to: File,
        bytes: Long,
        progress: (Long) -> Unit = {},
        stopped: () -> Boolean = { false },
    ) {
        if (!VoicePackSource.allowed(url)) throw DownloadFailure(DownloadFailure.Reason.REFUSED, "not a voice pack link")
        if (to.length() > bytes) to.delete()
        var have = if (to.isFile) to.length() else 0L
        progress(have)
        if (have == bytes) return
        val connection = connect(URL(url))
        if (connection !is HttpsURLConnection) throw DownloadFailure(DownloadFailure.Reason.REFUSED, "not HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            // The zip as it is: a compressed answer would not have the listed size.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val code =
                try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw DownloadFailure(DownloadFailure.Reason.NETWORK, e.toString())
                }
            val append =
                when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        if (have == 0L || connection.getHeaderField("Content-Range") != "bytes $have-${bytes - 1}/$bytes") {
                            throw DownloadFailure(DownloadFailure.Reason.SERVER, "unexpected Content-Range")
                        }
                        true
                    }

                    HttpURLConnection.HTTP_OK -> {
                        have = 0
                        false
                    }

                    RANGE_NOT_SATISFIABLE -> {
                        // The part on disk is not a beginning of this file: start again next time.
                        to.delete()
                        throw DownloadFailure(DownloadFailure.Reason.NETWORK, "range not satisfiable")
                    }

                    in 300..399 -> {
                        throw DownloadFailure(DownloadFailure.Reason.REFUSED, "redirect $code")
                    }

                    else -> {
                        throw DownloadFailure(
                            if (code >= 500 || code == HTTP_TOO_MANY) DownloadFailure.Reason.NETWORK else DownloadFailure.Reason.SERVER,
                            "HTTP $code",
                        )
                    }
                }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (length != null && length != bytes - have) {
                if (!append) to.delete()
                throw DownloadFailure(DownloadFailure.Reason.REFUSED, "size $length, expected ${bytes - have}")
            }
            to.parentFile?.mkdirs()
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(to, append).use { out ->
                        val buf = ByteArray(BUFFER)
                        var lastReport = have
                        while (!stopped()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (have + n > bytes) {
                                out.close()
                                to.delete()
                                throw DownloadFailure(DownloadFailure.Reason.REFUSED, "more than the listed size")
                            }
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastReport >= REPORT_BYTES || have == bytes) {
                                lastReport = have
                                progress(have)
                            }
                        }
                    }
                }
            } catch (e: DownloadFailure) {
                throw e
            } catch (e: IOException) {
                throw DownloadFailure(DownloadFailure.Reason.NETWORK, e.toString())
            }
            if (!stopped() && have != bytes) throw DownloadFailure(DownloadFailure.Reason.NETWORK, "ended at $have of $bytes")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val REPORT_BYTES = 256L * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_TOO_MANY = 429
    }
}
