package com.itsluminous.clearmovie.render.media3

import android.media.metrics.LogSessionId
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/** The BT.601 tagging encoder factory: what it asks for, and that it forwards everything else. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class RenderExporterEncodersTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sdr =
        Format
            .Builder()
            .setSampleMimeType(
                MimeTypes.VIDEO_H264,
            ).setWidth(1920)
            .setHeight(1080)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()

    /** A fake codec that only answers its name. */
    private fun codec(
        name: String,
        released: MutableList<String>,
    ): Codec =
        Proxy.newProxyInstance(Codec::class.java.classLoader, arrayOf(Codec::class.java)) { _, method, _ ->
            when (method.name) {
                "getName" -> name
                "release" -> released.add(name).let { null }
                else -> null
            }
        } as Codec

    @OptIn(ExperimentalApi::class)
    private class Fake(
        val names: ArrayDeque<String>,
        val released: MutableList<String>,
        val refuseBt601: Boolean = false,
        val make: (String) -> Codec,
    ) : Codec.EncoderFactory {
        val requested = mutableListOf<Format>()

        override fun createForAudioEncoding(
            format: Format,
            logSessionId: LogSessionId?,
        ): Codec = make("audio")

        override fun createForVideoEncoding(
            format: Format,
            logSessionId: LogSessionId?,
        ): Codec {
            requested += format
            if (refuseBt601 && format.colorInfo == RenderExporter.SDR_BT601_LIMITED) {
                throw ExportException.createForCodec(
                    IllegalArgumentException("BT.601 not supported"),
                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                    ExportException.CodecInfo("refused", true, false, "enc.a"),
                )
            }
            return make(names.removeFirst())
        }

        override fun isVideoFormatSupported(format: Format) = false

        override fun audioNeedsEncoding() = true

        override fun videoNeedsEncoding() = true
    }

    private fun wrapped(
        names: List<String>,
        measured: String?,
        refuseBt601: Boolean = false,
    ): Pair<Codec.EncoderFactory, Fake> {
        val released = mutableListOf<String>()
        val fake = Fake(ArrayDeque(names), released, refuseBt601) { codec(it, released) }
        val factory =
            RenderExporter.encoderFactory(context, EncodeSpec(1_000_000), DeviceColour(false, true, encoderName = measured)) { fake }
        return factory to fake
    }

    @Test
    fun `the measured encoder is asked for BT601`() {
        val (factory, fake) = wrapped(listOf("enc.a"), "enc.a")
        assertThat(factory.createForVideoEncoding(sdr, null).name).isEqualTo("enc.a")
        assertThat(fake.requested.single().colorInfo).isEqualTo(RenderExporter.SDR_BT601_LIMITED)
    }

    @Test
    fun `another encoder is opened again with the requested tag`() {
        val (factory, fake) = wrapped(listOf("enc.b", "enc.b"), "enc.a")
        factory.createForVideoEncoding(sdr, null)
        assertThat(
            fake.requested.map { it.colorInfo },
        ).containsExactly(RenderExporter.SDR_BT601_LIMITED, ColorInfo.SDR_BT709_LIMITED).inOrder()
        assertThat(fake.released).containsExactly("enc.b")
    }

    @Test
    fun `an encoder that refuses the BT601 tag is opened with the requested one`() {
        val (factory, fake) = wrapped(listOf("enc.a"), "enc.a", refuseBt601 = true)
        assertThat(factory.createForVideoEncoding(sdr, null).name).isEqualTo("enc.a")
        assertThat(
            fake.requested.map { it.colorInfo },
        ).containsExactly(RenderExporter.SDR_BT601_LIMITED, ColorInfo.SDR_BT709_LIMITED).inOrder()
        assertThat(fake.released).isEmpty()
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun `the interface's default methods are forwarded`() {
        val (factory, _) = wrapped(listOf("enc.a"), "enc.a")
        assertThat(factory.isVideoFormatSupported(sdr)).isFalse()
        assertThat(factory.audioNeedsEncoding()).isTrue()
        assertThat(factory.videoNeedsEncoding()).isTrue()
    }

    @Test
    fun `a device whose encoder is right, or was not named, gets the plain factory`() {
        val fake = Fake(ArrayDeque(listOf("x")), mutableListOf()) { codec(it, mutableListOf()) }
        assertThat(RenderExporter.encoderFactory(context, EncodeSpec(1_000_000), DeviceColour(true, false)) { fake }).isSameInstanceAs(fake)
        assertThat(
            RenderExporter.encoderFactory(context, EncodeSpec(1_000_000), DeviceColour(false, true, encoderName = null)) {
                fake
            },
        ).isSameInstanceAs(fake)
    }
}
