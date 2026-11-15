package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** BT.709 video items start with the correction exactly when the device needs it. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CompositionFactoryColourTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "media").apply { mkdirs() }

    /** A BT.709 video ("hd.mp4"), a BT.601 video ("sd.mp4") and a photo on MAIN. */
    private fun mainItems(colour: DeviceColour): List<EditedMediaItem> {
        listOf("hd.mp4", "sd.mp4", "p.jpg").forEach { File(root, it).writeBytes(byteArrayOf(0)) }
        val ids = SequentialIds("c")
        val project =
            ProjectFactory.blank(ProjectId("colour"), ids).addToMain(
                listOf(
                    VideoClip(ids.clipId(), MediaRef.Copied("hd.mp4"), sourceDurationMs = 2000),
                    VideoClip(ids.clipId(), MediaRef.Copied("sd.mp4"), sourceDurationMs = 2000),
                    PhotoClip(ids.clipId(), MediaRef.Copied("p.jpg"), 2000),
                ),
                ids = ids,
            )
        val plan = RenderPlanBuilder().build(project)
        val probe = SourceProbe { uri: Uri -> SourceInfo(2_000_000, hasAudio = false, bt709 = uri.lastPathSegment != "sd.mp4") }
        val result = AtomicReference<Any>()
        // build() refuses the main thread, which is the test thread here.
        Thread {
            result.set(
                runCatching {
                    CompositionFactory(context, FileMediaResolver(emptyMap(), root), probe) { colour }.build(plan)
                }.fold({ it }, { it }),
            )
        }.apply {
            start()
            join()
        }
        val value = result.get()
        if (value is Throwable) throw value
        val composition = value as Composition
        // Skip the transparent top layer; MAIN's lanes carry the items.
        return composition.sequences
            .drop(1)
            .filter { it.trackTypes == setOf(C.TRACK_TYPE_VIDEO) }
            .flatMap { it.editedMediaItems }
            .filter { it.mediaItem.localConfiguration != null }
    }

    private fun EditedMediaItem.file() = mediaItem.localConfiguration!!.uri.lastPathSegment

    @Test
    fun `on a GPU that misdecodes BT709 only BT709 video starts with the correction`() {
        val items = mainItems(DeviceColour(gpuDecodesBt709AsBt601 = true, encoderWritesBt601 = false))
        val hd = items.single { it.file() == "hd.mp4" }
        assertThat(hd.effects.videoEffects.first()).isSameInstanceAs(Bt709Correction)
        assertThat(hd.effects.videoEffects.count { it === Bt709Correction }).isEqualTo(1)
        for (other in items.filter { it.file() != "hd.mp4" }) {
            assertThat(other.effects.videoEffects).doesNotContain(Bt709Correction)
        }
    }

    @Test
    fun `a device that decodes BT709 correctly gets no correction`() {
        for (colour in listOf(DeviceColour(false, true), DeviceColour.TRUSTED)) {
            assertThat(mainItems(colour).flatMap { it.effects.videoEffects }).doesNotContain(Bt709Correction)
        }
    }
}
