package com.itsluminous.clearmovie.data.voice

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** One answer of a fake HTTPS server, and the request it saw (cm-voice-pack-download tests). */
internal class FakeHttps(
    url: URL,
    private val code: Int,
    private val headers: Map<String, String>,
    private val data: ByteArray,
    private val failAfter: Int? = null,
) : HttpsURLConnection(url) {
    val sent = mutableMapOf<String, String>()
    var followed: Boolean? = null

    override fun setRequestProperty(
        key: String,
        value: String,
    ) {
        sent[key] = value
    }

    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        followed = followRedirects
    }

    override fun getResponseCode(): Int = code

    override fun getHeaderField(name: String): String? = headers[name]

    override fun getInputStream(): InputStream =
        if (failAfter == null) {
            ByteArrayInputStream(data)
        } else {
            object : InputStream() {
                var at = 0

                override fun read(): Int {
                    if (at >= failAfter) throw IOException("connection reset")
                    return data[at++].toInt() and 0xff
                }
            }
        }

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun usingProxy() = false

    override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
