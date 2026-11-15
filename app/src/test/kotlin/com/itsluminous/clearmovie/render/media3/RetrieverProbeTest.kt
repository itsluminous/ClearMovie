package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File

/** [RetrieverProbe] reads the length and whether a sound track is there. */
@RunWith(RobolectricTestRunner::class)
class RetrieverProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(
        name: String,
        metadata: Map<Int, String>,
    ): Uri {
        val uri = Uri.fromFile(File(context.filesDir, name).apply { writeBytes(byteArrayOf(0)) })
        val source = DataSource.toDataSource(context, uri)
        metadata.forEach { (key, value) -> ShadowMediaMetadataRetriever.addMetadata(source, key, value) }
        return uri
    }

    @Test
    fun `a file without the has-audio key has no sound`() {
        // MediaMetadataRetriever leaves the key unset when there is no sound track.
        val uri = file("silent.mp4", mapOf(MediaMetadataRetriever.METADATA_KEY_DURATION to "2000"))
        assertThat(RetrieverProbe(context).probe(uri)).isEqualTo(SourceInfo(2_000_000, hasAudio = false))
    }

    @Test
    fun `a file with a sound track has sound`() {
        val uri =
            file(
                "sound.mp4",
                mapOf(MediaMetadataRetriever.METADATA_KEY_DURATION to "3000", MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO to "yes"),
            )
        assertThat(RetrieverProbe(context).probe(uri)).isEqualTo(SourceInfo(3_000_000, hasAudio = true))
    }

    @Test
    fun `a file without a length can't be used`() {
        val uri = file("broken.mp4", mapOf(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO to "yes"))
        assertThat(RetrieverProbe(context).probe(uri)).isNull()
    }
}
